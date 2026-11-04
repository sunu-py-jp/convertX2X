import test from 'node:test';
import assert from 'node:assert/strict';
import {existsSync} from 'node:fs';
import {mkdtemp,readFile,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {convertHtml} from '../src/service.js';
import {launchBrowser,securedPage,closeBrowser} from '../src/browser.js';

const run=promisify(execFile);
const executablePath=process.env.HTML2PPTX_CHROMIUM_PATH||process.env.HTML2PPTX_TEST_BROWSER;
const available=process.platform==='darwin'&&existsSync('/usr/bin/qlmanage')&&
  (executablePath||existsSync('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'));

test('Apple Quick Look displays the terminal rounded panel and cycle-end infinite circle',{skip:!available,timeout:45000},async()=>{
  const scratch=await mkdtemp(path.join(tmpdir(),'html2pptx-quicklook-'));
  let browser;
  try{
    const html=`<style>
      @keyframes status{from{background:red;border-radius:0}to{background:#cceee1;border-radius:38px}}
      @keyframes loop{from{transform:translateY(0)}to{transform:translateY(-20px)}}
      .panel{position:absolute;left:80px;top:100px;width:500px;height:230px;animation:status 1s linear both}
      .circle{position:absolute;left:800px;top:150px;width:130px;height:130px;background:#2675c9;border-radius:50%;animation:loop 2s linear infinite}
      </style><section data-slide><div class="panel"></div><div class="circle"></div></section>`;
    const result=await convertHtml(Buffer.from(html),{executablePath});
    assert.equal(result.report.animations.nativeEffects.length,0);
    assert.equal(result.report.animations.staticEffects.length,2);
    const input=path.join(scratch,'sample.pptx');
    await writeFile(input,result.pptx);
    await run('/usr/bin/qlmanage',['-t','-s','1280','-o',scratch,input],{timeout:15000});
    const thumbnail=await readFile(input+'.png');
    browser=await launchBrowser(scratch,executablePath);
    const page=await securedPage(browser,1280,720);
    const pixels=await page.evaluate(async data=>{
      const image=new Image();image.src='data:image/png;base64,'+data;await image.decode();
      const canvas=document.createElement('canvas');canvas.width=image.width;canvas.height=image.height;
      const context=canvas.getContext('2d');context.drawImage(image,0,0);
      const rgba=context.getImageData(0,0,canvas.width,canvas.height).data;
      let green=0,blue=0;
      for(let i=0;i<rgba.length;i+=4){
        // Quick Look color-manages its thumbnail; assert the colored regions
        // survive, without depending on the machine's ICC profile byte values.
        const [r,g,b,a]=rgba.subarray(i,i+4);
        if(r>150&&r<235&&g>r+12&&b>r+5&&g>b+3&&a>240)green++;
        if(b>r+100&&g>r+40&&b>g+30&&a>240)blue++;
      }
      return {green,blue};
    },thumbnail.toString('base64'));
    assert.ok(pixels.green>30000,`terminal panel missing: ${JSON.stringify(pixels)}`);
    assert.ok(pixels.blue>4000,`infinite-animation circle missing: ${JSON.stringify(pixels)}`);
  }finally{await closeBrowser(browser);await rm(scratch,{recursive:true,force:true});}
});
