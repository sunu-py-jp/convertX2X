import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Readable } from 'node:stream';
import { setTimeout as delay } from 'node:timers/promises';
import { readStorageRequest } from '../src/storage-request.js';
import { ConversionError } from '../src/errors.js';

const options = { limits: { maxInputBytes: 1024 * 1024 } };
const inputUrl = 'https://media.example.com/video.mp4?source=1';
const sasUrl = 'https://account.blob.core.windows.net/audio/result.m4a?sv=2025-11-05&sig=secret';
const validJson = JSON.stringify({ input: { url: inputUrl }, output: { sasUrl } });
const metadataJson = JSON.stringify({ output: { sasUrl } });
const boundary = 'Test-Storage-Form-123';
const formType = `multipart/form-data; boundary="${boundary}"`;

function request(body, contentType = 'application/json', headers = {}) {
  return new Request('http://localhost/api/convert-to-blob', {
    method: 'POST', headers: { 'content-type': contentType, ...headers },
    ...(body == null ? {} : { body, duplex: 'half' }),
  });
}

function multipart(parts, ending = true) {
  return Buffer.concat([
    ...parts.flatMap(part => [Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="${part.name}"`
      + (part.filename === null ? '' : `; filename="${part.filename ?? (part.name === 'request' ? 'request.json' : 'video.mp4')}"`)
      + `\r\nContent-Type: ${part.type ?? (part.name === 'request' ? 'application/json' : 'video/mp4')}\r\n`
      + (part.encoding ? `Content-Transfer-Encoding: ${part.encoding}\r\n` : '') + '\r\n'),
    Buffer.from(part.body ?? (part.name === 'request' ? metadataJson : 'video')), Buffer.from('\r\n')]),
    ...(ending ? [Buffer.from(`--${boundary}--\r\n`)] : []),
  ]);
}

function split(bytes, count = 7) {
  return Readable.toWeb(Readable.from((function* () {
    for (let index = 0; index < bytes.length; index += count) yield bytes.subarray(index, index + count);
  })()));
}

async function workspace(t) {
  const root = await mkdtemp(join(tmpdir(), 'storage-request-test-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  return { root, input: join(root, 'input.bin') };
}

async function waitFor(check) {
  for (let index = 0; index < 200; index++) {
    if (await check()) return;
    await delay(5);
  }
  assert.fail('Condition did not become true');
}

test('storage JSON accepts either key order and JSON escapes without writing a file', async t => {
  const { root, input } = await workspace(t);
  for (const value of [validJson,
    JSON.stringify({ output: { sasUrl }, input: { url: inputUrl } }),
    String.raw`{"\u0069nput":{"\u0075rl":"https:\/\/media.example.com/video.mp4?source=1"},"output":{"sas\u0055rl":` + JSON.stringify(sasUrl) + '}}']) {
    assert.deepEqual(await readStorageRequest(request(split(Buffer.from(value), 1)), input, options), { inputUrl, sasUrl });
  }
  assert.deepEqual(await readdir(root), []);
});

test('storage JSON rejects duplicate decoded keys at every nesting level', async t => {
  const { input } = await workspace(t);
  for (const value of [
    '{"input":{"url":"a"},"input":{"url":"b"},"output":{"sasUrl":"c"}}',
    '{"input":{"url":"a","\\u0075rl":"b"},"output":{"sasUrl":"c"}}',
    '{"input":{"url":"a"},"output":{"sasUrl":"b","sas\\u0055rl":"c"}}',
    '{"input":{"url":"a"},"output":{"sasUrl":"b"},"\\u006futput":{"sasUrl":"c"}}',
  ]) await assert.rejects(readStorageRequest(request(value), input, options), { status: 400, code: 'INVALID_STORAGE_REQUEST' });
});

test('storage JSON rejects invalid schemas, unbounded nesting, trailing values and malformed UTF-8', async t => {
  const { input } = await workspace(t);
  for (const value of ['[]', 'null', '{}', validJson + '{}', '\ufeff' + validJson, '\u00a0' + validJson,
    '{"input":{"url":true},"output":{"sasUrl":"b"}}',
    '{"input":{"url":" "},"output":{"sasUrl":"b"}}',
    '{"input":{"url":"a"},"output":{"sasUrl":""}}',
    '{"input":{"url":"a","headers":{}},"output":{"sasUrl":"b"}}',
    '{"input":{"url":"a"},"output":{"sasUrl":"b","overwrite":"true"}}',
    '{"input":{"url":"a"},"output":{"sasUrl":"b"},"headers":{}}',
    '{"input":{"url":{"nested":{"nested":{"nested":"a"}}}},"output":{"sasUrl":"b"}}',
    '{"input":{"url":"a",},"output":{"sasUrl":"b"}}',
    '{"input":{"url":"\\x"},"output":{"sasUrl":"b"}}',
    Buffer.from([0xff]), Buffer.concat([Buffer.from('{"input":{"url":"'), Buffer.from([0xc0, 0xaf]), Buffer.from('"},"output":{"sasUrl":"b"}}')])]) {
    await assert.rejects(readStorageRequest(request(value), input, options), { code: 'INVALID_STORAGE_REQUEST' });
  }
});

test('both URLs have independent 8192 character limits', async t => {
  const { input } = await workspace(t);
  const accepted = JSON.stringify({ input: { url: 'a'.repeat(8192) }, output: { sasUrl: 'b'.repeat(8192) } });
  assert.equal((await readStorageRequest(request(accepted), input, options)).sasUrl.length, 8192);
  for (const value of [
    { input: { url: 'a'.repeat(8193) }, output: { sasUrl: 'b' } },
    { input: { url: 'a' }, output: { sasUrl: 'b'.repeat(8193) } },
  ]) await assert.rejects(readStorageRequest(request(JSON.stringify(value)), input, options), { code: 'INVALID_STORAGE_REQUEST' });
});

test('JSON has a 32 KiB body cap with and without Content-Length', async t => {
  const { input } = await workspace(t);
  for (const req of [request(' '.repeat(32769)), request(validJson, 'application/json', { 'content-length': '32769' })]) {
    await assert.rejects(readStorageRequest(req, input, options), { status: 413, code: 'REQUEST_TOO_LARGE' });
  }
  await assert.rejects(readStorageRequest(request(validJson, 'application/json', { 'content-length': '-1' }), input, options), { code: 'INVALID_STORAGE_REQUEST' });
  for (const value of [null, '']) await assert.rejects(readStorageRequest(request(value), input, options), { code: 'EMPTY_INPUT' });
});

test('unsupported content types return a safe 415', async t => {
  const { input } = await workspace(t);
  for (const type of ['application/octet-stream', 'text/plain', 'multipart/mixed']) {
    await assert.rejects(readStorageRequest(request('secret', type), input, options), { status: 415, code: 'STORAGE_REQUEST_TYPE_REQUIRED' });
  }
});

for (const metadataFirst of [false, true]) {
  test(`multipart preserves every binary byte with split boundaries and metadata ${metadataFirst ? 'first' : 'last'}`, async t => {
    const { input } = await workspace(t);
    const bytes = Buffer.concat([Buffer.from(Array.from({ length: 256 }, (_, index) => index)), Buffer.alloc(100_000, 0xff)]);
    const parts = [{ name: 'file', body: bytes, filename: '../../../../unused.mp4' }, { name: 'request' }];
    if (metadataFirst) parts.reverse();
    assert.deepEqual(await readStorageRequest(request(split(multipart(parts), 13), formType), input, options), { inputUrl: null, sasUrl });
    assert.deepEqual(await readFile(input), bytes);
    assert.equal((await stat(input)).mode & 0o777, 0o600);
  });
}

test('browser FormData JSON Blob and video Blob are supported', async t => {
  const { input } = await workspace(t);
  const form = new FormData();
  form.append('request', new Blob([metadataJson], { type: 'application/json' }), 'request.json');
  form.append('file', new Blob([Buffer.from([0, 1, 255, 13, 10])], { type: 'video/mp4' }), 'video.mp4');
  const req = new Request('http://localhost/api/convert-to-blob', { method: 'POST', body: form });
  assert.deepEqual(await readStorageRequest(req, input, options), { inputUrl: null, sasUrl });
  assert.deepEqual(await readFile(input), Buffer.from([0, 1, 255, 13, 10]));
});

test('multipart uploads reach disk before EOF with bounded producer read-ahead', async t => {
  const { input } = await workspace(t);
  const header = multipart([{ name: 'file', body: '' }], false).subarray(0, -2);
  const footer = Buffer.concat([Buffer.from('\r\n'), multipart([{ name: 'request' }])]);
  const chunk = Buffer.alloc(64 * 1024, 9);
  let generated = 0;
  let maxAhead = 0;
  let release;
  const pause = new Promise(resolve => { release = resolve; });
  let finished = false;
  const stream = Readable.toWeb(Readable.from((async function* () {
    yield header;
    for (let index = 0; index < 32; index++) {
      const size = (await stat(input).catch(() => ({ size: 0 }))).size;
      maxAhead = Math.max(maxAhead, generated - size);
      generated += chunk.length;
      yield chunk;
      if (index === 15) await pause;
    }
    yield footer;
    finished = true;
  })(), { highWaterMark: 1 }));
  const pending = readStorageRequest(request(stream, formType), input, { limits: { maxInputBytes: 3 * 1024 * 1024 } });
  await waitFor(async () => (await stat(input).catch(() => ({ size: 0 }))).size >= 12 * 64 * 1024);
  assert.equal(finished, false);
  release();
  await pending;
  assert.equal((await stat(input)).size, 2 * 1024 * 1024);
  assert.ok(maxAhead <= 512 * 1024, `Producer read-ahead was ${maxAhead} bytes`);
});

test('malformed UTF-8 in metadata is rejected as raw bytes, regardless of part charset', async t => {
  const { root, input } = await workspace(t);
  const bad = Buffer.concat([Buffer.from('{"output":{"sasUrl":"https://example.com/'), Buffer.from([0xff]), Buffer.from('"}}')]);
  for (const type of ['application/json', 'application/json; charset=iso-8859-1']) {
    await assert.rejects(readStorageRequest(request(multipart([{ name: 'file' }, { name: 'request', body: bad, type }]), formType), input, options), { code: 'INVALID_STORAGE_REQUEST' });
    assert.deepEqual(await readdir(root), []);
  }
});

test('multipart metadata rejects input fields, unexpected keys and duplicates', async t => {
  const { root, input } = await workspace(t);
  for (const body of [validJson, '{"output":{"sasUrl":"a","sasUrl":"b"}}', '{"output":{"sasUrl":"a"},"headers":{}}']) {
    await assert.rejects(readStorageRequest(request(multipart([{ name: 'file' }, { name: 'request', body }]), formType), input, options), { code: 'INVALID_STORAGE_REQUEST' });
    assert.deepEqual(await readdir(root), []);
  }
});

test('multipart rejects text fields, duplicate parts, unknown parts and wrong JSON part MIME type', async t => {
  const { root, input } = await workspace(t);
  for (const parts of [
    [{ name: 'file' }, { name: 'request', filename: null }],
    [{ name: 'file' }, { name: 'file' }, { name: 'request' }],
    [{ name: 'request' }, { name: 'request' }, { name: 'file' }],
    [{ name: 'file' }, { name: 'request' }, { name: 'extra' }],
    [{ name: 'extra' }, { name: 'file' }, { name: 'request' }],
    [{ name: 'file' }, { name: 'request', type: 'text/plain' }],
    [{ name: 'file' }, { name: 'request', encoding: 'base64' }],
    [{ name: 'file', encoding: 'base64' }, { name: 'request' }],
    [{ name: 'file' }], [{ name: 'request' }],
  ]) {
    await assert.rejects(readStorageRequest(request(multipart(parts), formType), input, options), { code: 'INVALID_MULTIPART' });
    assert.deepEqual(await readdir(root), []);
  }
});

test('multipart file limit is inclusive and metadata has a separate 16 KiB limit', async t => {
  const { root, input } = await workspace(t);
  const small = { limits: { maxInputBytes: 4 } };
  await readStorageRequest(request(multipart([{ name: 'file', body: '1234' }, { name: 'request' }]), formType), input, small);
  assert.equal(await readFile(input, 'utf8'), '1234');
  await rm(input);
  await assert.rejects(readStorageRequest(request(multipart([{ name: 'file', body: '12345' }, { name: 'request' }]), formType), input, small), { code: 'INPUT_TOO_LARGE' });
  assert.deepEqual(await readdir(root), []);
  await assert.rejects(readStorageRequest(request(multipart([{ name: 'file' }, { name: 'request', body: ' '.repeat(16385) }]), formType), input, options), { code: 'REQUEST_TOO_LARGE' });
  assert.deepEqual(await readdir(root), []);
});

test('multipart envelope overhead is bounded even when video is much smaller than its cap', async t => {
  const { root, input } = await workspace(t);
  const body = Buffer.concat([multipart([{ name: 'file' }, { name: 'request' }]), Buffer.alloc(64 * 1024, 32)]);
  await assert.rejects(readStorageRequest(request(body, formType), input, options), { code: 'REQUEST_TOO_LARGE' });
  assert.deepEqual(await readdir(root), []);
  await assert.rejects(readStorageRequest(request('a', formType, { 'content-length': String(options.limits.maxInputBytes + 65537) }), input, options), { code: 'REQUEST_TOO_LARGE' });
});

test('empty or truncated multipart bodies fail and remove partially written files', async t => {
  const { root, input } = await workspace(t);
  await assert.rejects(readStorageRequest(request(multipart([{ name: 'file', body: '' }, { name: 'request' }]), formType), input, options), { code: 'EMPTY_INPUT' });
  assert.deepEqual(await readdir(root), []);
  await assert.rejects(readStorageRequest(request(multipart([{ name: 'file' }, { name: 'request' }], false), formType), input, options), { code: 'INVALID_MULTIPART' });
  assert.deepEqual(await readdir(root), []);
  for (const body of [null, '']) await assert.rejects(readStorageRequest(request(body, formType), input, options), { code: 'EMPTY_INPUT' });
  await assert.rejects(readStorageRequest(request('video', 'multipart/form-data'), input, options), { code: 'INVALID_MULTIPART' });
});

test('existing temporary path is preserved without overwrite or deletion', async t => {
  const { input } = await workspace(t);
  await writeFile(input, 'existing');
  await assert.rejects(readStorageRequest(request(multipart([{ name: 'file' }, { name: 'request' }]), formType), input, options), { code: 'STORAGE_ERROR' });
  assert.equal(await readFile(input, 'utf8'), 'existing');
});

test('abort cancels a stalled multipart source, waits for writes, and removes only its partial file', async t => {
  const { root, input } = await workspace(t);
  const controller = new AbortController();
  let cancelled = false;
  const partial = multipart([{ name: 'file', body: Buffer.alloc(128 * 1024, 1) }], false);
  const stream = new ReadableStream({ start(c) { c.enqueue(partial); }, cancel() { cancelled = true; } });
  const pending = readStorageRequest(request(stream, formType), input, { ...options, signal: controller.signal });
  await waitFor(async () => (await stat(input).catch(() => ({ size: 0 }))).size > 0);
  const reason = new ConversionError(504, 'CONVERSION_TIMEOUT', 'Deadline exceeded.');
  controller.abort(reason);
  await assert.rejects(pending, error => error === reason);
  assert.equal(cancelled, true);
  assert.deepEqual(await readdir(root), []);
});

test('already aborted requests do not create files or replace the original deadline error', async t => {
  const { root, input } = await workspace(t);
  const reason = new ConversionError(504, 'CONVERSION_TIMEOUT', 'Deadline exceeded.');
  for (const req of [request(validJson), request(multipart([{ name: 'file' }, { name: 'request' }]), formType)]) {
    await assert.rejects(readStorageRequest(req, input, { ...options, signal: AbortSignal.abort(reason) }), error => error === reason);
    assert.deepEqual(await readdir(root), []);
  }
});

test('public parser failures do not include request body, SAS signature, filenames or filesystem paths', async t => {
  const { input } = await workspace(t);
  const req = request(multipart([{ name: 'file', filename: 'private-name.mp4' }, { name: 'request', body: metadataJson + 'bad-secret' }]), formType);
  await assert.rejects(readStorageRequest(req, input, options), error => {
    assert.ok(error instanceof ConversionError);
    for (const value of ['secret', 'private-name', sasUrl, input]) assert.ok(!error.message.includes(value));
    return true;
  });
});
