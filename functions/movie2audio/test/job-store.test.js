import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, rm, stat, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { Readable } from 'node:stream';
import { setTimeout as delay } from 'node:timers/promises';
import { BlobServiceClient } from '@azure/storage-blob';
import { createJobStore, resultEvent, blobClient, queueClient } from '../src/job-store.js';
import { normalizeJobRequest, readJobSettings, CONTAINER_NAME } from '../src/job-request.js';
import { ConversionError } from '../src/errors.js';
import { createJobService } from '../src/jobs.js';

const limits = { maxInputBytes: 8 * 1024 * 1024, maxOutputBytes: 8 * 1024 * 1024, timeoutSeconds: 10 };
const settings = () => readJobSettings({ CONVERSION_STORAGE_CONNECTION_STRING: 'control-secret',
  CONVERSION_INPUT_STORAGE_MEDIA: 'input-secret', CONVERSION_OUTPUT_STORAGE_EXPORTS: 'output-secret' });
const request = () => normalizeJobRequest({ version: 1, jobId: randomUUID(),
  input: { storage: 'media', container: 'incoming', blobName: 'meeting/video.mp4' },
  output: { storage: 'exports', container: 'converted', prefix: 'audio' } });
const record = req => ({ job: { id: req.jobId, status: 'queued', filename: req.filename,
  createdAt: '2026-09-15T00:00:00.000Z', updatedAt: '2026-09-15T00:00:00.000Z', sizeBytes: null, errorCode: null, errorMessage: null },
  request: req, result: null });
const sdkError = (statusCode, code) => Object.assign(new Error('SDK credential ?sig=super-secret'), { statusCode, code });

function fakeStorage() {
  const blobs = new Map(); const calls = []; const messages = []; const hooks = {}; const leases = new Map(); let sequence = 0;
  const key = (connection, container, name) => `${connection}|${container}|${name}`;
  const put = (connection, container, name, data, metadata = {}) => { const value = { data: Buffer.from(data), metadata, etag: `"etag-${++sequence}"` }; blobs.set(key(connection, container, name), value); return value; };
  const service = connection => ({ getContainerClient(container) { return {
    listBlobsFlat({ prefix = '' } = {}) { return { byPage({ continuationToken, maxPageSize } = {}) {
      let done = false; return { async next() { if (done) return { done: true }; done = true;
        const all = [...blobs.keys()].filter(key => key.startsWith(`${connection}|${container}|${prefix}`))
          .map(key => key.slice(`${connection}|${container}|`.length)).sort().filter(name => !continuationToken || name > continuationToken);
        const names = all.slice(0, maxPageSize ?? 100);
        return { done: false, value: { segment: { blobItems: names.map(name => ({ name })) },
          continuationToken: all.length > names.length ? names.at(-1) : undefined } };
      } };
    } }; },
    async createIfNotExists(options) { calls.push({ operation: 'createContainer', connection, container, options }); await hooks.createContainer?.(); },
    getBlockBlobClient(name) {
      const blobKey = key(connection, container, name);
      const check = options => {
        if (options?.abortSignal?.aborted) throw options.abortSignal.reason;
        const existing = blobs.get(blobKey);
        if (options?.conditions?.ifNoneMatch === '*' && existing) throw sdkError(412, 'ConditionNotMet');
        if (leases.has(blobKey) && leases.get(blobKey) !== options?.conditions?.leaseId) throw sdkError(412, 'LeaseIdMissing');
      };
      return {
        async upload(data, size, options) {
          calls.push({ operation: 'upload', name, connection, container, options }); check(options);
          await hooks.upload?.(name, options);
          return put(connection, container, name, data, options.metadata);
        },
        async uploadStream(stream, blockSize, concurrency, options) {
          calls.push({ operation: 'uploadStream', name, connection, container, blockSize, concurrency, options }); check(options);
          await hooks.uploadStream?.(name, options);
          const chunks = []; for await (const chunk of stream) { if (options.abortSignal.aborted) throw options.abortSignal.reason; chunks.push(chunk); }
          return put(connection, container, name, Buffer.concat(chunks), options.metadata);
        },
        async getProperties(options) {
          calls.push({ operation: 'getProperties', name, connection, container, options });
          const existing = blobs.get(blobKey); if (!existing) throw sdkError(404, 'BlobNotFound');
          const properties = { contentLength: existing.data.length, etag: existing.etag, metadata: existing.metadata };
          await hooks.getProperties?.(name, properties);
          return properties;
        },
        async download(offset, count, options) {
          calls.push({ operation: 'download', name, connection, container, offset, count, options });
          await hooks.beforeDownload?.(name);
          const existing = blobs.get(blobKey); if (!existing) throw sdkError(404, 'BlobNotFound');
          if (options.conditions.ifMatch !== existing.etag) throw sdkError(412, 'ConditionNotMet');
          const response = { contentLength: existing.data.length, etag: existing.etag, readableStreamBody: Readable.from([existing.data]) };
          await hooks.download?.(name, response);
          return response;
        },
        async deleteIfExists(options) {
          check(options); const existing = blobs.get(blobKey);
          if (existing && options?.conditions?.ifMatch && options.conditions.ifMatch !== existing.etag) throw sdkError(412, 'ConditionNotMet');
          calls.push({ operation: 'delete', connection, container, name, options }); return { succeeded: blobs.delete(blobKey) };
        },
        getBlobLeaseClient(leaseId) { return { leaseId,
          async acquireLease(duration, options) { calls.push({ operation: 'acquire', duration, options });
            if (leases.has(blobKey)) throw sdkError(409, 'LeaseAlreadyPresent'); if (!blobs.has(blobKey)) throw sdkError(404, 'BlobNotFound'); leases.set(blobKey, leaseId); },
          async renewLease(options) { calls.push({ operation: 'renew', options }); await hooks.renew?.();
            if (leases.get(blobKey) !== leaseId) throw sdkError(409, 'LeaseIdMismatchWithLeaseOperation'); },
          async releaseLease(options) { calls.push({ operation: 'release', options });
            if (leases.get(blobKey) === leaseId) leases.delete(blobKey); }
        }; }
      };
    }
  }; } });
  const queueFactory = (connection, queueName = 'movie2audio-jobs') => ({ async createIfNotExists(options) { calls.push({ operation: 'createQueue', connection, options }); },
    async sendMessage(value, options) { calls.push({ operation: 'send', connection, queueName, options }); await hooks.send?.(queueName); messages.push(value); } });
  return { blobs, calls, messages, hooks, leases, put, key, service, queueFactory };
}

