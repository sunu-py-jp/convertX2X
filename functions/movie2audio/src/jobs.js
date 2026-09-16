import { randomUUID } from 'node:crypto';
import { chmod, lstat, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ConversionError, throwIfAborted } from './errors.js';
import { extractAudio } from './ffmpeg.js';
import { audioOutput, normalizeAudioOptions } from './audio-options.js';
import { CONTAINER_NAME, normalizeJobId, normalizeJobRequest, parseJobRequest, safeFilename } from './job-request.js';
import { createJobStore } from './job-store.js';

// Persist and surface fixed messages only: SDK errors and media metadata may contain
// credentials, private storage locations or attacker-controlled text.
const FAILURES = Object.freeze({
  ASYNC_DISABLED: [503, 'Asynchronous conversion is disabled; configure job storage with a connection string or identity endpoints.'],
  INVALID_JOB_ID: [400, 'The job ID must be a UUID.'],
  INVALID_JOB_REQUEST: [400, 'The queue request is invalid.'],
  INVALID_QUEUE_MESSAGE: [400, 'Send a version 1 or 2 job with registered storage aliases and blob paths only.'],
  INVALID_FILENAME: [400, 'The filename is invalid or too long.'],
  MISSING_FILENAME: [400, 'A filename is required.'],
  JOB_NOT_FOUND: [404, 'Job not found.'],
  JOB_NOT_READY: [409, 'The job has not completed successfully.'],
  JOB_ID_CONFLICT: [409, 'The job ID is already assigned to a different request.'],
  JOB_LOCKED: [503, 'Another worker is processing this job; retry later.'],
  JOB_BUSY: [503, 'Another worker is processing this job; retry later.'],
  JOB_LEASE_LOST: [503, 'The job lock was lost; retry the operation.'],
  CONVERSION_BUSY: [503, 'Another conversion is running; retry later.'],
  CONVERSION_TIMEOUT: [504, 'Audio extraction and storage exceeded the processing time limit.'],
  CONVERSION_INTERRUPTED: [503, 'Audio extraction was interrupted.'],
  EMPTY_INPUT: [400, 'A video file is required.'],
  INPUT_VERSION_MISMATCH: [409, 'The input no longer matches the requested version.'],
  INVALID_AUDIO_OPTIONS: [400, 'The audio conversion settings are invalid.'],
  UNKNOWN_RESULT_QUEUE: [400, 'The result queue alias is not registered.'],
  JOB_RESULT_EXPIRED: [410, 'The result retention period has expired.'],
  INPUT_NOT_FOUND: [422, 'The input blob was not found.'],
  UNKNOWN_INPUT_STORAGE: [400, 'The input storage alias is not registered.'],
  UNKNOWN_OUTPUT_STORAGE: [400, 'The output storage alias is not registered.'],
  INPUT_TOO_LARGE: [413, 'The video exceeds the configured input limit.'],
  OUTPUT_TOO_LARGE: [413, 'The extracted audio exceeds the configured output limit.'],
  INVALID_MEDIA: [422, 'The video is unsupported, empty, damaged or could not be extracted.'],
  NO_VIDEO_STREAM: [422, 'The input must contain a video stream; cover artwork does not count as video.'],
  NO_AUDIO_STREAM: [422, 'The video does not contain an audio stream.'],
  UNSUPPORTED_AUDIO_CODEC: [422, 'Copy mode requires AAC; transcode mode supports the documented AAC, Opus, MP3 and PCM codecs.'],
  EMPTY_OUTPUT: [422, 'Audio extraction produced an empty result.'],
  OUTPUT_MISSING: [500, 'Audio extraction produced no result.'],
  FFMPEG_UNAVAILABLE: [503, 'The bundled FFmpeg runtime could not be prepared or started.'],
  STORAGE_ERROR: [500, 'The temporary media file could not be inspected or written.'],
  STORAGE_UNAVAILABLE: [503, 'Storage is temporarily unavailable; retry the operation.'],
  JOB_STORAGE_ERROR: [503, 'Job storage is temporarily unavailable; retry the operation.'],
  INVALID_JOB_STATE: [500, 'The stored job state is invalid.'],
  RESULT_CHANGED: [409, 'The stored audio has changed since the job completed.'],
  RESULT_NOT_FOUND: [404, 'The stored audio was not found.'],
  JOB_BLOB_EXISTS: [409, 'A blob already exists at the requested storage location.'],
  SUBMISSION_FAILED: [503, 'The job submission could not be confirmed.'],
  SUBMISSION_EXPIRED: [503, 'The job submission did not complete within its retention period.'],
  PROCESSING_FAILED: [503, 'Conversion failed after repeated processing attempts.'],
});

