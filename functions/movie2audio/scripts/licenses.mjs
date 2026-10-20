import { createHash } from 'node:crypto';
import { readFile, readdir, writeFile } from 'node:fs/promises';
import { join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const compare = (a, b) => a < b ? -1 : a > b ? 1 : 0;
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const portable = path => path.split(sep).join('/');
const licenseName = /^(?:licen[sc]e|copying)(?:$|[._-])/i;
const noticeName = /^(?:notice|copyright|copyrightnotice|third[-_ ]party[-_ ]notices?)(?:$|[._-])/i;

async function entries(directory) {
  try {
    return (await readdir(directory, { withFileTypes: true })).sort((a, b) => compare(a.name, b.name));
  } catch (error) {
    if (error.code === 'ENOENT') return [];
    throw error;
  }
}

async function installedPackages(directory, result = []) {
  for (const entry of await entries(directory)) {
    if (entry.name.startsWith('.')) continue;
    const path = join(directory, entry.name);
    if (entry.isSymbolicLink()) throw new Error(`Linked dependency is not a standalone package: ${path}`);
    if (!entry.isDirectory()) continue;
    if (entry.name.startsWith('@')) {
      await installedPackages(path, result);
    } else {
      result.push(path);
      await installedPackages(join(path, 'node_modules'), result);
    }
  }
  return result;
}

async function legalFiles(directory, result = []) {
  for (const entry of await entries(directory)) {
    if (entry.name === 'node_modules' || entry.name === '.git') continue;
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      await legalFiles(path, result);
    } else if (licenseName.test(entry.name) || noticeName.test(entry.name)) {
      if (!entry.isFile()) throw new Error(`Legal document must be a regular file: ${path}`);
      result.push({ path, kind: licenseName.test(entry.name) ? 'license' : 'notice' });
    }
  }
  return result;
}

function checkLicenseBody(text, path) {
  // A manifest identifier or a "MIT License" heading alone is not the license text.
  if (text.length < 200 ||
      !/permission|redistribution|terms and conditions/i.test(text) ||
      !/warrant|liabilit|disclaim/i.test(text)) {
    throw new Error(`License body is missing or incomplete: ${path}`);
  }
}

async function document(root, path, kind, source) {
  const bytes = await readFile(path);
  const text = bytes.toString('utf8');
  if (!text.trim()) throw new Error(`Empty legal document: ${path}`);
  if (kind === 'license') checkLicenseBody(text, path);
  return {
    path: portable(relative(root, path)),
    kind,
    sha256: sha256(bytes),
    ...(source ? { source } : {}),
    text,
  };
}

export async function collectNodeLicenses(root) {
  root = resolve(root);
  const lock = JSON.parse(await readFile(join(root, 'package-lock.json'), 'utf8'));
  if (!lock.packages) throw new Error('A package-lock.json with a packages inventory is required');
  const manifest = JSON.parse(await readFile(join(root, 'third-party/node/overrides.json'), 'utf8'));
  if (manifest.schemaVersion !== 1 || !Array.isArray(manifest.overrides)) {
    throw new Error('Invalid Node license override manifest');
  }
  const overrides = new Map();
  for (const override of manifest.overrides) {
    const key = `${override.name}@${override.version}`;
    if (overrides.has(key)) throw new Error(`Duplicate license override: ${key}`);
    if (!override.file?.startsWith('third-party/node/licenses/') ||
        override.file.split('/').some(part => part === '..' || part === '.') ||
        !/^[a-f0-9]{64}$/.test(override.sha256) ||
        !override.packageIntegrity || !/^https:\/\//.test(override.source)) {
      throw new Error(`Invalid license override provenance: ${key}`);
    }
    const doc = await document(root, join(root, override.file), 'license', override.source);
    if (doc.sha256 !== override.sha256) throw new Error(`License override SHA-256 mismatch: ${key}`);
    overrides.set(key, { ...override, doc, used: false });
  }

  const packages = [];
  const seen = new Set();
  for (const directory of await installedPackages(join(root, 'node_modules'))) {
    const path = portable(relative(root, directory));
    const metadata = JSON.parse(await readFile(join(directory, 'package.json'), 'utf8'));
    const pinned = lock.packages[path];
    if (!pinned || pinned.dev || pinned.version !== metadata.version) {
      throw new Error(`Installed production package does not match package-lock.json: ${path}`);
    }
    const expectedName = path.slice(path.lastIndexOf('node_modules/') + 'node_modules/'.length);
    if (metadata.name !== expectedName) throw new Error(`Unexpected package name at ${path}: ${metadata.name}`);
    seen.add(path);
    const key = `${metadata.name}@${metadata.version}`;
    const documents = [];
    for (const file of await legalFiles(directory)) {
      documents.push(await document(root, file.path, file.kind));
    }
    const override = overrides.get(key);
    if (override) {
      if (override.packageIntegrity !== pinned.integrity || override.license !== metadata.license) {
        throw new Error(`License override package metadata mismatch: ${key}`);
      }
      if (documents.some(doc => doc.kind === 'license')) {
        throw new Error(`License override is no longer needed; review the upstream license: ${key}`);
      }
      override.used = true;
      documents.push(override.doc);
    }
    if (!documents.some(doc => doc.kind === 'license')) {
      throw new Error(`Missing license text for production package: ${key} (${path})`);
    }
    packages.push({
      name: metadata.name,
      version: metadata.version,
      path,
      declaredLicense: metadata.license ?? metadata.licenses?.map(value => value.type).join(' OR ') ?? 'See license text',
      integrity: pinned.integrity,
      documents: documents.sort((a, b) => compare(a.path, b.path)),
    });
  }
  for (const [path, pinned] of Object.entries(lock.packages)) {
    if (path && !pinned.dev && !pinned.optional && !seen.has(path)) {
      throw new Error(`Required production package is not installed: ${path}`);
    }
  }
  for (const [key, override] of overrides) {
    if (!override.used) throw new Error(`Unused license override; review version drift: ${key}`);
  }
  if (!packages.length) throw new Error('No installed production packages found');
  return packages.sort((a, b) => compare(a.path, b.path));
}