function fixture(options = {}) {
  const fake = fakeStorage();
  const store = createJobStore(settings(), { limits, blobServiceFactory: fake.service, queueFactory: fake.queueFactory, ...options });
  return { ...fake, store };
}
async function directory(t) { const dir = await mkdtemp(join(tmpdir(), 'movie-job-store-test-')); t.after(() => rm(dir, { recursive: true, force: true })); return dir; }

test('job storage clients are registered by role and never expose credentials in failures', async () => {
  const { store, hooks } = fixture(); const req = request();
  store.validateLocations(req);
  req.input.storage = 'exports'; assert.throws(() => store.validateLocations(req), { code: 'UNKNOWN_INPUT_STORAGE' });
  req.input.storage = 'media'; req.output.storage = 'media'; assert.throws(() => store.validateLocations(req), { code: 'UNKNOWN_OUTPUT_STORAGE' });
  hooks.send = () => { throw sdkError(500, 'InternalError'); };
  await assert.rejects(store.enqueue(request()), error => error.code === 'JOB_STORAGE_ERROR' && !error.message.includes('secret'));
  assert.throws(() => createJobStore(settings(), { limits, blobServiceFactory() { throw new Error('control-secret'); } }),
    error => !error.message.includes('secret'));
});

test('ensure creates private status once and existing records are never reset', async () => {
  const { store, calls, blobs, key } = fixture(); const req = request(); const initial = record(req);
  initial.secret = 'not-persisted'; initial.job.credentials = 'not-persisted';
  const first = await store.ensure(initial);
  assert.equal(first.job.status, 'queued');
  const changed = structuredClone(initial); changed.job.status = 'running';
  assert.equal((await store.ensure(changed)).job.status, 'queued');
  const serialized = blobs.get(key('control-secret', CONTAINER_NAME, `${req.jobId}/status.json`)).data.toString();
  assert.ok(!serialized.includes('not-persisted'));
  const creates = calls.filter(call => call.operation === 'createContainer'); assert.equal(creates.length, 1);
  assert.equal(creates[0].options.access, undefined);
  assert.ok(calls.filter(call => call.operation === 'upload').every(call => call.options.conditions.ifNoneMatch === '*'));
  assert.equal(await store.find(randomUUID()), null);
});

test('enqueue sends one Base64 encoding of canonical UTF-8 JSON without credentials', async () => {
  const { store, messages } = fixture(); const req = request();
  await store.enqueue(req);
  assert.equal(messages.length, 1);
  const json = Buffer.from(messages[0], 'base64').toString('utf8');
  assert.deepEqual(JSON.parse(json), req);
  assert.ok(!json.includes('secret') && !json.includes('https:'));
});

test('input upload and reads stream through disk with size and ETag checks', async t => {
  const dir = await directory(t); const source = join(dir, 'source.mp4'); const target = join(dir, 'download.mp4');
  const video = Buffer.alloc(2 * 1024 * 1024, 17); await writeFile(source, video);
  const { store, calls } = fixture(); const req = request();
  await store.saveInput(req, source); await store.readInput(req.input, target);
  assert.deepEqual(await readFile(target), video); assert.equal((await stat(target)).mode & 0o777, 0o600);
  const upload = calls.find(call => call.operation === 'uploadStream'); assert.equal(upload.blockSize, 4 * 1024 * 1024); assert.equal(upload.concurrency, 1);
  assert.equal(upload.options.conditions.ifNoneMatch, '*');
  const download = calls.find(call => call.operation === 'download'); assert.equal(download.count, video.length);
  assert.ok(download.options.conditions.ifMatch); assert.equal(download.options.maxRetryRequests, 0);
  await assert.rejects(store.saveInput(req, source), { code: 'JOB_BLOB_EXISTS' });
});