function error(code) {
  const [status, message] = FAILURES[code];
  return new ConversionError(status, code, message);
}

function safeFailure(failure) {
  if (failure instanceof ConversionError && Object.hasOwn(FAILURES, failure.code)) return error(failure.code);
  // An unknown permanent failure must not become a retryable one. Never retain its
  // message or arbitrary code, even if it was wrapped in ConversionError upstream.
  if (failure instanceof ConversionError && failure.status >= 400 && failure.status < 500) {
    return new ConversionError(failure.status, 'PROCESSING_FAILED', FAILURES.PROCESSING_FAILED[1]);
  }
  return error('STORAGE_UNAVAILABLE');
}

function terminal(job) { return job.status === 'succeeded' || job.status === 'failed'; }

function publicJob(job) {
  const errorCode = job.errorCode ? (Object.hasOwn(FAILURES, job.errorCode) ? job.errorCode : 'PROCESSING_FAILED') : null;
  return { id: job.id, status: job.status, filename: job.filename, createdAt: job.createdAt,
    updatedAt: job.updatedAt, sizeBytes: job.sizeBytes ?? null, errorCode,
    errorMessage: errorCode ? FAILURES[errorCode][1] : null };
}

function queued(request) {
  const now = new Date().toISOString();
  return { job: { id: request.jobId, status: 'queued', filename: request.filename,
    createdAt: now, updatedAt: now, sizeBytes: null, errorCode: null, errorMessage: null }, request, result: null, artifactTrackingVersion: 1 };
}

function transition(record, status, result = null, failure = null) {
  return { ...record, ...(record.submissionPending !== undefined ? { submissionPending: false } : {}), job: { ...publicJob(record.job), status, updatedAt: new Date().toISOString(),
    sizeBytes: result?.sizeBytes ?? null, errorCode: failure?.code ?? null, errorMessage: failure?.message ?? null },
  request: record.request, result,
    ...(record.request.notification && ['succeeded', 'failed'].includes(status)
      ? { outbox: { eventId: `${record.request.jobId}:${status}`, state: 'pending' } } : {}) };
}

function sameRequest(record, request) {
  // Canonical normalization also handles JSON object key ordering and omitted defaults.
  try { return JSON.stringify(normalizeJobRequest(record.request)) === JSON.stringify(request); }
  catch { return false; }
}

async function fileSize(path, maximum, isInput) {
  let info;
  try { info = await lstat(path); } catch { throw error(isInput ? 'STORAGE_ERROR' : 'OUTPUT_MISSING'); }
  if (!info.isFile()) throw error(isInput ? 'STORAGE_ERROR' : 'OUTPUT_MISSING');
  if (info.size === 0) throw error(isInput ? 'EMPTY_INPUT' : 'EMPTY_OUTPUT');
  if (info.size > maximum) throw error(isInput ? 'INPUT_TOO_LARGE' : 'OUTPUT_TOO_LARGE');
  return info.size;
}

