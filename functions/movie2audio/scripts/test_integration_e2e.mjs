import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID, createHash } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { BlobServiceClient } from '@azure/storage-blob';
import { QueueServiceClient } from '@azure/storage-queue';

// Dedicated local/test app only. EVENTS must name the supplied test result queue.
const base = (process.env.MOVIE_TEST_BASE_URL || 'http://localhost:7073/api').replace(/\/$/, '');
const connection = process.env.MOVIE_TEST_STORAGE_CONNECTION_STRING;
if (!connection) throw new Error('Set MOVIE_TEST_STORAGE_CONNECTION_STRING for dedicated test storage.');
const client = BlobServiceClient.fromConnectionString(connection);
const queues = QueueServiceClient.fromConnectionString(connection);
const events = queues.getQueueClient(process.env.MOVIE_TEST_EVENTS_QUEUE_NAME || 'integration-events');
const control = client.getContainerClient('movie2audio-jobs');
const sourceContainer = client.getContainerClient(`integration-${randomUUID().replaceAll('-', '')}`);
const ids = [];
const audio = await readFile(new URL('../test/fixtures/opus-video.mkv', import.meta.url));
const options = 'audioMode=transcode&audioFormat=wav&sampleRate=16000&channels=1';
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
async function waitJob(id) {
  const deadline = Date.now() + 90000;
  while (Date.now() < deadline) {
    const response = await fetch(`${base}/jobs/${id}`);
    if (response.status === 200) {
      const { job } = await response.json();
      if (job.status === 'failed') throw new Error(`Job failed: ${job.errorCode}`);
      if (job.status === 'succeeded') return job;
    }
    await delay(250);
  }
  throw new Error('Job did not complete within the test deadline.');
}
try {
  const sync = await fetch(`${base}/convert?${options}`, { method: 'POST', body: audio, headers: { 'Content-Type': 'application/octet-stream' } });
  assert.equal(sync.status, 200); assert.equal(sync.headers.get('content-type'), 'audio/wav');
  const wav = Buffer.from(await sync.arrayBuffer()); assert.equal(wav.toString('ascii', 0, 4), 'RIFF');
  const accepted = await fetch(`${base}/jobs?${options}&filename=meeting.mkv`, { method: 'POST', body: audio,
    headers: { 'Content-Type': 'application/octet-stream' } });
  assert.equal(accepted.status, 202); const submitted = await accepted.json(); ids.push(submitted.job.id);
  await waitJob(submitted.job.id);
  const asyncResult = await fetch(`${base}/jobs/${submitted.job.id}/result`);
  assert.equal(asyncResult.headers.get('content-type'), 'audio/wav');
  assert.equal(digest(Buffer.from(await asyncResult.arrayBuffer())), digest(wav));
  await sourceContainer.create();
  const source = sourceContainer.getBlockBlobClient('original.mkv'); const uploaded = await source.uploadData(audio);
  const jobId = randomUUID(); ids.push(jobId);
  const request = { version: 2, jobId, input: { container: sourceContainer.containerName, blobName: 'original.mkv', expectedETag: uploaded.etag },
    output: { container: sourceContainer.containerName, prefix: 'converted' }, metadata: { revision: 'e2e-1' },
    notification: { queue: 'events' }, options: { mode: 'transcode', format: 'wav', sampleRate: 16000, channels: 1 } };
  const jobQueue = queues.getQueueClient('movie2audio-jobs'); await jobQueue.createIfNotExists();
  await jobQueue.sendMessage(Buffer.from(JSON.stringify(request)).toString('base64'));
  const done = await waitJob(jobId);
  assert.equal(done.input.eTag, uploaded.etag); assert.equal(done.result.sha256, digest(wav));
  let event;
  for (let i = 0; i < 100 && !event; i++) {
    try {
      const result = await events.peekMessages({ numberOfMessages: 32 });
      event = result.peekedMessageItems.map(item => JSON.parse(Buffer.from(item.messageText, 'base64').toString())).find(item => item.jobId === jobId);
    } catch (error) { if (error.statusCode !== 404) throw error; }
    if (!event) await delay(100);
  }
  assert.ok(event); assert.equal(event.eventId, `${jobId}:succeeded`); assert.equal(event.metadata.revision, 'e2e-1');
  assert.equal(event.result.sha256, digest(wav)); assert.equal(await source.exists(), true);
  console.log(JSON.stringify({ synchronousWav: true, asynchronousWav: true, directQueueV2: true,
    expectedETag: true, resultNotification: true, sha256Match: true, bytes: wav.length }));
} finally {
  await sourceContainer.deleteIfExists().catch(() => {});
  for (const id of ids) {
    for await (const blob of control.listBlobsFlat({ prefix: `${id}/` })) await control.getBlockBlobClient(blob.name).deleteIfExists();
    await control.getBlockBlobClient(`_maintenance/jobs/${id}`).deleteIfExists();
  }
}
