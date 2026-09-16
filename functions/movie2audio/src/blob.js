import https from 'node:https';
import { constants } from 'node:fs';
import { open } from 'node:fs/promises';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { ConversionError, throwIfAborted } from './errors.js';
import { validateUrl, resolvePublic, directAgent } from './url.js';

const MAX_URL_LENGTH = 8192;
const NETWORK_TIMEOUT_MS = 10_000;
const MAX_RESPONSE_BYTES = 8192;
const BLOB_HOST = /^[a-z0-9]{3,24}\.blob\.core\.windows\.net$/;
const SAS_FIELDS = new Set(['sv', 'sr', 'sp', 'se', 'st', 'spr', 'sip', 'sig',
  'skoid', 'sktid', 'skt', 'ske', 'sks', 'skv', 'saoid', 'suoid', 'scid', 'ses',
  'rscc', 'rscd', 'rsce', 'rscl', 'rsct']);

const invalid = () => new ConversionError(400, 'INVALID_OUTPUT_SAS_URL',
  'Use an HTTPS URL for one new Azure blob with an unexpired blob SAS, explicit create/write permission, and HTTPS-only access.');
const failed = () => new ConversionError(502, 'OUTPUT_UPLOAD_FAILED',
  'The upload could not be confirmed. The blob might already have been created; verify it before retrying.');
const storageError = () => new ConversionError(500, 'STORAGE_ERROR', 'A readable, nonempty audio file is required.');
const tooLarge = () => new ConversionError(413, 'OUTPUT_TOO_LARGE', 'The audio exceeds the output size limit.');
const timedOut = () => new ConversionError(504, 'CONVERSION_TIMEOUT', 'The operation exceeded its time limit.');

export function parseOutputHosts(csv) {
  if (csv == null || (typeof csv === 'string' && csv.trim() === '')) return new Set();
  if (typeof csv !== 'string' || csv.length > MAX_URL_LENGTH) {
    throw new Error('Output hosts must be exact Azure public Blob Storage hostnames.');
  }
  const hosts = new Set();
  for (const value of csv.split(',')) {
    const host = value.trim().toLowerCase();
    if (!BLOB_HOST.test(host)) throw new Error('Output hosts must be exact Azure public Blob Storage hostnames.');
    hosts.add(host);
  }
  if (hosts.size > 32) throw new Error('At most 32 output hosts are supported.');
  return hosts;
}

function dateOnly(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const time = Date.parse(`${value}T00:00:00Z`);
  return Number.isFinite(time) && new Date(time).toISOString().slice(0, 10) === value;
}

function sasTime(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}(?:T(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d{1,7})?)?Z)?$/.test(value)
    || !dateOnly(value.slice(0, 10))) return NaN;
  return Date.parse(value);
}

