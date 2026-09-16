import { BlobServiceClient } from '@azure/storage-blob';
import { QueueServiceClient } from '@azure/storage-queue';
import { randomUUID } from 'node:crypto';
import { constants } from 'node:fs';
import { open, unlink } from 'node:fs/promises';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { ConversionError, throwIfAborted } from './errors.js';
import { CONTAINER_NAME, QUEUE_NAME, normalizeJobId, normalizeJobRequest } from './job-request.js';
export { readJobSettings } from './job-request.js';

const BLOCK_SIZE = 4 * 1024 * 1024;
const STATUS_LIMIT = 128 * 1024;
const SDK_OPTIONS = { retryOptions: { maxTries: 2, tryTimeoutInMs: 30_000 }, keepAliveOptions: { enable: true } };
const failure = () => new ConversionError(503, 'JOB_STORAGE_ERROR', 'The configured job storage operation failed.');
const invalidState = () => new ConversionError(500, 'INVALID_JOB_STATE', 'The stored job metadata is invalid.');
const leaseLost = () => new ConversionError(503, 'JOB_LEASE_LOST', 'The job processing lease was lost.');
const resultChanged = () => new ConversionError(409, 'RESULT_CHANGED', 'The stored audio result has changed.');
const codeIs = (error, code) => error?.code === code || error?.details?.errorCode === code;
const missing = error => error?.statusCode === 404;
const exists = error => error?.statusCode === 412 || (error?.statusCode === 409 && codeIs(error, 'BlobAlreadyExists'));
const operationSignal = (signal, milliseconds = 30_000) => signal
  ? AbortSignal.any([signal, AbortSignal.timeout(milliseconds)]) : AbortSignal.timeout(milliseconds);

function checkSize(size, maximum, input) {
  if (!Number.isSafeInteger(size) || size < 0) throw failure();
  if (size > maximum) throw new ConversionError(413, input ? 'INPUT_TOO_LARGE' : 'OUTPUT_TOO_LARGE',
    input ? 'The video exceeds the input size limit.' : 'The audio exceeds the output size limit.');
  if (input && size === 0) throw new ConversionError(400, 'EMPTY_INPUT', 'A nonempty video file is required.');
}

function recordId(record) {
  try {
    const id = normalizeJobId(record?.job?.id);
    if (id !== normalizeJobId(record?.request?.jobId)) throw invalidState();
    return id;
  } catch { throw invalidState(); }
}

// Persist only the known private record fields; never serialize SDK clients,
// settings, errors, or arbitrary properties supplied by an internal caller.
function normalizedRecord(record) {
  const id = recordId(record);
  const job = record.job;
  const request = normalizeJobRequest(record.request);
  if (!['queued', 'running', 'succeeded', 'failed'].includes(job.status)
    || typeof job.filename !== 'string' || job.filename !== request.filename
    || !['createdAt', 'updatedAt'].every(key => typeof job[key] === 'string' && Number.isFinite(Date.parse(job[key])))
    || (job.sizeBytes != null && (!Number.isSafeInteger(job.sizeBytes) || job.sizeBytes < 0))
    || (job.errorCode != null && (typeof job.errorCode !== 'string' || !/^[A-Z_]{1,80}$/.test(job.errorCode)))
    || (job.errorMessage != null && (typeof job.errorMessage !== 'string' || job.errorMessage.length > 512))) throw invalidState();
  const result = record.result == null ? null : normalizedResult(record.result);
  if (job.status === 'succeeded' && (!result || job.sizeBytes !== result.sizeBytes)) throw invalidState();
  if (result) {
    const root = `${request.output.prefix ? `${request.output.prefix}/` : ''}${id}/results/`;
    const suffix = result.blobName.slice(root.length);
    if (result.storage !== request.output.storage || result.container !== request.output.container
      || !result.blobName.startsWith(root) || !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\/audio\.m4a$/.test(suffix)
      || job.status !== 'succeeded') throw invalidState();
  }
  return {
    job: { id, status: job.status, filename: job.filename, createdAt: job.createdAt, updatedAt: job.updatedAt,
      sizeBytes: job.sizeBytes ?? null, errorCode: job.errorCode ?? null, errorMessage: job.errorMessage ?? null },
    request, result
  };
}

function serialize(record) {
  let bytes;
  try { bytes = Buffer.from(JSON.stringify(normalizedRecord(record))); } catch { throw invalidState(); }
  if (bytes.length > STATUS_LIMIT) throw invalidState();
  return bytes;
}

