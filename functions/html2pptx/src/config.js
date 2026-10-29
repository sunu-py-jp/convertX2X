function positive(env, name, fallback, max) {
  if (env[name] === undefined || env[name] === '') return fallback;
  const value = Number(env[name]);
  if (!Number.isSafeInteger(value) || value < 1 || value > max) throw new Error(`Invalid setting: ${name}`);
  return value;
}
export function configuration(env = process.env) {
  const mib = 1024 * 1024;
  return {
    maxInputBytes: positive(env, 'CONVERSION_MAX_INPUT_MB', 20, 100) * mib,
    maxUnpackedBytes: positive(env, 'CONVERSION_MAX_UNPACKED_MB', 100, 300) * mib,
    maxOutputBytes: positive(env, 'CONVERSION_MAX_OUTPUT_MB', 100, 300) * mib,
    maxFiles: positive(env, 'CONVERSION_MAX_PACKAGE_FILES', 500, 2000),
    maxSlides: positive(env, 'CONVERSION_MAX_SLIDES', 50, 100),
    maxDomNodes: positive(env, 'CONVERSION_MAX_DOM_NODES', 50000, 100000),
    maxDomDepth: positive(env, 'CONVERSION_MAX_DOM_DEPTH', 64, 128),
    maxCssBytes: positive(env, 'CONVERSION_MAX_CSS_BYTES', 2097152, 8388608),
    maxDimension: positive(env, 'CONVERSION_MAX_DIMENSION_PX', 4096, 8192),
    minDimension: 64,
    maxImagePixels: positive(env, 'CONVERSION_MAX_IMAGE_PIXELS', 20000000, 40000000),
    maxTotalImagePixels: positive(env, 'CONVERSION_MAX_TOTAL_IMAGE_PIXELS', 80000000, 160000000),
    maxShapes: positive(env, 'CONVERSION_MAX_SHAPES', 10000, 30000),
    timeoutMs: positive(env, 'CONVERSION_TIMEOUT_SECONDS', 180, 210) * 1000,
  };
}