export function validateBlobDestination(text, outputAllowedHosts) {
  if (!outputAllowedHosts?.size) {
    throw new ConversionError(503, 'OUTPUT_STORAGE_DISABLED',
      'Blob output is disabled; configure CONVERSION_OUTPUT_ALLOWED_HOSTS.');
  }
  let url;
  try { url = validateUrl(text, outputAllowedHosts); }
  catch (error) {
    if (error.code === 'URL_HOST_NOT_ALLOWED') {
      throw new ConversionError(403, 'OUTPUT_HOST_NOT_ALLOWED', 'The output Blob Storage host is not allowed.');
    }
    throw invalid();
  }
  if (!BLOB_HOST.test(url.hostname) || /%(?![0-9a-f]{2})/i.test(text)) throw invalid();
  const rawPath = /^https:\/\/[^/?#]+([^?#]*)/i.exec(text)?.[1];
  // URL normalizes dot segments before any request; a signed blob name must not change.
  if (!rawPath || url.pathname !== rawPath) throw invalid();
  const segments = rawPath.slice(1).split('/');
  if (segments.length < 2 || segments.some(part => !part)) throw invalid();
  let container;
  try {
    container = decodeURIComponent(segments[0]);
    for (const part of segments) {
      const decoded = decodeURIComponent(part);
      if (decoded === '.' || decoded === '..' || /[\\/\u0000-\u001f\u007f]/u.test(decoded)) throw invalid();
    }
  } catch { throw invalid(); }
  if (!/^[a-z0-9](?:[a-z0-9]|-(?!-)){1,61}[a-z0-9]$/.test(container)
    && container !== '$root' && container !== '$web') throw invalid();
  const seen = new Set();
  for (const part of url.search.slice(1).split('&')) {
    const key = part.split('=', 1)[0];
    if (!SAS_FIELDS.has(key) || seen.has(key) || !part.includes('=')) throw invalid();
    seen.add(key);
  }
  const query = url.searchParams;
  for (const [, value] of query) {
    if (!value || /[\u0000-\u001f\u007f]/u.test(value)) throw invalid();
  }
  if (query.get('sr') !== 'b' || !['c', 'w', 'cw'].includes(query.get('sp'))
    || query.get('spr') !== 'https' || !dateOnly(query.get('sv') ?? '')
    || query.get('sv') < '2019-12-12' || !query.get('sig')) throw invalid();
  const expiry = sasTime(query.get('se'));
  const start = query.has('st') ? sasTime(query.get('st')) : undefined;
  if (!Number.isFinite(expiry) || expiry <= Date.now()
    || (start !== undefined && (!Number.isFinite(start) || start > Date.now() || start >= expiry))) throw invalid();
  return { url, blobUrl: `${url.origin}${url.pathname}` };
}

function statusError(status) {
  if (status === 412) return new ConversionError(409, 'OUTPUT_BLOB_EXISTS', 'The destination blob already exists; choose a new blob name.');
  if (status === 403) return new ConversionError(403, 'OUTPUT_AUTH_FAILED', 'Azure Storage rejected the output SAS or its access restrictions.');
  if (status >= 300 && status <= 399) return new ConversionError(422, 'OUTPUT_REDIRECT_NOT_ALLOWED', 'Output redirects are not followed; use the final Blob Storage URL.');
  return failed();
}

export async function uploadAudio(path, sasUrl, { outputAllowedHosts, limits, signal: outerSignal }) {
  const target = validateBlobDestination(sasUrl, outputAllowedHosts);
  const controller = new AbortController();
  const signal = outerSignal ? AbortSignal.any([outerSignal, controller.signal]) : controller.signal;
  const operationTimer = setTimeout(() => controller.abort(timedOut()), limits.timeoutSeconds * 1000);
  let file;
  let source;
  let request;
  let response;
  let agent;
  let sending;
  let rejectResponse;
  let connectTimer;
  let headerTimer;
  let uploadedBytes = 0;
  const onAbort = () => {
    rejectResponse?.(signal.reason);
    response?.destroy();
    request?.destroy();
    source?.destroy();
  };
  signal.addEventListener('abort', onAbort, { once: true });
  try {
    throwIfAborted(signal);
    try { file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW); }
    catch { throw storageError(); }
    const info = await file.stat().catch(() => { throw storageError(); });
    if (!info.isFile() || info.size === 0) throw storageError();
    if (info.size > limits.maxOutputBytes) throw tooLarge();
    let address;
    try { address = await resolvePublic(target.url.hostname, signal); }
    catch (error) {
      throwIfAborted(signal);
      if (error.code === 'URL_ADDRESS_NOT_ALLOWED') {
        throw new ConversionError(403, 'OUTPUT_ADDRESS_NOT_ALLOWED', 'The output host must resolve only to public internet addresses.');
      }
      throw failed();
    }
    throwIfAborted(signal);
    agent = directAgent(target.url.hostname, address);
    const received = new Promise((resolve, reject) => {
      rejectResponse = reject;
      request = https.request(target.url, {
        method: 'PUT', agent, highWaterMark: 64 * 1024, maxHeaderSize: 16 * 1024,
        headers: { 'Content-Type': 'audio/mp4', 'Content-Length': String(info.size),
          'x-ms-blob-type': 'BlockBlob', 'If-None-Match': '*',
          'x-ms-version': '2023-11-03', 'x-ms-date': new Date().toUTCString(),
          'User-Agent': 'convertX2X-movie2audio/1.0' },
      }, incoming => {
        response = incoming;
        incoming.on('error', () => {});
        clearTimeout(connectTimer);
        clearTimeout(headerTimer);
        if (incoming.statusCode !== 201) { reject(statusError(incoming.statusCode)); return; }
        (async () => {
          let responseBytes = 0;
          for await (const chunk of incoming) {
            throwIfAborted(signal);
            responseBytes += chunk.length;
            if (responseBytes > MAX_RESPONSE_BYTES) throw failed();
          }
          if (incoming.complete === false) throw failed();
          resolve(incoming.headers);
        })().catch(reject);
      });
      request.on('error', () => reject(failed()));
      request.on('socket', socket => {
        socket.once('secureConnect', () => clearTimeout(connectTimer));
      });
      request.on('finish', () => {
        clearTimeout(connectTimer);
        if (!response) headerTimer = setTimeout(() => reject(failed()), NETWORK_TIMEOUT_MS);
      });
      request.setTimeout(NETWORK_TIMEOUT_MS, () => request.destroy(failed()));
      connectTimer = setTimeout(() => { reject(failed()); request.destroy(); }, NETWORK_TIMEOUT_MS);
    });
    source = file.createReadStream({ autoClose: false, start: 0, end: info.size - 1, highWaterMark: 64 * 1024 });
    const count = new Transform({
      transform(chunk, encoding, callback) {
        uploadedBytes += chunk.length;
        callback(uploadedBytes > info.size ? failed() : null, chunk);
      },
      flush(callback) { callback(uploadedBytes === info.size ? null : failed()); },
    });
    sending = pipeline(source, count, request, { signal });
    const [headers] = await Promise.all([received, sending]);
    throwIfAborted(signal);
    const result = { blobUrl: target.blobUrl, bytes: uploadedBytes };
    // ETags are opaque protocol values, not remote error text or arbitrary headers.
    if (typeof headers.etag === 'string' && /^"0x[0-9A-Fa-f]{1,32}"$/.test(headers.etag)) result.etag = headers.etag;
    return result;
  } catch (error) {
    throwIfAborted(signal);
    if (error instanceof ConversionError) throw error;
    throw failed();
  } finally {
    clearTimeout(operationTimer);
    clearTimeout(connectTimer);
    clearTimeout(headerTimer);
    signal.removeEventListener('abort', onAbort);
    controller.abort();
    response?.destroy();
    request?.destroy();
    source?.destroy();
    await sending?.catch(() => {});
    agent?.destroy();
    await file?.close().catch(() => {});
  }
}
