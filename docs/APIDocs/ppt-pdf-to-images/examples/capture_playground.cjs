#!/usr/bin/env node
// Requires Playwright + Chromium. Screenshots are of the actual local Playground.
'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');
const os=require('node:os');
const crypto=require('node:crypto');
const {chromium}=require('playwright');
const root=__dirname;
const base=process.env.EXAMPLES_BASE_URL||'http://localhost:7071';
assert(['localhost','127.0.0.1'].includes(new URL(base).hostname));
async function main(){
  const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHANNEL?{channel:process.env.PLAYWRIGHT_CHANNEL}:{})});
  const context=await browser.newContext({viewport:{width:1700,height:1000},deviceScaleFactor:1});
  const page=await context.newPage();const errors=[];const runs=[];
  page.on('pageerror',e=>errors.push(e.message));
  try {
    for(const [filename,pageNumber] of [['quarterly-review.pptx','2'],['operations-report.pdf','2']]){
      assert.equal((await page.goto(base+'/api/playground')).status(),200);
      await page.waitForFunction(()=>document.querySelector('#config-status').dataset.state!=='loading');
      await page.locator('#file-input').setInputFiles(path.join(root,'inputs',filename));
      await page.locator('#scope').selectOption('single');
      await page.locator('#page').fill(pageNumber);
      const waiting=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname==='/api/convert');
      await page.locator('#submit-button').click();const response=await waiting;
      assert.equal(response.status(),200);
      await page.locator('#download').waitFor({state:'visible'});
      await page.waitForFunction(()=>document.querySelector('#preview').naturalWidth>0);
      assert.equal(await page.locator('#error-message').isVisible(),false);
      const waitingDownload=page.waitForEvent('download');
      await page.locator('#download').click();
      const download=await waitingDownload;
      assert.equal(await download.failure(),null);
      const temporary=await fs.mkdtemp(path.join(os.tmpdir(),'convertx2x-image-example-'));
      let original;
      try {
        const downloaded=path.join(temporary,'page.png');
        await download.saveAs(downloaded);
        original=await fs.readFile(downloaded);
      } finally {await fs.rm(temporary,{recursive:true,force:true});}
      const stored=await fs.readFile(path.join(root,'outputs',path.parse(filename).name,'page-0002.png'));
      assert.equal(crypto.createHash('sha256').update(original).digest('hex'),crypto.createHash('sha256').update(stored).digest('hex'));
      const screenshot='screenshots/'+path.parse(filename).name+'-playground.png';
      await page.locator('.result-panel').screenshot({path:path.join(root,screenshot)});
      const screenshotBytes=await fs.readFile(path.join(root,screenshot));
      runs.push({input:'inputs/'+filename,page:2,status:response.status(),requestPath:new URL(response.url()).pathname+new URL(response.url()).search,preview:await page.locator('#preview').evaluate(i=>({width:i.naturalWidth,height:i.naturalHeight})),matchesZipPage:true,screenshot,screenshotSha256:crypto.createHash('sha256').update(screenshotBytes).digest('hex')});
    }
    assert.deepEqual(errors,[]);
    await fs.writeFile(path.join(root,'browser.json'),JSON.stringify({recordedAt:new Date().toISOString(),browser:await browser.version(),viewport:{width:1700,height:1000},screenshotRegion:'.result-panel',pageErrors:errors,runs},null,2)+'\n');
    console.log('PASS: 2 real Playground conversions; response images match ZIP pages; 2 panel screenshots; no page errors.');
  } finally {await browser.close();}
}
main().catch(error=>{console.error(error);process.exitCode=1;});
