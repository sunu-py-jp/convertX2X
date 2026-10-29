import test from 'node:test';
import assert from 'node:assert/strict';
import {existsSync} from 'node:fs';
import {mkdtemp,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {launchBrowser,closeBrowser,securedPage} from '../src/browser.js';
import {materializePseudoElements} from '../src/pseudo-elements.js';
import {prepareInput} from '../src/input.js';

const executablePath=process.env.HTML2PPTX_CHROMIUM_PATH||process.env.HTML2PPTX_TEST_BROWSER;
const skip=!(executablePath||process.platform==='linux'||existsSync('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'));
async function pageFor(t,html) {
  const scratch=await mkdtemp(path.join(os.tmpdir(),'html2pptx-pseudo-test-'));
  const browser=await launchBrowser(scratch,executablePath);
  t.after(async()=>{await closeBrowser(browser);await rm(scratch,{recursive:true,force:true});});
  const page=await securedPage(browser,900,600);
  const prepared=await prepareInput(Buffer.from(html));
  await page.setContent(prepared.html);
  return page;
}
const base='<style>body{margin:0;font-family:Arial;font-size:20px}.box{position:relative;margin:80px;width:420px;height:200px;background:#eee}</style>';
async function assertSamePixels(page,before) {
  const after=Buffer.from(await page.screenshot());
  if(after.equals(before)) return;
  const diff=await page.evaluate(async({before,after})=>{
    const pixels=async encoded=>{
      const image=await createImageBitmap(new Blob([Uint8Array.from(atob(encoded),c=>c.charCodeAt(0))],{type:'image/png'}));
      const canvas=document.createElement('canvas');canvas.width=image.width;canvas.height=image.height;
      const context=canvas.getContext('2d');context.drawImage(image,0,0);return context.getImageData(0,0,image.width,image.height).data;
    };
    const a=await pixels(before),b=await pixels(after);let changed=0,total=0;
    for(let i=0;i<a.length;i+=4){let delta=0;for(let c=0;c<3;c++)delta+=Math.abs(a[i+c]-b[i+c]);if(delta)changed++;total+=delta;}
    return {changedRatio:changed/(a.length/4),mean:total/(a.length/4*3)};
  },{before:before.toString('base64'),after:after.toString('base64')});
  // Computed matrix() serializes six decimal places: transformed antialiased edges
  // can differ by a tiny amount even when the geometry itself is unchanged.
  assert.ok(diff.changedRatio<0.0005&&diff.mean<0.01,JSON.stringify(diff));
}

test('empty before/after triangles, circles and lines preserve the original rendered appearance',{skip,timeout:15000},async t=>{
  const page=await pageFor(t,base+`<style>.box::before{content:"";position:absolute;left:40px;top:50px;border-top:30px solid transparent;border-bottom:30px solid transparent;border-left:60px solid #36a}.box::after{content:"";position:absolute;right:40px;top:50px;width:70px;height:70px;border-radius:50%;background:#fa7;border:3px solid #633}.line::before{content:"";display:block;position:absolute;top:150px;left:50px;width:250px;height:3px;background:#444}</style><div class="box"><div class="line"></div><p>Original text remains unchanged</p></div>`);
  const before=Buffer.from(await page.screenshot());
  const result=await page.evaluate(materializePseudoElements);
  assert.equal(result.materializedCount,3);
  await assertSamePixels(page,before);
});
test('in-flow empty decorations retain text positions and selector-dependent styling',{skip,timeout:15000},async t=>{
  const page=await pageFor(t,base+`<style>.box::before{content:"";display:inline-block;width:16px;height:16px;margin-right:12px;background:green;border-radius:50%}.box>strong:first-child{color:red}.box>em:last-child{color:blue}.box>em:nth-child(2){font-weight:bold}.box::after{content:"";display:inline-block;border-top:8px solid transparent;border-bottom:8px solid transparent;border-left:12px solid black;margin-left:8px}</style><div class="box"><strong>First label</strong> / <em>Second label</em></div>`);
  const before=Buffer.from(await page.screenshot());
  assert.equal((await page.evaluate(materializePseudoElements)).materializedCount,2);
  await assertSamePixels(page,before);
});
test('absolute and fixed decorations preserve transformed ancestor positions',{skip,timeout:15000},async t=>{
  const page=await pageFor(t,base+`<style>.box{transform:rotate(7deg)}.box::before{content:"";position:absolute;left:30%;top:35%;width:80px;height:40px;background:linear-gradient(90deg,red,blue);transform:skewX(15deg)}.box::after{content:"";position:fixed;right:20px;bottom:25px;width:20px;height:60px;background:#687}</style><div class="box">Caption</div>`);
  const before=Buffer.from(await page.screenshot());
  assert.equal((await page.evaluate(materializePseudoElements)).materializedCount,2);
  await assertSamePixels(page,before);
});
test('important pseudo rules are suppressed without painting a duplicate',{skip,timeout:15000},async t=>{
  const page=await pageFor(t,base+`<style>@layer original{#box::before{content:""!important;display:block!important;width:50px;height:50px;background:red}}</style><div class="box" id="box">Caption</div>`);
  const before=Buffer.from(await page.screenshot());
  assert.equal((await page.evaluate(materializePseudoElements)).materializedCount,1);
  await assertSamePixels(page,before);
});
test('text and counter pseudo content is rejected instead of lost',{skip,timeout:15000},async t=>{
  const page=await pageFor(t,base+'<style>.box::before{content:"Label"}</style><div class="box">Caption</div>');
  await assert.rejects(page.evaluate(materializePseudoElements),/UNSUPPORTED_LAYOUT:.*疑似要素/);
});
test('documents without pseudo decorations are not mutated',{skip,timeout:15000},async t=>{
  const page=await pageFor(t,base+'<div class="box">Caption</div>');
  const before=await page.content();
  assert.equal((await page.evaluate(materializePseudoElements)).materializedCount,0);
  assert.equal(await page.content(),before);
});
test('style expansion is bounded before any DOM mutation',{skip,timeout:15000},async t=>{
  const page=await pageFor(t,base+'<style>.box::before{content:"";display:block;width:10px;height:10px;background:red}</style><div class="box">Caption</div>');
  const before=await page.content();
  await assert.rejects(page.evaluate(materializePseudoElements,{maxStyleBytes:50}),/UNSUPPORTED_LAYOUT:.*スタイル量/);
  assert.equal(await page.content(),before);
});
