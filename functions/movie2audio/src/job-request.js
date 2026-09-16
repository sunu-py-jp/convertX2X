import { ConversionError } from './errors.js';

export const QUEUE_NAME = 'movie2audio-jobs';
export const CONTAINER_NAME = 'movie2audio-jobs';
export const MAX_JOB_MESSAGE_BYTES = 48 * 1024;
const invalid = () => new ConversionError(400, 'INVALID_QUEUE_MESSAGE', 'Send a version 1 job with registered storage aliases and blob paths only.');
const controls = /[\u0000-\u001f\u007f-\u009f]/u;

// The contract only needs objects, strings, null and the literal integer 1.
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
      if (text[index] === '1') { index++; return 1; }
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
  object(value, ['version', 'jobId', 'input', 'output', 'filename']);
  if (value.version !== 1) throw invalid();
  object(value.input, ['storage', 'container', 'blobName']);
  object(value.output, ['storage', 'container', 'prefix']);
  const prefixValue = string(value.output.prefix, true);
  if (prefixValue !== null && controls.test(prefixValue)) throw invalid();
  const result = {
    version: 1, jobId: normalizeJobId(value.jobId),
    input: { storage: storage(value.input.storage), container: container(value.input.container), blobName: path(value.input.blobName, 1024, false) },
    output: { storage: storage(value.output.storage), container: container(value.output.container), prefix: path((prefixValue ?? '').trim().replace(/\/+$/, ''), 512, true) },
    filename: safeFilename(value.filename, value.input.blobName)
  };
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

export function readJobSettings(env = process.env) {
  const connectionString = typeof env.CONVERSION_STORAGE_CONNECTION_STRING === 'string'
    ? env.CONVERSION_STORAGE_CONNECTION_STRING.trim() : '';
  if (!connectionString) return null;
  const read = prefix => {
    const connections = new Map([['default', connectionString]]);
    for (const [name, value] of Object.entries(env)) {
      if (!name.startsWith(prefix) || value == null || (typeof value === 'string' && !value.trim())) continue;
      const alias = name.slice(prefix.length);
      if (typeof value !== 'string' || !/^[A-Z][A-Z0-9_]{0,31}$/.test(alias) || alias === 'DEFAULT') {
        throw new Error('A registered storage alias setting is invalid.');
      }
      connections.set(alias.toLowerCase(), value.trim());
    }
    return connections;
  };
  return { connectionString, inputConnections: read('CONVERSION_INPUT_STORAGE_'), outputConnections: read('CONVERSION_OUTPUT_STORAGE_') };
}