test('download refuses replacement between HEAD and GET and never clobbers existing local files', async t => {
  const dir = await directory(t); const target = join(dir, 'target'); const f = fixture(); const req = request();
  f.put('input-secret', 'incoming', req.input.blobName, 'original');
  f.hooks.beforeDownload = () => f.put('input-secret', 'incoming', req.input.blobName, 'replacement');
  await assert.rejects(f.store.readInput(req.input, target), { code: 'JOB_STORAGE_ERROR' });
  await assert.rejects(stat(target), { code: 'ENOENT' });
  delete f.hooks.beforeDownload; await writeFile(target, 'preserved');
  await assert.rejects(f.store.readInput(req.input, target), { code: 'JOB_STORAGE_ERROR' }); assert.equal(await readFile(target, 'utf8'), 'preserved');
});

test('download enforces advertised length, configured limits, partial cleanup, missing and empty input', async t => {
  const dir = await directory(t); const target = join(dir, 'target'); const f = fixture({ limits: { ...limits, maxInputBytes: 8 } }); const req = request();
  await assert.rejects(f.store.readInput(req.input, target), { code: 'INPUT_NOT_FOUND' });
  f.put('input-secret', 'incoming', req.input.blobName, ''); await assert.rejects(f.store.readInput(req.input, target), { code: 'EMPTY_INPUT' });
  f.put('input-secret', 'incoming', req.input.blobName, 'too large'); await assert.rejects(f.store.readInput(req.input, target), { code: 'INPUT_TOO_LARGE' });
  assert.equal(f.calls.filter(call => call.operation === 'download').length, 0);
  f.put('input-secret', 'incoming', req.input.blobName, 'small');
  for (const contents of ['s', 'longer-than-small']) {
    f.hooks.download = (name, response) => { response.readableStreamBody = Readable.from([Buffer.from(contents)]); };
    await assert.rejects(f.store.readInput(req.input, target), { code: 'JOB_STORAGE_ERROR' });
    await assert.rejects(stat(target), { code: 'ENOENT' });
  }
});

test('completed results use separate attempt paths and ETag-pinned reads', async t => {
  const dir = await directory(t); const source = join(dir, 'audio.m4a'); const target = join(dir, 'download.m4a'); await writeFile(source, 'complete audio');
  const f = fixture(); const req = request();
  const first = await f.store.writeResult(req, source); const second = await f.store.writeResult(req, source);
  assert.notEqual(first.blobName, second.blobName);
  assert.match(first.blobName, new RegExp(`^audio/${req.jobId}/results/[a-f0-9-]{36}/audio\\.m4a$`));
  assert.equal(first.contentType, 'audio/mp4'); assert.equal(first.filename, 'audio.m4a'); assert.equal(first.sizeBytes, 14);
  await f.store.readResult(first, target); assert.equal(await readFile(target, 'utf8'), 'complete audio');
  await rm(target); f.put('output-secret', 'converted', first.blobName, 'changed audio');
  await assert.rejects(f.store.readResult(first, target), { code: 'RESULT_CHANGED' });
  await assert.rejects(stat(target), { code: 'ENOENT' });
});

test('uploads reject symlinks and oversize media; early SDK failure cancels disk streams', async t => {
  const dir = await directory(t); const source = join(dir, 'audio'); const linked = join(dir, 'linked'); await writeFile(source, Buffer.alloc(5 * 1024 * 1024)); await symlink(source, linked);
  const f = fixture(); const req = request();
  await assert.rejects(f.store.writeResult(req, linked), { code: 'JOB_STORAGE_ERROR' });
  const bounded = fixture({ limits: { ...limits, maxOutputBytes: 8 } });
  await assert.rejects(bounded.store.writeResult(req, source), { code: 'OUTPUT_TOO_LARGE' });
  f.hooks.uploadStream = () => { throw sdkError(403, 'AuthenticationFailed'); };
  await assert.rejects(f.store.writeResult(req, source), { code: 'JOB_STORAGE_ERROR' });
});

test('abort cancels disk download and removes only its owned partial file', async t => {
  const dir = await directory(t); const target = join(dir, 'target'); const f = fixture(); const req = request(); const controller = new AbortController();
  f.put('input-secret', 'incoming', req.input.blobName, '12345678');
  const cancelled = new ConversionError(504, 'CONVERSION_TIMEOUT', 'Timed out.');
  f.hooks.download = (name, response) => { response.readableStreamBody = Readable.from((async function* () {
    yield Buffer.from('1234'); controller.abort(cancelled); yield Buffer.from('5678');
  })()); };
  await assert.rejects(f.store.readInput(req.input, target, { signal: controller.signal }), { code: 'CONVERSION_TIMEOUT' });
  await assert.rejects(stat(target), { code: 'ENOENT' });
});

