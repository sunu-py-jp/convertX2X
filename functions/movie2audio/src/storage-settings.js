// Registered endpoints are administrator settings, never producer-supplied URLs.
function text(env, name) {
  const value = env[name];
  if (value == null) return '';
  if (typeof value !== 'string') throw new Error('A storage setting is invalid.');
  return value.trim();
}
function endpoint(value) {
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || url.pathname !== '/') throw 0;
    return url.origin;
  } catch { throw new Error('Identity storage endpoints must be HTTPS service origins without credentials.'); }
}
function identity(env, prefix, queue = false) {
  const blob = text(env, `${prefix}__blobServiceUri`);
  const queueUri = text(env, `${prefix}__queueServiceUri`);
  const clientId = text(env, `${prefix}__clientId`);
  if (!blob && !queueUri && !clientId) return null;
  if ((queue && !queueUri) || (!queue && !blob) || (clientId && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(clientId))) {
    throw new Error('An identity storage setting is incomplete or invalid.');
  }
  return Object.freeze({ ...(blob ? { blobServiceUri: endpoint(blob) } : {}),
    ...(queueUri ? { queueServiceUri: endpoint(queueUri) } : {}), ...(clientId ? { clientId } : {}) });
}
function connection(env, name, identityPrefix, queue = false) {
  const sharedKey = text(env, name);
  const managed = identity(env, identityPrefix, queue);
  if (sharedKey && managed) throw new Error('Choose connection string or identity settings for each storage registration.');
  return sharedKey || managed;
}
function days(env, name) {
  const value = text(env, name) || '0';
  if (!/^\d+$/.test(value) || !Number.isSafeInteger(+value) || +value > 36500) throw new Error(`${name} must be between 0 and 36500.`);
  return +value;
}
export function readJobSettings(env = process.env) {
  const connectionString = text(env, 'CONVERSION_STORAGE_CONNECTION_STRING');
  const control = connection(env, 'CONVERSION_STORAGE_CONNECTION_STRING', 'CONVERSION_STORAGE', true);
  if (!control) return null;
  if (typeof control !== 'string' && !control.blobServiceUri) throw new Error('Both control Blob and Queue service endpoints are required.');
  const read = prefix => {
    const registrations = new Map([['default', control]]);
    const aliases = new Set();
    for (const [name, value] of Object.entries(env)) {
      if (!name.startsWith(prefix) || value == null || value === '') continue;
      const rest = name.slice(prefix.length);
      const match = /^([A-Z][A-Z0-9_]{0,31}?)(?:__(blobServiceUri|clientId))?$/.exec(rest);
      if (!match || match[1] === 'DEFAULT') throw new Error('A registered storage alias setting is invalid.');
      aliases.add(match[1]);
    }
    for (const alias of aliases) {
      const value = connection(env, `${prefix}${alias}`, `${prefix}${alias}`);
      if (value) registrations.set(alias.toLowerCase(), value);
    }
    return registrations;
  };
  const notifications = new Map();
  const aliases = new Set();
  for (const [name, value] of Object.entries(env)) {
    if (!name.startsWith('CONVERSION_RESULT_QUEUE_') || value == null || value === '') continue;
    const match = /^CONVERSION_RESULT_QUEUE_([A-Z][A-Z0-9_]{0,31}?)__(queueName|connectionString|queueServiceUri|clientId)$/.exec(name);
    if (!match) throw new Error('A result queue registration is invalid.');
    aliases.add(match[1]);
  }
  for (const alias of aliases) {
    const prefix = `CONVERSION_RESULT_QUEUE_${alias}`;
    const queueName = text(env, `${prefix}__queueName`);
    if (!/^[a-z0-9](?:[a-z0-9]|-(?!-)){1,61}[a-z0-9]$/.test(queueName)) throw new Error('A registered result queue name is invalid.');
    // Result events use a different contract from work messages. Prevent a
    // routing loop into this function's work or poison queue.
    if (['movie2audio-jobs', 'movie2audio-jobs-poison'].includes(queueName)) {
      throw new Error('Result notifications must use a separate queue.');
    }
    const config = connection(env, `${prefix}__connectionString`, prefix, true);
    if (!config) throw new Error('A registered result queue connection is missing.');
    notifications.set(alias.toLowerCase(), { connection: config, queueName });
  }
  const create = text(env, 'CONVERSION_CREATE_RESOURCES') || 'true';
  if (!['true', 'false'].includes(create)) throw new Error('CONVERSION_CREATE_RESOURCES must be true or false.');
  const resultRetentionDays = days(env, 'CONVERSION_RESULT_RETENTION_DAYS');
  const stateRetentionDays = days(env, 'CONVERSION_STATE_RETENTION_DAYS');
  if (stateRetentionDays && (!resultRetentionDays || stateRetentionDays <= resultRetentionDays)) {
    throw new Error('State retention requires enabled result retention and must be longer than result retention.');
  }
  return { connectionString, control, inputConnections: read('CONVERSION_INPUT_STORAGE_'),
    outputConnections: read('CONVERSION_OUTPUT_STORAGE_'), notifications, createResources: create === 'true',
    resultRetentionDays, stateRetentionDays };
}

// The binding host reads these before starting. Use the same preparation in the
// local launcher and Azure deployment settings; mutation in the worker is too late.
export function queueBindingSettings(settings) {
  if (!settings) return {};
  const value = settings.control;
  return typeof value === 'string' ? { CONVERSION_QUEUE_CONNECTION_STRING: value } : {
    CONVERSION_QUEUE_CONNECTION_STRING__queueServiceUri: value.queueServiceUri,
    CONVERSION_QUEUE_CONNECTION_STRING__credential: 'managedidentity',
    ...(value.clientId ? { CONVERSION_QUEUE_CONNECTION_STRING__clientId: value.clientId } : {}) };
}
