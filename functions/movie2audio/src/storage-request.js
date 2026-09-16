import { open, unlink } from 'node:fs/promises';
import { Readable, Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import busboy from 'busboy';
import { ConversionError, throwIfAborted } from './errors.js';

const JSON_LIMIT = 32 * 1024;
const METADATA_LIMIT = 16 * 1024;
const ENVELOPE_LIMIT = 64 * 1024;
const URL_LIMIT = 8192;
const invalid = () => new ConversionError(400, 'INVALID_STORAGE_REQUEST', 'Send only the required input URL and output SAS URL fields.');
const multipartInvalid = () => new ConversionError(400, 'INVALID_MULTIPART', 'Send exactly one file part and one request JSON part.');
const tooLarge = () => new ConversionError(413, 'REQUEST_TOO_LARGE', 'The request metadata or multipart envelope exceeds its size limit.');
const inputTooLarge = () => new ConversionError(413, 'INPUT_TOO_LARGE', 'The video exceeds the input size limit.');
const empty = () => new ConversionError(400, 'EMPTY_INPUT', 'A nonempty request body and video file are required.');
const storageError = () => new ConversionError(500, 'STORAGE_ERROR', 'A fresh writable temporary input path is required.');

function declaredLength(request, maximum) {
  const value = request.headers.get('content-length');
  if (value === null) return;
  if (!/^\d+$/.test(value)) throw invalid();
  if (BigInt(value) > BigInt(maximum)) throw tooLarge();
}

// This endpoint has a fixed object/string schema. Parse those JSON values only,
// preserving duplicate detection even when a property name uses Unicode escapes.
function strictObject(bytes) {
  try {
    const text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes);
    const stringToken = /"(?:[^"\\\u0000-\u001f]|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*"/y;
    let index = 0;
    const whitespace = () => { while (/[ \t\r\n]/.test(text[index] ?? '\0')) index++; };
    const string = () => {
      whitespace();
      stringToken.lastIndex = index;
      const match = stringToken.exec(text);
      if (!match) throw invalid();
      index = stringToken.lastIndex;
      return JSON.parse(match[0]);
    };
    const object = depth => {
      whitespace();
      if (depth > 3 || text[index++] !== '{') throw invalid();
      const result = Object.create(null);
      whitespace();
      if (text[index] === '}') { index++; return result; }
      while (true) {
        const key = string();
        if (Object.hasOwn(result, key)) throw invalid();
        whitespace();
        if (text[index++] !== ':') throw invalid();
        whitespace();
        result[key] = text[index] === '{' ? object(depth + 1) : string();
        whitespace();
        const separator = text[index++];
        if (separator === '}') return result;
        if (separator !== ',') throw invalid();
      }
    };
    const result = object(0);
    whitespace();
    if (index !== text.length) throw invalid();
    return result;
  } catch { throw invalid(); }
}

function exactKeys(value, keys) {
  return value && typeof value === 'object' && Object.keys(value).length === keys.length
    && keys.every(key => Object.hasOwn(value, key));
}

function validUrl(value) {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= URL_LIMIT;
}

function parseMetadata(bytes, fromUrl) {
  const value = strictObject(bytes);
  if (!exactKeys(value, fromUrl ? ['input', 'output'] : ['output'])
    || !exactKeys(value.output, ['sasUrl']) || !validUrl(value.output.sasUrl)
    || (fromUrl && (!exactKeys(value.input, ['url']) || !validUrl(value.input.url)))) throw invalid();
  return { inputUrl: fromUrl ? value.input.url : null, sasUrl: value.output.sasUrl };
}

function bodySource(request, signal) {
  throwIfAborted(signal);
  if (!request.body) throw empty();
  return Readable.fromWeb(request.body, { signal, highWaterMark: 64 * 1024 });
}

async function readJson(request, signal) {
  declaredLength(request, JSON_LIMIT);
  const chunks = [];
  let size = 0;
  for await (const chunk of bodySource(request, signal)) {
    throwIfAborted(signal);
    size += chunk.length;
    if (size > JSON_LIMIT) throw tooLarge();
    chunks.push(chunk);
  }
  throwIfAborted(signal);
  if (size === 0) throw empty();
  return parseMetadata(Buffer.concat(chunks, size), true);
}

