import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp,readFile,writeFile,chmod,rm,access } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { convertHtml } from '../src/service.js';
import { configuration } from '../src/config.js';

const quote=value=>`'${value.replaceAll("'","'\\''")}'`;
const alive=pid=>{try{process.kill(pid,0);return true;}catch{return false;}};

test('a timeout during Chromium startup stops its process group and removes the conversion workspace',
  {skip:process.platform==='win32',timeout:15000},async()=>{
    const directory=await mkdtemp(path.join(os.tmpdir(),'html2pptx-startup-test-'));
    const executable=path.join(directory,'browser');
    const pidFile=path.join(directory,'browser.pid');
    const profileFile=path.join(directory,'profile.path');
    let pid;
    try {
      // Stand in for a browser that spawned successfully but never exposes CDP.
      await writeFile(executable,`#!/bin/sh\necho $$ > ${quote(pidFile)}\nfor arg in "$@"; do\n case "$arg" in --user-data-dir=*) printf '%s' "\${arg#--user-data-dir=}" > ${quote(profileFile)};; esac\ndone\nsleep 30\n`);
      await chmod(executable,0o700);
      const started=Date.now();
      await assert.rejects(convertHtml(Buffer.from('<p>startup deadline</p>'),{
        executablePath:executable,limits:{...configuration(),timeoutMs:1800},
      }),error=>error.code==='CONVERSION_TIMEOUT');
      assert.ok(Date.now()-started<7000,'cleanup must remain bounded');
      pid=Number(await readFile(pidFile,'utf8'));
      assert.ok(Number.isInteger(pid)&&pid>0,'the browser must actually have spawned');
      for(let i=0;i<20&&alive(pid);i++)await new Promise(resolve=>setTimeout(resolve,50));
      assert.equal(alive(pid),false,'startup must not leave the detached browser running');
      const profile=await readFile(profileFile,'utf8');
      await assert.rejects(access(path.dirname(profile)),error=>error.code==='ENOENT');
    } finally {
      // Only clean up the test-owned process if a regression leaves it behind.
      if(!pid){try{pid=Number(await readFile(pidFile,'utf8'));}catch{}}
      if(pid&&alive(pid)){try{process.kill(-pid,'SIGKILL');}catch{try{process.kill(pid,'SIGKILL');}catch{}}}
      await rm(directory,{recursive:true,force:true});
    }
  });
