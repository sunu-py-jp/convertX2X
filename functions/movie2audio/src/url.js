import dns from 'node:dns';
import https from 'node:https';
import tls from 'node:tls';
import { isIP } from 'node:net';
import { lstat, open, unlink } from 'node:fs/promises';
import ipaddr from 'ipaddr.js';
import { ConversionError, throwIfAborted } from './errors.js';

const MAX_URL_LENGTH = 8192;
const NETWORK_TIMEOUT_MS = 10_000;
const forbiddenTlds = new Set(['localhost', 'local', 'internal', 'invalid', 'test', 'onion']);
let dnsPending = false;

const invalidUrl = () => new ConversionError(400, 'INVALID_URL', 'Use an HTTPS URL on port 443 without credentials or a fragment');
const failed = () => new ConversionError(502, 'URL_FETCH_FAILED', 'The remote file could not be downloaded');
const storageError = () => new ConversionError(500, 'STORAGE_ERROR', 'A fresh writable temporary input path is required');
const tooLarge = () => new ConversionError(413, 'INPUT_TOO_LARGE', 'The remote file exceeds the input size limit');
const timedOut = () => new ConversionError(504, 'CONVERSION_TIMEOUT', 'The operation exceeded its time limit');
const emptyInput = () => new ConversionError(400, 'EMPTY_INPUT', 'The remote file is empty');

function validHost(host) {
  if (host.length > 253 || !host.includes('.') || host.endsWith('.')) return false;
  const labels = host.split('.');
  if (labels.some(label => !/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label))) return false;
  const tld = labels.at(-1);
  return /[a-z]/.test(tld) && !forbiddenTlds.has(tld);
}

export function parseAllowedHosts(csv) {
  if (csv == null) return new Set();
  if (typeof csv !== 'string') throw new Error('URL allowed hosts must be exact DNS names');
  if (csv.trim() === '') return new Set();
  if (csv.length > MAX_URL_LENGTH) throw new Error('Too many URL allowed hosts');
  const hosts = new Set();
  for (const value of csv.split(',')) {
    const host = value.trim().toLowerCase();
    if (!validHost(host)) throw new Error('URL allowed hosts must be exact DNS names');
    hosts.add(host);
  }
  if (hosts.size > 32) throw new Error('At most 32 URL allowed hosts are supported');
  return hosts;
}

