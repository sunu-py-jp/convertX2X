import assert from 'node:assert/strict';
import { copyFile, lstat, mkdir, mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import test from 'node:test';
import { ConversionError } from '../src/errors.js';
import { bundledFfmpegPlatform, extractAudio, loadBundledFfmpeg, prepareBundledFfmpeg, runProcess } from '../src/ffmpeg.js';
import { audioOptionsFromQuery, audioOutput, normalizeAudioOptions } from '../src/audio-options.js';

const FIXTURES = fileURLToPath(new URL('./fixtures/', import.meta.url));
const LIMITS = { maxInputBytes: 100 * 1024 * 1024, maxOutputBytes: 100 * 1024 * 1024, timeoutSeconds: 10 };

test('selects bundled executable names for each supported OS', () => {
  assert.deepEqual(bundledFfmpegPlatform('linux', 'x64'), { directory: 'linux-x86_64', suffix: '' });
  assert.deepEqual(bundledFfmpegPlatform('darwin', 'arm64'), { directory: 'macos-aarch64', suffix: '' });
  assert.deepEqual(bundledFfmpegPlatform('win32', 'x64'), { directory: 'windows-x86_64', suffix: '.exe' });
  assert.throws(() => bundledFfmpegPlatform('win32', 'arm64'), code('FFMPEG_UNAVAILABLE', 503));
});

async function workspace(t) {
  const directory = await mkdtemp(path.join(tmpdir(), 'movie2audio-test-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  return directory;
}

function deadline(t, milliseconds = 5000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(new ConversionError(504, 'CONVERSION_TIMEOUT',
    'Audio extraction exceeded the request time limit.')), milliseconds);
  t.after(() => clearTimeout(timer));
  return controller.signal;
}

function code(expected, status) {
  return error => {
    assert.equal(error.code, expected);
    if (status) assert.equal(error.status, status);
    return true;
  };
}

async function exists(target) {
  try { await lstat(target); return true; } catch (error) {
    if (error.code === 'ENOENT') return false;
    throw error;
  }
}

function topLevelBoxes(bytes) {
  const boxes = [];
  let offset = 0;
  while (offset < bytes.length) {
    assert.ok(bytes.length - offset >= 8, 'Complete MP4 box header');
    let size = bytes.readUInt32BE(offset);
    let header = 8;
    if (size === 1) {
      assert.ok(bytes.length - offset >= 16, 'Complete extended MP4 box header');
      size = Number(bytes.readBigUInt64BE(offset + 8));
      header = 16;
    } else if (size === 0) size = bytes.length - offset;
    assert.ok(Number.isSafeInteger(size) && size >= header && size <= bytes.length - offset,
      'Valid MP4 box size');
    boxes.push(bytes.toString('ascii', offset + 4, offset + 8));
    offset += size;
  }
  return boxes;
}

async function packetHashes(input, signal) {
  const runtime = await loadBundledFfmpeg();
  const result = await runProcess(runtime.ffprobe, ['-v', 'error', '-protocol_whitelist', 'file',
    '-select_streams', 'a:0', '-show_packets', '-show_data_hash', 'sha256',
    '-show_entries', 'packet=data_hash', '-of', 'json', input], { signal });
  assert.equal(result.exitCode, 0);
  return JSON.parse(result.stdout).packets.map(packet => packet.data_hash);
}

for (const container of ['mp4', 'mkv', 'avi', 'ts']) {
  test(`extracts AAC from ${container} with M4A metadata before audio`, async t => {
    const directory = await workspace(t);
    const input = path.join(FIXTURES, `aac-video.${container}`);
    const output = path.join(directory, 'audio.m4a');
    assert.deepEqual(await extractAudio(input, output, { limits: LIMITS, signal: deadline(t) }),
      { codec: 'aac', sampleRate: 48000, channels: 1 });
    const bytes = await readFile(output);
    assert.ok(bytes.length > 1000 && bytes.length < (await lstat(input)).size);
    const boxes = topLevelBoxes(bytes);
    assert.ok(boxes.includes('moov') && boxes.includes('mdat'));
    assert.ok(boxes.indexOf('moov') < boxes.indexOf('mdat'));
  });
}

test('preserves every compressed AAC packet without re-encoding', async t => {
  const directory = await workspace(t);
  const input = path.join(FIXTURES, 'aac-video.mp4');
  const output = path.join(directory, 'audio.m4a');
  const signal = deadline(t);
  await extractAudio(input, output, { limits: LIMITS, signal });
  const before = await packetHashes(input, signal);
  assert.equal(before.length, 48);
  assert.deepEqual(await packetHashes(output, signal), before);
});

for (const [fixture, expected] of [
  ['silent-video.mp4', 'NO_AUDIO_STREAM'],
  ['pcm-video.mkv', 'UNSUPPORTED_AUDIO_CODEC'],
  ['first-pcm-second-aac.mkv', 'UNSUPPORTED_AUDIO_CODEC'],
  ['audio-only.m4a', 'NO_VIDEO_STREAM'],
  ['audio-with-cover.m4a', 'NO_VIDEO_STREAM'],
]) {
  test(`rejects ${fixture} as ${expected}`, async t => {
    const directory = await workspace(t);
    const output = path.join(directory, 'audio.m4a');
    await assert.rejects(extractAudio(path.join(FIXTURES, fixture), output,
      { limits: LIMITS, signal: deadline(t) }), code(expected, 422));
    assert.equal(await exists(output), false);
  });
}

test('rejects empty, broken, truncated and local-file playlist inputs', async t => {
  const directory = await workspace(t);
  const fixture = await readFile(path.join(FIXTURES, 'aac-video.mp4'));
  for (const bytes of [Buffer.alloc(0), Buffer.from('not media'), fixture.subarray(0, 120),
    Buffer.from("ffconcat version 1.0\nfile '/etc/passwd'\n"),
    Buffer.from('#EXTM3U\n#EXTINF:1\nhttps://example.com/video.ts\n')]) {
    const input = path.join(directory, 'input');
    const output = path.join(directory, 'audio.m4a');
    await writeFile(input, bytes);
    await assert.rejects(extractAudio(input, output, { limits: LIMITS, signal: deadline(t) }),
      code('INVALID_MEDIA', 422));
    assert.equal(await exists(output), false);
  }
});

test('enforces byte limits and removes partial output', async t => {
  const directory = await workspace(t);
  const input = path.join(FIXTURES, 'aac-video.mp4');
  const output = path.join(directory, 'audio.m4a');
  await assert.rejects(extractAudio(input, output,
    { limits: { ...LIMITS, maxInputBytes: 100 }, signal: deadline(t) }), code('INPUT_TOO_LARGE', 413));
  await assert.rejects(extractAudio(input, output,
    { limits: { ...LIMITS, maxOutputBytes: 100 }, signal: deadline(t) }), code('OUTPUT_TOO_LARGE', 413));
  assert.equal(await exists(output), false);
});

test('rejects an aborted operation before creating output', async t => {
  const directory = await workspace(t);
  const output = path.join(directory, 'audio.m4a');
  const reason = new ConversionError(504, 'CONVERSION_TIMEOUT', 'Timed out.');
  await assert.rejects(extractAudio(path.join(FIXTURES, 'aac-video.mp4'), output,
    { limits: LIMITS, signal: AbortSignal.abort(reason) }), error => error === reason);
  assert.equal(await exists(output), false);
});

test('does not follow input symlinks or overwrite/delete existing outputs', async t => {
  const directory = await workspace(t);
  const input = path.join(FIXTURES, 'aac-video.mp4');
  const link = path.join(directory, 'input.mp4');
  const output = path.join(directory, 'audio.m4a');
  await symlink(input, link);
  await assert.rejects(extractAudio(link, output, { limits: LIMITS }), code('INVALID_MEDIA'));
  await writeFile(output, 'keep me');
  await assert.rejects(extractAudio(input, output, { limits: LIMITS }), code('STORAGE_ERROR'));
  assert.equal(await readFile(output, 'utf8'), 'keep me');
  const outputLink = path.join(directory, 'link.m4a');
  await symlink(output, outputLink);
  await assert.rejects(extractAudio(input, outputLink, { limits: LIMITS }), code('STORAGE_ERROR'));
  assert.equal(await readFile(outputLink, 'utf8'), 'keep me');
});

test('prepares reusable private executables with file-only protocols and the two allowed encoders', async t => {
  const runtime = await loadBundledFfmpeg();
  assert.equal(await loadBundledFfmpeg(), runtime);
  for (const target of [runtime.directory, runtime.ffmpeg, runtime.ffprobe]) {
    assert.equal((await lstat(target)).mode & 0o777, 0o700);
  }
  const signal = deadline(t);
  const protocols = await runProcess(runtime.ffmpeg, ['-hide_banner', '-protocols'], { signal });
  assert.equal(protocols.exitCode, 0);
  assert.equal(protocols.stdout, 'Supported file protocols:\nInput:\n  file\nOutput:\n  file\n');
  const encoders = await runProcess(runtime.ffmpeg, ['-hide_banner', '-encoders'], { signal });
  assert.equal(encoders.exitCode, 0);
  const names = encoders.stdout.split('\n').filter(line => /^ A[.A-Z]{5} [a-z]/.test(line))
    .map(line => line.trim().split(/\s+/)[1]);
  assert.deepEqual(names.sort(), ['aac', 'pcm_s16le']);
  const license = await runProcess(runtime.ffmpeg, ['-hide_banner', '-L'], { signal });
  assert.ok(license.stdout.includes('Lesser General Public'));
});

test('validates explicit audio modes, bounded profiles and repeated query options', () => {
  assert.deepEqual(normalizeAudioOptions(), { mode: 'copy', format: 'm4a', sampleRate: null, channels: null });
  assert.deepEqual(audioOutput({ mode: 'transcode', format: 'wav' }),
    { filename: 'audio.wav', contentType: 'audio/wav', codec: 'pcm_s16le', mode: 'transcode' });
  assert.deepEqual(audioOptionsFromQuery(new URLSearchParams('audioMode=transcode&audioFormat=wav&sampleRate=16000&channels=1')),
    { mode: 'transcode', format: 'wav', sampleRate: 16000, channels: 1 });
  for (const value of [{ mode: 'auto' }, { format: 'wav' }, { sampleRate: 16000 },
    { mode: 'transcode', channels: 9 }, { mode: 'transcode', sampleRate: '16000' },
    { mode: 'transcode', format: 'mp3' }, { mode: 'transcode', args: '-i /etc/passwd' }]) {
    assert.throws(() => normalizeAudioOptions(value), code('INVALID_AUDIO_OPTIONS', 400));
  }
  for (const text of ['audioMode=copy&audioMode=transcode', 'channels=-1', 'sampleRate=1e4', 'audioFormat=']) {
    assert.throws(() => audioOptionsFromQuery(new URLSearchParams(text)), code('INVALID_AUDIO_OPTIONS', 400));
  }
});

for (const fixture of ['aac-video.mp4', 'pcm-video.mkv', 'opus-video.mkv', 'mp3-video.mkv', 'stereo-pcm-video.mkv']) {
  for (const format of ['wav', 'm4a']) {
    test(`transcodes ${fixture} to ${format} with explicit 16 kHz mono audio`, async t => {
      const directory = await workspace(t);
      const output = path.join(directory, `audio.${format}`);
      assert.deepEqual(await extractAudio(path.join(FIXTURES, fixture), output, {
        limits: LIMITS, signal: deadline(t), options: { mode: 'transcode', format, sampleRate: 16000, channels: 1 },
      }), { codec: format === 'wav' ? 'pcm_s16le' : 'aac', sampleRate: 16000, channels: 1 });
      const bytes = await readFile(output);
      assert.ok(bytes.length > 1000);
      if (format === 'wav') {
        assert.equal(bytes.toString('ascii', 0, 4), 'RIFF');
        assert.equal(bytes.toString('ascii', 8, 12), 'WAVE');
        assert.ok(bytes.length < 40000, 'One second of 16kHz mono PCM fits its expected size');
      } else {
        const boxes = topLevelBoxes(bytes);
        assert.ok(boxes.indexOf('moov') < boxes.indexOf('mdat'));
      }
    });
  }
}

test('transcode preserves stereo when no channel normalization is requested', async t => {
  const directory = await workspace(t);
  assert.deepEqual(await extractAudio(path.join(FIXTURES, 'stereo-pcm-video.mkv'), path.join(directory, 'audio.wav'), {
    limits: LIMITS, signal: deadline(t), options: { mode: 'transcode', format: 'wav' },
  }), { codec: 'pcm_s16le', sampleRate: 48000, channels: 2 });
});

test('transcodes the first PCM track rather than selecting a later AAC track', async t => {
  const directory = await workspace(t);
  const input = path.join(FIXTURES, 'first-pcm-second-aac.mkv');
  const output = path.join(directory, 'audio.wav');
  await extractAudio(input, output, { limits: LIMITS, signal: deadline(t), options: { mode: 'transcode', format: 'wav' } });
  const runtime = await loadBundledFfmpeg();
  const expected = path.join(directory, 'expected.wav');
  const copied = await runProcess(runtime.ffmpeg, ['-nostdin', '-hide_banner', '-loglevel', 'error',
    '-i', input, '-map', '0:a:0', '-c:a', 'copy', '-map_metadata', '-1', '-map_chapters', '-1', '-f', 'wav', expected],
  { signal: deadline(t) });
  assert.equal(copied.exitCode, 0);
  const pcm = bytes => { const start = bytes.indexOf(Buffer.from('data')); return bytes.subarray(start + 8); };
  assert.deepEqual(pcm(await readFile(output)), pcm(await readFile(expected)));
});

test('accepts a silent audio track and still rejects a missing track in transcode mode', async t => {
  const directory = await workspace(t);
  const options = { mode: 'transcode', format: 'wav' };
  await extractAudio(path.join(FIXTURES, 'silent-pcm-video.mkv'), path.join(directory, 'audio.wav'),
    { limits: LIMITS, signal: deadline(t), options });
  await assert.rejects(extractAudio(path.join(FIXTURES, 'silent-video.mp4'), path.join(directory, 'none.wav'),
    { limits: LIMITS, signal: deadline(t), options }), code('NO_AUDIO_STREAM', 422));
});

test('removes transcode output after exceeding its byte limit', async t => {
  const directory = await workspace(t);
  const output = path.join(directory, 'audio.wav');
  await assert.rejects(extractAudio(path.join(FIXTURES, 'pcm-video.mkv'), output, {
    limits: { ...LIMITS, maxOutputBytes: 100 }, signal: deadline(t), options: { mode: 'transcode', format: 'wav' },
  }), code('OUTPUT_TOO_LARGE', 413));
  assert.equal(await exists(output), false);
});

test('rejects a bundle whose SHA256 does not match', async t => {
  const directory = await workspace(t);
  const platform = process.platform === 'darwin' ? 'macos-aarch64' : 'linux-x86_64';
  const resources = path.join(directory, platform);
  await mkdir(resources);
  await copyFile(path.join(FIXTURES, 'aac-video.mp4'), path.join(resources, 'ffmpeg'));
  await writeFile(path.join(resources, 'ffmpeg.sha256'), `${'0'.repeat(64)}\n`);
  await assert.rejects(prepareBundledFfmpeg(directory), code('FFMPEG_UNAVAILABLE', 503));
});

test('does not pass environment secrets to child processes', async t => {
  const result = await runProcess('/usr/bin/env', [], { signal: deadline(t) });
  assert.equal(result.exitCode, 0);
  assert.deepEqual(result.stdout.trim().split('\n').sort(), ['LANG=C', 'LC_ALL=C']);
});

for (const stream of ['stdout', 'stderr']) {
  test(`terminates processes that exceed the ${stream} bound`, async t => {
    await assert.rejects(runProcess(process.execPath, ['-e',
      `setInterval(() => process.${stream}.write('x'.repeat(65536)), 1)`],
    { signal: deadline(t) }), code('INVALID_MEDIA', 422));
  });
}

test('terminates a process as its output exceeds the file bound', async t => {
  const directory = await workspace(t);
  const output = path.join(directory, 'audio.m4a');
  await assert.rejects(runProcess(process.execPath, ['-e',
    "const fs = require('node:fs'); setInterval(() => fs.appendFileSync(process.argv[1], Buffer.alloc(4096)), 1)",
    output], { outputPath: output, maxOutputBytes: 100, signal: deadline(t) }), code('OUTPUT_TOO_LARGE', 413));
});

test('abort terminates the process group including descendants', async t => {
  const directory = await workspace(t);
  const pidFile = path.join(directory, 'child.pid');
  const reason = new ConversionError(504, 'CONVERSION_TIMEOUT', 'Timed out.');
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(reason), 10000);
  t.after(() => { clearTimeout(timer); controller.abort(reason); });
  const rejection = assert.rejects(runProcess(process.execPath, ['-e',
    "const child = require('node:child_process').spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)']);"
      + "require('node:fs').writeFileSync(process.argv[1], String(child.pid)); setInterval(() => {}, 1000)",
    pidFile], { signal: controller.signal }), error => error === reason);
  // Wait for the descendant to exist before cancellation, including under x64 emulation.
  for (let attempt = 0; attempt < 250 && !(await exists(pidFile)); attempt++) await delay(20);
  assert.ok(await exists(pidFile), 'The descendant started before cancellation');
  const start = Date.now();
  controller.abort(reason);
  await rejection;
  assert.ok(Date.now() - start < 3000);
  const pid = Number(await readFile(pidFile, 'utf8'));
  let alive = true;
  for (let attempt = 0; attempt < 100; attempt++) {
    try { process.kill(pid, 0); } catch (error) { if (error.code === 'ESRCH') { alive = false; break; } throw error; }
    if (process.platform === 'linux') {
      const stat = await readFile(`/proc/${pid}/stat`, 'utf8').catch(() => '');
      if (!stat || stat.slice(stat.lastIndexOf(')') + 2).startsWith('Z ')) { alive = false; break; }
    }
    await delay(10);
  }
  assert.equal(alive, false, 'The descendant cannot survive a cancelled conversion');
});

test('maps process startup failures to FFMPEG_UNAVAILABLE without raw diagnostics', async t => {
  await assert.rejects(runProcess('/does/not/exist', [], { signal: deadline(t) }),
    code('FFMPEG_UNAVAILABLE', 503));
});