/** Shared conversion implementation for HTTP-submitted and directly queued Blob jobs. */
export function createJobService(config, { store, extract = extractAudio, temporaryRoot = tmpdir(), admission } = {}) {
  if (!store && config.asyncEnabled) store = createJobStore(config.jobs, { limits: config.limits });

  function requireEnabled() {
    if (!config.asyncEnabled || !store) throw error('ASYNC_DISABLED');
  }

  async function requiredRecord(id, options) {
    const record = await store.find(id, options);
    if (!record) throw error('JOB_NOT_FOUND');
    return record;
  }

  async function submit(path, filename, { signal: outerSignal, options } = {}) {
    let lock;
    let record;
    let signal = outerSignal;
    try {
      requireEnabled();
      throwIfAborted(signal);
      const name = safeFilename(filename);
      await fileSize(path, config.limits.maxInputBytes, true);
      const id = randomUUID();
      const audio = normalizeAudioOptions(options);
      const version = audio.mode === 'copy' ? 1 : 2;
      const request = normalizeJobRequest({ version, jobId: id,
        input: { storage: 'default', container: CONTAINER_NAME, blobName: `${id}/input` },
        output: { storage: 'default', container: CONTAINER_NAME, prefix: '' }, filename: name, ...(version === 2 ? { options: audio } : {}) });
      record = { ...queued(request), submissionPending: true };
      await store.validateLocations(request);
      const existing = await store.ensure(record, { signal });
      if (!sameRequest(existing, request)) throw error('JOB_ID_CONFLICT');
      lock = await store.lock(id, { signal });
      signal = outerSignal ? AbortSignal.any([outerSignal, lock.signal]) : lock.signal;
      await store.saveInput(request, path, { signal });
      throwIfAborted(signal);
      // The status lease covers input upload and publication. A worker can start
      // after release; a crash before enqueue leaves an expirable pending submission.
      await store.enqueue(request, { signal });
      throwIfAborted(signal);
      record = { ...record, submissionPending: false };
      await lock.update(record);
      return publicJob(record.job);
    } catch (failure) {
      if (lock && record) {
        // If the lease/deadline was lost this write is rejected, and the durable
        // submission marker lets maintenance handle the abandoned state later.
        await lock.update(transition(record, 'failed', null, error('SUBMISSION_FAILED'))).catch(() => {});
      }
      throw safeFailure(outerSignal?.aborted ? outerSignal.reason : failure);
    } finally { await lock?.close().catch(() => {}); }
  }

  async function find(id, { signal } = {}) {
    try {
      requireEnabled();
      throwIfAborted(signal);
      const record = await store.find(normalizeJobId(id), { signal });
      throwIfAborted(signal);
      return record ? { ...publicJob(record.job), ...(record.request.version === 2 ? {
        metadata: record.request.metadata ?? {}, input: record.source ?? null, result: record.result,
        ...(record.resultExpiredAt ? { resultExpiredAt: record.resultExpiredAt } : {}) } : {}) } : null;
    } catch (failure) { throw safeFailure(signal?.aborted ? signal.reason : failure); }
  }

  async function download(id, path, { signal } = {}) {
    let ownsOutput = false;
    try {
      requireEnabled();
      throwIfAborted(signal);
      const record = await requiredRecord(normalizeJobId(id), { signal });
      if (record.job.status !== 'succeeded') throw error('JOB_NOT_READY');
      if (!record.result) throw error('OUTPUT_MISSING');
      if (record.resultExpiredAt) throw error('JOB_RESULT_EXPIRED');
      await store.readResult(record.result, path, { signal });
      ownsOutput = true;
      throwIfAborted(signal);
      const sizeBytes = await fileSize(path, config.limits.maxOutputBytes, false);
      if (sizeBytes !== record.result.sizeBytes) throw error('STORAGE_UNAVAILABLE');
      await chmod(path, 0o600);
      return { filename: record.result.filename, contentType: record.result.contentType, sizeBytes,
        ...(record.result.codec ? { codec: record.result.codec, mode: record.result.mode } : {}) };
    } catch (failure) {
      if (ownsOutput) await rm(path, { force: true }).catch(() => {});
      throw safeFailure(signal?.aborted ? signal.reason : failure);
    }
  }

  async function run(message, isPoison) {
    requireEnabled();
    let request;
    try { request = parseJobRequest(message); }
    catch (failure) {
      // A malformed poison message has no trustworthy job ID to address.
      if (isPoison) return;
      throw safeFailure(failure);
    }
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(error('CONVERSION_TIMEOUT')), config.limits.timeoutSeconds * 1000);
    timer.unref();
    let signal = controller.signal;
    let lock;
    let directory;
    let release;
    try {
      const existing = await store.ensure(queued(request), { signal });
      if (!sameRequest(existing, request)) {
        if (isPoison) return;
        throw error('JOB_ID_CONFLICT');
      }
      lock = await store.lock(request.jobId, { signal });
      signal = AbortSignal.any([signal, lock.signal]);
      throwIfAborted(signal);
      const record = await requiredRecord(request.jobId, { signal });
      if (!sameRequest(record, request)) {
        if (isPoison) return;
        throw error('JOB_ID_CONFLICT');
      }
      if (terminal(record.job)) {
        await store.deliver?.(record, lock, { signal }).catch(() => {});
        return;
      }
      if (isPoison) {
        throwIfAborted(signal);
        const failed = transition(record, 'failed', null, error('PROCESSING_FAILED'));
        await lock.update(failed);
        await store.deliver?.(failed, lock, { signal }).catch(() => {});
        return;
      }
      release = admission?.acquire();
      let running = transition(record, 'running');
      throwIfAborted(signal);
      await lock.update(running);
      try {
        await store.validateLocations(request);
        throwIfAborted(signal);
        directory = await mkdtemp(join(temporaryRoot, 'movie2audio-job-'));
        await chmod(directory, 0o700);
        const input = join(directory, 'input.bin');
        const output = join(directory, audioOutput(request.options).filename);
        const source = await store.readInput(request.input, input, { signal });
        if (source) { running = { ...running, source }; await lock.update(running); }
        await fileSize(input, config.limits.maxInputBytes, true);
        await chmod(input, 0o600);
        throwIfAborted(signal);
        await extract(input, output, { limits: config.limits, signal, options: request.options });
        throwIfAborted(signal);
        const bytes = await fileSize(output, config.limits.maxOutputBytes, false);
        await chmod(output, 0o600);
        const result = await store.writeResult(request, output, { signal });
        if (!result || result.sizeBytes !== bytes) throw error('STORAGE_UNAVAILABLE');
        // A lost lease or expired deadline may leave an unpublished artifact, but
        // must never publish success or overwrite another worker's state.
        throwIfAborted(signal);
        const completed = transition(running, 'succeeded', result);
        await lock.update(completed);
        // Delivery is independent of conversion. A durable pending outbox survives
        // send failures and is retried by the maintenance timer.
        await store.deliver?.(completed, lock, { signal }).catch(() => {});
      } catch (failure) {
        throwIfAborted(signal);
        const safe = safeFailure(failure);
        if (safe.status >= 500) throw safe;
        const failed = transition(running, 'failed', null, safe);
        await lock.update(failed);
        await store.deliver?.(failed, lock, { signal }).catch(() => {});
      }
    } catch (failure) {
      if (lock?.signal.aborted && !controller.signal.aborted) throw error('JOB_LEASE_LOST');
      throw safeFailure(controller.signal.aborted ? controller.signal.reason : failure);
    } finally {
      clearTimeout(timer);
      controller.abort();
      if (directory) await rm(directory, { recursive: true, force: true }).catch(() => {});
      try { await lock?.close(); } catch { /* The lease expires; no further state is published. */ }
      release?.();
    }
  }

  return { submit, find, download, maintenance: options => store.maintenance(options), process: message => run(message, false), poison: message => run(message, true) };
}