test('renewable leases fence state writes and release ownership', async () => {
  const f = fixture({ leaseRenewMs: 5 }); const initial = record(request()); await f.store.ensure(initial);
  const lease = await f.store.lock(initial.job.id);
  try {
    await assert.rejects(f.store.lock(initial.job.id), { code: 'JOB_BUSY' });
    const running = structuredClone(initial); running.job.status = 'running'; await lease.update(running);
    assert.equal((await f.store.find(initial.job.id)).job.status, 'running');
    const upload = f.calls.filter(call => call.operation === 'upload').at(-1); assert.ok(upload.options.conditions.leaseId);
    for (let tries = 0; !f.calls.some(call => call.operation === 'renew') && tries < 20; tries++) await delay(5);
    assert.ok(f.calls.some(call => call.operation === 'renew'));
    assert.equal(f.calls.find(call => call.operation === 'acquire').duration, 60);
  } finally { await lease.close(); }
  assert.equal(f.leases.size, 0); assert.equal(lease.signal.aborted, true);
  await assert.rejects(lease.update(initial), { code: 'JOB_LEASE_LOST' });
});

test('lease renewal loss immediately aborts work and forbids stale status publication', async () => {
  const f = fixture({ leaseRenewMs: 5 }); const initial = record(request()); await f.store.ensure(initial);
  f.hooks.renew = () => { throw sdkError(409, 'LeaseLost'); };
  const lease = await f.store.lock(initial.job.id);
  try {
    for (let tries = 0; !lease.signal.aborted && tries < 20; tries++) await delay(5);
    assert.equal(lease.signal.aborted, true); assert.equal(lease.signal.reason.code, 'JOB_LEASE_LOST');
    await assert.rejects(lease.update(initial), { code: 'JOB_LEASE_LOST' });
  } finally { await lease.close(); }
});

test('malformed persisted status cannot select arbitrary metadata or a different job', async () => {
  const f = fixture(); const req = request();
  for (const value of ['not JSON', JSON.stringify({ job: { id: req.jobId } }), JSON.stringify(record(request()))]) {
    f.put('control-secret', CONTAINER_NAME, `${req.jobId}/status.json`, value);
    await assert.rejects(f.store.find(req.jobId), { code: 'INVALID_JOB_STATE' });
  }
});

test('Azurite: SDK disk transfers, conditional create, actual leases and pinned results', { skip: process.env.MOVIE_TEST_AZURITE !== '1' }, async t => {
  const dir = await directory(t); const source = join(dir, 'source'); const downloaded = join(dir, 'downloaded'); await writeFile(source, Buffer.alloc(65537, 29));
  const connectionString = process.env.MOVIE_TEST_STORAGE_CONNECTION_STRING || 'UseDevelopmentStorage=true';
  const store = createJobStore(readJobSettings({ CONVERSION_STORAGE_CONNECTION_STRING: connectionString }), { limits });
  const req = normalizeJobRequest({ version: 1, jobId: randomUUID(), input: { container: CONTAINER_NAME, blobName: `${randomUUID()}/test-input` }, output: { container: CONTAINER_NAME }, filename: 'test.mp4' });
  const container = BlobServiceClient.fromConnectionString(connectionString).getContainerClient(CONTAINER_NAME);
  t.after(async () => {
    await container.getBlockBlobClient(req.input.blobName).deleteIfExists();
    for await (const blob of container.listBlobsFlat({ prefix: `${req.jobId}/` })) await container.getBlockBlobClient(blob.name).deleteIfExists();
  });
  const initial = record(req); await store.saveInput(req, source); await store.ensure(initial);
  const lease = await store.lock(req.jobId);
  try {
    await assert.rejects(store.lock(req.jobId), { code: 'JOB_BUSY' });
    await store.readInput(req.input, downloaded); assert.deepEqual(await readFile(downloaded), await readFile(source)); await rm(downloaded);
    const result = await store.writeResult(req, source);
    await lease.update({ ...initial, job: { ...initial.job, status: 'succeeded', sizeBytes: result.sizeBytes }, result });
    const persisted = await store.find(req.jobId); assert.equal(persisted.result.etag, result.etag);
    await store.readResult(persisted.result, downloaded); assert.deepEqual(await readFile(downloaded), await readFile(source));
    assert.equal((await store.ensure(initial)).job.status, 'succeeded');
    const blob = BlobServiceClient.fromConnectionString(connectionString).getContainerClient(CONTAINER_NAME).getBlockBlobClient(result.blobName);
    await blob.uploadData(Buffer.from('changed')); await rm(downloaded);
    await assert.rejects(store.readResult(result, downloaded), { code: 'RESULT_CHANGED' });
  } finally { await lease.close(); }
});

