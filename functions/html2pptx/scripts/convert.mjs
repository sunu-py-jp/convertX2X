import { readFile,writeFile,mkdir } from 'node:fs/promises';
import path from 'node:path';
import JSZip from 'jszip';
import { convertHtml } from '../src/service.js';
const [input,output='output']=process.argv.slice(2);
if(!input){console.error('Usage: npm run convert -- input.html output-directory');process.exit(1);}
const result=await convertHtml(await readFile(input),{filename:path.basename(input),captureHtml:true});
await mkdir(output,{recursive:true});
await writeFile(path.join(output,'presentation.pptx'),result.pptx);
await writeFile(path.join(output,'report.json'),JSON.stringify(result.report,null,2)+'\n');
for(const [i,preview] of result.previews.entries()) await writeFile(path.join(output,`html-${String(i+1).padStart(2,'0')}.png`),preview);
const zip=new JSZip();zip.file('presentation.pptx',result.pptx);zip.file('report.json',JSON.stringify(result.report,null,2));
await writeFile(path.join(output,'result.zip'),await zip.generateAsync({type:'nodebuffer',compression:'DEFLATE'}));
console.log(JSON.stringify({output:path.resolve(output),...result.report},null,2));
