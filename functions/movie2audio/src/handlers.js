import { createWriteStream } from 'node:fs';
import { lstat, mkdtemp, open, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Readable, Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { ConversionError, throwIfAborted } from './errors.js';
import { extractAudio } from './ffmpeg.js';
import { downloadVideo } from './url.js';
import { uploadAudio, validateBlobDestination } from './blob.js';
import { readStorageRequest } from './storage-request.js';
import { createAdmission } from './admission.js';
import { safeFilename } from './job-request.js';

const MAX_URL_BODY = 16384;
const COMMON_HEADERS = { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' };

function error(status, code, message) { return new ConversionError(status, code, message); }

export function jsonError(failure, context) {
  if (!(failure instanceof ConversionError)) {
    context?.error?.('Audio request failed; invocation=' + (context.invocationId ?? 'unknown'));
    failure = error(500, 'INTERNAL_ERROR', 'The request could not be completed.');
  }
  return { status: failure.status, headers: {
    ...COMMON_HEADERS, 'Content-Type': 'application/json; charset=utf-8',
    ...(failure.status === 503 ? { 'Retry-After': '3' } : {}),
  }, body: JSON.stringify({ error: { code: failure.code, message: failure.message } }) };
}

function declaredLength(request, maximum, code) {
  const text = request.headers.get('content-length');
  if (text === null) return;
  if (!/^\d+$/.test(text)) throw error(400, 'INVALID_CONTENT_LENGTH', 'Content-Length must be a nonnegative integer.');
  if (BigInt(text) > BigInt(maximum)) throw error(413, code, 'The request exceeds the configured input limit.');
}

export function parseUrlRequest(bytes) {
  // Exactly one string property: parsing each token also supports escaped JSON keys,
  // while rejecting duplicate keys before JSON.parse could discard them.
  const token = String.raw`"(?:[^"\\\u0000-\u001f]|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*"`;
  const pattern = new RegExp(String.raw`^\s*\{\s*(${token})\s*:\s*(${token})\s*\}\s*$`);
  try {
    const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    JSON.parse(text); // JSON permits only SP, TAB, CR and LF as structural whitespace.
    const match = pattern.exec(text);
    if (match && JSON.parse(match[1]) === 'url') {
      const url = JSON.parse(match[2]);
      if (url.trim()) return url;
    }
  } catch { /* Invalid encoding or JSON has the same public error. */ }
  throw error(400, 'INVALID_URL_REQUEST', 'Send exactly one nonempty string field named url.');
}

async function readSmallBody(request, signal) {
  declaredLength(request, MAX_URL_BODY, 'REQUEST_TOO_LARGE');
  if (!request.body) throw error(400, 'EMPTY_INPUT', 'A request body is required.');
  const source = Readable.fromWeb(request.body, { signal });
  const chunks = [];
  let size = 0;
  for await (const chunk of source) {
    size += chunk.length;
    if (size > MAX_URL_BODY) throw error(413, 'REQUEST_TOO_LARGE', 'The URL request exceeds 16 KiB.');
    chunks.push(chunk);
  }
  if (size === 0) throw error(400, 'EMPTY_INPUT', 'The request body is empty.');
  return Buffer.concat(chunks, size);
}

async function receiveVideo(request, path, maximum, signal) {
  declaredLength(request, maximum, 'INPUT_TOO_LARGE');
  if (!request.body) throw error(400, 'EMPTY_INPUT', 'A video file is required.');
  let size = 0;
  const limit = new Transform({ transform(chunk, encoding, callback) {
    size += chunk.length;
    callback(size > maximum ? error(413, 'INPUT_TOO_LARGE', 'The video exceeds the input size limit.') : null, chunk);
  } });
  await pipeline(Readable.fromWeb(request.body), limit,
    createWriteStream(path, { flags: 'wx', mode: 0o600 }), { signal });
  if (size === 0) throw error(400, 'EMPTY_INPUT', 'A video file is required.');
}

/** One slot includes the response stream's lifetime; each app worker has its own slot. */
export function createHandlers(config, { extract = extractAudio, download = downloadVideo, upload = uploadAudio,
  temporaryRoot = tmpdir(), admission = createAdmission(), jobs } = {}) {

  const requireAsync = () => {
    if (!config.asyncEnabled || !jobs) throw error(503, 'ASYNC_DISABLED',
      'Asynchronous conversion is disabled; configure CONVERSION_STORAGE_CONNECTION_STRING.');
  };

  function statusBody(request, job) {
    const path = new URL(request.url).pathname;
    const index = path.lastIndexOf('/jobs');
    const prefix = index >= 0 && !path.startsWith('//') ? path.slice(0, index) : '/api';
    const statusUrl = `${prefix}/jobs/${job.id}`;
    return { job, statusUrl, ...(job.status === 'succeeded' ? { resultUrl: `${statusUrl}/result` } : {}) };
  }

  async function convert(request, context, mode) {
    const fromUrl = mode === 'url' || mode === 'submitUrl';
    const storeOutput = mode === 'storage';
    const submit = mode === 'submit' || mode === 'submitUrl';
    const resultDownload = mode === 'result';
    if (submit || resultDownload) {
      try { requireAsync(); } catch (failure) { return jsonError(failure, context); }
    }
    const type = (request.headers.get('content-type') ?? '').split(';', 1)[0].trim().toLowerCase();
    if (storeOutput && type !== 'application/json' && type !== 'multipart/form-data') {
      return jsonError(error(415, 'STORAGE_REQUEST_TYPE_REQUIRED', 'Send application/json or multipart/form-data for Blob output.'), context);
    }
    if (!resultDownload && !storeOutput && (fromUrl ? type !== 'application/json' : type.startsWith('multipart/') || type === 'application/json')) {
      return jsonError(fromUrl
        ? error(415, 'JSON_BODY_REQUIRED', 'Send a JSON object with Content-Type application/json.')
        : error(415, 'RAW_BODY_REQUIRED', 'Send video bytes directly as the request body.'), context);
    }
    if (storeOutput && !config.outputStorageEnabled) {
      return jsonError(error(503, 'OUTPUT_STORAGE_DISABLED', 'Blob output is disabled; configure CONVERSION_OUTPUT_ALLOWED_HOSTS.'), context);
    }
    let release;
    try { release = admission.acquire(); } catch (failure) { return jsonError(failure, context); }
    const controller = new AbortController();
    const { signal } = controller;
    const timer = setTimeout(() => controller.abort(error(504, 'CONVERSION_TIMEOUT', 'The extraction and transfer deadline was exceeded.')),
      config.limits.timeoutSeconds * 1000);
    timer.unref();
    let directory;
    let cleanupPromise;
    const cleanup = () => cleanupPromise ??= (async () => {
      clearTimeout(timer);
      try { if (directory) await rm(directory, { recursive: true, force: true }); }
      catch { context?.warn?.('Temporary file cleanup failed; invocation=' + (context.invocationId ?? 'unknown')); }
      finally { release(); }
    })();
    try {
      let url;
      let sasUrl;
      const filename = submit ? safeFilename(new URL(request.url).searchParams.get('filename')
        ?? request.headers.get('x-file-name') ?? 'video.bin') : undefined;
      if (fromUrl) {
        url = parseUrlRequest(await readSmallBody(request, signal));
        if (!config.urlEnabled) throw error(503, 'URL_FETCH_DISABLED', 'URL input is disabled; configure CONVERSION_URL_ALLOWED_HOSTS.');
      }
      throwIfAborted(signal);
      directory = await mkdtemp(join(temporaryRoot, 'movie2audio-'));
      const input = join(directory, 'input.bin');
      const output = join(directory, 'audio.m4a');
      if (resultDownload) {
        await jobs.download(request.params?.id, output, { signal });
      } else {
        if (storeOutput) {
          const storage = await readStorageRequest(request, input, { limits: config.limits, signal });
          url = storage.inputUrl;
          sasUrl = storage.sasUrl;
          validateBlobDestination(sasUrl, config.outputAllowedHosts);
          if (url && !config.urlEnabled) throw error(503, 'URL_FETCH_DISABLED', 'URL input is disabled; configure CONVERSION_URL_ALLOWED_HOSTS.');
        }
        if (fromUrl || (storeOutput && url)) await download(url, input, { ...config, signal });
        else if (!storeOutput) await receiveVideo(request, input, config.limits.maxInputBytes, signal);
        throwIfAborted(signal);
        if (submit) {
          const job = await jobs.submit(input, filename, { signal });
          throwIfAborted(signal);
          await cleanup();
          const body = statusBody(request, job);
          return { status: 202, headers: { ...COMMON_HEADERS, 'Content-Type': 'application/json; charset=utf-8',
            Location: body.statusUrl, 'Retry-After': '3' }, body: JSON.stringify(body) };
        }
        await extract(input, output, { limits: config.limits, signal });
      }
      throwIfAborted(signal);
      const info = await lstat(output).catch(failure => {
        if (failure.code === 'ENOENT') throw error(500, 'OUTPUT_MISSING', 'Audio extraction produced no result.');
        throw failure;
      });
      if (!info.isFile()) throw error(500, 'OUTPUT_MISSING', 'Audio extraction produced no result.');
      if (info.size === 0) throw error(422, 'EMPTY_OUTPUT', 'Audio extraction produced an empty result.');
      if (info.size > config.limits.maxOutputBytes) throw error(413, 'OUTPUT_TOO_LARGE', 'The audio exceeds the output size limit.');
      if (storeOutput) {
        const stored = await upload(output, sasUrl, { outputAllowedHosts: config.outputAllowedHosts, limits: config.limits, signal });
        throwIfAborted(signal);
        await cleanup();
        return { status: 201, headers: { ...COMMON_HEADERS, 'Content-Type': 'application/json; charset=utf-8' },
          body: JSON.stringify({ status: 'succeeded', output: { blobUrl: stored.blobUrl, bytes: stored.bytes,
            ...(stored.etag ? { etag: stored.etag } : {}), contentType: 'audio/mp4' },
            audio: { codec: 'aac', mode: 'copy' } }) };
      }
      const handle = await open(output, 'r');
      let body;
      try { body = handle.createReadStream({ highWaterMark: 64 * 1024, signal }); }
      catch (failure) { await handle.close(); throw failure; }
      body.once('close', cleanup);
      // Errors after headers close the stream; an error JSON cannot replace audio bytes.
      body.on('error', () => {});
      return { status: 200, headers: { ...COMMON_HEADERS,
        'Content-Type': 'audio/mp4', 'Content-Disposition': 'attachment; filename="audio.m4a"',
        'X-Audio-Codec': 'aac', 'X-Audio-Mode': 'copy',
      }, body };
    } catch (failure) {
      await cleanup();
      return jsonError(signal.aborted ? signal.reason : failure, context);
    }
  }

  async function status(request, context) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(error(504, 'CONVERSION_TIMEOUT', 'The status request deadline was exceeded.')),
      config.limits.timeoutSeconds * 1000);
    timer.unref();
    try {
      requireAsync();
      const job = await jobs.find(request.params?.id, { signal: controller.signal });
      throwIfAborted(controller.signal);
      if (!job) throw error(404, 'JOB_NOT_FOUND', 'Job was not found.');
      return { status: 200, headers: { ...COMMON_HEADERS, 'Content-Type': 'application/json; charset=utf-8',
        'Retry-After': '3' }, body: JSON.stringify(statusBody(request, job)) };
    } catch (failure) { return jsonError(controller.signal.aborted ? controller.signal.reason : failure, context); }
    finally { clearTimeout(timer); }
  }

  return {
    convert: (request, context) => convert(request, context, 'file'),
    convertUrl: (request, context) => convert(request, context, 'url'),
    convertToBlob: (request, context) => convert(request, context, 'storage'),
    submit: (request, context) => convert(request, context, 'submit'),
    submitUrl: (request, context) => convert(request, context, 'submitUrl'),
    status,
    result: (request, context) => convert(request, context, 'result'),
  };
}
