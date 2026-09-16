import { parseAllowedHosts } from './url.js';
import { parseOutputHosts } from './blob.js';
import { readJobSettings } from './job-store.js';

const MAX_BYTES = 2147483639;

export function createConfig(env = process.env) {
  function positive(name, fallback, max) {
    const text = env[name]?.trim();
    if (!text) return fallback;
    if (!/^\+?\d+$/.test(text)) throw new Error(`${name} must be a positive integer`);
    const value = Number(text);
    if (!Number.isSafeInteger(value) || value <= 0 || value > max) {
      throw new Error(`${name} must be between 1 and ${max}`);
    }
    return value;
  }
  const allowedHosts = parseAllowedHosts(env.CONVERSION_URL_ALLOWED_HOSTS ?? '');
  const outputAllowedHosts = parseOutputHosts(env.CONVERSION_OUTPUT_ALLOWED_HOSTS ?? '');
  const jobs = readJobSettings(env);
  return Object.freeze({
    limits: Object.freeze({
      maxInputBytes: positive('CONVERSION_MAX_INPUT_BYTES', 104857600, MAX_BYTES),
      maxOutputBytes: positive('CONVERSION_MAX_OUTPUT_BYTES', 104857600, MAX_BYTES),
      timeoutSeconds: positive('CONVERSION_TIMEOUT_SECONDS', 180, 210),
    }),
    allowedHosts,
    urlEnabled: allowedHosts.size > 0,
    outputAllowedHosts,
    outputStorageEnabled: outputAllowedHosts.size > 0,
    jobs,
    asyncEnabled: jobs !== null,
  });
}

export function publicSettings(config) {
  return { urlEnabled: config.urlEnabled, asyncEnabled: config.asyncEnabled, ...config.limits,
    outputFormat: 'm4a', audioCodec: 'aac', audioMode: 'copy' };
}
