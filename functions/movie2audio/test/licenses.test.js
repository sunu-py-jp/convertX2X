import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, rm, writeFile, copyFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { collectNodeLicenses, writeOrCheckNotices } from '../scripts/licenses.mjs';

const moduleRoot = fileURLToPath(new URL('../', import.meta.url));
const license = await readFile(join(moduleRoot, 'LICENSE'), 'utf8');
const digest = text => createHash('sha256').update(text).digest('hex');
const save = (root, path, data) => writeFile(join(root, path), JSON.stringify(data, null, 2) + '\n');

async function fixture(t, { supplemental = false } = {}) {
  const root = await mkdtemp(join(tmpdir(), 'movie-license-test-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  await mkdir(join(root, 'node_modules/example'), { recursive: true });
  await mkdir(join(root, 'third-party/node/licenses'), { recursive: true });
  const metadata = { name: 'example', version: '1.0.0', license: 'MIT' };
  const lock = { lockfileVersion: 3, packages: { '': {}, 'node_modules/example': { version: '1.0.0', integrity: 'sha512-package' } } };
  const overrides = { schemaVersion: 1, overrides: [] };
  await save(root, 'node_modules/example/package.json', metadata);
  await save(root, 'package-lock.json', lock);
  if (supplemental) {
    await writeFile(join(root, 'third-party/node/licenses/example-LICENSE'), license);
    overrides.overrides.push({
      name: 'example', version: '1.0.0', license: 'MIT',
      packageIntegrity: 'sha512-package',
      file: 'third-party/node/licenses/example-LICENSE',
      source: 'https://example.com/pinned-commit/LICENSE',
      sha256: digest(license),
    });
  } else {
    await writeFile(join(root, 'node_modules/example/LICENSE'), license);
  }
  await save(root, 'third-party/node/overrides.json', overrides);
  return { root, metadata, lock, overrides };
}

test('full license and additional notice text are retained, including nested production packages', async t => {
  const { root, lock } = await fixture(t);
  await writeFile(join(root, 'node_modules/example/NOTICE'), 'Additional copyright notice\n');
  const child = 'node_modules/example/node_modules/child';
  await mkdir(join(root, child), { recursive: true });
  await save(root, `${child}/package.json`, { name: 'child', version: '2.0.0', license: 'MIT' });
  await writeFile(join(root, child, 'license.md'), license);
  lock.packages[child] = { version: '2.0.0', integrity: 'sha512-child' };
  await save(root, 'package-lock.json', lock);
  const packages = await collectNodeLicenses(root);
  assert.equal(packages.length, 2);
  assert.equal(packages[0].documents.find(doc => doc.kind === 'license').text, license);
  assert.equal(packages[0].documents.find(doc => doc.kind === 'notice').text, 'Additional copyright notice\n');
  await rm(join(root, child, 'license.md'));
  await assert.rejects(collectNodeLicenses(root), /Missing license text.*child@2.0.0/);
});

test('a license identifier in package.json does not replace the missing license file', async t => {
  const { root } = await fixture(t);
  await rm(join(root, 'node_modules/example/LICENSE'));
  await assert.rejects(collectNodeLicenses(root), /Missing license text.*example@1.0.0/);
});

test('a license heading and copyright without the permission and disclaimer body is rejected', async t => {
  const { root } = await fixture(t);
  await writeFile(join(root, 'node_modules/example/LICENSE'), 'MIT License\nCopyright (c) 2026 Example\n');
  await assert.rejects(collectNodeLicenses(root), /License body is missing or incomplete/);
});

test('empty additional notices are rejected even when a full license is present', async t => {
  const { root } = await fixture(t);
  await writeFile(join(root, 'node_modules/example/NOTICE'), '\n');
  await assert.rejects(collectNodeLicenses(root), /Empty legal document/);
});

test('a pinned supplemental license supplies its unmodified copyright and body', async t => {
  const { root } = await fixture(t, { supplemental: true });
  const [pkg] = await collectNodeLicenses(root);
  assert.equal(pkg.documents[0].text, license);
  assert.equal(pkg.documents[0].sha256, digest(license));
  assert.equal(pkg.documents[0].source, 'https://example.com/pinned-commit/LICENSE');
});

test('changed supplemental license bytes are rejected even if the license is still valid', async t => {
  const { root } = await fixture(t, { supplemental: true });
  await writeFile(join(root, 'third-party/node/licenses/example-LICENSE'), license + '\n');
  await assert.rejects(collectNodeLicenses(root), /License override SHA-256 mismatch/);
});

test('upgrading a package cannot silently reuse a supplemental license from the old version', async t => {
  const { root, metadata, lock } = await fixture(t, { supplemental: true });
  metadata.version = '2.0.0';
  lock.packages['node_modules/example'].version = '2.0.0';
  await save(root, 'node_modules/example/package.json', metadata);
  await save(root, 'package-lock.json', lock);
  await assert.rejects(collectNodeLicenses(root), /Missing license text.*example@2.0.0/);
});

test('supplemental license is bound to the locked npm archive integrity', async t => {
  const { root, lock } = await fixture(t, { supplemental: true });
  lock.packages['node_modules/example'].integrity = 'sha512-replaced';
  await save(root, 'package-lock.json', lock);
  await assert.rejects(collectNodeLicenses(root), /License override package metadata mismatch/);
});

test('an upstream package gaining a license requires review of the obsolete override', async t => {
  const { root } = await fixture(t, { supplemental: true });
  await writeFile(join(root, 'node_modules/example/LICENSE'), license);
  await assert.rejects(collectNodeLicenses(root), /License override is no longer needed/);
});

test('missing required packages and extra installed packages fail the inventory check', async t => {
  const { root, lock } = await fixture(t);
  lock.packages['node_modules/absent'] = { version: '1.0.0' };
  await save(root, 'package-lock.json', lock);
  await assert.rejects(collectNodeLicenses(root), /Required production package is not installed/);
  delete lock.packages['node_modules/absent'];
  delete lock.packages['node_modules/example'];
  await save(root, 'package-lock.json', lock);
  await assert.rejects(collectNodeLicenses(root), /does not match package-lock.json/);
});

test('standalone notices include original bodies, and offline verification detects edits to notices or inventory', async t => {
  const { root } = await fixture(t, { supplemental: true });
  await copyFile(join(moduleRoot, 'NOTICE'), join(root, 'NOTICE'));
  await mkdir(join(root, 'third-party/ffmpeg'), { recursive: true });
  for (const name of ['LICENSE.md', 'COPYING.LGPLv2.1', 'LICENSE-musl.txt',
    'COPYING.GCC-RUNTIME', 'COPYING.GPLv3', 'COPYING.MinGW-w64-runtime.txt']) {
    await copyFile(join(moduleRoot, 'third-party/ffmpeg', name), join(root, 'third-party/ffmpeg', name));
  }
  assert.equal(await writeOrCheckNotices(root), 1);
  const notices = await readFile(join(root, 'THIRD-PARTY-NOTICES.txt'), 'utf8');
  assert.ok(notices.includes(license));
  assert.ok(notices.includes('GNU LESSER GENERAL PUBLIC LICENSE'));
  assert.equal(await writeOrCheckNotices(root, { check: true }), 1);
  await writeFile(join(root, 'THIRD-PARTY-NOTICES.txt'), notices.replace('Permission is hereby granted', 'Permission omitted'));
  await assert.rejects(writeOrCheckNotices(root, { check: true }), /Generated license artifact differs.*THIRD-PARTY-NOTICES/);
  await writeFile(join(root, 'THIRD-PARTY-NOTICES.txt'), notices);
  await writeFile(join(root, 'third-party/node/inventory.json'), '{}\n');
  await assert.rejects(writeOrCheckNotices(root, { check: true }), /Generated license artifact differs.*inventory.json/);
});
