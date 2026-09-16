#!/usr/bin/env node
// Local Functions + Azurite integration. Uses only the emulator's public test key.
import assert from 'node:assert/strict';
import { randomUUID, createHash } from 'node:crypto';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { setTimeout as delay } from 'node:timers/promises';
import { BlobServiceClient } from '@azure/storage-blob';
import { QueueServiceClient } from '@azure/storage-queue';

const base = new URL(process.env.MOVIE_TEST_BASE_URL ?? 'http://localhost:7074/api/');
assert.equal(base.protocol, 'http:', 'This test is limited to local HTTP');
assert.ok(['localhost', '127.0.0.1', '[::1]'].includes(base.hostname), 'Use a local Functions host');
assert.ok(!base.username && !base.password && !base.search && !base.hash);
if (!base.pathname.endsWith('/')) base.pathname += '/';
const blobs = BlobServiceClient.fromConnectionString('UseDevelopmentStorage=true');
const queue = QueueServiceClient.fromConnectionString('UseDevelopmentStorage=true').getQueueClient('movie2audio-jobs');
const control = blobs.getContainerClient('movie2audio-jobs');
const tag = randomUUID().slice(0, 8);
const input = blobs.getContainerClient(`movie-e2e-in-${tag}`);
const output = blobs.getContainerClient(`movie-e2e-out-${tag}`);
const ids = [];
const results = [];
const video = await readFile(new URL('../test/fixtures/aac-video.mp4', import.meta.url));
const workspace = await mkdtemp(join(tmpdir(), 'movie-async-e2e-'));
const hash = value => createHash('sha256').update(value).digest('hex');

async function call(path, options = {}) {
  for (let attempt = 0; ; attempt++) {
    const response = await fetch(new URL(path, base), { ...options, redirect: 'error', signal: AbortSignal.timeout(220000) });
    const body = response.status === 503 ? await response.clone().json().catch(() => null) : null;
    if (body?.error?.code !== 'CONVERSION_BUSY' || attempt >= 20) return response;
    await response.body?.cancel();
    await delay(3000);
  }
}
async function status(id, expected = 'succeeded') {
  const deadline = Date.now() + 90000;
  while (Date.now() < deadline) {
    const response = await call(`jobs/${id}`);
    if (response.status === 404) { await delay(500); continue; }
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.deepEqual(Object.keys(body).sort(), body.job.status === 'succeeded'
      ? ['job', 'resultUrl', 'statusUrl'] : ['job', 'statusUrl']);
    assert.equal(body.job.id, id);
    if (['succeeded', 'failed'].includes(body.job.status)) {
      assert.equal(body.job.status, expected, body.job.errorCode ?? 'Unexpected terminal state');
      return body;
    }
    await delay(500);
  }
  assert.fail('Queue job did not finish within 90 seconds');
}
async function result(id, expectedHash) {
  const response = await call(`jobs/${id}/result`);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('content-type'), 'audio/mp4');
  assert.equal(response.headers.get('x-audio-mode'), 'copy');
  const body = Buffer.from(await response.arrayBuffer());
  assert.ok(body.length > 0);
  if (expectedHash) assert.equal(hash(body), expectedHash);
  return body;
}
async function stored(id) {
  const raw = await control.getBlockBlobClient(`${id}/status.json`).downloadToBuffer();
  return JSON.parse(raw.toString('utf8'));
}
async function enqueue(message) {
  await queue.sendMessage(Buffer.from(typeof message === 'string' ? message : JSON.stringify(message)).toString('base64'));
}
async function drained() {
  const deadline = Date.now() + 90000;
  while (Date.now() < deadline) {
    if ((await queue.getProperties()).approximateMessagesCount === 0) return;
    await delay(500);
  }
  assert.fail('Test queue did not drain');
}
function direct(blobName = 'video.mp4') {
  const jobId = randomUUID(); ids.push(jobId);
  return { version: 1, jobId, input: { container: input.containerName, blobName },
    output: { container: output.containerName, prefix: 'exports' }, filename: 'meeting.mp4' };
}

