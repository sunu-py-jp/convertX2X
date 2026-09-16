import azureFunctions from '@azure/functions';
import { createConfig } from './config.js';
import { createHandlers } from './handlers.js';
import { createPlayground } from './playground.js';
import { createAdmission } from './admission.js';
import { createJobStore } from './job-store.js';
import { createJobService } from './jobs.js';
import { QUEUE_NAME } from './job-request.js';

const { app } = azureFunctions;
app.setup({ enableHttpStream: true });
const config = createConfig();
const admission = createAdmission();
const jobs = config.asyncEnabled ? createJobService(config,
  { store: createJobStore(config.jobs, { limits: config.limits }), admission }) : undefined;
const handlers = createHandlers(config, { jobs, admission });
const playground = createPlayground(config);
app.http('ConvertHttp', { methods: ['POST'], authLevel: 'function', route: 'convert', handler: handlers.convert });
app.http('ConvertUrl', { methods: ['POST'], authLevel: 'function', route: 'convert-url', handler: handlers.convertUrl });
app.http('ConvertToBlob', { methods: ['POST'], authLevel: 'function', route: 'convert-to-blob', handler: handlers.convertToBlob });
app.http('SubmitConversion', { methods: ['POST'], authLevel: 'function', route: 'jobs', handler: handlers.submit });
app.http('SubmitUrlConversion', { methods: ['POST'], authLevel: 'function', route: 'jobs-url', handler: handlers.submitUrl });
app.http('GetConversion', { methods: ['GET'], authLevel: 'function', route: 'jobs/{id}', handler: handlers.status });
app.http('DownloadConversion', { methods: ['GET'], authLevel: 'function', route: 'jobs/{id}/result', handler: handlers.result });

// An unconfigured app has no Queue bindings, so synchronous APIs need no Queue connection.
if (jobs) {
  for (const [name, queueName, operation] of [
    ['ProcessConversion', QUEUE_NAME, 'process'],
    ['PoisonConversion', `${QUEUE_NAME}-poison`, 'poison'],
  ]) {
    app.storageQueue(name, { queueName, connection: 'CONVERSION_STORAGE_CONNECTION_STRING', dataType: 'binary',
      handler: async (message, context) => {
        try { await jobs[operation](message); }
        catch {
          // SDK exceptions and producer JSON can contain credentials; keep host diagnostics fixed.
          context.warn('Queue processing failed; invocation=' + context.invocationId);
          throw new Error('Queue processing failed; the message will follow the retry policy.');
        }
      } });
  }
}
app.http('Playground', { methods: ['GET'], authLevel: 'anonymous', route: 'playground', handler: playground.page });
app.http('PlaygroundAsset', { methods: ['GET'], authLevel: 'anonymous', route: 'playground/assets/{name}', handler: playground.asset });
app.http('PlaygroundConfig', { methods: ['GET'], authLevel: 'anonymous', route: 'playground/config', handler: playground.configuration });
app.http('Capabilities', { methods: ['GET'], authLevel: 'anonymous', route: 'capabilities', handler: playground.configuration });
