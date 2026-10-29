import { app } from '@azure/functions';
import { createHandlers } from './handlers.js';
import { configuration } from './config.js';
import { playground } from './playground.js';
app.setup({enableHttpStream:true});
const limits=configuration();
app.http('ConvertHtml',{methods:['POST'],authLevel:'function',route:'convert',handler:createHandlers({limits}).convert});
app.http('Playground',{methods:['GET'],authLevel:'anonymous',route:'playground',handler:()=>({headers:{'Content-Type':'text/html; charset=utf-8','X-Content-Type-Options':'nosniff'},body:playground})});
app.http('Capabilities',{methods:['GET'],authLevel:'anonymous',route:'capabilities',handler:()=>({jsonBody:{converter:'html2pptx',input:['html','htm','zip'],output:['pptx','zip'],async:false,limits}})});