test('an abort destroys a stalled response stream and removes a partially written download', async t => {
  const dir = await directory(t); const target = join(dir, 'stalled'); const f = fixture(); const req = request(); const controller = new AbortController();
  f.put('input-secret', 'incoming', req.input.blobName, '12345678');
  let incoming;
  f.hooks.download = (name, response) => {
    let sent = false;
    incoming = new Readable({ read() { if (!sent) { sent = true; this.push(Buffer.from('1234')); } } });
    response.readableStreamBody = incoming;
  };
  const done = f.store.readInput(req.input, target, { signal: controller.signal });
  const assertion = assert.rejects(done, { code: 'CONVERSION_TIMEOUT' });
  for (let tries = 0; tries < 50; tries++) {
    try { if ((await stat(target)).size > 0) break; } catch {}
    await delay(2);
  }
  controller.abort(new ConversionError(504, 'CONVERSION_TIMEOUT', 'Timed out.'));
  await assertion;
  assert.equal(incoming.destroyed, true);
  await assert.rejects(stat(target), { code: 'ENOENT' });
});

test('status updates cannot target another job and an Azure fence failure aborts the lease', async () => {
  const f = fixture(); const initial = record(request()); await f.store.ensure(initial); const lease = await f.store.lock(initial.job.id);
  try {
    await assert.rejects(lease.update(record(request())), { code: 'JOB_LEASE_LOST' });
    f.hooks.upload = () => { throw sdkError(412, 'LeaseIdMismatchWithBlobOperation'); };
    await assert.rejects(lease.update(initial), { code: 'JOB_LEASE_LOST' });
    assert.equal(lease.signal.aborted, true);
  } finally { await lease.close(); }
});

test('persisted success must select this job output, and independent blob reads ignore display filename length', async t => {
  const dir = await directory(t); const source = join(dir, 'source'); await writeFile(source, 'audio'); const f = fixture(); const req = request();
  const result = await f.store.writeResult(req, source);
  const completed = { ...record(req), job: { ...record(req).job, status: 'succeeded', sizeBytes: result.sizeBytes }, result };
  completed.result.blobName = 'another-job/audio.m4a';
  f.put('control-secret', CONTAINER_NAME, `${req.jobId}/status.json`, JSON.stringify(completed));
  await assert.rejects(f.store.find(req.jobId), { code: 'INVALID_JOB_STATE' });
  const long = { storage: 'media', container: 'incoming', blobName: 'x'.repeat(300) };
  f.put('input-secret', 'incoming', long.blobName, 'video');
  await f.store.readInput(long, join(dir, 'longname-input')); assert.equal(await readFile(join(dir, 'longname-input'), 'utf8'), 'video');
});

test('identity SDK clients use service origins and precreated mode never requests resource creation', async t => {
  const env = { CONVERSION_STORAGE__blobServiceUri: 'https://control.blob.core.windows.net',
    CONVERSION_STORAGE__queueServiceUri: 'https://control.queue.core.windows.net', CONVERSION_CREATE_RESOURCES: 'false' };
  const identity = readJobSettings(env);
  assert.equal(blobClient(identity.control).url, `${env.CONVERSION_STORAGE__blobServiceUri}/`);
  assert.equal(queueClient(identity.control).url, `${env.CONVERSION_STORAGE__queueServiceUri}/movie2audio-jobs`);
  const fake = fakeStorage(); const received = [];
  const store = createJobStore(identity, { limits, blobServiceFactory: config => { received.push(config); return fake.service('identity'); },
    queueFactory: () => fake.queueFactory('identity') });
  const req = normalizeJobRequest({ version: 1, jobId: randomUUID(), input: { container: 'input', blobName: 'video' }, output: { container: 'output' } });
  const dir = await directory(t); const path = join(dir, 'input'); await writeFile(path, 'video');
  await store.ensure(record(req)); await store.saveInput(req, path); await store.writeResult(req, path); await store.enqueue(req);
  assert.ok(received.every(item => typeof item === 'object' && item.blobServiceUri));
  assert.equal(fake.calls.filter(call => call.operation.startsWith('create')).length, 0);
});

test('producer expected ETag pins the exact source and result records SHA256 for uploaded bytes', async t => {
  const f = fixture(); const dir = await directory(t); const req = request();
  const source = f.put('input-secret', 'incoming', req.input.blobName, 'original');
  const versioned = { ...req.input, expectedETag: '"wrong-version"' };
  await assert.rejects(f.store.readInput(versioned, join(dir, 'wrong')), { code: 'INPUT_VERSION_MISMATCH' });
  assert.equal(f.calls.filter(call => call.operation === 'download').length, 0);
  versioned.expectedETag = source.etag;
  const actual = await f.store.readInput(versioned, join(dir, 'correct'));
  assert.equal(actual.eTag, source.etag);
  f.hooks.beforeDownload = () => f.put('input-secret', 'incoming', req.input.blobName, 'changed');
  await assert.rejects(f.store.readInput(versioned, join(dir, 'race')), { code: 'INPUT_VERSION_MISMATCH' });
  const result = await f.store.writeResult(req, join(dir, 'correct'));
  assert.equal(result.sha256, (await import('node:crypto')).createHash('sha256').update('original').digest('hex'));
});

