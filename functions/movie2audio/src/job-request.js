import { ConversionError } from './errors.js';
import { normalizeAudioOptions } from './audio-options.js';
export { readJobSettings } from './storage-settings.js';

export const QUEUE_NAME = 'movie2audio-jobs';
export const CONTAINER_NAME = 'movie2audio-jobs';
export const MAX_JOB_MESSAGE_BYTES = 48 * 1024;
const invalid = () => new ConversionError(400, 'INVALID_QUEUE_MESSAGE', 'Send a version 1 or 2 job with registered storage aliases and blob paths only.');
const controls = /[\u0000-\u001f\u007f-\u009f]/u;

// This bounded grammar preserves duplicate keys and accepts only contract scalar types.
// Parsing this small grammar keeps duplicate keys visible, including escaped names.
function strictJson(bytes) {
  try {
    const text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes);
    const token = /"(?:[^"\\\u0000-\u001f]|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*"/y;
    let index = 0;
    const whitespace = () => { while (/[ \t\r\n]/.test(text[index] ?? '\0')) index++; };
    const string = () => {
      whitespace(); token.lastIndex = index;
      const match = token.exec(text);
      if (!match) throw invalid();
      index = token.lastIndex;
      return JSON.parse(match[0]);
    };
    const value = depth => {
      whitespace();
      if (depth > 3) throw invalid();
      if (text[index] === '"') return string();
      if (text.startsWith('null', index)) { index += 4; return null; }
      if (/[0-9]/.test(text[index] ?? '')) {
        const match = /^(?:0|[1-9][0-9]*)/.exec(text.slice(index));
        index += match[0].length; const number = Number(match[0]);
        if (!Number.isSafeInteger(number)) throw invalid();
        return number;
      }
      if (text[index++] !== '{') throw invalid();
      const result = Object.create(null);
      whitespace();
      if (text[index] === '}') { index++; return result; }
      while (true) {
        const key = string();
        if (Object.hasOwn(result, key)) throw invalid();
        whitespace();
        if (text[index++] !== ':') throw invalid();
        result[key] = value(depth + 1);
        whitespace();
        const separator = text[index++];
        if (separator === '}') return result;
        if (separator !== ',') throw invalid();
      }
    };
    const result = value(0);
    whitespace();
    if (index !== text.length) throw invalid();
    return result;
  } catch { throw invalid(); }
}

function object(value, fields) {
  if (!value || typeof value !== 'object' || Array.isArray(value)
    || ![Object.prototype, null].includes(Object.getPrototypeOf(value))
    || Object.keys(value).some(key => !fields.includes(key))) throw invalid();
}
function string(value, optional = false) {
  if (optional && value == null) return null;
  if (typeof value !== 'string' || (!optional && !value.trim())) throw invalid();
  // Unpaired surrogates cannot be preserved as a UTF-8 blob path.
  if (!value.isWellFormed()) throw invalid();
  return value;
}
function container(value) {
  string(value);
  if (!/^[a-z0-9](?:[a-z0-9]|-(?!-)){1,61}[a-z0-9]$/.test(value)) throw invalid();
  return value;
}
function storage(value) {
  if (value == null) return 'default';
  string(value);
  if (!/^[a-z][a-z0-9_]{0,31}$/.test(value)) throw invalid();
  return value;
}
function path(value, maximum, allowEmpty) {
  string(value, allowEmpty);
  if (value == null || value.length > maximum || (!allowEmpty && !value.trim())
    || controls.test(value) || /[\\?#]/.test(value) || value.includes('://')
    || value.split('/').some(segment => segment === '.' || segment === '..')) throw invalid();
  return value;
}

export function normalizeJobId(id) {
  if (typeof id !== 'string' || !/^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i.test(id)) {
    throw new ConversionError(400, 'INVALID_JOB_ID', 'The job ID must be a UUID.');
  }
  return id.toLowerCase();
}

export function safeFilename(value, fallback = 'video') {
  const name = string(value, true);
  const selected = name?.trim() ? name : string(fallback);
  const result = selected.replaceAll('\\', '/').split('/').at(-1).replace(/[\u0000-\u001f\u007f-\u009f]/gu, '_');
  if (!result.trim() || result === '.' || result === '..' || result.length > 255) throw invalid();
  return result;
}

export function normalizeJobRequest(value) {
  const v2 = value?.version === 2;
  object(value, ['version', 'jobId', 'input', 'output', 'filename', ...(v2 ? ['metadata', 'notification', 'options'] : [])]);
  if (value.version !== 1 && !v2) throw invalid();
  object(value.input, ['storage', 'container', 'blobName', ...(v2 ? ['expectedETag'] : [])]);
  object(value.output, ['storage', 'container', 'prefix']);
  const prefixValue = string(value.output.prefix, true);
  if (prefixValue !== null && controls.test(prefixValue)) throw invalid();
  const result = {
    version: value.version, jobId: normalizeJobId(value.jobId),
    input: { storage: storage(value.input.storage), container: container(value.input.container), blobName: path(value.input.blobName, 1024, false) },
    output: { storage: storage(value.output.storage), container: container(value.output.container), prefix: path((prefixValue ?? '').trim().replace(/\/+$/, ''), 512, true) },
    filename: safeFilename(value.filename, value.input.blobName)
  };
  if (v2) {
    if (value.input.expectedETag != null) {
      if (typeof value.input.expectedETag !== 'string' || !/^"[A-Za-z0-9._:-]{1,128}"$/.test(value.input.expectedETag)) throw invalid();
      result.input.expectedETag = value.input.expectedETag;
    }
    if (value.metadata != null) {
      const keys = Object.keys(value.metadata).sort();
      object(value.metadata, keys);
      if (keys.length > 16) throw invalid();
      const metadata = Object.create(null);
      for (const key of keys) {
        if (!key.trim() || key.length > 64 || controls.test(key) || !key.isWellFormed()) throw invalid();
        const entry = string(value.metadata[key], true);
        if (entry == null || entry.length > 512 || controls.test(entry)) throw invalid();
        metadata[key] = entry;
      }
      if (Buffer.byteLength(JSON.stringify(metadata)) > 8192) throw invalid();
      result.metadata = { ...metadata };
    }
    if (value.notification != null) {
      object(value.notification, ['queue']);
      if (value.notification.queue == null) throw invalid();
      result.notification = { queue: storage(value.notification.queue) };
    }
    try { result.options = normalizeAudioOptions(value.options); } catch { throw invalid(); }
  }
  if (Buffer.byteLength(JSON.stringify(result), 'utf8') > MAX_JOB_MESSAGE_BYTES) throw invalid();
  return result;
}

/** Receives decoded UTF-8 bytes from the Functions binary queue binding. Never decode Base64 here. */
export function parseJobRequest(message) {
  if ((!Buffer.isBuffer(message) && typeof message !== 'string')
    || (typeof message === 'string' && !message.isWellFormed())) throw invalid();
  const bytes = Buffer.isBuffer(message) ? message : Buffer.from(message, 'utf8');
  if (bytes.length === 0 || bytes.length > MAX_JOB_MESSAGE_BYTES) throw invalid();
  return normalizeJobRequest(strictJson(bytes));
}
