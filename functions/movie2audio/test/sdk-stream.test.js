import assert from 'node:assert/strict';
import { copyFile, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Readable, Writable } from 'node:stream';
import { setTimeout as delay } from 'node:timers/promises';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import azureFunctions from '@azure/functions';
import { patchSdk } from '../scripts/patch-sdk.mjs';
import { createHandlers } from '../src/handlers.js';

const require = createRequire(import.meta.url);
const MODULE = fileURLToPath(new URL('../', import.meta.url));
const SDK = join(MODULE, 'node_modules', '@azure', 'functions');
const CHUNK = 64 * 1024;

async function workspace(t) {
  const directory = await mkdtemp(join(tmpdir(), 'movie2audio-sdk-test-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  return directory;
}

async function patchedCopy(t) {
  const root = await workspace(t);
  const destination = join(root, 'node_modules', '@azure', 'functions');
  await mkdir(join(destination, 'dist'), { recursive: true });
  await copyFile(join(SDK, 'package.json'), join(destination, 'package.json'));
  await copyFile(join(SDK, 'dist', 'azure-functions.js'), join(destination, 'dist', 'azure-functions.js'));
  await patchSdk(root);
  return { root, destination, text: await readFile(join(destination, 'dist', 'azure-functions.js'), 'utf8') };
}

function functionText(bundle, signature, exportName) {
  const start = bundle.indexOf(signature);
  const end = bundle.indexOf(`\nexports.${exportName} = ${exportName};`, start);
  assert.ok(start >= 0 && end > start, 'The tested function comes from the actual patched SDK bundle');
  return bundle.slice(start, end);
}

function responseSender(bundle, response) {
  const source = functionText(bundle, 'async function sendProxyResponse(', 'sendProxyResponse');
  return new Function('require', 'responses', 'nonNull_1', 'invocationIdHeader', 'setCookies',
    `${source}; return sendProxyResponse;`)(require, { test: response },
    { nonNullProp: (object, key) => object[key] }, 'x-ms-invocation-id', () => {});
}

function streamRequestFactory(bundle) {
  const source = functionText(bundle, 'function createStreamRequest(', 'createStreamRequest');
  return new Function('HttpRequest', 'nonNull_1', 'errors_1', '__rest', `${source}; return createStreamRequest;`)(
    class { constructor(init) { this.nativeRequest = init.nativeRequest; this.params = init.params; } },
    { nonNullProp: (object, key) => object[key], isDefined: value => value !== null && value !== undefined },
    { AzFuncSystemError: Error }, (object, excluded) => Object.fromEntries(
      Object.entries(object).filter(([key]) => !excluded.includes(key))));
}

class SlowResponse extends Writable {
  constructor(milliseconds = 2) {
    super({ highWaterMark: CHUNK });
    this.milliseconds = milliseconds;
    this.headers = new Map();
    this.maxQueuedBytes = 0;
    this.writtenBytes = 0;
    this.finishedWriting = false;
  }
  setHeader(name, value) { this.headers.set(name.toLowerCase(), value); }
  write(chunk, ...rest) {
    const accepted = super.write(chunk, ...rest);
    this.maxQueuedBytes = Math.max(this.maxQueuedBytes, this.writableLength);
    return accepted;
  }
  _write(chunk, encoding, callback) {
    this.writtenBytes += chunk.length;
    setTimeout(callback, this.milliseconds);
  }
  _final(callback) {
    setTimeout(() => { this.finishedWriting = true; callback(); }, this.milliseconds);
  }
}

test('SDK patch is idempotent, version pinned and rejects unexpected bundle changes', async t => {
  const { root, destination } = await patchedCopy(t);
  assert.equal((await patchSdk(root)).changed, false);
  const target = join(destination, 'dist', 'azure-functions.js');
  await writeFile(target, (await readFile(target, 'utf8')) + '\n// unexpected change\n');
  await assert.rejects(patchSdk(root), /pinned SHA256/);
  const metadata = JSON.parse(await readFile(join(destination, 'package.json'), 'utf8'));
  metadata.version = '4.16.3';
  await writeFile(join(destination, 'package.json'), JSON.stringify(metadata));
  await assert.rejects(patchSdk(root), /Unsupported @azure\/functions version/);
});

test('actual SDK response patch waits for slow writes and final completion without buffering the whole file', async t => {
  const { text } = await patchedCopy(t);
  let produced = 0;
  const chunks = 128;
  const source = Readable.from((async function* () {
    for (let index = 0; index < chunks; index++) { produced++; yield Buffer.alloc(CHUNK); }
  })());
  const userRes = new azureFunctions.HttpResponse({ status: 200, headers: { 'Content-Type': 'audio/mp4' }, body: source });
  const proxy = new SlowResponse(2);
  const sending = responseSender(text, proxy)('test', userRes);
  await delay(10);
  assert.ok(produced < 20, 'A slow consumer must stop the source well before all 128 chunks are read');
  await sending;
  assert.equal(proxy.writtenBytes, chunks * CHUNK);
  assert.ok(proxy.maxQueuedBytes <= 2 * CHUNK, 'The HTTP proxy write queue stays bounded by chunk/high-water marks');
  assert.equal(proxy.finishedWriting, true, 'Invocation delivery waits for writable completion');
  assert.equal(proxy.headers.get('content-type'), 'audio/mp4');
  assert.equal(proxy.headers.get('x-ms-invocation-id'), 'test');
});

test('a closed proxy cancels a pending response body read', async t => {
  const { text } = await patchedCopy(t);
  let cancelled = false;
  const body = new ReadableStream({ pull() { return new Promise(() => {}); }, cancel() { cancelled = true; } });
  const proxy = new SlowResponse();
  const sending = responseSender(text, proxy)('test', new azureFunctions.HttpResponse({ body }));
  const rejected = assert.rejects(sending);
  await delay(10);
  proxy.destroy(new Error('Client closed the proxy connection'));
  await rejected;
  assert.equal(cancelled, true);
});

test('closing the SDK proxy destroys the handler file stream, removes files and releases its slot', async t => {
  const { text } = await patchedCopy(t);
  const temporaryRoot = await workspace(t);
  const config = { limits: { maxInputBytes: 100 * CHUNK, maxOutputBytes: 100 * CHUNK, timeoutSeconds: 5 },
    allowedHosts: new Set(), urlEnabled: false };
  const handlers = createHandlers(config, { temporaryRoot,
    extract: async (input, output) => { await writeFile(output, Buffer.alloc(64 * CHUNK)); } });
  const request = () => ({ url: 'http://localhost/api/convert', headers: new Headers({ 'content-type': 'application/octet-stream' }),
    body: new Response(Buffer.from('test video')).body });
  const first = await handlers.convert(request(), {});
  assert.equal(first.status, 200);
  const proxy = new SlowResponse(20);
  const sending = responseSender(text, proxy)('test', new azureFunctions.HttpResponse(first));
  const rejected = assert.rejects(sending);
  await delay(10);
  proxy.destroy(new Error('Client disconnected'));
  await rejected;
  for (let attempt = 0; attempt < 100 && (await readdir(temporaryRoot)).length; attempt++) await delay(5);
  assert.deepEqual(await readdir(temporaryRoot), []);
  assert.equal(first.body.destroyed, true);
  const next = await handlers.convert(request(), {});
  assert.equal(next.status, 200);
  next.body.destroy();
  for (let attempt = 0; attempt < 100 && (await readdir(temporaryRoot)).length; attempt++) await delay(5);
  assert.deepEqual(await readdir(temporaryRoot), []);
});

test('SDK request headers come only from the proxy even when JSON overwrites trigger metadata', async t => {
  const { text } = await patchedCopy(t);
  const createRequest = streamRequestFactory(text);
  const body = '{"url":"https://example.com/movie.mp4","headers":{}}';
  const proxy = Readable.from([Buffer.from(body)]);
  proxy.method = 'POST';
  proxy.url = '/api/convert-url';
  proxy.headers = { 'x-forwarded-host': 'localhost:7073', 'x-forwarded-proto': 'http',
    'content-type': 'application/json', 'content-length': String(Buffer.byteLength(body)) };
  const result = createRequest(proxy, { Headers: { json: '{"content-type":"video/mp4","content-length":"1"}' },
    name: { string: 'real-route-param' } });
  assert.equal(result.nativeRequest.headers.get('content-type'), 'application/json');
  assert.equal(result.nativeRequest.headers.get('content-length'), String(Buffer.byteLength(body)));
  assert.equal(await result.nativeRequest.text(), body);
  assert.equal(result.params.name, 'real-route-param');
});

test('absent real Content-Type and Content-Length cannot be forged through metadata', async t => {
  const { text } = await patchedCopy(t);
  const proxy = Readable.from([Buffer.from('{}')]);
  proxy.method = 'POST';
  proxy.url = '/api/convert-url';
  proxy.headers = { 'x-forwarded-host': 'localhost:7073', 'x-forwarded-proto': 'http' };
  const result = streamRequestFactory(text)(proxy, { Headers: {
    json: '{"content-type":"application/json","content-length":"100"}',
  } });
  assert.equal(result.nativeRequest.headers.get('content-type'), null);
  assert.equal(result.nativeRequest.headers.get('content-length'), null);
  await result.nativeRequest.body.cancel();
});
