#!/usr/bin/env node
import { readFile, stat } from 'node:fs/promises';
import { BlobServiceClient } from '@azure/storage-blob';
import { QueueServiceClient } from '@azure/storage-queue';
import { parseJobRequest, readJobSettings, QUEUE_NAME } from '../src/job-request.js';

try {
  const [source, requestPath, ...extra] = process.argv.slice(2);
  if (!source || !requestPath || extra.length) throw new Error('usage');
  const settings = readJobSettings();
  if (!settings) throw new Error('settings');
  const request = parseJobRequest(await readFile(requestPath));
  if (source !== '--queue-only') {
    const connection = settings.inputConnections.get(request.input.storage);
    if (!connection) throw new Error('alias');
    const info = await stat(source);
    if (!info.isFile() || !info.size) throw new Error('file');
    const container = BlobServiceClient.fromConnectionString(connection).getContainerClient(request.input.container);
    await container.createIfNotExists();
    await container.getBlockBlobClient(request.input.blobName).uploadFile(source, {
      blockSize: 4 * 1024 * 1024, concurrency: 1, maxSingleShotSize: 4 * 1024 * 1024,
      conditions: { ifNoneMatch: '*' }, blobHTTPHeaders: { blobContentType: 'application/octet-stream' },
    });
  }
  const queue = QueueServiceClient.fromConnectionString(settings.connectionString).getQueueClient(QUEUE_NAME);
  await queue.createIfNotExists();
  // The Functions host decodes this single Base64 layer before passing raw UTF-8 bytes.
  await queue.sendMessage(Buffer.from(JSON.stringify(request), 'utf8').toString('base64'));
  console.log(JSON.stringify({ jobId: request.jobId }));
} catch {
  console.error('Submission failed. Use: node examples/QueueProducer.mjs <video-file|--queue-only> <request.json>. Check the registered storage settings and unique input path.');
  process.exitCode = 1;
}
