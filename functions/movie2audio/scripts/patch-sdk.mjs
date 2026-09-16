import { createHash } from 'node:crypto';
import { readFile, rename, unlink, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const VERSION = '4.16.2';
const ORIGINAL_SHA256 = 'dd4f8b15ff9251e4aca97e79b9673ec3c826d7bd58dd6992983814fa7fffd6e9';
const PATCHED_SHA256 = 'd8c1ade4b35bd06e58eade4f64a97cd53fc0dec90536e6f4b58b82a926f193c2';
const START = 'function sendProxyResponse(invocationId, userRes) {';
const END = '\nexports.sendProxyResponse = sendProxyResponse;';
const ORIGINAL_HEADERS = `    let headers;
    const headersData = (0, fromRpcTypedData_1.fromRpcTypedData)(rpcHeaders);
    if (typeof headersData === 'object' && (0, nonNull_1.isDefined)(headersData)) {
        headers = headersData;
    }`;
const TRANSPORT_HEADERS = `    // convertX2X: JSON body fields must not replace transport request headers.
    const headers = new Headers();
    for (const [name, value] of Object.entries(proxyReq.headers)) {
        if (Array.isArray(value)) {
            for (const item of value) headers.append(name, item);
        } else if (typeof value === 'string') {
            headers.set(name, value);
        }
    }`;

// The SDK's MIT copyright/license notices remain in the installed bundle and LICENSE.
// Its 4.16.2 proxy writes without waiting for drain. This replacement changes only
// response delivery to pipeline(), which handles backpressure and cancellation.
const REPLACEMENT = `// convertX2X compatibility patch: scripts/patch-sdk.mjs, SDK 4.16.2.
async function sendProxyResponse(invocationId, userRes) {
    const proxyRes = (0, nonNull_1.nonNullProp)(responses, invocationId);
    delete responses[invocationId];
    let source;
    try {
        for (const [key, val] of userRes.headers.entries()) {
            proxyRes.setHeader(key, val);
        }
        proxyRes.setHeader(invocationIdHeader, invocationId);
        proxyRes.statusCode = userRes.status;
        if (userRes.cookies.length > 0) {
            setCookies(userRes, proxyRes);
        }
        const stream = require("node:stream");
        source = userRes.body ? stream.Readable.fromWeb(userRes.body) : stream.Readable.from([]);
        await new Promise((resolve, reject) => {
            stream.pipeline(source, proxyRes, error => error ? reject(error) : resolve());
        });
    } catch (error) {
        if (source) source.destroy();
        else if (userRes.body) await userRes.body.cancel().catch(() => {});
        proxyRes.destroy();
        throw error;
    }
}`;

function sha256(value) { return createHash('sha256').update(value).digest('hex'); }

/** Patch only the exact locked SDK build, and refuse unreviewed dependency changes. */
export async function patchSdk(moduleRoot = dirname(dirname(fileURLToPath(import.meta.url)))) {
  const sdk = join(moduleRoot, 'node_modules', '@azure', 'functions');
  const metadata = JSON.parse(await readFile(join(sdk, 'package.json'), 'utf8'));
  if (metadata.version !== VERSION || metadata.main !== './dist/azure-functions.js') {
    throw new Error('Unsupported @azure/functions version or entry point; review the HTTP streaming patch before upgrading.');
  }
  const target = join(sdk, 'dist', 'azure-functions.js');
  const bytes = await readFile(target);
  const checksum = sha256(bytes);
  if (checksum === PATCHED_SHA256) return { changed: false, version: VERSION, sha256: checksum };
  if (checksum !== ORIGINAL_SHA256) {
    throw new Error('The @azure/functions bundle does not match its pinned SHA256; refusing to apply the HTTP streaming patch.');
  }
  const original = bytes.toString('utf8');
  const start = original.indexOf(START);
  const end = original.indexOf(END, start);
  if (start < 0 || end < 0 || original.indexOf(START, start + START.length) !== -1) {
    throw new Error('The SDK HTTP proxy patch location was not found exactly once.');
  }
  const firstHeader = original.indexOf(ORIGINAL_HEADERS);
  if (firstHeader < 0 || original.indexOf(ORIGINAL_HEADERS, firstHeader + ORIGINAL_HEADERS.length) !== -1) {
    throw new Error('The SDK request header patch location was not found exactly once.');
  }
  const patched = (original.slice(0, start) + REPLACEMENT + original.slice(end))
    .replace(ORIGINAL_HEADERS, TRANSPORT_HEADERS);
  const patchedChecksum = sha256(patched);
  if (patchedChecksum !== PATCHED_SHA256) throw new Error('The reviewed SDK patch checksum does not match.');
  const temporary = `${target}.movie2audio-patch`;
  try {
    await writeFile(temporary, patched, { flag: 'wx', mode: 0o644 });
    await rename(temporary, target);
  } finally { await unlink(temporary).catch(() => {}); }
  return { changed: true, version: VERSION, sha256: patchedChecksum };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const result = await patchSdk();
  console.log(`Azure Functions ${result.version}: HTTP stream backpressure patch ${result.changed ? 'applied' : 'verified'}.`);
}