function normalizedResult(result) {
  try {
    const request = normalizeJobRequest({ version: 1, jobId: '00000000-0000-0000-0000-000000000000',
      input: { storage: result.storage, container: result.container, blobName: result.blobName },
      output: { container: result.container }, filename: 'audio.m4a' });
    if (!Number.isSafeInteger(result.sizeBytes) || result.sizeBytes < 1 || typeof result.etag !== 'string'
      || !/^"[A-Za-z0-9._:-]{1,128}"$/.test(result.etag)
      || result.contentType !== 'audio/mp4' || result.filename !== 'audio.m4a') throw invalidState();
    return { storage: request.input.storage, container: request.input.container, blobName: request.input.blobName,
      contentType: 'audio/mp4', filename: 'audio.m4a', sizeBytes: result.sizeBytes, etag: result.etag };
  } catch { throw invalidState(); }
}

export function createJobStore(settings, { limits, blobServiceFactory = connection => BlobServiceClient.fromConnectionString(connection, SDK_OPTIONS),
  queueFactory = connection => QueueServiceClient.fromConnectionString(connection, SDK_OPTIONS).getQueueClient(QUEUE_NAME),
  leaseRenewMs = 20_000 } = {}) {
  if (!settings?.connectionString || !(settings.inputConnections instanceof Map) || !(settings.outputConnections instanceof Map)
    || !Number.isSafeInteger(limits?.maxInputBytes) || !Number.isSafeInteger(limits?.maxOutputBytes)) {
    throw new Error('The configured job storage settings are invalid.');
  }
  const inputClients = new Map();
  const outputClients = new Map();
  const configured = connection => {
    try { return blobServiceFactory(connection); } catch { throw new Error('A configured blob storage connection is invalid.'); }
  };
  const control = configured(settings.connectionString).getContainerClient(CONTAINER_NAME);
  let queue;
  try { queue = queueFactory(settings.connectionString); } catch { throw new Error('The configured queue connection is invalid.'); }
  let initialization;
  const initialize = async signal => {
    throwIfAborted(signal);
    initialization ??= (async () => {
      await control.createIfNotExists({ abortSignal: operationSignal(signal) });
      await queue.createIfNotExists({ abortSignal: operationSignal(signal) });
    })().catch(error => { initialization = undefined; throw error; });
    await initialization;
    throwIfAborted(signal);
  };
  const resolve = (role, alias) => {
    const connections = role === 'input' ? settings.inputConnections : settings.outputConnections;
    const connection = connections.get(alias);
    if (!connection) throw new ConversionError(400, role === 'input' ? 'UNKNOWN_INPUT_STORAGE' : 'UNKNOWN_OUTPUT_STORAGE',
      'The storage alias is not registered for this operation.');
    const clients = role === 'input' ? inputClients : outputClients;
    if (!clients.has(alias)) clients.set(alias, configured(connection));
    return clients.get(alias);
  };
  const statusBlob = id => control.getBlockBlobClient(`${normalizeJobId(id)}/status.json`);
  const validateLocations = request => {
    const normalized = normalizeJobRequest(request);
    resolve('input', normalized.input.storage);
    resolve('output', normalized.output.storage);
    return normalized;
  };
  const guarded = async (signal, action) => {
    try { throwIfAborted(signal); const result = await action(); throwIfAborted(signal); return result; }
    catch (error) { throwIfAborted(signal); if (error instanceof ConversionError) throw error; throw failure(); }
  };

  async function download(blob, { maximum, input = false, signal, expectedEtag, expectedSize, memory = false, path }) {
    let file;
    let created = false;
    let success = false;
    let stream;
    let downloadSignal;
    let onAbort;
    try {
      const properties = await blob.getProperties({ abortSignal: operationSignal(signal) });
      const size = properties.contentLength;
      checkSize(size, maximum, input);
      if (typeof properties.etag !== 'string' || !properties.etag) throw failure();
      if ((expectedEtag && properties.etag !== expectedEtag) || (expectedSize != null && size !== expectedSize)) throw resultChanged();
      // The ETag is part of the GET condition, so replacement between HEAD and
      // GET cannot bypass the size limit or alter a committed job result.
      downloadSignal = operationSignal(signal);
      const response = await blob.download(0, size, { abortSignal: downloadSignal,
        conditions: { ifMatch: properties.etag }, maxRetryRequests: 0 });
      stream = response.readableStreamBody;
      if (stream) {
        stream.on('error', () => {});
        onAbort = () => stream.destroy(downloadSignal.reason);
        downloadSignal.addEventListener('abort', onAbort, { once: true });
        if (downloadSignal.aborted) onAbort();
      }
      if (!stream || response.contentLength !== size || response.etag !== properties.etag) throw failure();
      if (!memory) { file = await open(path, 'wx', 0o600); created = true; }
      let count = 0;
      const chunks = [];
      for await (const chunk of stream) {
        throwIfAborted(signal);
        count += chunk.length;
        if (count > size || count > maximum) throw failure();
        if (memory) chunks.push(Buffer.from(chunk));
        else for (let offset = 0; offset < chunk.length;) {
          throwIfAborted(signal);
          const { bytesWritten } = await file.write(chunk, offset, Math.min(64 * 1024, chunk.length - offset));
          if (!bytesWritten) throw failure();
          offset += bytesWritten;
        }
      }
      if (count !== size) throw failure();
      await file?.close(); file = undefined;
      throwIfAborted(signal);
      success = true;
      return memory ? Buffer.concat(chunks, count) : undefined;
    } catch (error) {
      throwIfAborted(signal);
      if (expectedEtag && error?.statusCode === 412) throw resultChanged();
      throw error;
    } finally {
      if (onAbort) downloadSignal.removeEventListener('abort', onAbort);
      stream?.destroy();
      await file?.close().catch(() => {});
      if (created && !success) await unlink(path).catch(() => {});
    }
  }

  async function upload(blob, path, contentType, maximum, input, signal) {
    let file;
    let stream;
    let transfer;
    let limiter;
    let uploading;
    const controller = new AbortController();
    try {
      file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
      const properties = await file.stat();
      if (!properties.isFile()) throw failure();
      checkSize(properties.size, maximum, input);
      if (properties.size === 0) throw failure();
      const size = properties.size;
      let count = 0;
      stream = file.createReadStream({ autoClose: false, highWaterMark: 64 * 1024 });
      limiter = new Transform({ transform(chunk, encoding, callback) {
        count += chunk.length;
        callback(count > size ? failure() : null, chunk);
      }, flush(callback) { callback(count !== size ? failure() : null); } });
      const scoped = AbortSignal.any([controller.signal, operationSignal(signal, Math.max(30_000, (limits.timeoutSeconds ?? 180) * 1000))]);
      transfer = pipeline(stream, limiter, { signal: scoped });
      // uploadStream buffers at most one 4 MiB block per transfer. Only the
      // final block-list commit makes a complete, non-overwriting blob visible.
      uploading = blob.uploadStream(limiter, BLOCK_SIZE, 1, { abortSignal: scoped,
        conditions: { ifNoneMatch: '*' }, blobHTTPHeaders: { blobContentType: contentType } });
      const [response] = await Promise.all([uploading, transfer]);
      if (count !== size || typeof response.etag !== 'string') throw failure();
      return { sizeBytes: size, etag: response.etag };
    } catch (error) {
      throwIfAborted(signal);
      if (exists(error)) throw new ConversionError(409, 'JOB_BLOB_EXISTS', 'The destination blob already exists.');
      throw error;
    } finally {
      controller.abort();
      limiter?.destroy();
      stream?.destroy();
      await uploading?.catch(() => {});
      await transfer?.catch(() => {});
      await file?.close().catch(() => {});
    }
  }

  const find = (id, { signal } = {}) => guarded(signal, async () => {
    try {
      const bytes = await download(statusBlob(id), { maximum: STATUS_LIMIT, signal, memory: true });
      let record;
      try { record = normalizedRecord(JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes))); }
      catch { throw invalidState(); }
      if (record.job.id !== normalizeJobId(id)) throw invalidState();
      return record;
    } catch (error) { if (missing(error)) return null; throw error; }
  });

  return {
    validateLocations,
    ensure: (record, { signal } = {}) => guarded(signal, async () => {
      const data = serialize(record);
      await initialize(signal);
      try {
        await statusBlob(recordId(record)).upload(data, data.length, { abortSignal: operationSignal(signal),
          conditions: { ifNoneMatch: '*' }, blobHTTPHeaders: { blobContentType: 'application/json; charset=utf-8' } });
      } catch (error) { if (!exists(error)) throw error; }
      const persisted = await find(recordId(record), { signal });
      if (!persisted) throw failure();
      return persisted;
    }),
    find,
    saveInput: (request, path, { signal } = {}) => guarded(signal, async () => {
      request = validateLocations(request);
      await initialize(signal);
      const container = resolve('input', request.input.storage).getContainerClient(request.input.container);
      await container.createIfNotExists({ abortSignal: operationSignal(signal) });
      await upload(container.getBlockBlobClient(request.input.blobName), path, 'application/octet-stream', limits.maxInputBytes, true, signal);
    }),
    enqueue: (request, { signal } = {}) => guarded(signal, async () => {
      request = validateLocations(request);
      await initialize(signal);
      await queue.sendMessage(Buffer.from(JSON.stringify(request), 'utf8').toString('base64'), { abortSignal: operationSignal(signal) });
    }),
    readInput: (source, path, { signal } = {}) => guarded(signal, async () => {
      // Apply the same path and alias schema even when called without a full request.
      source = normalizeJobRequest({ version: 1, jobId: '00000000-0000-0000-0000-000000000000', input: source,
        output: { container: CONTAINER_NAME }, filename: 'video' }).input;
      const blob = resolve('input', source.storage).getContainerClient(source.container).getBlockBlobClient(source.blobName);
      try { await download(blob, { maximum: limits.maxInputBytes, input: true, signal, path }); }
      catch (error) {
        if (missing(error)) throw new ConversionError(422, 'INPUT_NOT_FOUND', 'The specified input blob does not exist.');
        throw error;
      }
    }),
    writeResult: (request, path, { signal } = {}) => guarded(signal, async () => {
      request = validateLocations(request);
      const container = resolve('output', request.output.storage).getContainerClient(request.output.container);
      await container.createIfNotExists({ abortSignal: operationSignal(signal) });
      const root = `${request.output.prefix ? `${request.output.prefix}/` : ''}${request.jobId}/results/${randomUUID()}/`;
      const blobName = `${root}audio.m4a`;
      const uploaded = await upload(container.getBlockBlobClient(blobName), path, 'audio/mp4', limits.maxOutputBytes, false, signal);
      return normalizedResult({ storage: request.output.storage, container: request.output.container, blobName,
        contentType: 'audio/mp4', filename: 'audio.m4a', ...uploaded });
    }),
    readResult: (result, path, { signal } = {}) => guarded(signal, async () => {
      result = normalizedResult(result);
      const blob = resolve('output', result.storage).getContainerClient(result.container).getBlockBlobClient(result.blobName);
      try { await download(blob, { maximum: limits.maxOutputBytes, signal, path, expectedEtag: result.etag, expectedSize: result.sizeBytes }); }
      catch (error) {
        if (missing(error)) throw new ConversionError(404, 'RESULT_NOT_FOUND', 'The stored audio result was not found.');
        throw error;
      }
    }),
    lock: (id, { signal: outerSignal } = {}) => guarded(outerSignal, async () => {
      const normalizedId = normalizeJobId(id);
      const blob = statusBlob(normalizedId);
      const lease = blob.getBlobLeaseClient(randomUUID());
      const controller = new AbortController();
      const onAbort = () => controller.abort(outerSignal.reason);
      outerSignal?.addEventListener('abort', onAbort, { once: true });
      if (outerSignal?.aborted) onAbort();
      try { await lease.acquireLease(60, { abortSignal: operationSignal(controller.signal) }); }
      catch (error) {
        outerSignal?.removeEventListener('abort', onAbort);
        if (error?.statusCode === 409 || error?.statusCode === 412) {
          throw new ConversionError(409, 'JOB_BUSY', 'This job is already being processed.');
        }
        throw error;
      }
      let closed = false;
      let timer;
      let renewal;
      const schedule = () => {
        timer = setTimeout(() => {
          if (closed || controller.signal.aborted) return;
          renewal = lease.renewLease({ abortSignal: operationSignal(controller.signal, 10_000) })
            .then(() => { if (!closed) schedule(); })
            .catch(() => { if (!closed) controller.abort(leaseLost()); });
        }, leaseRenewMs);
        timer.unref?.();
      };
      schedule();
      return {
        signal: controller.signal,
        update: record => guarded(controller.signal, async () => {
          if (closed || recordId(record) !== normalizedId) throw leaseLost();
          const data = serialize(record);
          try { await blob.upload(data, data.length, { abortSignal: operationSignal(controller.signal),
            conditions: { leaseId: lease.leaseId }, blobHTTPHeaders: { blobContentType: 'application/json; charset=utf-8' } }); }
          catch (error) {
            if (error?.statusCode === 409 || error?.statusCode === 412) { controller.abort(leaseLost()); throw controller.signal.reason; }
            throw error;
          }
        }),
        close: async () => {
          if (closed) return;
          closed = true;
          clearTimeout(timer);
          outerSignal?.removeEventListener('abort', onAbort);
          controller.abort(leaseLost());
          await renewal?.catch(() => {});
          try { await lease.releaseLease({ abortSignal: AbortSignal.timeout(5_000) }); } catch { /* The finite lease expires independently. */ }
        }
      };
    })
  };
}
