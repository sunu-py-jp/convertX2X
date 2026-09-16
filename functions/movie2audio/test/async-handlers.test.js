import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { createConfig, publicSettings } from '../src/config.js';
import { createHandlers } from '../src/handlers.js';
import { createAdmission } from '../src/admission.js';
import { ConversionError } from '../src/errors.js';

const config = createConfig({ CONVERSION_STORAGE_CONNECTION_STRING: 'UseDevelopmentStorage=true',
  CONVERSION_URL_ALLOWED_HOSTS: 'media.example.com' });
const id = '8497051e-e399-4449-a155-a81f84e16e75';
const job = { id, status: 'queued', filename: 'video.mp4', createdAt: '2026-09-15T00:00:00Z',
  updatedAt: '2026-09-15T00:00:00Z', sizeBytes: null, errorCode: null, errorMessage: null };
const context = { error() {}, warn() {} };
const errorCode = response => JSON.parse(response.body).error.code;
function request(path = 'jobs', body = 'video', type = 'application/octet-stream') {
  const request = new Request(`https://example.com/api/${path}`, body === null ? { method: 'GET' }
    : { method: 'POST', body, headers: { 'Content-Type': type }, duplex: 'half' });
  request.params = { id };
  return request;
}
async function workspace(t) {
  const root = await mkdtemp(join(tmpdir(), 'movie-async-http-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  return root;
}
async function clean(root) {
  for (let attempt = 0; attempt < 100; attempt++) {
    if ((await readdir(root)).length === 0) return;
    await delay(5);
  }
  assert.fail('Temporary workspace was not removed');
}

test('optional storage controls all async APIs without exposing its settings', async () => {
  assert.equal(publicSettings(config).asyncEnabled, true);
  assert.ok(!JSON.stringify(publicSettings(config)).includes('DevelopmentStorage'));
  const handlers = createHandlers(createConfig({}));
  for (const operation of ['submit', 'submitUrl', 'status', 'result']) {
    assert.equal(errorCode(await handlers[operation](request(), context)), 'ASYNC_DISABLED');
  }
});

test('HTTP submit receives the complete file and returns 202 without invoking FFmpeg', async t => {
  const root = await workspace(t);
  const handlers = createHandlers(config, { temporaryRoot: root,
    extract: () => assert.fail('Conversion runs in the Queue worker'),
    jobs: { submit: async (path, filename, { signal }) => {
      assert.equal((await readFile(path)).toString(), 'video');
      assert.equal(filename, 'meeting.mp4');
      assert.equal(signal.aborted, false);
      return { ...job, filename };
    } } });
  const result = await handlers.submit(request('jobs?filename=meeting.mp4&code=SECRET'), context);
  assert.equal(result.status, 202);
  assert.equal(result.headers.Location, `/api/jobs/${id}`);
  assert.equal(result.headers['Retry-After'], '3');
  assert.equal(JSON.parse(result.body).statusUrl, `/api/jobs/${id}`);
  assert.ok(!result.body.includes('SECRET'));
  assert.deepEqual(await readdir(root), []);
});

test('URL submit persists downloaded bytes and excludes the source URL from acceptance', async t => {
  const root = await workspace(t);
  const handlers = createHandlers(config, { temporaryRoot: root,
    download: async (url, path) => {
      assert.equal(url, 'https://media.example.com/video.mp4?sig=SECRET');
      await writeFile(path, 'downloaded');
    },
    jobs: { submit: async (path, filename) => {
      assert.equal((await readFile(path)).toString(), 'downloaded');
      assert.equal(filename, 'video.bin');
      return job;
    } } });
  const result = await handlers.submitUrl(request('jobs-url', JSON.stringify({ url: 'https://media.example.com/video.mp4?sig=SECRET' }), 'application/json'), context);
  assert.equal(result.status, 202);
  assert.ok(!result.body.includes('SECRET'));
  assert.deepEqual(await readdir(root), []);
});

test('status polling remains available while the shared conversion admission is held', async () => {
  const admission = createAdmission();
  const release = admission.acquire();
  const handlers = createHandlers(config, { admission, jobs: { find: async () => ({ ...job, status: 'running' }) } });
  assert.equal(errorCode(await handlers.convert(request(), context)), 'CONVERSION_BUSY');
  assert.equal(errorCode(await handlers.submit(request(), context)), 'CONVERSION_BUSY');
  const result = await handlers.status(request(`jobs/${id}?code=SECRET`, null), context);
  assert.equal(result.status, 200);
  assert.equal(JSON.parse(result.body).job.status, 'running');
  assert.equal(JSON.parse(result.body).resultUrl, undefined);
  release(); release();
  const releaseAgain = admission.acquire();
  assert.throws(() => admission.acquire(), error => error.code === 'CONVERSION_BUSY');
  releaseAgain();
});

test('successful status advertises a relative authenticated download path', async () => {
  const handlers = createHandlers(config, { jobs: { find: async () => ({ ...job, status: 'succeeded', sizeBytes: 5 }) } });
  const result = await handlers.status(request(`jobs/${id}`, null), context);
  assert.equal(JSON.parse(result.body).resultUrl, `/api/jobs/${id}/result`);
  assert.equal(result.headers['Cache-Control'], 'no-store');
});

test('result download streams M4A and retains admission and disk until the response closes', async t => {
  const root = await workspace(t);
  const payload = Buffer.alloc(256 * 1024, 71);
  const handlers = createHandlers(config, { temporaryRoot: root, jobs: { download: async (actual, path) => {
    assert.equal(actual, id); await writeFile(path, payload);
    return { filename: 'audio.m4a', contentType: 'audio/mp4', sizeBytes: payload.length };
  } } });
  const result = await handlers.result(request(`jobs/${id}/result`, null), context);
  assert.equal(result.status, 200);
  assert.equal(result.headers['Content-Type'], 'audio/mp4');
  assert.equal(errorCode(await handlers.submit(request(), context)), 'CONVERSION_BUSY');
  assert.equal((await readdir(root)).length, 1);
  const chunks = [];
  for await (const chunk of result.body) { assert.ok(chunk.length <= 65536); chunks.push(chunk); }
  assert.deepEqual(Buffer.concat(chunks), payload);
  await clean(root);
});

test('submission and result failures clean the workspace and release admission', async t => {
  const root = await workspace(t);
  const admission = createAdmission();
  const handlers = createHandlers(config, { temporaryRoot: root, admission, jobs: {
    submit: async () => { throw new Error('Storage SAS SECRET'); },
    download: async () => { throw new ConversionError(409, 'JOB_NOT_READY', 'The job has not completed successfully.'); },
    find: async () => null,
  } });
  const result = await handlers.submit(request(), context);
  assert.equal(result.status, 500); assert.ok(!result.body.includes('SECRET'));
  assert.equal(errorCode(await handlers.result(request('', null), context)), 'JOB_NOT_READY');
  assert.equal(errorCode(await handlers.status(request('', null), context)), 'JOB_NOT_FOUND');
  assert.deepEqual(await readdir(root), []);
  admission.acquire()();
});

test('async file inputs use the same raw-body and size limits as synchronous conversion', async t => {
  const root = await workspace(t);
  const small = { ...config, limits: { ...config.limits, maxInputBytes: 3 } };
  const handlers = createHandlers(small, { temporaryRoot: root, jobs: { submit: () => assert.fail('Must not queue invalid upload') } });
  assert.equal(errorCode(await handlers.submit(request(), context)), 'INPUT_TOO_LARGE');
  assert.equal(errorCode(await handlers.submit(request('jobs', ''), context)), 'EMPTY_INPUT');
  assert.equal(errorCode(await handlers.submit(request('jobs', 'form', 'multipart/form-data'), context)), 'RAW_BODY_REQUIRED');
  assert.equal(errorCode(await handlers.submitUrl(request('jobs-url', '{"url":"first","url":"second"}', 'application/json'), context)), 'INVALID_URL_REQUEST');
  assert.deepEqual(await readdir(root), []);
});

test('all HTTP input paths parse the same bounded audio options; result downloads honor stored format', async t => {
  const root = await workspace(t);
  let actual;
  const handlers = createHandlers(config, { temporaryRoot: root, jobs: {
    submit: async (path, filename, options) => { actual = options.options; return job; },
    download: async (id, path) => { await writeFile(path, 'RIFFaudio'); return { filename: 'audio.wav', contentType: 'audio/wav', codec: 'pcm_s16le', mode: 'transcode', sizeBytes: 9 }; }
  } });
  const accepted = await handlers.submit(request('jobs?audioMode=transcode&audioFormat=wav&sampleRate=16000&channels=1'), context);
  assert.equal(accepted.status, 202); assert.deepEqual(actual, { mode: 'transcode', format: 'wav', sampleRate: 16000, channels: 1 });
  assert.equal(errorCode(await handlers.submit(request('jobs?audioMode=copy&audioMode=transcode'), context)), 'INVALID_AUDIO_OPTIONS');
  const response = await handlers.result(request(`jobs/${id}/result?audioFormat=m4a`, null), context);
  assert.equal(response.headers['Content-Type'], 'audio/wav'); assert.equal(response.headers['X-Audio-Codec'], 'pcm_s16le');
  assert.equal(response.headers['Content-Disposition'], 'attachment; filename="audio.wav"');
  for await (const chunk of response.body) { assert.ok(chunk.length); }
  await clean(root);
});
