import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { convertInWorker } from './engine.js';
import { observeBrowserProcesses, stopManagedBrowsers } from './browser.js';
import { ConversionError } from './errors.js';
const notify=message=>{if(process.connected)process.send?.(message,()=>{});};
observeBrowserProcesses(notify);
process.on('message',message=>{if(message?.type==='cancel')stopManagedBrowsers();});
process.once('disconnect',stopManagedBrowsers);
process.once('message',async options=>{
  try {
    const input=await readFile(path.join(options.scratch,'input.bin'));
    const result=await convertInWorker(input,{...options,onBrowser:pid=>notify({browserPid:pid})});
    await writeFile(path.join(options.scratch,'presentation.pptx'),result.pptx);
    for(const [i,preview] of result.previews.entries()) await writeFile(path.join(options.scratch,`html-${i+1}.png`),preview);
    notify({ok:true,report:result.report,previewCount:result.previews.length});
  } catch(error) {
    // No raw HTML, browser stderr or arbitrary stack traces leave the worker.
    const known=error instanceof ConversionError||error?.code==='UNSUPPORTED_SVG';
    notify({ok:false,error:{code:known?error.code:'CONVERSION_FAILED',
      message:known?String(error.message).slice(0,500):'HTMLをPowerPointへ変換できませんでした。',status:known?(error.status||error.statusCode||422):500,
      ...(known&&error.details?{details:error.details}:{})}});
  } finally {process.disconnect?.();}
});
