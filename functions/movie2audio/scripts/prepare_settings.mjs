import { writeFile } from 'node:fs/promises';
import { readJobSettings, queueBindingSettings } from '../src/storage-settings.js';

// Called by the deployment helper with a private temporary path. Secrets never
// appear in command arguments, stdout, or raw SDK/CLI diagnostics.
try {
  if (process.argv.length !== 3) throw new Error();
  const jobs = readJobSettings();
  const settings = Object.fromEntries(Object.entries(process.env).filter(([key]) => key.startsWith('CONVERSION_')
    && !key.startsWith('CONVERSION_QUEUE_CONNECTION_STRING')));
  Object.assign(settings, queueBindingSettings(jobs));
  const identityKeys = ['CONVERSION_STORAGE__blobServiceUri', 'CONVERSION_STORAGE__queueServiceUri', 'CONVERSION_STORAGE__clientId',
    'CONVERSION_QUEUE_CONNECTION_STRING__queueServiceUri', 'CONVERSION_QUEUE_CONNECTION_STRING__credential', 'CONVERSION_QUEUE_CONNECTION_STRING__clientId'];
  const deletions = typeof jobs?.control === 'object'
    ? ['CONVERSION_STORAGE_CONNECTION_STRING', 'CONVERSION_QUEUE_CONNECTION_STRING',
      ...(jobs.control.clientId ? [] : ['CONVERSION_STORAGE__clientId', 'CONVERSION_QUEUE_CONNECTION_STRING__clientId'])]
    : identityKeys;
  if (!jobs) deletions.push('CONVERSION_STORAGE_CONNECTION_STRING', 'CONVERSION_QUEUE_CONNECTION_STRING');
  for (const key of deletions) delete settings[key];
  await writeFile(process.argv[2], JSON.stringify({ settings, deletions }), { flag: 'wx', mode: 0o600 });
} catch { process.stderr.write('Storage configuration could not be prepared. Check the registered settings.\n'); process.exitCode = 1; }
