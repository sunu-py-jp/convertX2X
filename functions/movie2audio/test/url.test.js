import test from 'node:test';
import assert from 'node:assert/strict';
import dns from 'node:dns';
import https from 'node:https';
import tls from 'node:tls';
import { EventEmitter } from 'node:events';
import { Readable } from 'node:stream';
import { mkdtemp, readFile, writeFile, rm, access, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { parseAllowedHosts, validateUrl, isPublicAddress, downloadVideo } from '../src/url.js';
import { ConversionError } from '../src/errors.js';

const hosts = new Set(['media.example.com']);
const source = 'https://media.example.com/video.mp4?sig=private-secret';
const limits = { maxInputBytes: 4, timeoutSeconds: 2 };
const hasCode = code => error => error instanceof ConversionError && error.code === code && !String(error).includes('private-secret');
const options = extra => ({ allowedHosts: hosts, limits, ...extra });

async function outputPath(t) {
  const directory = await mkdtemp(join(tmpdir(), 'movie-url-test-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  return join(directory, 'input');
}

function mockRemote(t, { chunks = [Buffer.from('test')], status = 200, headers = {}, rawHeaders = [], addresses = [{ address: '8.8.8.8', family: 4 }], lookupError, requestError, beforeResponse, complete = true } = {}) {
  const calls = { dns: [], requests: [], tls: [], destroyed: 0 };
  t.mock.method(dns, 'lookup', (host, opts, callback) => {
    calls.dns.push({ host, opts });
    queueMicrotask(() => callback(lookupError, addresses));
  });
  t.mock.method(tls, 'connect', settings => { calls.tls.push(settings); return {}; });
  t.mock.method(https, 'request', (url, settings, callback) => {
    calls.requests.push({ url, settings });
    settings.agent.createConnection({ host: url.hostname, port: 443 });
    const req = new EventEmitter();
    req.setTimeout = (ms, fn) => { req.timeout = { ms, fn }; return req; };
    req.destroy = error => { calls.destroyed++; if (error) queueMicrotask(() => req.emit('error', error)); return req; };
    req.end = () => queueMicrotask(async () => {
      if (requestError) { req.emit('error', requestError); return; }
      await beforeResponse?.();
      const response = Readable.from(chunks);
      response.statusCode = status;
      response.headers = headers;
      response.rawHeaders = rawHeaders;
      response.complete = complete;
      calls.response = response;
      callback(response);
    });
    return req;
  });
  return calls;
}

test('canonicalizes exact host configuration and keeps signed paths and queries', () => {
  assert.deepEqual(parseAllowedHosts(' Media.Example.com,media.example.com,cdn.example.com '), new Set(['media.example.com', 'cdn.example.com']));
  assert.deepEqual(parseAllowedHosts(' '), new Set());
  assert.deepEqual(parseAllowedHosts(undefined), new Set());
  const url = validateUrl('https://Media.Example.com:443/a%20b.mp4?sig=a%2Fb%2Bc%3D&se=2026-09-16', hosts);
  assert.equal(url.pathname, '/a%20b.mp4');
  assert.equal(url.search, '?sig=a%2Fb%2Bc%3D&se=2026-09-16');
});

for (const value of ['*.example.com', 'https://example.com', 'example.com:443', '127.0.0.1', '2130706433', '[::1]', 'example.com,', 'localhost', 'metadata.google.internal', 'host.local', 'a..com', 'example.com.', 'host.test', 'user@example.com', '-x.example.com', 'host.onion']) {
  test(`rejects unsafe configured host ${value}`, () => assert.throws(() => parseAllowedHosts(value)));
}

test('bounds configured host count and length', () => {
  assert.throws(() => parseAllowedHosts(Array.from({ length: 33 }, (_, i) => `host${i}.example.com`).join(',')));
  assert.throws(() => parseAllowedHosts('a'.repeat(8193)));
});

for (const value of ['http://media.example.com/video.mp4', 'file:///etc/passwd', 'https://media.example.com:8443/video.mp4', 'https://user:pass@media.example.com/a', 'https://@media.example.com/a', 'https://media.example.com/a#fragment', 'https://media.example.com/a#', 'https://127.0.0.1/a', 'https://[::1]/a', 'https://2130706433/a', 'https://media.example.com./a', ' https://media.example.com/a', 'https://media.example.com\\@127.0.0.1/a', 'https://media.example.com/a\r\nInjected: yes', 'https:media.example.com/a', 'https://%6dedia.example.com/a']) {
  test(`rejects ambiguous or unsafe URL ${JSON.stringify(value)}`, () => assert.throws(() => validateUrl(value, hosts), hasCode('INVALID_URL')));
}

test('requires explicit URL enablement and exact host equality', () => {
  assert.throws(() => validateUrl(source, new Set()), hasCode('URL_FETCH_DISABLED'));
  for (const host of ['sub.media.example.com', 'media.example.com.evil.com']) {
    assert.throws(() => validateUrl(`https://${host}/a`, hosts), hasCode('URL_HOST_NOT_ALLOWED'));
  }
});

for (const address of ['0.0.0.0', '10.1.2.3', '100.64.0.1', '100.127.255.255', '127.1.2.3', '169.254.169.254', '172.16.0.1', '172.31.255.255', '192.0.0.8', '192.0.2.1', '192.88.99.1', '192.168.1.1', '198.18.0.1', '198.19.255.255', '198.51.100.1', '203.0.113.1', '224.0.0.1', '240.0.0.1', '255.255.255.255', '168.63.129.16', '::', '::1', 'fe80::1', 'fc00::1', 'ff02::1', '::ffff:127.0.0.1', '::ffff:8.8.8.8', '64:ff9b::7f00:1', '2001::7f00:1', '2001:db8::1', '2002:7f00:1::1', '3fff::1', 'garbage', '8.8.8.8%lo0']) {
  test(`blocks private, reserved, metadata or tunneled address ${address}`, () => assert.equal(isPublicAddress(address), false));
}

for (const address of ['8.8.8.8', '1.1.1.1', '100.128.0.1', '172.32.0.1', '2001:4860:4860::8888', '2606:4700:4700::1111']) {
  test(`accepts public address ${address}`, () => assert.equal(isPublicAddress(address), true));
}

test('pins actual TLS destination to validated DNS answer while verifying original hostname', async t => {
  const calls = mockRemote(t, { headers: { 'content-length': '4' }, addresses: [{ address: '8.8.8.8', family: 4 }, { address: '1.1.1.1', family: 4 }] });
  const output = await outputPath(t);
  assert.deepEqual(await downloadVideo(source, output, options()), { bytes: 4 });
  assert.equal(await readFile(output, 'utf8'), 'test');
  assert.equal(calls.dns.length, 1);
  assert.deepEqual(calls.dns[0], { host: 'media.example.com', opts: { all: true, verbatim: true } });
  assert.equal(calls.requests.length, 1);
  assert.equal(calls.requests[0].url.search, '?sig=private-secret');
  assert.deepEqual(calls.requests[0].settings.headers, { Accept: 'video/*, application/octet-stream;q=0.9', 'Accept-Encoding': 'identity', 'User-Agent': 'convertX2X-movie2audio/1.0' });
  assert.equal(calls.tls[0].host, '8.8.8.8');
  assert.equal(calls.tls[0].port, 443);
  assert.equal(calls.tls[0].servername, 'media.example.com');
  assert.equal(calls.tls[0].rejectUnauthorized, true);
  assert.equal(calls.tls[0].checkServerIdentity, tls.checkServerIdentity);
  assert.equal(calls.tls[0].autoSelectFamily, false);
  assert.equal(calls.response.destroyed, true);
});

test('direct connection remains pinned even if HTTPS, ALL and Node proxy environment variables are set', async t => {
  for (const name of ['HTTPS_PROXY', 'ALL_PROXY', 'NODE_USE_ENV_PROXY']) {
    const previous = process.env[name];
    t.after(() => { if (previous === undefined) delete process.env[name]; else process.env[name] = previous; });
    process.env[name] = name === 'NODE_USE_ENV_PROXY' ? '1' : 'http://127.0.0.1:3128';
  }
  const calls = mockRemote(t);
  await downloadVideo(source, await outputPath(t), options());
  assert.equal(calls.tls[0].host, '8.8.8.8');
  assert.deepEqual(calls.requests[0].settings.agent.options.proxyEnv, {});
});

test('refuses entire DNS response if any address is nonpublic', async t => {
  const calls = mockRemote(t, { addresses: [{ address: '8.8.8.8', family: 4 }, { address: '127.0.0.1', family: 4 }] });
  const output = await outputPath(t);
  await assert.rejects(downloadVideo(source, output, options()), hasCode('URL_ADDRESS_NOT_ALLOWED'));
  assert.equal(calls.requests.length, 0);
  await assert.rejects(access(output));
});

test('validates DNS results on every new download', async t => {
  const calls = mockRemote(t);
  const output = await outputPath(t);
  await downloadVideo(source, output, options());
  t.mock.method(dns, 'lookup', (host, opts, callback) => queueMicrotask(() => callback(null, [{ address: '169.254.169.254', family: 4 }])));
  await assert.rejects(downloadVideo(source, `${output}-second`, options()), hasCode('URL_ADDRESS_NOT_ALLOWED'));
  assert.equal(calls.requests.length, 1);
});

test('does not leak DNS or transport exception details', async t => {
  mockRemote(t, { lookupError: new Error('private-secret') });
  await assert.rejects(downloadVideo(source, await outputPath(t), options()), error => hasCode('URL_FETCH_FAILED')(error) && error.cause === undefined);
  t.mock.restoreAll();
  mockRemote(t, { requestError: new Error('private-secret') });
  await assert.rejects(downloadVideo(source, await outputPath(t), options()), error => hasCode('URL_FETCH_FAILED')(error) && error.cause === undefined);
});

test('does not stack OS DNS jobs after cancellation; permits work once lookup really finishes', async t => {
  const calls = mockRemote(t);
  let pendingCallback;
  let count = 0;
  t.mock.method(dns, 'lookup', (host, opts, callback) => { count++; pendingCallback = callback; });
  const output = await outputPath(t);
  const controller = new AbortController();
  const first = downloadVideo(source, output, options({ signal: controller.signal }));
  while (!pendingCallback) await new Promise(resolve => setImmediate(resolve));
  controller.abort(new ConversionError(504, 'CONVERSION_TIMEOUT', 'The operation exceeded its time limit'));
  await assert.rejects(first, hasCode('CONVERSION_TIMEOUT'));
  await assert.rejects(downloadVideo(source, `${output}-second`, options()), hasCode('URL_FETCH_FAILED'));
  assert.equal(count, 1);
  assert.equal(calls.requests.length, 0);
  pendingCallback(null, [{ address: '8.8.8.8', family: 4 }]);
  await new Promise(resolve => setImmediate(resolve));
  t.mock.method(dns, 'lookup', (host, opts, callback) => queueMicrotask(() => callback(null, [{ address: '8.8.8.8', family: 4 }])));
  await downloadVideo(source, `${output}-third`, options());
});

test('denies URLs before creating a file or attempting DNS', async t => {
  const calls = mockRemote(t);
  const output = await outputPath(t);
  await assert.rejects(downloadVideo('https://attacker.example.com/a?sig=private-secret', output, options()), hasCode('URL_HOST_NOT_ALLOWED'));
  assert.equal(calls.dns.length, 0);
  await assert.rejects(access(output));
});

for (const makeExisting of [async output => writeFile(output, 'preserve'), async output => { await writeFile(`${output}-target`, 'preserve'); await symlink(`${output}-target`, output); }]) {
  test('preserves existing file or symlink without starting a request', async t => {
    const calls = mockRemote(t);
    const output = await outputPath(t);
    await makeExisting(output);
    await assert.rejects(downloadVideo(source, output, options()), hasCode('STORAGE_ERROR'));
    assert.equal(await readFile(output, 'utf8'), 'preserve');
    assert.equal(calls.requests.length, 0);
    assert.equal(calls.dns.length, 0);
  });
}

test('preserves a path created by another caller between validation and opening', async t => {
  const output = await outputPath(t);
  mockRemote(t, { beforeResponse: () => writeFile(output, 'preserve') });
  await assert.rejects(downloadVideo(source, output, options()), hasCode('STORAGE_ERROR'));
  assert.equal(await readFile(output, 'utf8'), 'preserve');
});

test('accepts exactly maximum bytes with no Content-Length and streams successive chunks', async t => {
  mockRemote(t, { chunks: [Buffer.from('te'), Buffer.from('st')] });
  const output = await outputPath(t);
  assert.deepEqual(await downloadVideo(source, output, options()), { bytes: 4 });
  assert.equal(await readFile(output, 'utf8'), 'test');
});

for (const specimen of [
  { label: 'unknown length overflow', chunks: [Buffer.from('test'), Buffer.from('!')], code: 'INPUT_TOO_LARGE' },
  { label: 'declared large file', headers: { 'content-length': '5' }, code: 'INPUT_TOO_LARGE' },
  { label: 'huge declared length', headers: { 'content-length': '999999999999999999999999999' }, code: 'INPUT_TOO_LARGE' },
  { label: 'zero declared length', headers: { 'content-length': '0' }, code: 'EMPTY_INPUT' },
  { label: 'empty body', chunks: [], code: 'EMPTY_INPUT' },
  { label: 'redirect', status: 302, headers: { location: 'http://169.254.169.254/' }, code: 'URL_REDIRECT_NOT_ALLOWED' },
  { label: 'missing file', status: 404, code: 'URL_FETCH_FAILED' },
  { label: 'gzip body', headers: { 'content-encoding': 'gzip' }, code: 'URL_ENCODING_NOT_SUPPORTED' },
  { label: 'negative length', headers: { 'content-length': '-1' }, code: 'URL_FETCH_FAILED' },
  { label: 'noninteger length', headers: { 'content-length': '1.5' }, code: 'URL_FETCH_FAILED' },
  { label: 'multiple length header values', headers: { 'content-length': ['4', '4'] }, code: 'URL_FETCH_FAILED' },
  { label: 'duplicate raw length headers', headers: { 'content-length': '4' }, rawHeaders: ['Content-Length', '4', 'Content-Length', '4'], code: 'URL_FETCH_FAILED' },
  { label: 'ambiguous chunked and length', headers: { 'content-length': '4', 'transfer-encoding': 'chunked' }, code: 'URL_FETCH_FAILED' },
  { label: 'length mismatch', chunks: [Buffer.from('ab')], headers: { 'content-length': '4' }, code: 'URL_FETCH_FAILED' },
  { label: 'incomplete response', complete: false, code: 'URL_FETCH_FAILED' },
]) {
  test(`rejects ${specimen.label} and removes only its own partial file`, async t => {
    const calls = mockRemote(t, specimen);
    const output = await outputPath(t);
    await assert.rejects(downloadVideo(source, output, options()), hasCode(specimen.code));
    await assert.rejects(access(output));
    assert.equal(calls.requests.length, 1);
    assert.equal(calls.response.destroyed, true);
  });
}

test('aborting a stalled response destroys the connection and removes partial output', async t => {
  const controller = new AbortController();
  const response = new Readable({ read() {} });
  response.statusCode = 200;
  response.headers = {};
  let destroyed = false;
  mockRemote(t);
  t.mock.method(https, 'request', (url, settings, callback) => {
    const req = new EventEmitter();
    req.setTimeout = () => req;
    req.destroy = () => { destroyed = true; return req; };
    req.end = () => queueMicrotask(() => callback(response));
    return req;
  });
  const output = await outputPath(t);
  const download = downloadVideo(source, output, options({ signal: controller.signal }));
  while (true) {
    try { await access(output); break; } catch { await new Promise(resolve => setImmediate(resolve)); }
  }
  controller.abort(new ConversionError(504, 'CONVERSION_TIMEOUT', 'The operation exceeded its time limit'));
  await assert.rejects(download, hasCode('CONVERSION_TIMEOUT'));
  assert.equal(destroyed, true);
  assert.equal(response.destroyed, true);
  await assert.rejects(access(output));
});

test('outer cancellation before entry performs no DNS, request, or file write', async t => {
  const calls = mockRemote(t);
  const controller = new AbortController();
  controller.abort(new ConversionError(504, 'CONVERSION_TIMEOUT', 'The operation exceeded its time limit'));
  const output = await outputPath(t);
  await assert.rejects(downloadVideo(source, output, options({ signal: controller.signal })), hasCode('CONVERSION_TIMEOUT'));
  assert.equal(calls.dns.length, 0);
  await assert.rejects(access(output));
});

test('a connection failing before body iteration is handled without an unhandled stream error', async t => {
  mockRemote(t);
  t.mock.method(https, 'request', (url, settings, callback) => {
    const req = new EventEmitter();
    req.setTimeout = () => req;
    req.destroy = () => req;
    req.end = () => queueMicrotask(() => {
      const response = new Readable({ read() {} });
      response.statusCode = 200;
      response.headers = {};
      callback(response);
      queueMicrotask(() => response.destroy(new Error('private-secret')));
    });
    return req;
  });
  const output = await outputPath(t);
  await assert.rejects(downloadVideo(source, output, options()), hasCode('URL_FETCH_FAILED'));
  await assert.rejects(access(output));
});

test('the operation deadline also bounds a server that never sends response headers', async t => {
  mockRemote(t);
  let destroyed = false;
  t.mock.method(https, 'request', () => {
    const req = new EventEmitter();
    req.setTimeout = () => req;
    req.destroy = () => { destroyed = true; return req; };
    req.end = () => {};
    return req;
  });
  const output = await outputPath(t);
  await assert.rejects(downloadVideo(source, output, options({ limits: { ...limits, timeoutSeconds: 0.02 } })), hasCode('CONVERSION_TIMEOUT'));
  assert.equal(destroyed, true);
  await assert.rejects(access(output));
});
