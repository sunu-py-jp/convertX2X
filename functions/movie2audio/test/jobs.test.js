import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { copyFile, lstat, mkdir, mkdtemp, readFile, readdir, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { ConversionError, throwIfAborted } from '../src/errors.js';
import { extractAudio } from '../src/ffmpeg.js';
import { createJobService } from '../src/jobs.js';
import { CONTAINER_NAME, normalizeJobRequest } from '../src/job-request.js';

const FIXTURES = fileURLToPath(new URL('./fixtures/', import.meta.url));
const LIMITS = { maxInputBytes: 100 * 1024 * 1024, maxOutputBytes: 100 * 1024 * 1024, timeoutSeconds: 10 };
const CONFIG = { asyncEnabled: true, jobs: {}, limits: LIMITS };
const SECRET = 'https://private.blob.core.windows.net/data/secret?sig=DO-NOT-PUBLISH';
const clone = value => structuredClone(value);
const sourceKey = source => `${source.storage}/${source.container}/${source.blobName}`;
const expectCode = (code, status) => failure => {
  assert.equal(failure.code, code);
  if (status) assert.equal(failure.status, status);
  assert.ok(!failure.message.includes(SECRET));
  return true;
};

function request(overrides = {}) {
  return normalizeJobRequest({ version: 1, jobId: randomUUID(),
    input: { storage: 'default', container: 'incoming', blobName: 'meetings/video.mp4' },
    output: { storage: 'default', container: 'outgoing', prefix: 'meetings' },
    filename: 'video.mp4', ...overrides });
}

function createAdmission() {
  let busy = false;
  const events = [];
  return { events, get busy() { return busy; }, acquire() {
    if (busy) throw new ConversionError(503, 'CONVERSION_BUSY', 'Busy.');
    busy = true; events.push('acquire');
    let released = false;
    return () => { if (!released) { released = true; busy = false; events.push('release'); } };
  } };
}

class FakeStore {
  records = new Map();
  inputs = new Map();
  results = new Map();
  leases = new Map();
  messages = [];
  events = [];
  history = [];
  inputPaths = [];
  resultPaths = [];
  async validateLocations(req) {
    this.events.push('validate');
    if (!['default', 'source'].includes(req.input.storage)) throw new ConversionError(400, 'UNKNOWN_INPUT_STORAGE', SECRET);
    if (!['default', 'destination'].includes(req.output.storage)) throw new ConversionError(400, 'UNKNOWN_OUTPUT_STORAGE', SECRET);
  }
  async ensure(record, { signal } = {}) {
    throwIfAborted(signal);
    this.events.push('ensure');
    if (!this.records.has(record.job.id)) this.records.set(record.job.id, clone(record));
    return clone(this.records.get(record.job.id));
  }
  async find(id, { signal } = {}) { throwIfAborted(signal); return clone(this.records.get(id) ?? null); }
  async lock(id, { signal } = {}) {
    throwIfAborted(signal);
    if (this.leases.has(id)) throw new ConversionError(409, 'JOB_BUSY', SECRET);
    const controller = new AbortController();
    this.leases.set(id, controller);
    const abort = () => controller.abort(signal.reason);
    signal?.addEventListener('abort', abort, { once: true });
    this.events.push('lock');
    return { signal: controller.signal,
      update: async record => {
        throwIfAborted(controller.signal);
        this.events.push(`update:${record.job.status}`);
        this.history.push(clone(record));
        this.records.set(id, clone(record));
      }, close: async () => {
        signal?.removeEventListener('abort', abort);
        this.events.push('unlock');
        this.leases.delete(id);
      } };
  }
  async saveInput(req, path, { signal } = {}) {
    throwIfAborted(signal);
    this.events.push('saveInput');
    this.inputs.set(sourceKey(req.input), await readFile(path));
  }
  async enqueue(req, { signal } = {}) {
    throwIfAborted(signal);
    this.events.push('enqueue');
    assert.ok(this.inputs.has(sourceKey(req.input)), 'Input precedes queue publication');
    assert.equal(this.records.get(req.jobId)?.job.status, 'queued', 'State precedes queue publication');
    this.messages.push(JSON.stringify(req));
  }
  async readInput(source, path, { signal } = {}) {
    throwIfAborted(signal);
    this.events.push('readInput');
    const bytes = this.inputs.get(sourceKey(source));
    if (!bytes) throw new ConversionError(422, 'INPUT_NOT_FOUND', SECRET);
    this.inputPaths.push(path);
    await writeFile(path, bytes, { flag: 'wx', mode: 0o600 });
  }
  async writeResult(req, path, { signal } = {}) {
    throwIfAborted(signal);
    this.events.push('writeResult');
    this.resultPaths.push(path);
    const bytes = await readFile(path);
    assert.equal((await lstat(path)).mode & 0o777, 0o600, 'Private output file');
    assert.equal((await lstat(dirname(path))).mode & 0o777, 0o700, 'Private work directory');
    const descriptor = { storage: req.output.storage, container: req.output.container,
      blobName: [req.output.prefix, req.jobId, 'attempts', randomUUID(), 'audio.m4a'].filter(Boolean).join('/'),
      filename: 'audio.m4a', contentType: 'audio/mp4', sizeBytes: bytes.length, etag: '"test"' };
    this.results.set(sourceKey(descriptor), bytes);
    return descriptor;
  }
  async readResult(result, path, { signal } = {}) {
    throwIfAborted(signal);
    this.events.push('readResult');
    const bytes = this.results.get(sourceKey(result));
    if (!bytes) throw new ConversionError(404, 'RESULT_NOT_FOUND', SECRET);
    await writeFile(path, bytes, { flag: 'wx', mode: 0o600 });
  }
}

async function setup(t, options = {}) {
  const directory = await mkdtemp(join(tmpdir(), 'movie-jobs-test-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const work = join(directory, 'work');
  await mkdir(work);
  const store = options.store ?? new FakeStore();
  const admission = options.admission ?? createAdmission();
  const config = options.config ?? CONFIG;
  const service = createJobService(config, { store, admission, temporaryRoot: work,
    ...(options.extract ? { extract: options.extract } : {}) });
  return { directory, work, store, service, admission };
}

async function seed(store, req, fixture = 'aac-video.mp4') {
  store.inputs.set(sourceKey(req.input), await readFile(join(FIXTURES, fixture)));
  return JSON.stringify(req);
}

async function exists(path) { return lstat(path).then(() => true, failure => {
  if (failure.code === 'ENOENT') return false;
  throw failure;
}); }

test('HTTP submission persists input and queued status before publication, then extracts real AAC and downloads M4A', async t => {
  const { directory, work, store, service, admission } = await setup(t);
  const job = await service.submit(join(FIXTURES, 'aac-video.mp4'), '../会議.mp4');
  assert.equal(job.status, 'queued');
  assert.equal(job.filename, '会議.mp4');
  assert.deepEqual(Object.keys(job).sort(), ['id', 'status', 'filename', 'createdAt', 'updatedAt', 'sizeBytes', 'errorCode', 'errorMessage'].sort());
  assert.equal(store.history.length, 0, 'Submission defers FFmpeg');
  assert.deepEqual(store.events.slice(-3), ['saveInput', 'ensure', 'enqueue']);
  const req = JSON.parse(store.messages[0]);
  assert.equal(req.input.container, CONTAINER_NAME);
  assert.equal(req.input.blobName, `${job.id}/input`);
  assert.equal(req.output.prefix, '');
  assert.equal(req.jobId, job.id);
  assert.equal(admission.events.length, 0, 'HTTP owns submission admission');

  await service.process(store.messages[0]);
  const completed = await service.find(job.id.toUpperCase());
  assert.equal(completed.status, 'succeeded');
  assert.ok(completed.sizeBytes > 1000);
  assert.equal(completed.errorCode, null);
  assert.deepEqual(store.history.map(record => record.job.status), ['running', 'succeeded']);
  assert.deepEqual(await readdir(work), []);
  assert.deepEqual(admission.events, ['acquire', 'release']);
  const destination = join(directory, 'download.m4a');
  assert.deepEqual(await service.download(job.id, destination), { filename: 'audio.m4a', contentType: 'audio/mp4', sizeBytes: completed.sizeBytes });
  const output = await readFile(destination);
  assert.equal(output.toString('ascii', 4, 8), 'ftyp');
  assert.ok(output.indexOf(Buffer.from('moov')) < output.indexOf(Buffer.from('mdat')), 'M4A metadata precedes audio');
  assert.deepEqual(output, store.results.values().next().value);
  assert.equal((await lstat(destination)).mode & 0o777, 0o600);
  assert.ok(!JSON.stringify(completed).includes('blobName'));
});

test('direct queue producer uses registered input/output aliases without HTTP and duplicate success is a no-op', async t => {
  let extractions = 0;
  const { work, store, service, admission } = await setup(t, { extract: (...args) => { extractions++; return extractAudio(...args); } });
  const req = request({ input: { storage: 'source', container: 'incoming', blobName: '会議/input.mp4' },
    output: { storage: 'destination', container: 'outgoing', prefix: 'teams/日本語' } });
  const message = await seed(store, req);
  await service.process(Buffer.from(message));
  const before = clone(store.records.get(req.jobId));
  assert.equal(before.result.storage, 'destination');
  assert.ok(before.result.blobName.startsWith(`teams/日本語/${req.jobId}/`));
  const differentlyOrdered = { filename: req.filename, output: req.output, input: req.input,
    jobId: req.jobId.toUpperCase(), version: 1 };
  const release = admission.acquire();
  await service.process(JSON.stringify(differentlyOrdered));
  await service.poison(message);
  release();
  assert.equal(extractions, 1);
  assert.deepEqual(store.records.get(req.jobId), before);
  assert.deepEqual(await readdir(work), []);
});

test('real invalid media becomes a terminal failed job, and duplicates do not run FFmpeg again', async t => {
  let extractions = 0;
  const { work, store, service } = await setup(t, { extract: (...args) => { extractions++; return extractAudio(...args); } });
  const req = request();
  const message = await seed(store, req, 'silent-video.mp4');
  await service.process(message);
  assert.equal((await service.find(req.jobId)).status, 'failed');
  assert.equal((await service.find(req.jobId)).errorCode, 'NO_AUDIO_STREAM');
  assert.equal(store.results.size, 0);
  await service.process(message);
  await service.poison(message);
  assert.equal(extractions, 1);
  assert.deepEqual(await readdir(work), []);
});

test('conflicting requests cannot change an existing job or its result, including poison delivery', async t => {
  const { store, service } = await setup(t);
  const req = request();
  await service.process(await seed(store, req));
  const before = clone(store.records.get(req.jobId));
  const conflict = JSON.stringify({ ...req, input: { ...req.input, blobName: 'different.mp4' } });
  await assert.rejects(service.process(conflict), expectCode('JOB_ID_CONFLICT', 409));
  await service.poison(conflict);
  assert.deepEqual(store.records.get(req.jobId), before);
  assert.equal(store.results.size, 1);
});

test('malformed, credential-bearing and duplicate-key requests are rejected without creating state; poison ignores them', async t => {
  const { store, service } = await setup(t);
  for (const message of ['not JSON', '{}', JSON.stringify({ ...request(), sasUrl: SECRET }),
    JSON.stringify(request()).replace('"version":1', '"version":1,"version":1')]) {
    await assert.rejects(service.process(message), expectCode('INVALID_QUEUE_MESSAGE', 400));
    await service.poison(message);
  }
  assert.equal(store.records.size, 0);
  assert.equal(store.leases.size, 0);
});

test('permanent input/location errors are fixed, public messages and do not expose storage details', async t => {
  const { store, service } = await setup(t);
  for (const [req, code] of [[request(), 'INPUT_NOT_FOUND'],
    [request({ input: { storage: 'unknown', container: 'incoming', blobName: 'video.mp4' } }), 'UNKNOWN_INPUT_STORAGE'],
    [request({ output: { storage: 'unknown', container: 'outgoing', prefix: '' } }), 'UNKNOWN_OUTPUT_STORAGE']]) {
    await service.process(JSON.stringify(req));
    const job = await service.find(req.jobId);
    assert.equal(job.status, 'failed');
    assert.equal(job.errorCode, code);
    assert.ok(!JSON.stringify(job).includes(SECRET));
  }
  assert.equal(store.results.size, 0);
});

test('transient extraction failure stays retryable and a later delivery succeeds', async t => {
  let fail = true;
  const { work, store, service, admission } = await setup(t, { extract: (...args) => {
    if (fail) throw new ConversionError(503, 'FFMPEG_UNAVAILABLE', SECRET);
    return extractAudio(...args);
  } });
  const req = request();
  const message = await seed(store, req);
  await assert.rejects(service.process(message), expectCode('FFMPEG_UNAVAILABLE', 503));
  const interim = await service.find(req.jobId);
  assert.equal(interim.status, 'running');
  assert.equal(interim.errorCode, null);
  assert.equal(admission.busy, false);
  assert.deepEqual(await readdir(work), []);
  fail = false;
  await service.process(message);
  assert.equal((await service.find(req.jobId)).status, 'succeeded');
});

test('raw SDK failure is redacted, remains retryable, and poison makes a terminal failure', async t => {
  const { work, store, service } = await setup(t);
  const req = request();
  const message = await seed(store, req);
  store.writeResult = async () => { throw new Error(SECRET); };
  await assert.rejects(service.process(message), expectCode('STORAGE_UNAVAILABLE', 503));
  assert.equal((await service.find(req.jobId)).status, 'running');
  await service.poison(message);
  const failed = await service.find(req.jobId);
  assert.equal(failed.status, 'failed');
  assert.equal(failed.errorCode, 'PROCESSING_FAILED');
  assert.ok(!JSON.stringify(store.history).includes(SECRET));
  await service.process(message);
  assert.deepEqual(await readdir(work), []);
});

test('timeout aborts extraction, releases admission and leaves the job for retry then poison', async t => {
  let observedSignal;
  const { work, store, service, admission } = await setup(t, {
    config: { ...CONFIG, limits: { ...LIMITS, timeoutSeconds: 0.05 } },
    extract: async (input, output, { signal }) => {
      observedSignal = signal;
      await new Promise((resolve, reject) => {
        // Keep the test active; production FFmpeg and HTTP sockets keep the event loop active.
        const keepAlive = setInterval(() => {}, 1000);
        signal.addEventListener('abort', () => { clearInterval(keepAlive); reject(signal.reason); }, { once: true });
        if (signal.aborted) { clearInterval(keepAlive); reject(signal.reason); }
      });
    }
  });
  const req = request();
  const message = await seed(store, req);
  await assert.rejects(service.process(message), expectCode('CONVERSION_TIMEOUT', 504));
  assert.equal(observedSignal.aborted, true);
  assert.equal((await service.find(req.jobId)).status, 'running');
  assert.deepEqual(await readdir(work), []);
  assert.equal(admission.busy, false);
  await service.poison(message);
  assert.equal((await service.find(req.jobId)).errorCode, 'PROCESSING_FAILED');
});

test('loss of renewable lease aborts work and never publishes success or failure', async t => {
  const store = new FakeStore();
  const req = request();
  const { work, service, admission } = await setup(t, { store,
    extract: async (input, output, { signal }) => {
      await copyFile(input, output);
      store.leases.get(req.jobId).abort(new Error(SECRET));
      assert.equal(signal.aborted, true);
    } });
  await assert.rejects(service.process(await seed(store, req)), expectCode('JOB_LEASE_LOST', 503));
  assert.deepEqual(store.history.map(record => record.job.status), ['running']);
  assert.equal(store.results.size, 0);
  assert.equal(store.leases.size, 0);
  assert.equal(admission.busy, false);
  assert.deepEqual(await readdir(work), []);
});

test('lease loss after upload does not publish the artifact and a retry can create a new attempt', async t => {
  const { work, store, service } = await setup(t);
  const req = request();
  const message = await seed(store, req);
  const write = store.writeResult.bind(store);
  store.writeResult = async (...args) => {
    const result = await write(...args);
    store.leases.get(req.jobId).abort(new ConversionError(503, 'JOB_LEASE_LOST', SECRET));
    return result;
  };
  await assert.rejects(service.process(message), expectCode('JOB_LEASE_LOST', 503));
  assert.equal((await service.find(req.jobId)).status, 'running');
  assert.equal(store.records.get(req.jobId).result, null);
  assert.equal(store.results.size, 1);
  store.writeResult = write;
  await service.process(message);
  assert.equal((await service.find(req.jobId)).status, 'succeeded');
  assert.equal(store.results.size, 2);
  assert.deepEqual(await readdir(work), []);
});

test('the shared admission rejects queued work while synchronous work is active and leaves it queued', async t => {
  const { store, service, admission, work } = await setup(t);
  const releaseHttp = admission.acquire();
  const req = request();
  const message = await seed(store, req);
  await assert.rejects(service.process(message), expectCode('CONVERSION_BUSY', 503));
  assert.equal((await service.find(req.jobId)).status, 'queued');
  assert.equal(store.leases.size, 0);
  assert.equal(store.inputPaths.length, 0);
  assert.equal(admission.busy, true, 'Worker cannot release the HTTP owner slot');
  releaseHttp();
  await service.process(message);
  assert.equal((await service.find(req.jobId)).status, 'succeeded');
  assert.deepEqual(await readdir(work), []);
});

test('the worker retains admission and local files until upload and publication complete', async t => {
  const { store, service, admission, work } = await setup(t);
  const req = request();
  const message = await seed(store, req);
  let finishUpload;
  let uploadStarted;
  const started = new Promise(resolve => { uploadStarted = resolve; });
  const write = store.writeResult.bind(store);
  store.writeResult = async (...args) => {
    uploadStarted();
    await new Promise(resolve => { finishUpload = resolve; });
    return write(...args);
  };
  const pending = service.process(message);
  await started;
  assert.equal(admission.busy, true);
  assert.equal((await service.find(req.jobId)).status, 'running');
  assert.equal(await exists(store.inputPaths[0]), true);
  assert.throws(() => admission.acquire(), expectCode('CONVERSION_BUSY', 503));
  const other = request();
  await assert.rejects(service.process(await seed(store, other)), expectCode('CONVERSION_BUSY', 503));
  finishUpload();
  await pending;
  assert.equal(admission.busy, false);
  assert.equal((await service.find(req.jobId)).status, 'succeeded');
  assert.deepEqual(await readdir(work), []);
});

test('a concurrent duplicate lease conflict stays retryable and cannot release the first lease', async t => {
  const { store, service } = await setup(t);
  const req = request();
  const message = await seed(store, req);
  await store.ensure({ job: { id: req.jobId, status: 'queued', filename: req.filename }, request: req, result: null });
  const held = await store.lock(req.jobId);
  await assert.rejects(service.process(message), expectCode('JOB_BUSY', 503));
  assert.equal(store.leases.size, 1);
  await held.close();
  await service.process(message);
  assert.equal((await service.find(req.jobId)).status, 'succeeded');
});

test('submission rejects empty, oversized and symlink inputs without enqueueing', async t => {
  const { directory, store, service } = await setup(t, { config: { ...CONFIG, limits: { ...LIMITS, maxInputBytes: 100 } } });
  const empty = join(directory, 'empty');
  await writeFile(empty, '');
  await assert.rejects(service.submit(empty, 'empty.mp4'), expectCode('EMPTY_INPUT', 400));
  await assert.rejects(service.submit(join(FIXTURES, 'aac-video.mp4'), 'video.mp4'), expectCode('INPUT_TOO_LARGE', 413));
  const link = join(directory, 'link');
  await symlink(empty, link);
  await assert.rejects(service.submit(link, 'video.mp4'), expectCode('STORAGE_ERROR', 500));
  assert.equal(store.inputs.size, 0);
  assert.equal(store.messages.length, 0);
  assert.equal(store.records.size, 0);
});

test('a failed queue publication does not report acceptance or delete persisted input and status', async t => {
  const { store, service } = await setup(t);
  store.enqueue = async () => { throw new Error(SECRET); };
  await assert.rejects(service.submit(join(FIXTURES, 'aac-video.mp4'), 'video.mp4'), expectCode('STORAGE_UNAVAILABLE', 503));
  assert.equal(store.inputs.size, 1);
  assert.equal(store.records.size, 1);
  assert.equal(store.records.values().next().value.job.status, 'queued');
  assert.equal(store.messages.length, 0);
});

test('download requires a succeeded result, preserves an existing target, and cleans up invalid downloads', async t => {
  const { directory, store, service } = await setup(t);
  const target = join(directory, 'audio.m4a');
  await assert.rejects(service.download(randomUUID(), target), expectCode('JOB_NOT_FOUND', 404));
  const job = await service.submit(join(FIXTURES, 'aac-video.mp4'), 'video.mp4');
  await assert.rejects(service.download(job.id, target), expectCode('JOB_NOT_READY', 409));
  await service.process(store.messages[0]);
  await writeFile(target, 'keep');
  await assert.rejects(service.download(job.id, target), expectCode('STORAGE_UNAVAILABLE', 503));
  assert.equal(await readFile(target, 'utf8'), 'keep');
  await rm(target);
  const record = store.records.get(job.id);
  store.results.set(sourceKey(record.result), Buffer.from('short'));
  await assert.rejects(service.download(job.id, target), expectCode('STORAGE_UNAVAILABLE', 503));
  assert.equal(await exists(target), false);
});

test('empty and oversized extractor output fails before publishing any result', async t => {
  for (const [bytes, code] of [[Buffer.alloc(0), 'EMPTY_OUTPUT'], [Buffer.alloc(101), 'OUTPUT_TOO_LARGE']]) {
    const { store, service, work } = await setup(t, { config: { ...CONFIG, limits: { ...LIMITS, maxOutputBytes: 100 } },
      extract: (input, output) => writeFile(output, bytes) });
    const req = request();
    await service.process(await seed(store, req));
    assert.equal((await service.find(req.jobId)).errorCode, code);
    assert.equal(store.results.size, 0);
    assert.deepEqual(await readdir(work), []);
  }
});

test('unknown permanent errors keep fixed public information while known errors replace untrusted messages', async t => {
  for (const [failure, code] of [[new ConversionError(422, SECRET, SECRET), 'PROCESSING_FAILED'],
    [new ConversionError(422, 'INVALID_MEDIA', SECRET), 'INVALID_MEDIA']]) {
    const { store, service } = await setup(t, { extract: () => { throw failure; } });
    const req = request();
    await service.process(await seed(store, req));
    const job = await service.find(req.jobId);
    assert.equal(job.status, 'failed');
    assert.equal(job.errorCode, code);
    assert.ok(!JSON.stringify(store.records.get(req.jobId)).includes(SECRET));
  }
});

test('disabled storage rejects every job operation and aborts do not start I/O', async t => {
  const disabled = createJobService({ ...CONFIG, asyncEnabled: false });
  const id = randomUUID();
  for (const task of [() => disabled.submit('/not/read', 'file.mp4'), () => disabled.find(id),
    () => disabled.download(id, '/not/written'), () => disabled.process('{}'), () => disabled.poison('{}')]) {
    await assert.rejects(task, expectCode('ASYNC_DISABLED', 503));
  }
  const { service, store } = await setup(t);
  const signal = AbortSignal.abort(new ConversionError(504, 'CONVERSION_TIMEOUT', SECRET));
  await assert.rejects(service.submit('/not/read', 'file.mp4', { signal }), expectCode('CONVERSION_TIMEOUT', 504));
  await assert.rejects(service.find(id, { signal }), expectCode('CONVERSION_TIMEOUT', 504));
  await assert.rejects(service.download(id, '/not/written', { signal }), expectCode('CONVERSION_TIMEOUT', 504));
  assert.equal(store.events.length, 0);
});
