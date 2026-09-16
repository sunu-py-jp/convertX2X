import { spawn } from 'node:child_process';
import { createHash, timingSafeEqual } from 'node:crypto';
import { createReadStream, createWriteStream, lstatSync, rmSync } from 'node:fs';
import { chmod, lstat, mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { Transform } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { fileURLToPath } from 'node:url';
import { ConversionError, throwIfAborted } from './errors.js';

const RESOURCE_DIRECTORY = fileURLToPath(new URL('../resources/ffmpeg/', import.meta.url));
const STDOUT_LIMIT = 256 * 1024;
const STDERR_LIMIT = 32 * 1024;
const INPUT_ARGUMENTS = Object.freeze([
  '-max_alloc', '67108864', '-protocol_whitelist', 'file',
  '-format_whitelist', 'mov,matroska,webm,avi,mpegts,flv',
  '-probesize', '10485760', '-analyzeduration', '10000000', '-max_streams', '32',
]);
let runtimePromise;

const invalidMedia = () => new ConversionError(422, 'INVALID_MEDIA',
  'The video is unsupported, empty, damaged or could not be extracted.');
const unavailable = () => new ConversionError(503, 'FFMPEG_UNAVAILABLE',
  'The bundled FFmpeg runtime could not be prepared or started.');
const outputTooLarge = () => new ConversionError(413, 'OUTPUT_TOO_LARGE',
  'The extracted audio exceeds the configured output limit.');
const storageError = () => new ConversionError(500, 'STORAGE_ERROR',
  'The temporary media file could not be inspected or written.');

function platformName() {
  if (process.platform === 'linux' && process.arch === 'x64') return 'linux-x86_64';
  if (process.platform === 'darwin' && process.arch === 'arm64') return 'macos-aarch64';
  throw new ConversionError(503, 'FFMPEG_UNAVAILABLE',
    'Bundled FFmpeg supports Linux x64 and macOS Apple Silicon.');
}

/** Only trusted, packaged resources are passed here; HTTP input never selects an executable. */
export async function prepareBundledFfmpeg(resourceDirectory = RESOURCE_DIRECTORY) {
  const platform = platformName();
  let directory;
  try {
    directory = await mkdtemp(path.join(tmpdir(), 'movie2audio-runtime-'));
    await chmod(directory, 0o700);
    for (const name of ['ffmpeg', 'ffprobe']) {
      const source = path.join(resourceDirectory, platform, name);
      const expected = (await readFile(`${source}.sha256`, 'ascii')).trim();
      if (!/^[0-9a-f]{64}$/.test(expected) || !(await lstat(source)).isFile()) throw unavailable();
      const digest = createHash('sha256');
      await pipeline(createReadStream(source), new Transform({
        transform(chunk, encoding, callback) { digest.update(chunk); callback(null, chunk); },
      }), createWriteStream(path.join(directory, name), { flags: 'wx', mode: 0o700 }));
      if (!timingSafeEqual(Buffer.from(expected, 'hex'), digest.digest())) throw unavailable();
      await chmod(path.join(directory, name), 0o700);
    }
    const runtime = Object.freeze({ directory, ffmpeg: path.join(directory, 'ffmpeg'),
      ffprobe: path.join(directory, 'ffprobe') });
    return runtime;
  } catch {
    if (directory) await rm(directory, { recursive: true, force: true }).catch(() => {});
    throw unavailable();
  }
}

/** Reuse the owner-only copy. Extraction works when the deployed package is read-only. */
export async function loadBundledFfmpeg() {
  if (!runtimePromise) {
    runtimePromise = prepareBundledFfmpeg().then(runtime => {
      process.once('exit', () => {
        try { rmSync(runtime.directory, { recursive: true, force: true }); } catch { /* Best effort on exit. */ }
      });
      return runtime;
    }).catch(error => { runtimePromise = undefined; throw error; });
  }
  const runtime = await runtimePromise;
  try {
    if (!(await lstat(runtime.directory)).isDirectory()) throw unavailable();
    for (const executable of [runtime.ffmpeg, runtime.ffprobe]) {
      const info = await lstat(executable);
      if (!info.isFile() || (info.mode & 0o777) !== 0o700) throw unavailable();
    }
  } catch { throw unavailable(); }
  return runtime;
}

function checkOutput(outputPath, maxOutputBytes) {
  if (!outputPath) return;
  try {
    const info = lstatSync(outputPath);
    if (!info.isFile()) throw storageError();
    if (info.size > maxOutputBytes) throw outputTooLarge();
  } catch (error) {
    if (error.code === 'ENOENT') return;
    if (error instanceof ConversionError) throw error;
    throw storageError();
  }
}

/** Fixed argument arrays, no shell, no inherited secrets, bounded logs and process lifetime. */
export async function runProcess(executable, args, { cwd, outputPath, maxOutputBytes, signal } = {}) {
  throwIfAborted(signal);
  return new Promise((resolve, reject) => {
    let child;
    try {
      child = spawn(executable, args, { cwd, shell: false, detached: true,
        env: { LANG: 'C', LC_ALL: 'C' }, stdio: ['ignore', 'pipe', 'pipe'] });
    } catch { reject(unavailable()); return; }
    let failure;
    let stdoutBytes = 0;
    let stderrBytes = 0;
    const stdout = [];
    const killGroup = () => {
      // Both supported platforms are POSIX. A private process group also covers descendants.
      if (child.pid) {
        try { process.kill(-child.pid, 'SIGKILL'); } catch { /* Already exited. */ }
      }
      try { child.kill('SIGKILL'); } catch { /* Already exited. */ }
    };
    const fail = error => { failure ??= error; killGroup(); };
    const onAbort = () => fail(signal.reason ?? new ConversionError(503,
      'CONVERSION_INTERRUPTED', 'Audio extraction was interrupted.'));
    const monitor = setInterval(() => {
      try { checkOutput(outputPath, maxOutputBytes); } catch (error) { fail(error); }
    }, 25);
    child.stdout.on('data', chunk => {
      stdoutBytes += chunk.length;
      if (stdoutBytes > STDOUT_LIMIT) fail(invalidMedia());
      else stdout.push(chunk);
    });
    child.stderr.on('data', chunk => {
      stderrBytes += chunk.length;
      if (stderrBytes > STDERR_LIMIT) fail(invalidMedia());
    });
    child.stdout.on('error', () => fail(invalidMedia()));
    child.stderr.on('error', () => fail(invalidMedia()));
    child.on('error', () => fail(unavailable()));
    // Kill any remaining descendants immediately, so inherited stdio cannot delay close.
    child.on('exit', killGroup);
    child.on('close', exitCode => {
      clearInterval(monitor);
      signal?.removeEventListener('abort', onAbort);
      killGroup();
      try { checkOutput(outputPath, maxOutputBytes); } catch (error) { failure ??= error; }
      if (failure) reject(failure);
      else resolve({ exitCode, stdout: Buffer.concat(stdout).toString('utf8') });
    });
    signal?.addEventListener('abort', onAbort, { once: true });
    if (signal?.aborted) onAbort();
  });
}

async function validatePaths(inputPath, outputPath, limits) {
  try {
    const input = await lstat(inputPath);
    if (!input.isFile() || input.size === 0) throw invalidMedia();
    if (input.size > limits.maxInputBytes) throw new ConversionError(413, 'INPUT_TOO_LARGE',
      'The video exceeds the configured input limit.');
    if (inputPath === outputPath || !(await lstat(path.dirname(outputPath))).isDirectory()) throw storageError();
    try { await lstat(outputPath); } catch (error) {
      if (error.code === 'ENOENT') return;
      throw error;
    }
    throw new ConversionError(500, 'STORAGE_ERROR', 'A fresh temporary output path is required.');
  } catch (error) {
    if (error instanceof ConversionError) throw error;
    throw storageError();
  }
}

async function probe(runtime, inputPath, requireVideo, signal) {
  const result = await runProcess(runtime.ffprobe, ['-hide_banner', '-loglevel', 'error',
    ...INPUT_ARGUMENTS, '-show_entries',
    'stream=index,codec_type,codec_name,sample_rate,channels,nb_frames:stream_disposition=attached_pic:format=format_name',
    '-of', 'json', '-i', inputPath], { cwd: path.dirname(inputPath), signal });
  if (result.exitCode !== 0) throw invalidMedia();
  let streams;
  try { streams = JSON.parse(result.stdout).streams; } catch { throw invalidMedia(); }
  if (!Array.isArray(streams)) throw invalidMedia();
  const video = streams.some(stream => stream.codec_type === 'video'
    && Number(stream.disposition?.attached_pic ?? 0) === 0);
  const firstAudio = streams.find(stream => stream.codec_type === 'audio');
  if (requireVideo && !video) throw new ConversionError(422, 'NO_VIDEO_STREAM',
    'The input must contain a video stream; cover artwork does not count as video.');
  if (!firstAudio) throw new ConversionError(422, 'NO_AUDIO_STREAM',
    'The video does not contain an audio stream.');
  if (firstAudio.codec_name !== 'aac') throw new ConversionError(422, 'UNSUPPORTED_AUDIO_CODEC',
    'The first audio stream must use AAC. Audio is not re-encoded.');
  const index = Number(firstAudio.index);
  const sampleRate = Number(firstAudio.sample_rate);
  const channels = Number(firstAudio.channels);
  if (!Number.isInteger(index) || index < 0 || !Number.isInteger(sampleRate) || sampleRate <= 0
    || !Number.isInteger(channels) || channels <= 0) throw invalidMedia();
  return { index, audio: { codec: 'aac', sampleRate, channels }, frames: Number(firstAudio.nb_frames ?? -1) };
}

/** Finish and validate a seekable M4A file; AAC packets are never re-encoded. */
export async function extractAudio(inputPath, outputPath, { limits, signal } = {}) {
  throwIfAborted(signal);
  inputPath = path.resolve(inputPath);
  outputPath = path.resolve(outputPath);
  await validatePaths(inputPath, outputPath, limits);
  const timerController = new AbortController();
  const timer = setTimeout(() => timerController.abort(new ConversionError(504, 'CONVERSION_TIMEOUT',
    'Audio extraction exceeded the request time limit.')), limits.timeoutSeconds * 1000);
  const extractionSignal = signal ? AbortSignal.any([signal, timerController.signal]) : timerController.signal;
  let ownsOutput = false;
  let success = false;
  try {
    const runtime = await loadBundledFfmpeg();
    throwIfAborted(extractionSignal);
    const source = await probe(runtime, inputPath, true, extractionSignal);
    ownsOutput = true;
    // MOV external file references stay disabled by the pinned demuxer's defaults.
    // MOV-only options cannot be passed here because they break the other demuxers.
    const result = await runProcess(runtime.ffmpeg, ['-nostdin', '-hide_banner', '-loglevel', 'error',
      '-xerror', '-n', ...INPUT_ARGUMENTS, '-i', inputPath, '-map', `0:${source.index}`,
      '-c:a', 'copy', '-vn', '-sn', '-dn', '-map_metadata', '-1', '-map_chapters', '-1',
      '-movflags', '+faststart', '-f', 'ipod', outputPath],
    { cwd: path.dirname(inputPath), outputPath, maxOutputBytes: limits.maxOutputBytes, signal: extractionSignal });
    if (result.exitCode !== 0) throw invalidMedia();
    const output = await lstat(outputPath);
    if (!output.isFile() || output.size === 0) throw invalidMedia();
    if (output.size > limits.maxOutputBytes) throw outputTooLarge();
    const extracted = await probe(runtime, outputPath, false, extractionSignal);
    if (!(extracted.frames > 0) || extracted.audio.sampleRate !== source.audio.sampleRate
      || extracted.audio.channels !== source.audio.channels) throw invalidMedia();
    throwIfAborted(extractionSignal);
    success = true;
    return extracted.audio;
  } catch (error) {
    if (error instanceof ConversionError || extractionSignal.aborted) throw error;
    throw storageError();
  } finally {
    clearTimeout(timer);
    if (ownsOutput && !success) await rm(outputPath, { force: true }).catch(() => {});
  }
}
