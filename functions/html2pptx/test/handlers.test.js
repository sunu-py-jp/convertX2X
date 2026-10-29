import test from 'node:test';import assert from 'node:assert/strict';import JSZip from 'jszip';
import {createHandlers} from '../src/handlers.js';import {configuration} from '../src/config.js';
const req=(body,query='filename=input.html')=>({query:new URLSearchParams(query),headers:new Headers(),body:body?[Buffer.from(body)]:null});
test('HTTP returns PPTX or ZIP with a machine-readable report',async()=>{
  const handler=createHandlers({convert:async(input,{filename})=>{assert.equal(filename,'input.html');return {pptx:Buffer.from('pptx'),report:{version:1}};}}).convert;
  const archive=await handler(req('<h1>A</h1>'));assert.equal(archive.status,200);
  const zip=await JSZip.loadAsync(archive.body);assert.equal(await zip.file('presentation.pptx').async('string'),'pptx');assert.equal(JSON.parse(await zip.file('report.json').async('string')).version,1);
  const direct=await handler(req('html','filename=input.html&format=pptx'));assert.equal(direct.body.toString(),'pptx');
});
test('HTTP rejects excessive input and concurrent requests',async()=>{
  let finish;const busy=createHandlers({limits:{...configuration(),maxInputBytes:16},convert:()=>new Promise(r=>{finish=r;})}).convert;
  assert.equal((await busy(req('x'.repeat(17)))).status,413);
  const first=busy(req('first'));await new Promise(r=>setImmediate(r));assert.equal((await busy(req('second'))).status,429);
  finish({pptx:Buffer.from('x'),report:{}});assert.equal((await first).status,200);
});
