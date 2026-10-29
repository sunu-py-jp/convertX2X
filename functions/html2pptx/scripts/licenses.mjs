import { createHash } from 'node:crypto';
import { readFile, readdir, writeFile, rm } from 'node:fs/promises';
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
      // JSZip ships a build-time license header in addition to its full LICENSE.
      // Preserve the header as a notice; it is not a substitute for the full text.
      result.push({ path, kind: licenseName.test(entry.name) && entry.name !== 'license_header.js' ? 'license' : 'notice' });
    }
  }
  return result;
}

function checkLicenseBody(text, path) {
  // A manifest identifier or a "MIT License" heading alone is not the license text.
  if (text.length < 200 ||
      !/permission|redistribution|terms and conditions|license grants and conditions|anyone is free to copy/i.test(text) ||
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

async function exclusionsFor(root, lock) {
  const manifest = JSON.parse(await readFile(join(root, 'third-party/node/exclusions.json'), 'utf8'));
  if (manifest.schemaVersion !== 1 || !Array.isArray(manifest.exclusions)) throw new Error('Invalid Node exclusion manifest');
  const result = new Map();
  for (const item of manifest.exclusions) {
    if (item.path !== 'node_modules/https' || item.name !== 'https' || item.version !== '1.0.0' ||
        !/^[a-f0-9]{64}$/.test(item.packageJsonSha256) || result.has(item.path)) {
      throw new Error('Only the pinned, implementation-free https metadata package may be excluded');
    }
    const pinned = lock.packages[item.path];
    if (!pinned || pinned.version !== item.version || pinned.integrity !== item.packageIntegrity) throw new Error('Excluded package metadata differs from lockfile');
    const files = await entries(join(root, item.path));
    if (files.length) {
      if (files.length !== 1 || files[0].name !== 'package.json' || !files[0].isFile()) throw new Error('Excluded package now contains implementation files; review required');
      if (sha256(await readFile(join(root, item.path, 'package.json'))) !== item.packageJsonSha256) throw new Error('Excluded package hash changed');
    }
    result.set(item.path, item);
  }
  return result;
}

export async function removeExcludedNodePackages(root) {
  root = resolve(root);
  const lock = JSON.parse(await readFile(join(root, 'package-lock.json'), 'utf8'));
  for (const item of (await exclusionsFor(root, lock)).values()) await rm(join(root, item.path), { recursive: true, force: true });
}

export async function collectNodeLicenses(root) {
  root = resolve(root);
  const lock = JSON.parse(await readFile(join(root, 'package-lock.json'), 'utf8'));
  if (!lock.packages) throw new Error('A package-lock.json with a packages inventory is required');
  const excluded = await exclusionsFor(root, lock);
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
    if (excluded.has(path)) continue;
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
    if (path && !pinned.dev && !pinned.optional && !seen.has(path) && !excluded.has(path)) {
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
  const provenance = JSON.parse(await readFile(join(root, 'third-party/chromium/provenance.json'), 'utf8'));
  const chromiumPackage = JSON.parse(await readFile(join(root, 'node_modules/@sparticuz/chromium/package.json'), 'utf8'));
  if (provenance.schemaVersion !== 1 || chromiumPackage.version !== provenance.wrapperVersion) throw new Error('Chromium provenance version mismatch');
  const nativeDocuments = [];
  for (const item of provenance.documents) {
    if (!item.path.startsWith('third-party/chromium/') || item.path.split('/').some(p => p === '..') || !/^https:\/\//.test(item.source)) throw new Error('Invalid native license provenance');
    const bytes = await readFile(join(root, item.path));
    if (sha256(bytes) !== item.sha256) throw new Error(`Native document hash changed: ${item.path}`);
    if (item.kind === 'license' || item.kind === 'notice') nativeDocuments.push(await document(root, join(root, item.path), item.kind, item.source));
  }
  const archives = await entries(join(root, 'node_modules/@sparticuz/chromium/bin'));
  if (archives.length !== provenance.archives.length) throw new Error('Chromium bundled archive inventory changed');
  for (const item of provenance.archives) {
    if (!item.path.startsWith('node_modules/@sparticuz/chromium/bin/') || item.path.split('/').some(p => p === '..')) throw new Error('Invalid Chromium archive path');
    const bytes = await readFile(join(root, item.path));
    if (bytes.length !== item.bytes || sha256(bytes) !== item.sha256) throw new Error(`Bundled Chromium archive changed: ${item.path}`);
  }
  const fontProvenance = JSON.parse(await readFile(join(root, 'third-party/fonts/noto-sans-jp/provenance.json'), 'utf8'));
  if (fontProvenance.schemaVersion !== 1 || fontProvenance.family !== 'Noto Sans JP' || fontProvenance.license !== 'OFL-1.1'
      || !/^[0-9a-f]{40}$/.test(fontProvenance.source?.commit) || !/^[0-9a-f]{64}$/.test(fontProvenance.source?.sha256)
      || !fontProvenance.source?.url?.startsWith(`https://raw.githubusercontent.com/google/fonts/${fontProvenance.source.commit}/ofl/notosansjp/`)) {
    throw new Error('Japanese font provenance mismatch');
  }
  const fontDocuments = [];
  for (const item of fontProvenance.documents) {
    if (!['assets/fonts/OFL.txt', 'third-party/fonts/noto-sans-jp/METADATA.pb'].includes(item.path)
        || !item.source?.startsWith(`https://raw.githubusercontent.com/google/fonts/${fontProvenance.source.commit}/ofl/notosansjp/`)) throw new Error('Invalid Japanese font document provenance');
    const bytes = await readFile(join(root, item.path));
    if (sha256(bytes) !== item.sha256) throw new Error(`Japanese font document hash changed: ${item.path}`);
    if (item.kind === 'license') fontDocuments.push(await document(root, join(root, item.path), 'license', item.source));
  }
  if (fontDocuments.length !== 1 || fontDocuments[0].path !== 'assets/fonts/OFL.txt') throw new Error('Japanese font OFL is missing');
  const fontFiles = (await entries(join(root, 'assets/fonts'))).filter(item => /\.(?:ttf|otf)$/i.test(item.name));
  if (fontFiles.length !== 2 || fontProvenance.files.length !== 2) throw new Error('Japanese font inventory changed');
  for (const [style, weight] of [['Regular', 400], ['Bold', 700]]) {
    const expected = `assets/fonts/NotoSansJP-${style}.ttf`;
    const item = fontProvenance.files.find(file => file.path === expected);
    if (!item || item.family !== 'Noto Sans JP' || item.legacyFamily !== 'Noto Sans JP' || item.style !== style
        || item.weight !== weight || item.variable !== false || item.outline !== 'glyf') throw new Error(`Japanese font metadata mismatch: ${expected}`);
    const bytes = await readFile(join(root, expected));
    if (bytes.length !== item.bytes || sha256(bytes) !== item.sha256) throw new Error(`Japanese font binary changed: ${expected}`);
  }
  const header = await readFile(join(root, 'NOTICE.md'), 'utf8');
  let notices = `THIRD-PARTY NOTICES — HTML2PPTX\n\n${header.trim()}\n\n`;
  notices += `This file reproduces the license and notice files for all ${packages.length} shipped production npm packages, followed by Chromium, its bundled runtime libraries and fonts.\n`;
  notices += 'Original package documents remain in node_modules/. Supplemental upstream documents and their provenance remain in third-party/node/.\n';
  notices += 'The installed package inventory and document SHA-256 values are in third-party/node/inventory.json.\n';
  for (const pkg of packages) {
    notices += `\n${'='.repeat(78)}\n${pkg.name}@${pkg.version}\nInstalled path: ${pkg.path}\nDeclared license: ${pkg.declaredLicense}\n`;
    for (const doc of pkg.documents) notices += renderDocument(doc);
  }
  notices += `\n${'='.repeat(78)}\nChromium, SwiftShader/Vulkan, AL2023 runtime libraries and fonts\n\nSee third-party/chromium/README.md and provenance.json for matching source versions, archives and full license provenance.\n`;
  for (const doc of nativeDocuments) notices += renderDocument(doc);
  for (const doc of fontDocuments) notices += renderDocument(doc);

  const stripText = ({ text, ...metadata }) => metadata;
  const inventory = JSON.stringify({
    schemaVersion: 1,
    packageCount: packages.length,
    packages: packages.map(pkg => ({ ...pkg, documents: pkg.documents.map(stripText) })),
    excludedMetadataPackages: JSON.parse(await readFile(join(root, 'third-party/node/exclusions.json'), 'utf8')).exclusions,
    nativeDocuments: nativeDocuments.map(stripText),
    chromiumArchives: provenance.archives,
    fontDocuments: fontDocuments.map(stripText),
    fontSource: fontProvenance.source,
    fontGenerator: fontProvenance.generator,
    fontFiles: fontProvenance.files,
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
  console.log(`${check ? 'Verified' : 'Generated'} third-party notices for ${count} production npm packages and bundled Chromium/runtime/font licenses.`);
}
