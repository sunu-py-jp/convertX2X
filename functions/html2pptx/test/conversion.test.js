import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import JSZip from 'jszip';
import { DOMParser } from '@xmldom/xmldom';
import { convertHtml } from '../src/service.js';
import { configuration } from '../src/config.js';
const root=new URL('../',import.meta.url);
const browser=process.env.HTML2PPTX_CHROMIUM_PATH||process.env.HTML2PPTX_TEST_BROWSER;
const canRun=Boolean(browser||process.platform==='linux'||existsSync('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'));
const byLocal=(doc,name)=>[...doc.getElementsByTagName('*')].filter(n=>n.localName===name);
const parse=s=>new DOMParser().parseFromString(s,'application/xml');
test('business and image examples preserve SVG labels, pictures, editable shapes and valid tables',{skip:!canRun,timeout:180000},async t=>{
  const catalog=JSON.parse(await readFile(new URL('examples/catalog.json',root)));
  for(const example of catalog.examples)await t.test(example.id,async()=>{
    const result=await convertHtml(await readFile(new URL('examples/'+example.filename,root)),{filename:example.filename,executablePath:browser});
    const zip=await JSZip.loadAsync(result.pptx,{checkCRC32:true});
    const names=Object.keys(zip.files).filter(n=>/^ppt\/slides\/slide\d+\.xml$/.test(n));
    assert.equal(names.length,example.slides);
    const documents=await Promise.all(names.map(async n=>parse(await zip.file(n).async('string'))));
    const text=documents.map(d=>byLocal(d,'t').map(n=>n.textContent).join('\n')).join('\n');
    for(const token of example.expectedSVGNativeText)assert.ok(text.includes(token),`Missing native text: ${token}`);
    assert.equal(Object.keys(zip.files).filter(n=>/^ppt\/media\/.*\.svg$/.test(n)).length,0);
    assert.equal(documents.reduce((sum,d)=>sum+byLocal(d,'pic').length,0),example.expectedOriginalImageCount);
    for(const doc of documents){
      const ids=byLocal(doc,'cNvPr').map(n=>n.getAttribute('id'));assert.equal(new Set(ids).size,ids.length);
      for(const row of byLocal(doc,'tr'))assert.ok(Number(row.getAttribute('h'))>0);
      assert.ok(byLocal(doc,'sp').length>5);
    }
    if(example.id==='organization-governance'){
      assert.ok(documents.reduce((sum,d)=>sum+byLocal(d,'sp').length,0)>150);
      assert.ok(documents.some(d=>byLocal(d,'custGeom').length>20&&byLocal(d,'lnTo').length>100)); // Editable dashed curves are bounded-error subpaths.
      assert.ok(documents.some(d=>byLocal(d,'prstGeom').some(n=>n.getAttribute('prst')==='ellipse')));
    }
  });
});
test('plain arbitrary HTML, ZIP assets and editable gradients',{skip:!canRun,timeout:60000},async()=>{
  const html='<style>body{padding:32px}h1{color:#163b62}table{border-collapse:collapse}td,th{border:1px solid #aac;padding:12px}.gradient{width:400px;height:100px;background:linear-gradient(to right,#def,#abc);border-radius:20px}</style><h1>任意の資料 &amp; 123</h1><div class="gradient">編集できる背景</div><table><tr><th>項目</th><th>値</th></tr><tr><td>数量</td><td>42</td></tr></table><img width="100" src="logo.png">';
  const zip=new JSZip();zip.file('index.html',html);zip.file('logo.png',await readFile(new URL('examples/product-dashboard.png',root)));
  const result=await convertHtml(await zip.generateAsync({type:'nodebuffer'}),{filename:'input.zip',executablePath:browser});
  assert.equal(result.report.slideCount,1);assert.equal(result.report.slides[0].tables,1);assert.equal(result.report.slides[0].images,1);
  const output=await JSZip.loadAsync(result.pptx);const doc=parse(await output.file('ppt/slides/slide1.xml').async('string'));
  assert.ok(byLocal(doc,'gradFill').length);assert.ok(byLocal(doc,'t').some(n=>n.textContent==='任意の資料 & 123'));
});
test('unsupported appearance, outside content and overflow fail without image fallback',{skip:!canRun,timeout:60000},async()=>{
  for(const [html,code]of [
    ['<p>outside</p><section data-slide><h1>slide</h1></section>','CONTENT_OUTSIDE_SLIDES'],
    ['<div style="width:4000px;height:200px">wide</div>','SLIDE_OVERFLOW'],
    ['<h1 style="filter:blur(2px)">blurred</h1>','UNSUPPORTED_LAYOUT'],
    ['<div style="margin:50px;transform:skew(30deg);background:red;width:100px">skew</div>','UNSUPPORTED_LAYOUT'],
    ['<section data-slide><svg xmlns="http://www.w3.org/2000/svg" style="position:absolute;left:1200px" width="300" height="100"><rect width="300" height="100"/></svg></section>','SLIDE_OVERFLOW'],
    ['<div style="width:160px;overflow:hidden;white-space:nowrap">This long sentence must never silently appear unclipped in a slide</div>','SLIDE_OVERFLOW'],
    ['<div style="visibility:hidden"><p style="visibility:visible">Visible content</p></div>','UNSUPPORTED_LAYOUT'],
    ['<table style="margin:50px;transform:rotate(15deg)"><tr><td>A</td></tr></table>','UNSUPPORTED_TABLE'],
    ['<div style="width:100px;height:100px;background:red"></div><section data-slide>slide</section>','CONTENT_OUTSIDE_SLIDES'],
  ])await assert.rejects(convertHtml(Buffer.from(html),{executablePath:browser}),e=>e.code===code);
});
test('head attributes, hidden tables and image opacity preserve safe layout',{skip:!canRun,timeout:60000},async()=>{
  const png=await readFile(new URL('examples/product-dashboard.png',root));
  const html=`<html><head id="head" title="angle > bracket"><style>body{padding:30px}</style></head><body><h1>通常の本文</h1><table style="visibility:hidden"><tr><td>hidden 1</td></tr><tr><td>hidden 2</td></tr></table><table><tr><td>visible</td></tr></table><img style="opacity:.5;width:200px" src="data:image/png;base64,${png.toString('base64')}"></body></html>`;
  const result=await convertHtml(Buffer.from(html),{executablePath:browser});
  const zip=await JSZip.loadAsync(result.pptx);const doc=parse(await zip.file('ppt/slides/slide1.xml').async('string'));
  assert.equal(byLocal(doc,'tbl').length,1);assert.equal(byLocal(doc,'tr').length,1);
  assert.equal(byLocal(doc,'alphaModFix')[0].getAttribute('amt'),'50000');
  assert.ok(byLocal(doc,'latin').some(n=>n.getAttribute('typeface')==='Noto Sans JP'));
  const rels=parse(await zip.file('ppt/slides/_rels/slide1.xml.rels').async('string'));
  const rid=byLocal(doc,'blip')[0].getAttribute('r:embed'),rel=byLocal(rels,'Relationship').find(r=>r.getAttribute('Id')===rid);
  assert.ok(rel);assert.deepEqual(await zip.file('ppt/'+rel.getAttribute('Target').replace('../','')).async('nodebuffer'),png);
});
test('timeout stops a conversion worker',{skip:!canRun,timeout:10000},async()=>{
  await assert.rejects(convertHtml(Buffer.from('<h1>Timeout</h1>'),{limits:{...configuration(),timeoutMs:10},executablePath:browser}),e=>e.code==='CONVERSION_TIMEOUT');
});