function integrated(options = {}) {
  const fake = fakeStorage();
  const settings = readJobSettings({ CONVERSION_STORAGE_CONNECTION_STRING: 'control-secret',
    CONVERSION_INPUT_STORAGE_MEDIA: 'input-secret', CONVERSION_OUTPUT_STORAGE_EXPORTS: 'output-secret',
    CONVERSION_RESULT_QUEUE_EVENTS__connectionString: 'events-secret', CONVERSION_RESULT_QUEUE_EVENTS__queueName: 'events',
    CONVERSION_RESULT_RETENTION_DAYS: '7', CONVERSION_STATE_RETENTION_DAYS: '30', ...options.env });
  const store = createJobStore(settings, { limits, blobServiceFactory: fake.service, queueFactory: fake.queueFactory });
  const config = { asyncEnabled: true, limits, jobs: settings };
  let conversions = 0;
  const service = createJobService(config, { store, extract: async (input, output) => {
    conversions++; await writeFile(output, 'converted audio');
  } });
  const req = normalizeJobRequest({ ...request(), version: 2, metadata: { revision: '7' }, notification: { queue: 'events' } });
  fake.put('input-secret', 'incoming', req.input.blobName, 'original video');
  return { ...fake, store, service, req, config, conversions: () => conversions };
}

test('durable outbox retries notification independently after terminal commit without reconversion', async () => {
  const f = integrated(); let unavailable = true;
  f.hooks.send = queue => { if (queue === 'events' && unavailable) throw sdkError(503, 'Unavailable'); };
  await f.service.process(JSON.stringify(f.req));
  let completed = await f.store.find(f.req.jobId);
  assert.equal(completed.job.status, 'succeeded'); assert.equal(completed.outbox.state, 'pending');
  assert.ok(completed.source.eTag); assert.ok(completed.result.sha256);
  unavailable = false;
  const counts = await f.store.maintenance();
  assert.equal(counts.delivered, 1); assert.equal(counts.failed, 0);
  completed = await f.store.find(f.req.jobId); assert.equal(completed.outbox.state, 'sent');
  const event = JSON.parse(Buffer.from(f.messages[0], 'base64').toString());
  assert.equal(event.eventId, `${f.req.jobId}:succeeded`); assert.equal(event.metadata.revision, '7');
  assert.equal(event.input.eTag, completed.source.eTag); assert.equal(event.result.sha256, completed.result.sha256);
  assert.equal(JSON.stringify(event).includes('secret'), false);
  await f.service.process(JSON.stringify(f.req)); await f.service.poison(JSON.stringify(f.req));
  assert.equal(f.conversions(), 1); assert.equal(f.messages.length, 1);
});

test('send-before-ack crash repeats the same event ID and never changes the terminal outcome', async () => {
  const f = integrated(); let rejectAck = true;
  f.hooks.upload = (name, options) => {
    if (name.endsWith('/status.json') && options.conditions.leaseId && f.messages.length && rejectAck) throw sdkError(503, 'Unavailable');
  };
  await f.service.process(JSON.stringify(f.req));
  assert.equal((await f.store.find(f.req.jobId)).outbox.state, 'pending'); assert.equal(f.messages.length, 1);
  rejectAck = false; await f.store.maintenance();
  assert.equal(f.messages.length, 2); assert.equal(f.messages[0], f.messages[1]); assert.equal(f.conversions(), 1);
  assert.equal((await f.store.find(f.req.jobId)).outbox.state, 'sent');
});

test('version mismatch and exhausted retry poison emit fixed terminal failure notifications', async () => {
  const f = integrated();
  f.req.input.expectedETag = '"outdated"';
  await f.service.process(JSON.stringify(f.req));
  const failed = await f.store.find(f.req.jobId);
  assert.equal(failed.job.errorCode, 'INPUT_VERSION_MISMATCH'); assert.equal(failed.outbox.state, 'sent');
  const event = resultEvent(failed); assert.deepEqual(event.error, { code: 'INPUT_VERSION_MISMATCH', retryable: false });
  assert.equal(event.input.eTag, null); assert.equal(f.conversions(), 0);
  const other = integrated(); await other.service.poison(JSON.stringify(other.req));
  assert.equal((await other.store.find(other.req.jobId)).job.errorCode, 'PROCESSING_FAILED'); assert.equal(other.messages.length, 1);
  await other.service.poison('not JSON'); assert.equal(other.messages.length, 1);
});

