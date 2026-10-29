import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { collectNodeLicenses, removeExcludedNodePackages, writeOrCheckNotices } from '../scripts/licenses.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const json = (path, value) => writeFile(path, JSON.stringify(value));
async function fixture(t, { name = 'sample', version = '1.0.0', license = 'MIT', files = {} } = {}) {
  const dir = await mkdtemp(join(tmpdir(), 'html2pptx-license-test-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  await mkdir(join(dir, 'third-party/node'), { recursive: true });
  await mkdir(join(dir, 'node_modules', name), { recursive: true });
  await json(join(dir, 'package-lock.json'), { packages: { '': { name: 'test' }, [`node_modules/${name}`]: { version, integrity: 'sha512-example' } } });
  await json(join(dir, 'third-party/node/overrides.json'), { schemaVersion: 1, overrides: [] });
  await json(join(dir, 'third-party/node/exclusions.json'), { schemaVersion: 1, exclusions: [] });
  await json(join(dir, 'node_modules', name, 'package.json'), { name, version, license });
  for (const [file, value] of Object.entries(files)) await writeFile(join(dir, 'node_modules', name, file), value);
  return dir;
}

test('dependency declaration alone is not accepted as a license body', async t => {
  const dir = await fixture(t);
  await assert.rejects(collectNodeLicenses(dir), /Missing license text/u);
  await writeFile(join(dir, 'node_modules/sample/LICENSE'), 'MIT License');
  await assert.rejects(collectNodeLicenses(dir), /missing or incomplete/u);
});

test('full dependency license and notice are preserved with hashes', async t => {
  const original = await readFile(join(root, 'LICENSE'), 'utf8');
  const dir = await fixture(t, { files: { LICENSE: original, NOTICE: 'Original third-party attribution.' } });
  const [dependency] = await collectNodeLicenses(dir);
  assert.equal(dependency.documents.length, 2);
  assert.equal(dependency.documents.find(d => d.kind === 'license').text, original);
  assert.equal(dependency.documents.find(d => d.kind === 'notice').text, 'Original third-party attribution.');
  await json(join(dir, 'node_modules/sample/package.json'), { name: 'sample', version: '2.0.0', license: 'MIT' });
  await assert.rejects(collectNodeLicenses(dir), /does not match package-lock/u);
});

test('only the exact empty https package may be excluded from a standalone artifact', async t => {
  const dir = await fixture(t, { name: 'https', version: '1.0.0', license: 'ISC' });
  const bytes = await readFile(join(dir, 'node_modules/https/package.json'));
  await json(join(dir, 'third-party/node/exclusions.json'), { schemaVersion: 1, exclusions: [{ path: 'node_modules/https', name: 'https', version: '1.0.0', packageIntegrity: 'sha512-example', packageJsonSha256: createHash('sha256').update(bytes).digest('hex') }] });
  await writeFile(join(dir, 'node_modules/https/index.js'), 'export default 1;');
  await assert.rejects(removeExcludedNodePackages(dir), /now contains implementation files/u);
  await rm(join(dir, 'node_modules/https/index.js'));
  await removeExcludedNodePackages(dir);
  await assert.rejects(stat(join(dir, 'node_modules/https')), { code: 'ENOENT' });
  assert.equal(typeof (await import('node:https')).request, 'function');
});

test('committed notices cover the installed npm packages and exact bundled native archives', async () => {
  assert.ok(await writeOrCheckNotices(root, { check: true }) > 0);
  const notices = await readFile(join(root, 'THIRD-PARTY-NOTICES.txt'), 'utf8');
  assert.match(notices, /Mozilla Public License Version 2\.0/u);
  assert.match(notices, /SIL OPEN FONT LICENSE Version 1\.1/u);
  assert.match(notices, /SwiftShader/u);
  assert.match(notices, /GNU LESSER GENERAL PUBLIC LICENSE/u);
});
