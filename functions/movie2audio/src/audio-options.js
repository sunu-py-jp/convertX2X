import { ConversionError } from './errors.js';

const RATES = new Set([8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000]);
const invalid = () => new ConversionError(400, 'INVALID_AUDIO_OPTIONS',
  'Use copy with M4A and unchanged audio, or transcode with M4A/WAV and supported sample rate/channels.');

/** A bounded public contract; clients never supply encoder names or FFmpeg arguments. */
export function normalizeAudioOptions(value) {
  if (value == null) value = {};
  if (typeof value !== 'object' || Array.isArray(value)
    || ![Object.prototype, null].includes(Object.getPrototypeOf(value))
    || Object.keys(value).some(key => !['mode', 'format', 'sampleRate', 'channels'].includes(key))) throw invalid();
  const mode = value.mode ?? 'copy';
  const format = value.format ?? 'm4a';
  const sampleRate = value.sampleRate ?? null;
  const channels = value.channels ?? null;
  if (!['copy', 'transcode'].includes(mode) || !['m4a', 'wav'].includes(format)
    || (sampleRate !== null && (!Number.isInteger(sampleRate) || !RATES.has(sampleRate)))
    || (channels !== null && (!Number.isInteger(channels) || channels < 1 || channels > 8))
    || (mode === 'copy' && (format !== 'm4a' || sampleRate !== null || channels !== null))) throw invalid();
  return { mode, format, sampleRate, channels };
}

export function audioOutput(value) {
  const options = normalizeAudioOptions(value);
  return options.format === 'wav'
    ? { filename: 'audio.wav', contentType: 'audio/wav', codec: 'pcm_s16le', mode: options.mode }
    : { filename: 'audio.m4a', contentType: 'audio/mp4', codec: 'aac', mode: options.mode };
}

export function audioOptionsFromQuery(query) {
  const options = {};
  for (const [field, key] of [['audioMode', 'mode'], ['audioFormat', 'format'],
    ['sampleRate', 'sampleRate'], ['channels', 'channels']]) {
    const values = query.getAll(field);
    if (values.length > 1) throw invalid();
    if (!values.length) continue;
    if (key === 'sampleRate' || key === 'channels') {
      if (!/^[0-9]+$/.test(values[0])) throw invalid();
      options[key] = Number(values[0]);
    } else options[key] = values[0];
  }
  return normalizeAudioOptions(options);
}