test('retention deletes only owned expired outputs; pending events and running jobs remain protected', async () => {
  const f = integrated(); f.hooks.send = () => { throw sdkError(503, 'Unavailable'); };
  await f.service.process(JSON.stringify(f.req));
  let completed = await f.store.find(f.req.jobId);
  const resultKey = f.key('output-secret', 'converted', completed.result.blobName);
  const later = Date.now() + 40 * 86400000;
  await f.store.maintenance({ now: later }); assert.ok(f.blobs.has(resultKey)); assert.ok(await f.store.find(f.req.jobId));
  delete f.hooks.send; await f.store.maintenance({ now: Date.now() + 8 * 86400000 });
  assert.ok(f.blobs.has(resultKey), 'Delayed notification grants a full result retention window');
  await f.store.maintenance({ now: Date.now() + 16 * 86400000 });
  completed = await f.store.find(f.req.jobId); assert.ok(completed.resultExpiredAt); assert.equal(f.blobs.has(resultKey), false);
  assert.ok(f.blobs.has(f.key('input-secret', 'incoming', f.req.input.blobName)), 'Caller source remains');
  await assert.rejects(f.service.download(f.req.jobId, '/never-written'), { code: 'JOB_RESULT_EXPIRED' });
  assert.ok(await f.store.find(f.req.jobId), 'Dedupe state retained beyond artifacts');
  await f.store.maintenance({ now: later }); assert.equal(await f.store.find(f.req.jobId), null);
});

test('ownership metadata and exact ETag protect a caller replacement from retention deletion', async () => {
  const f = integrated(); await f.service.process(JSON.stringify(f.req));
  const completed = await f.store.find(f.req.jobId);
  f.put('output-secret', 'converted', completed.result.blobName, 'caller replacement');
  await f.store.maintenance({ now: Date.now() + 40 * 86400000 });
  assert.equal(f.blobs.get(f.key('output-secret', 'converted', completed.result.blobName)).data.toString(), 'caller replacement');
  assert.equal(await f.store.find(f.req.jobId), null, 'New tracked state can expire without deleting caller replacement');
  const original = f.blobs.get(f.key('input-secret', 'incoming', f.req.input.blobName)); assert.equal(original.data.toString(), 'original video');
});

test('Azurite: versioned real WAV conversion, durable result queue retry, owned retention and source preservation',
  { skip: process.env.MOVIE_TEST_AZURITE !== '1' }, async t => {
  const connection = process.env.MOVIE_TEST_STORAGE_CONNECTION_STRING || 'UseDevelopmentStorage=true';
  const suffix = randomUUID().replaceAll('-', '');
  const inputs = `inputs-${suffix}`; const outputs = `output-${suffix}`; const eventName = `events-${suffix}`;
  const client = BlobServiceClient.fromConnectionString(connection);
  const input = client.getContainerClient(inputs); const output = client.getContainerClient(outputs);
  const events = queueClient(connection, eventName);
  await input.create(); await output.create();
  const source = input.getBlockBlobClient('original.mp4');
  const bytes = await readFile(new URL('./fixtures/opus-video.mkv', import.meta.url));
  const created = await source.uploadData(bytes);
  const cfg = readJobSettings({ CONVERSION_STORAGE_CONNECTION_STRING: connection,
    CONVERSION_RESULT_QUEUE_EVENTS__connectionString: connection, CONVERSION_RESULT_QUEUE_EVENTS__queueName: eventName,
    CONVERSION_RESULT_RETENTION_DAYS: '1', CONVERSION_STATE_RETENTION_DAYS: '2' });
  let failDelivery = true;
  const store = createJobStore(cfg, { limits, queueFactory: (config, name) => {
    const target = queueClient(config, name);
    if (name === eventName) return { createIfNotExists: opts => target.createIfNotExists(opts),
      sendMessage: (message, opts) => { if (failDelivery) throw new Error('Simulated send outage'); return target.sendMessage(message, opts); } };
    return target;
  } });
  const service = createJobService({ asyncEnabled: true, jobs: cfg, limits }, { store });
  const req = normalizeJobRequest({ version: 2, jobId: randomUUID(), input: { container: inputs, blobName: 'original.mp4', expectedETag: created.etag },
    output: { container: outputs, prefix: 'exports' }, metadata: { revision: 'case-7' }, notification: { queue: 'events' },
    options: { mode: 'transcode', format: 'wav', sampleRate: 16000, channels: 1 } });
  t.after(async () => {
    await input.deleteIfExists(); await output.deleteIfExists(); await events.deleteIfExists();
    const control = client.getContainerClient(CONTAINER_NAME);
    for await (const blob of control.listBlobsFlat({ prefix: `${req.jobId}/` })) await control.getBlockBlobClient(blob.name).deleteIfExists();
  });
  await service.process(JSON.stringify(req));
  let persisted = await store.find(req.jobId);
  assert.equal(persisted.job.status, 'succeeded'); assert.equal(persisted.outbox.state, 'pending');
  assert.equal(persisted.source.eTag, created.etag); assert.equal(persisted.result.contentType, 'audio/wav');
  const dir = await directory(t); const target = join(dir, 'audio.wav');
  const downloaded = await service.download(req.jobId, target);
  assert.equal(downloaded.contentType, 'audio/wav'); assert.equal((await readFile(target)).toString('ascii', 0, 4), 'RIFF');
  failDelivery = false;
  const maintenance = await store.maintenance(); assert.equal(maintenance.failed, 0); assert.equal(maintenance.delivered, 1);
  persisted = await store.find(req.jobId); assert.equal(persisted.outbox.state, 'sent');
  const received = await events.receiveMessages(); assert.equal(received.receivedMessageItems.length, 1);
  const event = JSON.parse(Buffer.from(received.receivedMessageItems[0].messageText, 'base64').toString('utf8'));
  assert.equal(event.result.sha256, persisted.result.sha256); assert.equal(event.metadata.revision, 'case-7');
  await service.process(JSON.stringify(req)); assert.equal((await events.peekMessages()).peekedMessageItems.length, 0);
  await store.maintenance({ now: Date.now() + 1.5 * 86400000 });
  assert.ok((await store.find(req.jobId)).resultExpiredAt);
  assert.equal(await output.getBlockBlobClient(persisted.result.blobName).exists(), false);
  assert.equal(await source.exists(), true, 'The directly referenced original remains untouched');
  await store.maintenance({ now: Date.now() + 3 * 86400000 });
  assert.equal(await store.find(req.jobId), null);
});

