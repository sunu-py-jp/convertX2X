import { fork, execFile } from 'node:child_process';
import { mkdtemp,writeFile,readFile,rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { configuration } from './config.js';
import { ConversionError } from './errors.js';

const WORKER=fileURLToPath(new URL('./worker.js',import.meta.url));
export async function convertHtml(input,{filename='input.html',limits=configuration(),executablePath=process.env.HTML2PPTX_CHROMIUM_PATH,captureHtml=false}={}) {
  if(!Buffer.isBuffer(input)) input=Buffer.from(input);
  if(input.length>limits.maxInputBytes) throw new ConversionError('INPUT_LIMIT','入力ファイルのサイズが上限を超えています。',413);
  const scratch=await mkdtemp(path.join(os.tmpdir(),'html2pptx-'));
  let child,browserPid,timer,completed=false;
  const killGroup=async pid=>{
    if(!pid)return;
    if(process.platform==='win32') {
      await new Promise(resolve=>execFile('taskkill',['/PID',String(pid),'/T','/F'],{timeout:1500,windowsHide:true},()=>resolve()));
      return;
    }
    try{process.kill(-pid,'SIGKILL');}catch{try{process.kill(pid,'SIGKILL');}catch{}}
  };
  try {
    await writeFile(path.join(scratch,'input.bin'),input);
    // Separate process bounds CPU/heap work and never inherits Azure credentials.
    child=fork(WORKER,[],{detached:process.platform!=='win32',stdio:['ignore','ignore','ignore','ipc'],
      execArgv:['--max-old-space-size=1536'],env:{PATH:process.env.PATH||'/usr/bin:/bin',HOME:scratch,TMPDIR:os.tmpdir(),
        ...(process.platform==='win32'?{SystemRoot:process.env.SystemRoot,TEMP:os.tmpdir(),TMP:os.tmpdir()}:{} )}});
    const result=await new Promise((resolve,reject)=>{
      let done=false;
      const finish=(error,result)=>{if(done)return;done=true;clearTimeout(timer);error?reject(error):resolve(result);};
      child.on('message',message=>{
        if(message.browserPid){browserPid=message.browserPid;return;}
        if(message.browserClosedPid){if(browserPid===message.browserClosedPid)browserPid=undefined;return;}
        if(message.ok) finish(null,message);
        else if(message.error) finish(new ConversionError(message.error.code,message.error.message,message.error.status,message.error.details));
      });
      child.on('error',()=>finish(new ConversionError('WORKER_FAILED','変換処理を起動できませんでした。',503)));
      child.on('exit',()=>finish(new ConversionError('WORKER_FAILED','変換処理が完了前に終了しました。',503)));
      timer=setTimeout(()=>finish(new ConversionError('CONVERSION_TIMEOUT','変換処理の制限時間を超えました。',504)),limits.timeoutMs);
      child.send({filename,limits,scratch,executablePath,captureHtml});
    });
    const pptx=await readFile(path.join(scratch,'presentation.pptx'));
    const previews=[];
    for(let i=1;i<=result.previewCount;i++) previews.push(await readFile(path.join(scratch,`html-${i}.png`)));
    completed=true;
    return {pptx,report:result.report,previews};
  } finally {
    clearTimeout(timer);
    // Give the worker a short cleanup opportunity, including startup before CDP.
    // Pending creation/exit notifications are processed during this grace period.
    if(!completed&&child&&child.exitCode===null&&child.signalCode===null) {
      if(child.connected)child.send({type:'cancel'},()=>{});
      let cleanupTimer;
      try {await Promise.race([new Promise(resolve=>child.once('exit',resolve)),new Promise(resolve=>{cleanupTimer=setTimeout(resolve,250);})]);}
      finally {clearTimeout(cleanupTimer);}
    }
    // A successful engine result is sent only after closeBrowser has completed;
    // do not signal an old browser PID after it has exited and could be reused.
    if(!completed)await killGroup(browserPid);
    if(child&&child.exitCode===null&&child.signalCode===null) await killGroup(child.pid);
    await rm(scratch,{recursive:true,force:true,maxRetries:3,retryDelay:100});
  }
}
