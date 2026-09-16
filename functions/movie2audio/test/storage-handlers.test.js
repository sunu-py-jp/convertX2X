import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { createConfig, publicSettings } from '../src/config.js';
import { createHandlers } from '../src/handlers.js';
import { ConversionError } from '../src/errors.js';

const host = 'outputexample.blob.core.windows.net';
const signature = Buffer.alloc(32, 27).toString('base64');
const blobUrl = `https://${host}/results/meeting/audio.m4a`;
const sasUrl = `${blobUrl}?sv=2023-11-03&sr=b&sp=c&spr=https&se=${encodeURIComponent(new Date(Date.now() + 3600000).toISOString())}&sig=${encodeURIComponent(signature)}`;
const config = createConfig({ CONVERSION_OUTPUT_ALLOWED_HOSTS: host, CONVERSION_URL_ALLOWED_HOSTS: 'media.example.com' });
const fixture = new URL('./fixtures/aac-video.mp4', import.meta.url);
const context = { error() {}, warn() {} };
const json = value => new Request('http://localhost/api/convert-to-blob', { method: 'POST',
  headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value) });
const valid = () => ({ input: { url: 'https://media.example.com/meeting.mp4' }, output: { sasUrl } });
const errorCode = response => JSON.parse(response.body).error.code;

async function workspace(t) {
  const root = await mkdtemp(join(tmpdir(), 'movie-storage-handler-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  return root;
}

test('output storage is opt-in and never exposes the host list in public configuration', async () => {
  const disabled = createConfig({});
  assert.equal(disabled.outputStorageEnabled, false);
  assert.equal(config.outputStorageEnabled, true);
  assert.equal(publicSettings(config).outputAllowedHosts, undefined);
  assert.equal(Object.keys(publicSettings(config)).length, 8);
  const result = await createHandlers(disabled).convertToBlob(json(valid()), context);
  assert.equal(result.status, 503);
  assert.equal(errorCode(result), 'OUTPUT_STORAGE_DISABLED');
});

test('JSON source uses actual FFmpeg then stores exactly the completed M4A; no SAS is returned', async t => {
  const root = await workspace(t);
  let saved;
  const handlers = createHandlers(config, { temporaryRoot: root,
    download: async (url, path) => {
      assert.equal(url, 'https://media.example.com/meeting.mp4');
      await writeFile(path, await readFile(fixture));
    },
    upload: async (path, destination, options) => {
      assert.equal(destination, sasUrl);
      assert.equal(options.outputAllowedHosts.has(host), true);
      saved = await readFile(path);
      return { blobUrl, bytes: saved.length, etag: '"created-etag"', sasUrl: destination };
    } });
  const result = await handlers.convertToBlob(json(valid()), context);
  assert.equal(result.status, 201);
  assert.ok(saved.length > 1000);
  assert.equal(saved.subarray(4, 8).toString(), 'ftyp');
  const body = JSON.parse(result.body);
  assert.deepEqual(body, { status: 'succeeded', output: { blobUrl, bytes: saved.length, etag: '"created-etag"', contentType: 'audio/mp4' }, audio: { codec: 'aac', mode: 'copy' } });
  assert.ok(!result.body.includes(signature) && !result.body.includes('sig=') && !result.body.includes('sasUrl'));
  assert.deepEqual(await readdir(root), []);
});

test('multipart source works without enabling URL input and does not forward the supplied file name', async t => {
  const root = await workspace(t);
  const localConfig = createConfig({ CONVERSION_OUTPUT_ALLOWED_HOSTS: host });
  const form = new FormData();
  form.append('request', new Blob([JSON.stringify({ output: { sasUrl } })], { type: 'application/json' }), 'request.json');
  form.append('file', new Blob([await readFile(fixture)], { type: 'video/mp4' }), 'arbitrary-name.mp4');
  const handlers = createHandlers(localConfig, { temporaryRoot: root,
    download: () => assert.fail('No URL download for a multipart input'),
    upload: async (path, target) => {
      assert.equal(target, sasUrl);
      assert.equal(path.split('/').at(-1), 'audio.m4a');
      return { blobUrl, bytes: (await readFile(path)).length };
    } });
  const response = await handlers.convertToBlob(new Request('http://localhost/api/convert-to-blob', { method: 'POST', body: form }), context);
  assert.equal(response.status, 201);
  assert.deepEqual(await readdir(root), []);
});

test('disallowed destination is rejected before downloading input or invoking FFmpeg', async t => {
  const root = await workspace(t);
  const handlers = createHandlers(config, { temporaryRoot: root,
    download: () => assert.fail('Invalid destination must not fetch input'),
    extract: () => assert.fail('Invalid destination must not extract'),
    upload: () => assert.fail('Invalid destination must not upload') });
  const input = valid();
  input.output.sasUrl = sasUrl.replace(host, 'otheraccount.blob.core.windows.net');
  const response = await handlers.convertToBlob(json(input), context);
  assert.equal(response.status, 403);
  assert.equal(errorCode(response), 'OUTPUT_HOST_NOT_ALLOWED');
  assert.deepEqual(await readdir(root), []);
});

test('storage upload shares the admission slot with both download APIs', async t => {
  const root = await workspace(t);
  let reached;
  let finish;
  const uploading = new Promise(resolve => { reached = resolve; });
  const gate = new Promise(resolve => { finish = resolve; });
  const handlers = createHandlers(config, { temporaryRoot: root,
    download: async (url, path) => writeFile(path, 'video'),
    extract: async (input, output) => writeFile(output, 'audio'),
    upload: async () => { reached(); await gate; return { blobUrl, bytes: 5 }; } });
  const pending = handlers.convertToBlob(json(valid()), context);
  await uploading;
  assert.equal((await readdir(root)).length, 1);
  assert.equal(errorCode(await handlers.convert(new Request('http://localhost/api/convert', { method: 'POST', body: 'video' }), context)), 'CONVERSION_BUSY');
  assert.equal(errorCode(await handlers.convertUrl(json({ url: 'https://media.example.com/a' }), context)), 'CONVERSION_BUSY');
  assert.equal(errorCode(await handlers.convertToBlob(json(valid()), context)), 'CONVERSION_BUSY');
  finish();
  assert.equal((await pending).status, 201);
  assert.deepEqual(await readdir(root), []);
});

test('storage conflicts and aborts clean up locally and never retry the upload', async t => {
  const root = await workspace(t);
  for (const abort of [false, true]) {
    let attempts = 0;
    const short = { ...config, limits: { ...config.limits, timeoutSeconds: 0.03 } };
    const handlers = createHandlers(short, { temporaryRoot: root,
      download: async (url, path) => writeFile(path, 'video'),
      extract: async (input, output) => writeFile(output, 'audio'),
      upload: async (path, target, { signal }) => {
        attempts++;
        if (!abort) throw new ConversionError(409, 'OUTPUT_BLOB_EXISTS', 'The destination blob already exists.');
        await delay(100, undefined, { signal });
      } });
    const response = await handlers.convertToBlob(json(valid()), context);
    assert.equal(response.status, abort ? 504 : 409);
    assert.equal(attempts, 1);
    assert.deepEqual(await readdir(root), []);
  }
});
