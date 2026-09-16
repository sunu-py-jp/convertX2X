import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Readable } from 'node:stream';
import { setTimeout as delay } from 'node:timers/promises';
import azureFunctions from '@azure/functions';
import { createConfig, publicSettings } from '../src/config.js';
import { createHandlers, parseUrlRequest } from '../src/handlers.js';
import { createPlayground } from '../src/playground.js';
import { ConversionError } from '../src/errors.js';

const context = { invocationId: 'test', error() {}, warn() {} };
const { HttpRequest, HttpResponse } = azureFunctions;
const config = createConfig({});
function request(body, contentType = 'application/octet-stream', headers = {}) {
  if (body instanceof ReadableStream) return new Request('http://localhost/api/convert', {
    method: 'POST', headers: { 'Content-Type': contentType, ...headers }, body, duplex: 'half',
  });
  return new HttpRequest({ url: 'http://localhost/api/convert', method: 'POST',
    headers: { 'Content-Type': contentType, ...headers }, body: body == null ? undefined : { bytes: Buffer.from(body) } });
}
async function waitFor(check) {
  for (let i = 0; i < 150; i++) {
    if (await check()) return;
    await delay(10);
  }
  assert.fail('Condition did not become true');
}
async function workspace(t) {
  const root = await mkdtemp(join(tmpdir(), 'movie-handler-test-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  return root;
}
function code(response) { return JSON.parse(response.body).error.code; }

test('configuration preserves defaults and validates bounded settings without exposing hosts', () => {
  assert.deepEqual(config.limits, { maxInputBytes: 104857600, maxOutputBytes: 104857600, timeoutSeconds: 180 });
  assert.deepEqual(createConfig({ CONVERSION_MAX_INPUT_BYTES: ' ' }).limits, config.limits);
  for (const value of ['0', '-1', '1.5', 'NaN', '1e5', '2147483640']) {
    assert.throws(() => createConfig({ CONVERSION_MAX_INPUT_BYTES: value }));
  }
  assert.throws(() => createConfig({ CONVERSION_TIMEOUT_SECONDS: '211' }));
  const actual = publicSettings(createConfig({ CONVERSION_URL_ALLOWED_HOSTS: 'media.example.com' }));
  assert.deepEqual(Object.keys(actual).sort(), ['asyncEnabled', 'audioCodec', 'audioFormats', 'audioMode', 'audioModes', 'maxInputBytes', 'maxOutputBytes', 'outputFormat', 'timeoutSeconds', 'urlEnabled']);
  assert.equal(actual.urlEnabled, true);
  assert.ok(!JSON.stringify(actual).includes('example.com'));
});

test('URL JSON accepts one escaped string field and rejects duplicates, malformed UTF-8 and extra values', () => {
  const url = 'https://media.example.com/video.mp4?token=abc';
  assert.equal(parseUrlRequest(Buffer.from(JSON.stringify({ url }))), url);
  assert.equal(parseUrlRequest(Buffer.from(String.raw`{"\u0075rl":"https:\/\/media.example.com/a"}`)), 'https://media.example.com/a');
  for (const value of ['{"url":"a","url":"b"}', '{"url":"a","headers":{}}', '{"url":"a"} {}',
    '{"url":5}', '{"url":" "}', '[]', '{"url":"a\nb"}', '{"url":"\\x"}', '{"URL":"a"}',
    '{"url":"a","\\u0075rl":"b"}', '\u00a0{"url":"a"}', '{\f"url":"a"}']) {
    assert.throws(() => parseUrlRequest(Buffer.from(value)), { code: 'INVALID_URL_REQUEST' });
  }
  assert.throws(() => parseUrlRequest(Buffer.from([0xff])), { code: 'INVALID_URL_REQUEST' });
});

test('upload reaches disk before EOF; output streams and keeps admission/temp files until consumed', async t => {
  const root = await workspace(t);
  const payload = Buffer.alloc(256 * 1024, 7);
  let inputController;
  let extracted = false;
  const upload = new ReadableStream({ start(c) { inputController = c; c.enqueue(Buffer.from('first')); } });
  const handlers = createHandlers(config, { temporaryRoot: root, extract: async (input, output) => {
    extracted = true;
    assert.equal(await readFile(input, 'utf8'), 'firstsecond');
    await writeFile(output, payload);
  } });
  const pending = handlers.convert(request(upload), context);
  await waitFor(async () => {
    const dirs = await readdir(root);
    if (!dirs.length) return false;
    return (await stat(join(root, dirs[0], 'input.bin')).catch(() => ({ size: 0 }))).size === 5;
  });
  assert.equal(extracted, false);
  inputController.enqueue(Buffer.from('second'));
  inputController.close();
  const response = await pending;
  assert.equal(response.status, 200);
  assert.ok(response.body instanceof Readable);
  assert.equal(response.headers['Content-Type'], 'audio/mp4');
  assert.equal(response.headers['Content-Length'], undefined);
  assert.equal((await readdir(root)).length, 1);
  assert.equal(code(await handlers.convert(request('other'), context)), 'CONVERSION_BUSY');
  // Use the actual Azure SDK response conversion, which is also used by the worker.
  const sdkResponse = new HttpResponse(response);
  const reader = sdkResponse.body.getReader();
  const first = await reader.read();
  assert.equal(first.done, false);
  assert.ok(first.value.byteLength <= 65536);
  const chunks = [Buffer.from(first.value)];
  while (true) {
    const next = await reader.read();
    if (next.done) break;
    chunks.push(Buffer.from(next.value));
  }
  assert.deepEqual(Buffer.concat(chunks), payload);
  await waitFor(async () => (await readdir(root)).length === 0);
});

test('response cancellation closes the file and releases admission', async t => {
  const root = await workspace(t);
  const handlers = createHandlers(config, { temporaryRoot: root, extract: async (input, output) => writeFile(output, Buffer.alloc(1024 * 1024)) });
  const response = await handlers.convert(request('video'), context);
  const reader = new HttpResponse(response).body.getReader();
  await reader.read();
  await reader.cancel();
  await waitFor(async () => (await readdir(root)).length === 0);
  const next = await handlers.convert(request('again'), context);
  assert.equal(next.status, 200);
  next.body.destroy();
  await waitFor(async () => (await readdir(root)).length === 0);
});

test('bounded upload aborts a stalled producer on timeout and removes workspace', async t => {
  const root = await workspace(t);
  let cancelled = false;
  const short = { ...config, limits: { ...config.limits, timeoutSeconds: 0.05 } };
  const handlers = createHandlers(short, { temporaryRoot: root, extract: () => assert.fail('Must not extract') });
  const keepAlive = setTimeout(() => {}, 2000);
  t.after(() => clearTimeout(keepAlive));
  const response = await handlers.convert(request(new ReadableStream({ cancel() { cancelled = true; } })), context);
  assert.equal(response.status, 504);
  assert.equal(code(response), 'CONVERSION_TIMEOUT');
  assert.equal(cancelled, true);
  assert.deepEqual(await readdir(root), []);
});

test('timeout closes an unconsumed response instead of holding the conversion slot forever', async t => {
  const root = await workspace(t);
  const short = { ...config, limits: { ...config.limits, timeoutSeconds: 0.05 } };
  const handlers = createHandlers(short, { temporaryRoot: root, extract: async (input, output) => writeFile(output, Buffer.alloc(1024 * 1024)) });
  const response = await handlers.convert(request('video'), context);
  assert.equal(response.status, 200);
  await waitFor(async () => (await readdir(root)).length === 0);
  assert.equal(response.body.destroyed, true);
});

test('body limits apply with and without Content-Length; failures clean partial files', async t => {
  const root = await workspace(t);
  const small = { ...config, limits: { ...config.limits, maxInputBytes: 4 } };
  const handlers = createHandlers(small, { temporaryRoot: root, extract: () => assert.fail('Must not extract') });
  for (const [body, headers] of [['12345', {}], ['1', { 'Content-Length': '5' }], [null, {}]]) {
    const response = await handlers.convert(request(body, 'application/octet-stream', headers), context);
    assert.equal(code(response), body === null ? 'EMPTY_INPUT' : 'INPUT_TOO_LARGE');
    assert.deepEqual(await readdir(root), []);
  }
  assert.equal(code(await handlers.convert(request('a', 'application/json'), context)), 'RAW_BODY_REQUIRED');
  assert.equal(code(await handlers.convert(request('a', 'multipart/form-data; boundary=x'), context)), 'RAW_BODY_REQUIRED');
});

test('URL and file entry points share extraction and reject bounded invalid JSON', async t => {
  const root = await workspace(t);
  const enabled = createConfig({ CONVERSION_URL_ALLOWED_HOSTS: 'media.example.com' });
  let downloaded = false;
  const handlers = createHandlers(enabled, { temporaryRoot: root,
    download: async (url, path, options) => {
      assert.equal(url, 'https://media.example.com/v.mp4');
      assert.ok(options.signal instanceof AbortSignal);
      downloaded = true;
      await writeFile(path, 'video');
    }, extract: async (input, output) => {
      assert.equal(await readFile(input, 'utf8'), 'video');
      await writeFile(output, 'audio');
    } });
  const response = await handlers.convertUrl(request('{"url":"https://media.example.com/v.mp4"}', 'application/json'), context);
  assert.equal(downloaded, true);
  assert.equal(await new HttpResponse(response).text(), 'audio');
  await waitFor(async () => (await readdir(root)).length === 0);
  assert.equal(code(await handlers.convertUrl(request('a'.repeat(16385), 'application/json'), context)), 'REQUEST_TOO_LARGE');
  assert.equal(code(await handlers.convertUrl(request('{"url":"a","url":"b"}', 'application/json'), context)), 'INVALID_URL_REQUEST');
  assert.equal(code(await createHandlers(config).convertUrl(request('{"url":"https://example.com/a"}', 'application/json'), context)), 'URL_FETCH_DISABLED');
});

test('extraction error removes files and returns a safe JSON error', async t => {
  const root = await workspace(t);
  const handlers = createHandlers(config, { temporaryRoot: root, extract: async () => { throw new ConversionError(422, 'NO_AUDIO_STREAM', 'No audio.'); } });
  const response = await handlers.convert(request('video'), context);
  assert.equal(response.status, 422);
  assert.equal(code(response), 'NO_AUDIO_STREAM');
  assert.deepEqual(await readdir(root), []);
});

test('Playground keeps CSP and only serves named assets and public settings', async () => {
  const playground = createPlayground(config);
  const page = await playground.page({ url: 'http://localhost/api/playground' });
  assert.equal(page.status, 200);
  assert.match(page.body.toString(), /Movie/);
  assert.match(page.headers['Content-Security-Policy'], /default-src 'none'/);
  assert.equal(playground.page({ url: 'http://localhost/api/playground/' }).headers.Location, '/api/playground');
  assert.equal((await playground.asset({ params: { name: 'app.js' } })).status, 200);
  for (const name of ['../../host.json', '__proto__', 'constructor']) assert.equal(playground.asset({ params: { name } }).status, 404);
  assert.deepEqual(JSON.parse(playground.configuration().body), publicSettings(config));
});
