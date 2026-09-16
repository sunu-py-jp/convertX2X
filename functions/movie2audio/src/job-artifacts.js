import { randomUUID } from 'node:crypto';
import { normalizeJobId, normalizeJobRequest, CONTAINER_NAME } from './job-request.js';

const MARKER = 'convertx2x_movie2audio';
const LIMIT = 16 * 1024;
const terminal = record => ['succeeded', 'failed'].includes(record?.job?.status);
export const retentionTimestamp = record => Math.max(Date.parse(record.job.updatedAt), Date.parse(record.outbox?.sentAt) || 0);
const expired = (date, days, now) => days > 0 && Number.isFinite(Date.parse(date)) && now - Date.parse(date) >= days * 86400000;

/** Exact-location ownership journal. Never enumerate or remove caller prefixes. */
export function createArtifactLedger({ control, resolve, operationSignal, ensureIndex }) {
  async function read(blob, signal) {
    const properties = await blob.getProperties({ abortSignal: operationSignal(signal) });
    if (properties.contentLength > LIMIT) throw new Error('Invalid artifact journal.');
    const response = await blob.download(0, properties.contentLength, { abortSignal: operationSignal(signal),
      conditions: { ifMatch: properties.etag }, maxRetryRequests: 0 });
    const chunks = []; let length = 0;
    for await (const chunk of response.readableStreamBody) {
      length += chunk.length; if (length > LIMIT) throw new Error('Invalid artifact journal.'); chunks.push(chunk);
    }
    const value = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (value.version !== 1 || !['input', 'output'].includes(value.role) || !Number.isFinite(Date.parse(value.createdAt))
      || (value.etag != null && !/^"[A-Za-z0-9._:-]{1,128}"$/.test(value.etag))
      || normalizeJobId(value.jobId) !== value.jobId || normalizeJobId(value.token) !== value.token) throw new Error('Invalid artifact journal.');
    const source = normalizeJobRequest({ version: 1, jobId: value.jobId, input: value.location,
      output: { container: CONTAINER_NAME }, filename: 'artifact' }).input;
    if (JSON.stringify(source) !== JSON.stringify(value.location)) throw new Error('Invalid artifact journal.');
    return { value, etag: properties.etag };
  }
  return {
    async reserve(jobId, role, location, signal) {
      await ensureIndex(jobId, signal);
      const token = randomUUID();
      const name = `${normalizeJobId(jobId)}/artifacts/${token}.json`;
      const record = { version: 1, jobId, token, role, location, createdAt: new Date().toISOString() };
      const bytes = Buffer.from(JSON.stringify(record));
      const journal = control.getBlockBlobClient(name);
      const reserved = await journal.upload(bytes, bytes.length, { abortSignal: operationSignal(signal),
        conditions: { ifNoneMatch: '*' }, blobHTTPHeaders: { blobContentType: 'application/json' } });
      return { metadata: { convertx2x_owner: MARKER, convertx2x_token: token, convertx2x_job: jobId },
        commit: async etag => {
          if (!/^"[A-Za-z0-9._:-]{1,128}"$/.test(etag)) throw new Error('Invalid artifact ETag.');
          const committed = Buffer.from(JSON.stringify({ ...record, etag }));
          await journal.upload(committed, committed.length, { abortSignal: operationSignal(signal),
            conditions: { ifMatch: reserved.etag }, blobHTTPHeaders: { blobContentType: 'application/json' } });
        } };
    },
    async remove(name, { record, days, now = Date.now(), signal } = {}) {
      const match = /^([a-f0-9-]{36})\/artifacts\/([a-f0-9-]{36})\.json$/.exec(name);
      if (!match) return false;
      const journal = control.getBlockBlobClient(name);
      const { value, etag } = await read(journal, signal);
      if (value.jobId !== match[1] || value.token !== match[2]) throw new Error('Invalid artifact journal.');
      // Live, still-retained and unnotified jobs are protected. An abandoned HTTP
      // upload without status becomes eligible only after its journal retention.
      if (record ? !terminal(record) || record.outbox?.state === 'pending'
        || !(days > 0 && now - retentionTimestamp(record) >= days * 86400000) : !expired(value.createdAt, days, now)) return false;
      const blob = resolve(value.role, value.location.storage).getContainerClient(value.location.container)
        .getBlockBlobClient(value.location.blobName);
      try {
        const properties = await blob.getProperties({ abortSignal: operationSignal(signal) });
        if (properties.metadata?.convertx2x_owner === MARKER && properties.metadata?.convertx2x_token === value.token
          && properties.metadata?.convertx2x_job === value.jobId
          && (!value.etag || properties.etag === value.etag)) {
          await blob.deleteIfExists({ abortSignal: operationSignal(signal), conditions: { ifMatch: properties.etag } });
        }
        // A replacement belongs to the caller. Drop only our journal, never that blob.
      } catch (error) { if (error?.statusCode !== 404) throw error; }
      await journal.deleteIfExists({ abortSignal: operationSignal(signal), conditions: { ifMatch: etag } });
      return true;
    },
    async list(jobId, signal, maxPageSize = 100) {
      const pages = control.listBlobsFlat({ prefix: `${normalizeJobId(jobId)}/artifacts/`, abortSignal: operationSignal(signal) })
        .byPage({ maxPageSize });
      const page = await pages.next();
      return page.done ? [] : page.value.segment.blobItems.map(item => item.name);
    }
  };
}
