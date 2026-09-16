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
  for (const version of ['"1"', '1.0', '1e0', 'true', 'null', '3', '-1']) {
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

test('version 2 retains strict parsing and adds bounded metadata, expected ETag, audio options and registered notifications', () => {
  const value = { ...request(), version: 2, input: { ...request().input, expectedETag: '"revision-1"' },
    metadata: { revision: '2', correlationId: 'req-123' }, notification: { queue: 'results' },
    options: { mode: 'transcode', format: 'wav', sampleRate: 16000, channels: 1 } };
  const parsed = parseJobRequest(JSON.stringify(value));
  assert.equal(parsed.input.expectedETag, '"revision-1"'); assert.equal(parsed.options.channels, 1);
  assert.deepEqual(Object.keys(parsed.metadata), ['correlationId', 'revision']);
  for (const mutate of [v => { v.version = 1; }, v => { v.notification.queue = 'https://example.com'; },
    v => { v.input.expectedETag = '*'; }, v => { v.metadata.revision = 2; },
    v => { v.metadata = Object.fromEntries(Array.from({ length: 17 }, (_, i) => [`key${i}`, 'x'])); },
    v => { v.metadata = { ['k'.repeat(65)]: 'x' }; }, v => { v.metadata = { key: 'x'.repeat(513) }; },
    v => { v.metadata = { key: 'line\nbreak' }; }, v => { v.options.extra = '-i https://evil'; },
    v => { v.options.channels = 9; }, v => { v.notification.extra = 'secret'; }]) {
    const copy = structuredClone(value); mutate(copy); invalid(() => normalizeJobRequest(copy));
  }
  invalid(() => parseJobRequest(JSON.stringify(value).replace('"revision":"2"', '"revision":"2","revision":"3"')));
  invalid(() => parseJobRequest(JSON.stringify(value).replace('"sampleRate":16000', '"sampleRate":16000.0')));
  value.metadata = Object.fromEntries(Array.from({ length: 16 }, (_, i) => [`key${i}`, '漢'.repeat(512)]));
  invalid(() => normalizeJobRequest(value));
});

test('identity settings cover independent registered storage roles, queue destinations and opt-in retention', () => {
  const env = { CONVERSION_STORAGE__blobServiceUri: 'https://control.blob.core.windows.net',
    CONVERSION_STORAGE__queueServiceUri: 'https://control.queue.core.windows.net',
    CONVERSION_INPUT_STORAGE_MEDIA__blobServiceUri: 'https://media.blob.core.windows.net',
    CONVERSION_OUTPUT_STORAGE_EXPORTS: 'registered-secret',
    CONVERSION_RESULT_QUEUE_EVENTS__queueName: 'results',
    CONVERSION_RESULT_QUEUE_EVENTS__queueServiceUri: 'https://events.queue.core.windows.net',
    CONVERSION_CREATE_RESOURCES: 'false', CONVERSION_RESULT_RETENTION_DAYS: '7', CONVERSION_STATE_RETENTION_DAYS: '30' };
  const value = readJobSettings(env);
  assert.equal(value.control.blobServiceUri, env.CONVERSION_STORAGE__blobServiceUri);
  assert.equal(value.inputConnections.get('media').blobServiceUri, env.CONVERSION_INPUT_STORAGE_MEDIA__blobServiceUri);
  assert.equal(value.outputConnections.get('exports'), 'registered-secret');
  assert.equal(value.notifications.get('events').queueName, 'results'); assert.equal(value.createResources, false);
  assert.equal(value.resultRetentionDays, 7); assert.equal(value.stateRetentionDays, 30);
  for (const change of [{ CONVERSION_STORAGE_CONNECTION_STRING: 'secret' }, { CONVERSION_STORAGE__blobServiceUri: '' },
    { CONVERSION_STORAGE__queueServiceUri: 'http://private.example' }, { CONVERSION_STORAGE__queueServiceUri: 'https://example.com?sig=secret' },
    { CONVERSION_RESULT_QUEUE_EVENTS__queueName: '../results' }, { CONVERSION_CREATE_RESOURCES: 'yes' },
    { CONVERSION_STATE_RETENTION_DAYS: '7' }, { CONVERSION_RESULT_RETENTION_DAYS: '0' },
    { CONVERSION_INPUT_STORAGE_MEDIA: 'also-secret' }]) {
    assert.throws(() => readJobSettings({ ...env, ...change }), error => !error.message.includes('secret'));
  }
});


test('metadata preserves Unicode and prototype-like keys as plain bounded data', () => {
  const value = { ...request(), version: 2, metadata: JSON.parse('{"版":"第2版","__proto__":"opaque","constructor":"opaque"}') };
  const parsed = parseJobRequest(JSON.stringify(value));
  assert.equal(parsed.metadata['版'], '第2版'); assert.equal(parsed.metadata.__proto__, 'opaque');
  assert.equal(Object.getPrototypeOf(parsed.metadata), Object.prototype);
  assert.equal({}.opaque, undefined);
  value.metadata = { ' ': 'empty' }; invalid(() => normalizeJobRequest(value));
});


test('result registrations cannot route terminal events back to work or poison queues', () => {
  for (const name of [QUEUE_NAME, `${QUEUE_NAME}-poison`]) {
    assert.throws(() => readJobSettings({ CONVERSION_STORAGE_CONNECTION_STRING: 'private-secret',
      CONVERSION_RESULT_QUEUE_EVENTS__connectionString: 'private-secret', CONVERSION_RESULT_QUEUE_EVENTS__queueName: name }),
    error => error.message.includes('separate queue') && !error.message.includes('private-secret'));
  }
});
