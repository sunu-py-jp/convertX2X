#!/usr/bin/env node
// Run an actual Queue-triggered conversion against local Functions + Azurite.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash, randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
const require = createRequire(new URL('../../../../functions/movie2audio/package.json', import.meta.url));
const { BlobServiceClient } = require('@azure/storage-blob');
const { QueueServiceClient } = require('@azure/storage-queue');
const blobs = BlobServiceClient.fromConnectionString('UseDevelopmentStorage=true');
const queues = QueueServiceClient.fromConnectionString('UseDevelopmentStorage=true');
const jobId = randomUUID();
const tag = jobId.slice(0, 8);
const input = blobs.getContainerClient(`movie-docs-input-${tag}`);
const output = blobs.getContainerClient(`movie-docs-output-${tag}`);
const queue = queues.getQueueClient('movie2audio-jobs');
const control = blobs.getContainerClient('movie2audio-jobs');
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const request = { version: 1, jobId, input: { storage: 'default', container: input.containerName, blobName: 'complex-input.mp4' }, output: { storage: 'default', container: output.containerName, prefix: 'exports' }, filename: 'complex-input.mp4' };
await input.create(); await output.create(); await queue.createIfNotExists();
try {
  const source = await readFile(new URL('complex-input.mp4', import.meta.url));
  await input.getBlockBlobClient('complex-input.mp4').uploadData(source, { conditions: { ifNoneMatch: '*' } });
  const started = performance.now();
  await queue.sendMessage(Buffer.from(JSON.stringify(request)).toString('base64'));
  let status;
  while (performance.now() - started < 120000) {
    const response = await fetch(`http://localhost:7073/api/jobs/${jobId}`);
    if (response.status === 200) {
      status = await response.json();
      if (['succeeded', 'failed'].includes(status.job.status)) break;
    }
    await delay(500);
  }
  assert.equal(status?.job.status, 'succeeded');
  const saved = JSON.parse((await control.getBlockBlobClient(`${jobId}/status.json`).downloadToBuffer()).toString());
  const outputBytes = await output.getBlockBlobClient(saved.result.blobName).downloadToBuffer();
  const expected = await readFile(new URL('complex-audio.m4a', import.meta.url));
  assert.equal(hash(outputBytes), hash(expected));
  const downloaded = await fetch(`http://localhost:7073/api/jobs/${jobId}/result`);
  assert.equal(downloaded.status, 200);
  assert.equal(hash(Buffer.from(await downloaded.arrayBuffer())), hash(expected));
  await writeFile(new URL('queue-audio.m4a', import.meta.url), outputBytes);
  await writeFile(new URL('queue-request.json', import.meta.url), JSON.stringify(request, null, 2) + '\n');
  const evidence = { capturedAt: new Date().toISOString(), environment: 'local Functions Core Tools + Azurite; not Azure Storage', request, transport: 'Azure Storage Queue SDK; JSON encoded as Base64 once', status, storedResult: saved.result, elapsedMsIncludingQueueAndTransfer: Math.round(performance.now() - started), output: { file: 'queue-audio.m4a', sizeBytes: outputBytes.length, sha256: hash(outputBytes) }, verification: { sameBytesAsSync: true, blobAndHttpDownloadMatch: true } };
  await writeFile(new URL('queue-run.json', import.meta.url), JSON.stringify(evidence, null, 2) + '\n');
  console.log(JSON.stringify(evidence, null, 2));
} finally {
  for await (const item of control.listBlobsFlat({ prefix: `${jobId}/` })) await control.getBlobClient(item.name).deleteIfExists();
  await input.deleteIfExists(); await output.deleteIfExists();
}
