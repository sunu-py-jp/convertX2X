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

test('Apple Quick Look preserves each slide background without reusing earlier slide shapes',{skip:!available,timeout:60000},async()=>{
  const scratch=await mkdtemp(path.join(tmpdir(),'html2pptx-quicklook-slides-'));
  let browser;
  try{
    const html=`<!doctype html><html lang="ja"><head><meta charset="utf-8"><style>
      body{margin:0}section{position:relative;width:1280px;height:720px}
      h1{position:absolute;left:500px;top:100px;margin:0;font-size:48px;color:#132d3d}
      .first{background:white}.left{position:absolute;left:0;top:0;width:420px;height:720px;background:#132d3d}
      .paper{background:#f4f1e9}.blue{background:#185d9a}.blue h1{color:white}
      </style></head><body>
      <section data-slide class="first"><div class="left"></div><h1>一枚目 ABC</h1></section>
      <section data-slide class="paper"><h1>二枚目 ABC</h1></section>
      <section data-slide class="blue"><h1>三枚目 ABC</h1></section>
      </body></html>`;
    const result=await convertHtml(Buffer.from(html),{executablePath});
    assert.equal(result.report.slideCount,3);
    assert.ok(result.report.slides.every(slide=>slide.images===0&&slide.textBoxes>0),'text and backgrounds must remain editable');
    const input=path.join(scratch,'sample.pptx');
    await writeFile(input,result.pptx);
    // -t covers only the first slide. The full preview exposes attachment reuse
    // across slides, which can replace later backgrounds with earlier shapes.
    await run('/usr/bin/qlmanage',['-p','-o',scratch,input],{timeout:15000});
    const preview=path.join(scratch,'sample.pptx.qlpreview');
    const markup=await readFile(path.join(preview,'Preview.html'),'utf8');
    browser=await launchBrowser(scratch,executablePath);
    const page=await securedPage(browser,1280,720);
    await page.setContent(markup,{waitUntil:'domcontentloaded'});
    const slides=await page.evaluate(()=>[...document.querySelectorAll('div.slide')].map(slide=>({
      text:slide.textContent,
      attachments:[...slide.querySelectorAll('img')].map(image=>image.getAttribute('src')),
      backgrounds:[...slide.querySelectorAll('div')].map(element=>getComputedStyle(element).backgroundColor)
        .filter(color=>/^rgb\(/.test(color)).map(color=>color.match(/\d+/g).map(Number))
    })));
    assert.equal(slides.length,3);
    for(const [index,label] of ['一枚目','二枚目','三枚目'].entries())assert.ok(slides[index].text.includes(label));
    const attachments=[...new Set(slides.flatMap(slide=>slide.attachments))];
    for(const name of attachments)assert.match(name,/^Attachment\d+\.pdf$/);
    if(attachments.length)await run('/usr/bin/qlmanage',['-t','-s','256','-o',scratch,...attachments.map(name=>path.join(preview,name))],{timeout:15000});
    const thumbnails=await Promise.all(attachments.map(async name=>({
      name,data:(await readFile(path.join(scratch,name+'.png'))).toString('base64')
    })));
    const colors=await page.evaluate(async thumbnails=>{
      const colors={};
      for(const {name,data} of thumbnails){
        const image=new Image();image.src='data:image/png;base64,'+data;await image.decode();
        const canvas=document.createElement('canvas');canvas.width=image.width;canvas.height=image.height;
        const context=canvas.getContext('2d');context.drawImage(image,0,0);
        // The native rectangles fill each PDF's center. Broad channel checks
        // tolerate Quick Look color management without accepting white/missing fills.
        colors[name]=[...context.getImageData(Math.floor(image.width/2),Math.floor(image.height/2),1,1).data].slice(0,3);
      }
      return colors;
    },thumbnails);
    const navy=([r,g,b])=>r<80&&g<110&&b<130&&b>r+20;
    const paper=([r,g,b])=>r>225&&g>220&&b>205&&r>b+3&&r-b<35;
    const blue=([r,g,b])=>b>130&&b>r+60&&g>r+35;
    // Quick Look may express ordinary rectangles as HTML backgrounds and groups
    // as PDF attachments. Either representation must retain each slide's colors.
    const fills=slides.map(slide=>[...slide.backgrounds,...slide.attachments.map(name=>colors[name])]);
    assert.ok(fills[0].some(navy),`first-slide panel missing: ${JSON.stringify(fills)}`);
    assert.ok(fills[1].some(paper),`second-slide paper background missing: ${JSON.stringify(fills)}`);
    assert.ok(fills[2].some(blue),`third-slide blue background missing: ${JSON.stringify(fills)}`);
    assert.ok(fills.slice(1).every(slide=>!slide.some(navy)),`earlier panel leaked into later slides: ${JSON.stringify(fills)}`);
  }finally{await closeBrowser(browser);await rm(scratch,{recursive:true,force:true});}
});
