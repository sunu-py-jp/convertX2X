import test from 'node:test';
import assert from 'node:assert/strict';
import dns from 'node:dns';
import https from 'node:https';
import http from 'node:http';
import tls from 'node:tls';
import { Readable, Writable } from 'node:stream';
import { mkdtemp, writeFile, readFile, rm, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { parseOutputHosts, validateBlobDestination, uploadAudio } from '../src/blob.js';
import { ConversionError } from '../src/errors.js';

const host = 'sampleaccount.blob.core.windows.net';
const outputAllowedHosts = new Set([host]);
const query = 'sv=2023-11-03&sr=b&sp=c&spr=https&se=2099-01-01T00%3A00%3A00Z&sig=private-secret%2B%2F%3D';
const sas = `https://${host}/results/folder/audio%20name.m4a?${query}`;
const options = extra => ({ outputAllowedHosts, limits: { maxOutputBytes: 104857600, timeoutSeconds: 2 }, ...extra });
const hasCode = code => error => error instanceof ConversionError && error.code === code
  && !String(error).includes('private-secret') && error.cause === undefined;

async function audioFile(t, bytes = Buffer.from('audio-data')) {
  const directory = await mkdtemp(join(tmpdir(), 'movie-blob-test-'));
  t.after(() => rm(directory, { force: true, recursive: true }));
  const path = join(directory, 'audio.m4a');
  await writeFile(path, bytes);
  return path;
}

function mockRemote(t, { status = 201, headers = { etag: '"0x8CB171BA9E94B0B"' },
  chunks = [], complete = true, addresses = [{ address: '8.8.8.8', family: 4 }],
  lookupError, requestError, delay = 0, stalled = false, earlyStatus, onWrite } = {}) {
  const calls = { dns: [], requests: [], tls: [], chunks: [], destroyed: 0, maxQueued: 0 };
  t.mock.method(dns, 'lookup', (hostname, settings, callback) => {
    calls.dns.push({ hostname, settings });
    queueMicrotask(() => callback(lookupError, addresses));
  });
  t.mock.method(tls, 'connect', settings => { calls.tls.push(settings); return {}; });
  t.mock.method(https, 'request', (url, settings, callback) => {
    calls.requests.push({ url, settings });
    settings.agent.createConnection({ host: url.hostname, port: 443 });
    const respond = code => {
      if (calls.response) return;
      const response = Readable.from(chunks);
      response.statusCode = code;
      response.headers = headers;
      response.complete = complete;
      calls.response = response;
      callback(response);
    };
    const request = new Writable({
      autoDestroy: false, highWaterMark: 1024,
      write(chunk, encoding, done) {
        calls.chunks.push(Buffer.from(chunk));
        calls.maxQueued = Math.max(calls.maxQueued, request.writableLength);
        onWrite?.(request, calls);
        if (earlyStatus) queueMicrotask(() => respond(earlyStatus));
        if (stalled) return;
        if (delay) setTimeout(() => done(requestError), delay);
        else queueMicrotask(() => done(requestError));
      },
      final(done) {
        done();
        queueMicrotask(() => respond(status));
      },
      destroy(error, done) { calls.destroyed++; done(error); },
    });
    request.setTimeout = (ms, fn) => { calls.timeout = { ms, fn }; return request; };
    calls.request = request;
    return request;
  });
  return calls;
}

test('output configuration allows only exact public Azure account Blob hosts', () => {
  assert.deepEqual(parseOutputHosts(` SAMPLEACCOUNT.blob.core.windows.net,${host},other123.blob.core.windows.net `),
    new Set([host, 'other123.blob.core.windows.net']));
  assert.deepEqual(parseOutputHosts(' '), new Set());
  assert.deepEqual(parseOutputHosts(undefined), new Set());
  for (const value of ['*.blob.core.windows.net', 'blob.core.windows.net', 'ab.blob.core.windows.net',
    `${'a'.repeat(25)}.blob.core.windows.net`, 'account-with-dash.blob.core.windows.net',
    'sampleaccount.privatelink.blob.core.windows.net', 'sampleaccount.dfs.core.windows.net',
    'sampleaccount.blob.core.windows.net.attacker.com', 'https://sampleaccount.blob.core.windows.net',
    `${host}:443`, `${host}.`, '127.0.0.1', `${host},`, {}, 'a'.repeat(8193)]) {
    assert.throws(() => parseOutputHosts(value));
  }
  assert.throws(() => parseOutputHosts(Array.from({ length: 33 }, (_, i) => `account${i}.blob.core.windows.net`).join(',')));
});

test('validates one blob SAS without rewriting signed path or query', () => {
  const target = validateBlobDestination(sas, outputAllowedHosts);
  assert.equal(target.url.href, sas);
  assert.equal(target.url.search, `?${query}`);
  assert.equal(target.blobUrl, `https://${host}/results/folder/audio%20name.m4a`);
  for (const permission of ['c', 'w', 'cw']) {
    assert.ok(validateBlobDestination(sas.replace('sp=c&', `sp=${permission}&`), outputAllowedHosts));
  }
  const delegated = `${sas}&skoid=owner&sktid=tenant&skt=2020-01-01T00%3A00%3A00Z&ske=2099-01-01T00%3A00%3A00Z&sks=b&skv=2023-11-03&scid=correlation`;
  assert.equal(validateBlobDestination(delegated, outputAllowedHosts).url.href, delegated);
});

test('disabled output and untrusted host fail before credential parsing', () => {
  assert.throws(() => validateBlobDestination(sas, new Set()), hasCode('OUTPUT_STORAGE_DISABLED'));
  assert.throws(() => validateBlobDestination(sas.replace(host, 'otheraccount.blob.core.windows.net'), outputAllowedHosts), hasCode('OUTPUT_HOST_NOT_ALLOWED'));
});

test('rejects broad, expired, ambiguous, operation-changing, or malformed SAS URLs', () => {
  const bad = [
    sas.replace('https:', 'http:'), sas.replace(host, `${host}:8443`),
    sas.replace(host, `user@${host}`), `${sas}#`, ` ${sas}`, sas.replace(host, '%73ampleaccount.blob.core.windows.net'),
    sas.replace('folder/audio%20name.m4a', 'folder/../audio.m4a'), sas.replace('folder/audio%20name.m4a', '%2e%2e/audio.m4a'),
    sas.replace('folder/audio%20name.m4a', 'folder%2faudio.m4a'), sas.replace('folder/audio%20name.m4a', 'folder\\audio.m4a'),
    sas.replace('folder/audio%20name.m4a', 'folder/%00.m4a'), sas.replace('/results/folder/audio%20name.m4a', '/results'),
    sas.replace('/results/folder/audio%20name.m4a', '/results/'), sas.replace('/results/', '/bad--container/'),
    sas.replace('sr=b', 'sr=c'), sas.replace('sp=c', 'sp=rcw'), sas.replace('sp=c', 'sp='), sas.replace('sp=c', 'sp=wc'),
    sas.replace('spr=https', 'spr=https,http'), sas.replace('spr=https&', ''), sas.replace('sig=', 'sig=%0D'),
    sas.replace('sig=private-secret%2B%2F%3D', 'sig='), sas.replace('sig=', '%73ig='),
    sas.replace('sv=2023-11-03', 'sv=2018-11-09'), sas.replace('sv=2023-11-03', 'sv=2023-02-30'),
    sas.replace('2099-01-01T00%3A00%3A00Z', '2000-01-01T00%3A00%3A00Z'),
    sas.replace('2099-01-01T00%3A00%3A00Z', '2099-02-30T00%3A00%3A00Z'),
    `${sas}&st=2099-01-01T00%3A00%3A00Z`, `${sas}&sp=w`, `${sas}&sig=other`, `${sas}&`, `${sas}&unknown=x`,
    `${sas}&comp=block`, `${sas}&restype=container`, `${sas}&snapshot=x`, `${sas}&versionid=x`,
    `${sas}&api-version=2012-02-12`, `${sas}&si=stored-policy`, `${sas}&ss=b`, `${sas}&srt=o`,
    `${sas}&rsct=audio%2Gmp4`, `${sas}&rscc=%0d%0a`,
  ];
  for (const value of bad) assert.throws(() => validateBlobDestination(value, outputAllowedHosts), hasCode('INVALID_OUTPUT_SAS_URL'));
});

test('PUT copies exact disk bytes, atomically avoids overwrite, pins TLS, and returns no SAS', async t => {
  const bytes = Buffer.alloc(150000, 42);
  const path = await audioFile(t, bytes);
  const calls = mockRemote(t, { delay: 1 });
  const result = await uploadAudio(path, sas, options());
  assert.deepEqual(result, { blobUrl: `https://${host}/results/folder/audio%20name.m4a`, bytes: bytes.length, etag: '"0x8CB171BA9E94B0B"' });
  assert.equal(JSON.stringify(result).includes('private-secret'), false);
  assert.deepEqual(Buffer.concat(calls.chunks), bytes);
  assert.equal(calls.requests.length, 1);
  assert.equal(calls.dns.length, 1);
  assert.equal(calls.requests[0].url.href, sas);
  const request = calls.requests[0].settings;
  assert.equal(request.method, 'PUT');
  assert.equal(request.headers['Content-Type'], 'audio/mp4');
  assert.equal(request.headers['Content-Length'], String(bytes.length));
  assert.equal(request.headers['x-ms-blob-type'], 'BlockBlob');
  assert.equal(request.headers['If-None-Match'], '*');
  assert.equal(request.headers.Authorization, undefined);
  assert.equal(request.headers['x-ms-copy-source'], undefined);
  assert.equal(calls.tls[0].host, '8.8.8.8');
  assert.equal(calls.tls[0].servername, host);
  assert.equal(calls.tls[0].rejectUnauthorized, true);
  assert.equal(calls.tls[0].checkServerIdentity, tls.checkServerIdentity);
  assert.equal(calls.tls[0].autoSelectFamily, false);
  assert.deepEqual(request.agent.options.proxyEnv, {});
  assert.ok(calls.chunks.length > 1);
  assert.ok(calls.chunks.every(chunk => chunk.length <= 65536));
  assert.ok(calls.maxQueued <= 65536, `queued ${calls.maxQueued} bytes despite delayed writes`);
  assert.equal(calls.request.destroyed, true);
  assert.equal(calls.response.destroyed, true);
  assert.deepEqual(await readFile(path), bytes);
});

test('real Node ClientRequest finishes the upload before accepting Azure-style 201', async t => {
  const bytes = Buffer.alloc(200000, 37);
  let received;
  const server = http.createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    received = { method: request.method, url: request.url, headers: request.headers, body: Buffer.concat(chunks) };
    response.writeHead(201, { ETag: '"0xABCDEF123"', 'Content-Length': '0' });
    response.end();
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  t.mock.method(dns, 'lookup', (hostname, settings, callback) => {
    queueMicrotask(() => callback(null, [{ address: '8.8.8.8', family: 4 }]));
  });
  // Only this test replaces transport with a loopback server. Production still
  // uses the pinned TLS agent exercised in the preceding test.
  t.mock.method(https, 'request', (url, settings, callback) => http.request({
    hostname: '127.0.0.1', port: server.address().port, method: settings.method,
    path: `${url.pathname}${url.search}`, headers: settings.headers, agent: false,
  }, callback));
  const result = await uploadAudio(await audioFile(t, bytes), sas, options());
  assert.equal(result.bytes, bytes.length);
  assert.equal(result.etag, '"0xABCDEF123"');
  assert.equal(received.method, 'PUT');
  assert.equal(received.url, `/results/folder/audio%20name.m4a?${query}`);
  assert.equal(received.headers['content-length'], String(bytes.length));
  assert.equal(received.headers['if-none-match'], '*');
  assert.deepEqual(received.body, bytes);
});

test('rejects mixed public/private DNS answers before uploading any audio', async t => {
  const calls = mockRemote(t, { addresses: [{ address: '8.8.8.8', family: 4 }, { address: '169.254.169.254', family: 4 }] });
  await assert.rejects(uploadAudio(await audioFile(t), sas, options()), hasCode('OUTPUT_ADDRESS_NOT_ALLOWED'));
  assert.equal(calls.requests.length, 0);
});

test('validates destination before attempting disk or network access', async t => {
  const calls = mockRemote(t);
  await assert.rejects(uploadAudio('/missing', `${sas}&sp=rw`, options()), hasCode('INVALID_OUTPUT_SAS_URL'));
  assert.equal(calls.dns.length, 0);
});

test('rejects empty, oversized and symlinked outputs before network access', async t => {
  const calls = mockRemote(t);
  const path = await audioFile(t, Buffer.alloc(5));
  await assert.rejects(uploadAudio(path, sas, options({ limits: { maxOutputBytes: 4, timeoutSeconds: 2 } })), hasCode('OUTPUT_TOO_LARGE'));
  await assert.rejects(uploadAudio(await audioFile(t, Buffer.alloc(0)), sas, options()), hasCode('STORAGE_ERROR'));
  const linked = `${path}-link`;
  await symlink(path, linked);
  await assert.rejects(uploadAudio(linked, sas, options()), hasCode('STORAGE_ERROR'));
  assert.equal(calls.dns.length, 0);
});

for (const [status, code, appStatus] of [[412, 'OUTPUT_BLOB_EXISTS', 409], [403, 'OUTPUT_AUTH_FAILED', 403],
  [302, 'OUTPUT_REDIRECT_NOT_ALLOWED', 422], [404, 'OUTPUT_UPLOAD_FAILED', 502],
  [200, 'OUTPUT_UPLOAD_FAILED', 502], [500, 'OUTPUT_UPLOAD_FAILED', 502]]) {
  test(`maps Azure status ${status} without exposing body or retrying`, async t => {
    const calls = mockRemote(t, { status, chunks: [Buffer.from(`Azure error mentions ${sas}`)], headers: { location: sas } });
    await assert.rejects(uploadAudio(await audioFile(t), sas, options()), error => hasCode(code)(error) && error.status === appStatus);
    assert.equal(calls.requests.length, 1);
    assert.equal(calls.response.destroyed, true);
  });
}

test('an early authorization rejection stops an in-progress upload', async t => {
  const calls = mockRemote(t, { earlyStatus: 403, stalled: true });
  await assert.rejects(uploadAudio(await audioFile(t, Buffer.alloc(250000)), sas, options()), hasCode('OUTPUT_AUTH_FAILED'));
  assert.equal(calls.requests.length, 1);
  assert.equal(calls.request.destroyed, true);
  assert.ok(Buffer.concat(calls.chunks).length < 250000);
});

test('DNS and transport failures are redacted and state upload outcome uncertainty', async t => {
  const path = await audioFile(t);
  mockRemote(t, { lookupError: new Error(sas) });
  await assert.rejects(uploadAudio(path, sas, options()), hasCode('OUTPUT_UPLOAD_FAILED'));
  t.mock.restoreAll();
  const calls = mockRemote(t, { requestError: new Error(sas) });
  await assert.rejects(uploadAudio(path, sas, options()), error => hasCode('OUTPUT_UPLOAD_FAILED')(error) && error.message.includes('might already'));
  assert.equal(calls.requests.length, 1);
});

test('response body is bounded and an incomplete success is not reported as stored', async t => {
  const path = await audioFile(t);
  mockRemote(t, { chunks: [Buffer.alloc(8193)] });
  await assert.rejects(uploadAudio(path, sas, options()), hasCode('OUTPUT_UPLOAD_FAILED'));
  t.mock.restoreAll();
  mockRemote(t, { complete: false });
  await assert.rejects(uploadAudio(path, sas, options()), hasCode('OUTPUT_UPLOAD_FAILED'));
});

test('does not echo arbitrary or secret-bearing ETag header text', async t => {
  mockRemote(t, { headers: { etag: `"${sas}"` } });
  const result = await uploadAudio(await audioFile(t), sas, options());
  assert.equal(result.etag, undefined);
});

test('outer cancellation destroys stalled request and preserves completed local audio', async t => {
  const controller = new AbortController();
  const path = await audioFile(t, Buffer.alloc(250000));
  const calls = mockRemote(t, { stalled: true, onWrite: () => queueMicrotask(() => controller.abort(new ConversionError(504, 'CONVERSION_TIMEOUT', 'Deadline reached.'))) });
  await assert.rejects(uploadAudio(path, sas, options({ signal: controller.signal })), hasCode('CONVERSION_TIMEOUT'));
  assert.equal(calls.request.destroyed, true);
  assert.equal((await readFile(path)).length, 250000);
});

test('own total deadline bounds a stalled disk-to-network transfer', async t => {
  const calls = mockRemote(t, { stalled: true });
  await assert.rejects(uploadAudio(await audioFile(t), sas, options({ limits: { maxOutputBytes: 100, timeoutSeconds: 0.03 } })), hasCode('CONVERSION_TIMEOUT'));
  assert.equal(calls.request.destroyed, true);
});

test('already-aborted invocation does not perform DNS or PUT', async t => {
  const calls = mockRemote(t);
  const controller = new AbortController();
  controller.abort(new ConversionError(504, 'CONVERSION_TIMEOUT', 'Deadline reached.'));
  await assert.rejects(uploadAudio('/missing', sas, options({ signal: controller.signal })), hasCode('CONVERSION_TIMEOUT'));
  assert.equal(calls.dns.length, 0);
  assert.equal(calls.requests.length, 0);
});

test('WAV output uses the validated audio/wav media type in its actual Azure PUT', async t => {
  const calls = mockRemote(t);
  await uploadAudio(await audioFile(t), sas, options({ contentType: 'audio/wav' }));
  assert.equal(calls.requests[0].settings.headers['Content-Type'], 'audio/wav');
  await assert.rejects(uploadAudio('/not-opened', sas, options({ contentType: 'text/html' })), hasCode('STORAGE_ERROR'));
});