export function validateUrl(text, allowedHosts) {
  if (allowedHosts.size === 0) {
    throw new ConversionError(503, 'URL_FETCH_DISABLED', 'URL input is disabled; configure CONVERSION_URL_ALLOWED_HOSTS');
  }
  // WHATWG URL would silently normalize backslashes, whitespace, and encoded hosts.
  // Reject these ambiguities before parsing while preserving signed paths/queries.
  if (typeof text !== 'string' || !text || text.length > MAX_URL_LENGTH || /[\\\s\u0000-\u001f\u007f#]/u.test(text)) throw invalidUrl();
  const authority = /^https:\/\/([^/?#]+)/i.exec(text)?.[1];
  if (!authority || /[@%\u0080-\uffff]/u.test(authority)) throw invalidUrl();
  let url;
  try { url = new URL(text); } catch { throw invalidUrl(); }
  if (url.protocol !== 'https:' || url.username || url.password || url.hash || (url.port && url.port !== '443') || !validHost(url.hostname)) throw invalidUrl();
  if (!allowedHosts.has(url.hostname)) {
    throw new ConversionError(403, 'URL_HOST_NOT_ALLOWED', 'The URL host is not allowed');
  }
  return url;
}

export function isPublicAddress(text) {
  if (typeof text !== 'string' || !isIP(text) || text.includes('%')) return false;
  let bytes;
  try { bytes = ipaddr.parse(text).toByteArray(); } catch { return false; }
  if (bytes.length === 4) {
    const [a, b, c, d] = bytes;
    return !(a === 0 || a === 10 || a === 127 || a >= 224
      || (a === 100 && b >= 64 && b <= 127)
      || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31)
      || (a === 192 && ((b === 0 && (c === 0 || c === 2)) || (b === 88 && c === 99) || b === 168))
      || (a === 198 && (b === 18 || b === 19 || (b === 51 && c === 100)))
      || (a === 203 && b === 0 && c === 113)
      || (a === 168 && b === 63 && c === 129 && d === 16));
  }
  // Restrict IPv6 to global unicast, excluding special-purpose, documentation,
  // mapped IPv4 and tunneling ranges that can reach forbidden IPv4 addresses.
  if (bytes.length !== 16 || (bytes[0] & 0xe0) !== 0x20) return false;
  const first = (bytes[0] << 8) | bytes[1];
  const second = (bytes[2] << 8) | bytes[3];
  return !(first === 0x2002 || (first === 0x2001 && (second < 0x0200 || second === 0x0db8))
    || (first === 0x3fff && second < 0x1000));
}

export async function resolvePublic(host, signal) {
  throwIfAborted(signal);
  // OS getaddrinfo cannot reliably be cancelled. Keep admission occupied until
  // its callback actually returns, including after the caller's deadline.
  if (dnsPending) throw failed();
  dnsPending = true;
  const lookup = new Promise((resolve, reject) => {
    try {
      dns.lookup(host, { all: true, verbatim: true }, (error, addresses) => {
        dnsPending = false;
        if (error || !Array.isArray(addresses) || addresses.length === 0) reject(failed());
        else resolve(addresses);
      });
    } catch {
      dnsPending = false;
      reject(failed());
    }
  });
  let timer;
  let onAbort;
  try {
    const addresses = await Promise.race([
      lookup,
      new Promise((resolve, reject) => {
        timer = setTimeout(() => reject(failed()), NETWORK_TIMEOUT_MS);
        onAbort = () => reject(signal.reason);
        signal.addEventListener('abort', onAbort, { once: true });
        if (signal.aborted) onAbort();
      }),
    ]);
    throwIfAborted(signal);
    if (addresses.some(entry => !entry || !isPublicAddress(entry.address)
      || isIP(entry.address) !== entry.family)) {
      throw new ConversionError(403, 'URL_ADDRESS_NOT_ALLOWED', 'The URL must resolve only to public internet addresses');
    }
    return addresses[0];
  } finally {
    clearTimeout(timer);
    signal.removeEventListener('abort', onAbort);
  }
}

export function directAgent(hostname, address) {
  const agent = new https.Agent({ keepAlive: false, maxSockets: 1, proxyEnv: {} });
  agent.createConnection = options => tls.connect({
    ...options,
    host: address.address,
    port: 443,
    family: address.family,
    autoSelectFamily: false,
    servername: hostname,
    rejectUnauthorized: true,
    checkServerIdentity: tls.checkServerIdentity,
    ALPNProtocols: ['http/1.1'],
  });
  return agent;
}

function expectedLength(response, maximum) {
  const values = [];
  for (let index = 0; index < (response.rawHeaders?.length ?? 0); index += 2) {
    if (response.rawHeaders[index].toLowerCase() === 'content-length') values.push(response.rawHeaders[index + 1]);
  }
  const length = response.headers['content-length'];
  if (values.length > 1 || Array.isArray(length) || (length != null && (!/^\d+$/.test(length)
    || response.headers['transfer-encoding'] != null))) throw failed();
  if (length == null) return undefined;
  const size = BigInt(length);
  if (size > BigInt(maximum)) throw tooLarge();
  if (size === 0n) throw emptyInput();
  return Number(size);
}

export async function downloadVideo(text, output, { allowedHosts, limits, signal: outerSignal }) {
  const url = validateUrl(text, allowedHosts);
  const controller = new AbortController();
  const signal = outerSignal ? AbortSignal.any([outerSignal, controller.signal]) : controller.signal;
  const operationTimer = setTimeout(() => controller.abort(timedOut()), limits.timeoutSeconds * 1000);
  let request;
  let response;
  let agent;
  let file;
  let created = false;
  let complete = false;
  let headerTimer;
  let rejectResponse;
  const onAbort = () => {
    rejectResponse?.(signal.reason);
    response?.destroy();
    request?.destroy();
  };
  signal.addEventListener('abort', onAbort, { once: true });
  try {
    throwIfAborted(signal);
    try {
      await lstat(output);
      throw storageError();
    } catch (error) {
      if (error.code !== 'ENOENT') throw storageError();
    }
    const address = await resolvePublic(url.hostname, signal);
    throwIfAborted(signal);
    agent = directAgent(url.hostname, address);
    response = await new Promise((resolve, reject) => {
      rejectResponse = reject;
      request = https.request(url, {
        method: 'GET', agent, highWaterMark: 64 * 1024,
        headers: { Accept: 'video/*, application/octet-stream;q=0.9', 'Accept-Encoding': 'identity',
          'User-Agent': 'convertX2X-movie2audio/1.0' },
      }, incoming => {
        // A peer can fail while the fresh file is being opened, before the
        // async iterator installs its listeners. The stream retains errored.
        incoming.on('error', () => {});
        clearTimeout(headerTimer);
        resolve(incoming);
      });
      request.on('error', reject);
      request.setTimeout(NETWORK_TIMEOUT_MS, () => request.destroy(failed()));
      headerTimer = setTimeout(() => { reject(failed()); request.destroy(); }, NETWORK_TIMEOUT_MS);
      request.end();
    });
    throwIfAborted(signal);
    if (response.statusCode >= 300 && response.statusCode <= 399) {
      throw new ConversionError(422, 'URL_REDIRECT_NOT_ALLOWED', 'Use the final direct video URL; redirects are not followed');
    }
    if (response.statusCode !== 200) throw failed();
    const encoding = response.headers['content-encoding'] ?? 'identity';
    if (typeof encoding !== 'string' || encoding.toLowerCase() !== 'identity') {
      throw new ConversionError(422, 'URL_ENCODING_NOT_SUPPORTED', 'The remote server must return an uncompressed file response');
    }
    const expected = expectedLength(response, limits.maxInputBytes);
    try { file = await open(output, 'wx', 0o600); created = true; }
    catch { throw storageError(); }
    let total = 0;
    for await (const chunk of response) {
      throwIfAborted(signal);
      if (!Buffer.isBuffer(chunk)) throw failed();
      total += chunk.length;
      if (total > limits.maxInputBytes) throw tooLarge();
      for (let offset = 0; offset < chunk.length;) {
        throwIfAborted(signal);
        try {
          const { bytesWritten } = await file.write(chunk, offset, Math.min(64 * 1024, chunk.length - offset));
          if (bytesWritten === 0) throw storageError();
          offset += bytesWritten;
        } catch { throw storageError(); }
      }
    }
    throwIfAborted(signal);
    if (total === 0) throw emptyInput();
    if (response.complete === false || (expected !== undefined && total !== expected)) throw failed();
    try { await file.close(); file = undefined; } catch { throw storageError(); }
    throwIfAborted(signal);
    complete = true;
    return { bytes: total };
  } catch (error) {
    throwIfAborted(signal);
    if (error instanceof ConversionError) throw error;
    throw failed();
  } finally {
    clearTimeout(operationTimer);
    clearTimeout(headerTimer);
    signal.removeEventListener('abort', onAbort);
    response?.destroy();
    request?.destroy();
    agent?.destroy();
    await file?.close().catch(() => {});
    if (created && !complete) await unlink(output).catch(() => {});
  }
}