function renderDocument(doc) {
  return `\n--- ${doc.path} ---\nSHA-256: ${doc.sha256}\n${doc.source ? `Upstream source: ${doc.source}\n` : ''}\n${doc.text}${doc.text.endsWith('\n') ? '' : '\n'}`;
}

export async function writeOrCheckNotices(root, { check = false } = {}) {
  root = resolve(root);
  const packages = await collectNodeLicenses(root);
  const ffmpeg = [];
  for (const name of ['LICENSE.md', 'COPYING.LGPLv2.1', 'LICENSE-musl.txt',
    'COPYING.GCC-RUNTIME', 'COPYING.GPLv3', 'COPYING.MinGW-w64-runtime.txt']) {
    // These include project-specific licensing explanations as well as full license texts.
    ffmpeg.push(await document(root, join(root, 'third-party/ffmpeg', name), 'notice'));
  }
  const header = await readFile(join(root, 'NOTICE'), 'utf8');
  let notices = `THIRD-PARTY NOTICES — Movie2Audio\n\n${header.trim()}\n\n`;
  notices += `This file reproduces the license and notice files for all ${packages.length} installed production npm packages, followed by FFmpeg and runtime notices.\n`;
  notices += 'Original package documents remain in node_modules/. Supplemental upstream documents and their provenance remain in third-party/node/.\n';
  notices += 'The installed package inventory and document SHA-256 values are in third-party/node/inventory.json.\n';
  for (const pkg of packages) {
    notices += `\n${'='.repeat(78)}\n${pkg.name}@${pkg.version}\nInstalled path: ${pkg.path}\nDeclared license: ${pkg.declaredLicense}\n`;
    for (const doc of pkg.documents) notices += renderDocument(doc);
  }
  notices += `\n${'='.repeat(78)}\nFFmpeg, musl, GCC runtime and MinGW-w64 runtime\n\nSee third-party/ffmpeg/README.md for the corresponding source, build and redistribution details.\n`;
  for (const doc of ffmpeg) notices += renderDocument(doc);

  const stripText = ({ text, ...metadata }) => metadata;
  const inventory = JSON.stringify({
    schemaVersion: 1,
    packageCount: packages.length,
    packages: packages.map(pkg => ({ ...pkg, documents: pkg.documents.map(stripText) })),
    ffmpegDocuments: ffmpeg.map(stripText),
  }, null, 2) + '\n';
  for (const [path, content] of [
    ['THIRD-PARTY-NOTICES.txt', notices],
    ['third-party/node/inventory.json', inventory],
  ]) {
    if (check) {
      if (await readFile(join(root, path), 'utf8') !== content) {
        throw new Error(`Generated license artifact differs; regenerate and review: ${path}`);
      }
    } else {
      await writeFile(join(root, path), content);
    }
  }
  return packages.length;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const check = args.includes('--check');
  const directories = args.filter(arg => arg !== '--check');
  if (directories.length !== 1 || directories[0].startsWith('-')) {
    throw new Error('Usage: node scripts/licenses.mjs <production-package-directory> [--check]');
  }
  const count = await writeOrCheckNotices(directories[0], { check });
  console.log(`${check ? 'Verified' : 'Generated'} third-party notices for ${count} production npm packages and bundled FFmpeg/runtime licenses.`);
}