try {
  const capabilities = await (await call('capabilities')).json();
  assert.equal(capabilities.asyncEnabled, true, 'Enable CONVERSION_STORAGE_CONNECTION_STRING on the test host');
  await input.create(); await output.create(); await queue.createIfNotExists();
  const sync = await call('convert', { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: video });
  assert.equal(sync.status, 200);
  const reference = Buffer.from(await sync.arrayBuffer());
  const referenceHash = hash(reference);
  const submitted = await call('jobs?filename=meeting.mp4', { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: video });
  assert.equal(submitted.status, 202);
  const accepted = await submitted.json(); ids.push(accepted.job.id);
  assert.equal(accepted.job.status, 'queued');
  assert.equal(submitted.headers.get('location'), `/api/jobs/${accepted.job.id}`);
  const complete = await status(accepted.job.id);
  assert.equal(complete.job.filename, 'meeting.mp4');
  const audio = await result(accepted.job.id, referenceHash);
  assert.equal(complete.job.sizeBytes, audio.length);
  results.push({ case: 'HTTP upload -> Queue -> M4A result', bytes: audio.length, sameAsSync: true });

  await input.getBlockBlobClient('video.mp4').uploadData(video, { conditions: { ifNoneMatch: '*' } });
  const request = direct();
  await enqueue(request);
  await status(request.jobId);
  await result(request.jobId, referenceHash);
  const record = await stored(request.jobId);
  assert.equal(record.result.container, output.containerName);
  assert.match(record.result.blobName, new RegExp(`^exports/${request.jobId}/results/[0-9a-f-]{36}/audio\\.m4a$`));
  assert.equal(record.result.contentType, 'audio/mp4');
  const blobBytes = await output.getBlockBlobClient(record.result.blobName).downloadToBuffer();
  assert.equal(hash(blobBytes), referenceHash);
  results.push({ case: 'direct Base64 Queue -> selected output Blob', bytes: blobBytes.length });
  await enqueue(request); await drained();
  assert.deepEqual((await stored(request.jobId)).result, record.result);
  results.push({ case: 'duplicate terminal job preserves the result' });

  const produced = direct();
  produced.input.blobName = `${produced.jobId}/video.mp4`;
  const requestFile = join(workspace, 'request.json');
  await writeFile(requestFile, JSON.stringify(produced), { mode: 0o600 });
  const producer = fileURLToPath(new URL('../examples/QueueProducer.mjs', import.meta.url));
  const sample = fileURLToPath(new URL('../test/fixtures/aac-video.mp4', import.meta.url));
  const producerOptions = { env: { ...process.env, CONVERSION_STORAGE_CONNECTION_STRING: 'UseDevelopmentStorage=true' }, timeout: 90000 };
  const posted = await promisify(execFile)(process.execPath, [producer, sample, requestFile], producerOptions);
  assert.equal(JSON.parse(posted.stdout).jobId, produced.jobId);
  await status(produced.jobId);
  await result(produced.jobId, referenceHash);
  const producedRecord = await stored(produced.jobId);
  await promisify(execFile)(process.execPath, [producer, '--queue-only', requestFile], producerOptions);
  await drained();
  assert.deepEqual((await stored(produced.jobId)).result, producedRecord.result);
  results.push({ case: 'shipped QueueProducer uploads, enqueues, and resends without overwrite' });

  const missing = direct('missing.mp4');
  await enqueue(missing);
  const failure = await status(missing.jobId, 'failed');
  assert.equal(failure.job.errorCode, 'INPUT_NOT_FOUND');
  assert.equal((await call(`jobs/${missing.jobId}/result`)).status, 409);
  results.push({ case: 'missing input fails with no downloadable result' });

  const bad = await call('jobs?filename=bad.mp4', { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: Buffer.from('invalid video') });
  assert.equal(bad.status, 202);
  const badJob = await bad.json(); ids.push(badJob.job.id);
  assert.equal((await status(badJob.job.id, 'failed')).job.errorCode, 'INVALID_MEDIA');
  results.push({ case: 'invalid media is rejected by the actual FFmpeg worker' });

  const poison = direct();
  const poisonQueue = QueueServiceClient.fromConnectionString('UseDevelopmentStorage=true').getQueueClient('movie2audio-jobs-poison');
  await poisonQueue.createIfNotExists();
  await poisonQueue.sendMessage(Buffer.from(JSON.stringify(poison)).toString('base64'));
  assert.equal((await status(poison.jobId, 'failed')).job.errorCode, 'PROCESSING_FAILED');
  results.push({ case: 'poison handler records terminal failure' });

  // Malformed messages exhaust retries before poison handling. Opt in only on a
  // dedicated host using maxDequeueCount=2 and visibilityTimeout=00:00:01.
  if (process.env.MOVIE_TEST_FAST_RETRY === '1') {
    const duplicate = direct();
    await enqueue(JSON.stringify(duplicate).replace('"version":1', '"version":2,"version":1'));
    await drained();
    assert.equal(await control.getBlockBlobClient(`${duplicate.jobId}/status.json`).exists(), false);
    results.push({ case: 'Queue trigger preserves bytes and rejects duplicate JSON keys' });
  }

  if (process.env.MOVIE_TEST_VIDEO_URL) {
    const response = await call('jobs-url', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url: process.env.MOVIE_TEST_VIDEO_URL }) });
    assert.equal(response.status, 202);
    const acceptedUrl = await response.json(); ids.push(acceptedUrl.job.id);
    await status(acceptedUrl.job.id);
    const data = await result(acceptedUrl.job.id);
    const saved = await stored(acceptedUrl.job.id);
    assert.equal(saved.request.input.storage, 'default');
    assert.equal(saved.request.input.blobName, `${acceptedUrl.job.id}/input`);
    assert.ok(!JSON.stringify(saved).includes(process.env.MOVIE_TEST_VIDEO_URL));
    results.push({ case: 'URL submission stores bytes and queues only Blob references', bytes: data.length });
  }
  console.log(JSON.stringify({ passed: true, environment: 'local Functions + Azurite', results }, null, 2));
} finally {
  await rm(workspace, { recursive: true, force: true });
  // Only this test's private containers and newly generated job prefixes are removed.
  await input.deleteIfExists(); await output.deleteIfExists();
  for (const id of ids) {
    for await (const blob of control.listBlobsFlat({ prefix: `${id}/` })) {
      await control.getBlobClient(blob.name).deleteIfExists();
    }
  }
}
