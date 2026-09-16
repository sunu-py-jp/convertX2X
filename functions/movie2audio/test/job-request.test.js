import test from 'node:test';
import assert from 'node:assert/strict';
import { CONTAINER_NAME, QUEUE_NAME, MAX_JOB_MESSAGE_BYTES, normalizeJobId, normalizeJobRequest, parseJobRequest, readJobSettings, safeFilename } from '../src/job-request.js';

const id = 'A359A7C9-F23F-4C16-B998-FA56A200CD03';
const request = () => ({ version: 1, jobId: id, input: { container: 'incoming', blobName: '営業/meeting.mp4' }, output: { container: 'converted', prefix: ' audio///  ' } });
const invalid = action => assert.throws(action, error => error.code === 'INVALID_QUEUE_MESSAGE');

test('queue requests normalize the same defaults, filename and UUID as existing functions', () => {
  assert.equal(CONTAINER_NAME, 'movie2audio-jobs'); assert.equal(QUEUE_NAME, CONTAINER_NAME);
  assert.deepEqual(parseJobRequest(Buffer.from(JSON.stringify(request()))), {
    version: 1, jobId: id.toLowerCase(), input: { storage: 'default', container: 'incoming', blobName: '営業/meeting.mp4' },
    output: { storage: 'default', container: 'converted', prefix: 'audio' }, filename: 'meeting.mp4'
  });
  const value = request(); value.output.prefix = null; value.input.storage = null; value.filename = '';
  assert.equal(normalizeJobRequest(value).output.prefix, '');
  assert.equal(normalizeJobRequest(value).filename, 'meeting.mp4');
  assert.equal(safeFilename('C:\\uploads\\my\nvideo.mp4'), 'my_video.mp4');
  assert.equal(normalizeJobId(id), id.toLowerCase());
});

test('queue parsing rejects duplicate fields, escaped duplicates and trailing data', () => {
  const json = JSON.stringify(request());
  for (const source of [json.replace('"version":1', '"version":1,"version":1'),
    json.replace('"version":1', '"version":1,"v\\u0065rsion":1'),
    json.replace('"container":"incoming"', '"container":"incoming","container":"other"'),
    `${json}null`, `${json}\n{}`, `[${json}]`, `\ufeff${json}`]) invalid(() => parseJobRequest(source));
});

test('queue parsing is strict about scalar types and integer version syntax', () => {
  for (const version of ['"1"', '1.0', '1e0', 'true', 'null', '2', '-1']) {
    invalid(() => parseJobRequest(JSON.stringify(request()).replace('"version":1', `"version":${version}`)));
  }
  for (const mutate of [value => { value.filename = 1; }, value => { value.input = null; },
    value => { value.output.prefix = false; }, value => { value.input.container = 1; },
    value => { value.output = []; }, value => { value.extra = 'unknown'; },
    value => { value.input.url = 'https://example.com?sig=secret'; },
    value => { value.output.sasUrl = 'https://example.com?sig=secret'; }, value => { value.options = {}; }]) {
    const value = request(); mutate(value); invalid(() => normalizeJobRequest(value));
  }
});

test('queue rejects malformed UTF-8 and enforces decoded-byte size before parsing', () => {
  const prefix = Buffer.from(JSON.stringify(request()).replace('営業', ''));
  invalid(() => parseJobRequest(Buffer.concat([prefix.subarray(0, 15), Buffer.from([0xc3, 0x28]), prefix.subarray(15)])));
  invalid(() => parseJobRequest(Buffer.alloc(MAX_JOB_MESSAGE_BYTES + 1, 32)));
  invalid(() => parseJobRequest(' '));
  invalid(() => parseJobRequest({ ...request() }));
  invalid(() => parseJobRequest(Buffer.from(JSON.stringify(request())).toString('base64')));
});

test('blob references allow only aliases and paths, never URLs, credentials or traversal', () => {
  for (const blobName of ['https://example.com/movie.mp4', 'movie.mp4?sig=secret', 'movie.mp4#fragment', '../movie.mp4', 'safe/../movie.mp4', 'safe\\movie.mp4', 'movie\u0000.mp4', '\ud800']) {
    const value = request(); value.input.blobName = blobName; invalid(() => normalizeJobRequest(value));
  }
  for (const prefix of ['../out', 'ok/./out', 'https://example.com', 'audio?sig=secret', 'audio\n']) {
    const value = request(); value.output.prefix = prefix; invalid(() => normalizeJobRequest(value));
  }
  for (const container of ['ab', 'AUDIO', '-audio', 'audio-', 'a--b', 'a'.repeat(64)]) {
    const value = request(); value.input.container = container; invalid(() => normalizeJobRequest(value));
  }
  for (const alias of ['UPPER', '', 'a-b', '1alias', 'https://example.com', 'a'.repeat(33)]) {
    const value = request(); value.input.storage = alias; invalid(() => normalizeJobRequest(value));
  }
  for (const badId of ['1-1-1-1-1', 'garbage', null, id + '\n']) assert.throws(() => normalizeJobId(badId), { code: 'INVALID_JOB_ID' });
});

test('filename and path length bounds prevent oversized or ambiguous metadata', () => {
  for (const filename of ['.', '..', 'a'.repeat(256), 'folder/']) invalid(() => safeFilename(filename));
  let value = request(); value.input.blobName = 'a'.repeat(1025); invalid(() => normalizeJobRequest(value));
  value = request(); value.output.prefix = 'a'.repeat(513); invalid(() => normalizeJobRequest(value));
});

test('job storage is opt-in and separate input/output aliases never serialize credentials', () => {
  assert.equal(readJobSettings({}), null);
  assert.equal(readJobSettings({ CONVERSION_STORAGE_CONNECTION_STRING: '  ', CONVERSION_INPUT_STORAGE_BAD: 'secret' }), null);
  const settings = readJobSettings({ CONVERSION_STORAGE_CONNECTION_STRING: ' control-secret ',
    CONVERSION_INPUT_STORAGE_MEDIA: ' input-secret ', CONVERSION_OUTPUT_STORAGE_EXPORTS: ' output-secret ', CONVERSION_INPUT_STORAGE_EMPTY: '' });
  assert.deepEqual([...settings.inputConnections], [['default', 'control-secret'], ['media', 'input-secret']]);
  assert.deepEqual([...settings.outputConnections], [['default', 'control-secret'], ['exports', 'output-secret']]);
  const value = request(); value.input.storage = 'media'; value.output.storage = 'exports';
  const json = JSON.stringify(normalizeJobRequest(value)); assert.ok(!json.includes('secret'));
  for (const suffix of ['DEFAULT', 'lower', '1BAD', 'A-B']) {
    assert.throws(() => readJobSettings({ CONVERSION_STORAGE_CONNECTION_STRING: 'secret', [`CONVERSION_INPUT_STORAGE_${suffix}`]: 'super-secret' }),
      error => !error.message.includes('super-secret') && !error.message.includes('secret'));
  }
});
