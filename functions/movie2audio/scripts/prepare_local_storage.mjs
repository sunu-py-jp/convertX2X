import { BlobServiceClient } from '@azure/storage-blob';
import { QueueServiceClient } from '@azure/storage-queue';

const connection = 'UseDevelopmentStorage=true';
const options = { retryOptions: { maxTries: 2, tryTimeoutInMs: 3000 } };

try {
  const blobs = BlobServiceClient.fromConnectionString(connection, options);
  const queues = QueueServiceClient.fromConnectionString(connection, options);
  await blobs.getContainerClient('movie2audio-jobs').createIfNotExists();
  await Promise.all([
    queues.getQueueClient('movie2audio-jobs').createIfNotExists(),
    queues.getQueueClient('movie2audio-jobs-poison').createIfNotExists(),
  ]);
} catch {
  process.stderr.write('Azurite resources could not be prepared. Check ports 10000 and 10001.\n');
  process.exitCode = 1;
}