async function readMultipart(request, inputPath, { limits, signal }, contentType) {
  declaredLength(request, limits.maxInputBytes + ENVELOPE_LIMIT);
  if (contentType.length > 1024) throw multipartInvalid();
  let parser;
  try {
    parser = busboy({ headers: { 'content-type': contentType }, highWaterMark: 64 * 1024, fileHwm: 64 * 1024,
      // Busboy emits partsLimit when it finishes the Nth part, even if the form
      // ends there. Allow parsing that extra part so it can never be ignored.
      limits: { files: 2, fields: 0, parts: 3, fieldSize: 0 } });
  } catch { throw multipartInvalid(); }
  parser.on('error', () => {});
  let file;
  let created = false;
  let success = false;
  let fileBytes = 0;
  let metadataBytes = 0;
  let receivedBytes = 0;
  let firstFailure;
  const metadata = [];
  const seen = new Set();
  const partStreams = new Set();
  const partJobs = [];
  const fail = failure => {
    firstFailure ??= failure;
    // Avoid reentrantly destroying Busboy while it is emitting a file event.
    queueMicrotask(() => {
      for (const stream of partStreams) stream.destroy(firstFailure);
      parser.destroy(firstFailure);
    });
  };
  const receivePart = async (name, stream) => {
    if (name === 'file') {
      throwIfAborted(signal);
      try { file = await open(inputPath, 'wx', 0o600); created = true; }
      catch { throw storageError(); }
    }
    for await (const chunk of stream) {
      throwIfAborted(signal);
      if (name === 'request') {
        metadataBytes += chunk.length;
        if (metadataBytes > METADATA_LIMIT) throw tooLarge();
        metadata.push(Buffer.from(chunk));
      } else {
        fileBytes += chunk.length;
        if (fileBytes > limits.maxInputBytes) throw inputTooLarge();
        for (let offset = 0; offset < chunk.length;) {
          throwIfAborted(signal);
          try {
            const { bytesWritten } = await file.write(chunk, offset, Math.min(64 * 1024, chunk.length - offset));
            if (!bytesWritten) throw storageError();
            offset += bytesWritten;
          } catch { throw storageError(); }
        }
      }
    }
    throwIfAborted(signal);
    if (name === 'file' && fileBytes === 0) throw empty();
  };
  parser.on('file', (name, stream, info) => {
    partStreams.add(stream);
    stream.on('error', () => {});
    if (!['file', 'request'].includes(name) || seen.has(name) || info.filename === undefined
      || !['binary', '7bit', '8bit'].includes(info.encoding.toLowerCase())
      || (name === 'request' && info.mimeType !== 'application/json')) {
      fail(multipartInvalid());
      return;
    }
    seen.add(name);
    partJobs.push(receivePart(name, stream).catch(fail));
  });
  parser.on('fieldsLimit', () => fail(multipartInvalid()));
  parser.on('filesLimit', () => fail(multipartInvalid()));
  parser.on('partsLimit', () => fail(multipartInvalid()));
  const limiter = new Transform({ transform(chunk, encoding, callback) {
    receivedBytes += chunk.length;
    callback(receivedBytes > limits.maxInputBytes + ENVELOPE_LIMIT ? tooLarge() : null, chunk);
  } });
  try {
    await pipeline(bodySource(request, signal), limiter, parser, { signal });
    await Promise.all(partJobs);
    throwIfAborted(signal);
    if (firstFailure) throw firstFailure;
    if (receivedBytes === 0) throw empty();
    if (receivedBytes - fileBytes > ENVELOPE_LIMIT) throw tooLarge();
    if (seen.size !== 2 || !seen.has('file') || !seen.has('request')) throw multipartInvalid();
    const result = parseMetadata(Buffer.concat(metadata, metadataBytes), false);
    try { await file.close(); file = undefined; } catch { throw storageError(); }
    throwIfAborted(signal);
    success = true;
    return result;
  } catch (failure) {
    fail(firstFailure ?? (receivedBytes === 0 && !signal?.aborted ? empty() : failure));
    throw firstFailure;
  } finally {
    for (const stream of partStreams) stream.destroy();
    await Promise.all(partJobs);
    await file?.close().catch(() => {});
    if (created && !success) await unlink(inputPath).catch(() => {});
  }
}

/** Stream the fixed multipart contract to disk, or read its bounded URL JSON form. */
export async function readStorageRequest(request, inputPath, options) {
  const contentType = (request.headers.get('content-type') ?? '').trim();
  const type = contentType.split(';', 1)[0].trim().toLowerCase();
  try {
    if (type === 'application/json') return await readJson(request, options.signal);
    if (type === 'multipart/form-data') return await readMultipart(request, inputPath, options, contentType);
    throw new ConversionError(415, 'STORAGE_REQUEST_TYPE_REQUIRED', 'Send application/json or multipart/form-data.');
  } catch (failure) {
    throwIfAborted(options.signal);
    if (failure instanceof ConversionError) throw failure;
    throw type === 'multipart/form-data' ? multipartInvalid() : invalid();
  }
}