test('an interrupted HTTP submission expires after retention, while ordinary queued or running jobs do not', async t => {
  const f = integrated(); const req = f.req; delete req.notification; req.input.blobName = 'pending-upload';
  const old = { ...record(req), submissionPending: true }; await f.store.ensure(old);
  const dir = await directory(t); const file = join(dir, 'input'); await writeFile(file, 'video');
  // This models a crash while submitting, after the durable input but before enqueue.
  await f.store.saveInput(req, file);
  const now = Date.parse(old.job.updatedAt) + 8 * 86400000;
  await f.store.maintenance({ now });
  const expired = await f.store.find(req.jobId); assert.equal(expired.job.errorCode, 'SUBMISSION_EXPIRED');
  assert.equal(expired.submissionPending, false);
  const other = { ...request(), jobId: randomUUID() };
  const queued = record(other); await f.store.ensure(queued);
  await f.store.maintenance({ now: now + 60 * 86400000 });
  assert.equal((await f.store.find(other.jobId)).job.status, 'queued');
});

test('retention preserves an output modified with the same ownership metadata after upload', async () => {
  const f = integrated(); await f.service.process(JSON.stringify(f.req));
  const result = (await f.store.find(f.req.jobId)).result;
  const key = f.key('output-secret', 'converted', result.blobName);
  const previous = f.blobs.get(key);
  f.put('output-secret', 'converted', result.blobName, 'caller changed content', previous.metadata);
  await f.store.maintenance({ now: Date.now() + 40 * 86400000 });
  assert.equal(f.blobs.get(key).data.toString(), 'caller changed content');
});

test('maintenance scans only job indexes and advances a bounded durable cursor across jobs', async () => {
  const f = integrated(); const ids = [];
  for (let index = 0; index < 3; index++) { const req = request(); ids.push(req.jobId); await f.store.ensure(record(req)); }
  for (let index = 0; index < 250; index++) f.put('control-secret', CONTAINER_NAME, `large-output/${index}.png`, 'unrelated');
  const visited = new Set();
  for (let index = 0; index < 3; index++) {
    f.calls.length = 0;
    const counts = await f.store.maintenance({ pageSize: 1 }); assert.equal(counts.scanned, 1);
    for (const call of f.calls) if (call.name?.endsWith('/status.json')) visited.add(call.name.split('/')[0]);
  }
  assert.deepEqual([...visited].sort(), ids.sort());
  assert.equal([...f.blobs.keys()].filter(key => key.includes('large-output/')).length, 250);
});

test('maintenance persists progress after a slow failing notification so later jobs are not starved', async t => {
  const f = integrated(); f.hooks.send = () => { throw sdkError(503, 'Unavailable'); };
  const ids = [];
  for (let i = 0; i < 3; i++) {
    const req = { ...f.req, jobId: randomUUID() }; ids.push(req.jobId);
    await f.service.process(JSON.stringify(req));
  }
  let now = Date.now(); let delayed = false;
  t.mock.method(Date, 'now', () => now);
  f.hooks.getProperties = name => {
    if (!delayed && name === `${ids.sort()[0]}/status.json`) { delayed = true; now += 36_000; }
  };
  const first = await f.store.maintenance(); assert.equal(first.scanned, 1); assert.equal(first.failed, 1);
  const progress = JSON.parse(f.blobs.get(f.key('control-secret', CONTAINER_NAME, '_maintenance/checkpoint.json')).data);
  assert.equal(progress.afterName, `_maintenance/jobs/${ids[0]}`);
  const second = await f.store.maintenance(); assert.equal(second.scanned, 2); assert.equal(second.failed, 2);
});
