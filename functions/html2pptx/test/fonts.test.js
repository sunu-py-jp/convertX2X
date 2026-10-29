import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp,rm } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import JSZip from 'jszip';
import { DOMParser } from '@xmldom/xmldom';
import { convertHtml } from '../src/service.js';
import { FONT_FAMILY,normalizeDocumentFonts } from '../src/fonts.js';
import { launchBrowser,trustedFontCss,closeBrowser } from '../src/browser.js';
const executablePath=process.env.HTML2PPTX_CHROMIUM_PATH||process.env.HTML2PPTX_TEST_BROWSER;
const canRun=Boolean(executablePath||process.platform==='linux'||existsSync('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'));
const parse=text=>new DOMParser().parseFromString(text,'application/xml');
const find=(node,name)=>[...node.getElementsByTagName('*')].filter(n=>n.localName===name);
const html=family=>`<!doctype html><html><head><style>
*{box-sizing:border-box}body{margin:0;font-family:'${family}'}section{position:relative;width:1280px;height:720px;padding:36px;background:white}
h1{font:bold 32px/1.4 '${family}'!important;margin:0 0 12px}p{margin:0 0 16px}table{border-collapse:collapse;font:400 16px/1.5 '${family}'}td,th{border:1px solid #aaa;padding:10px 14px}th{font-weight:700}svg{display:block;margin-top:20px}
</style></head><body><section data-slide>
<h1 id="heading" style="font-family:'${family}'!important">日本語の見出し ABC</h1>
<p id="paragraph" style="font:italic 400 20px/1.3 '${family}'!important"><span id="regular">通常本文</span> <b id="bold">強調本文</b></p>
<table><tr><th id="table-heading" style="font-family:'${family}'!important">部門</th><th>金額</th></tr><tr><td id="table-body">開発</td><td>100</td></tr></table>
<svg xmlns="http://www.w3.org/2000/svg" width="850" height="150" font-family="${family}"><rect x="1" y="1" width="840" height="140" fill="#eef4f9"/><text id="svg-regular" x="20" y="45" font-size="24" style="font-family:'${family}'!important">SVG通常<tspan id="svg-bold" x="20" dy="48" style="font:700 24px '${family}'!important">SVG強調</tspan></text></svg>
</section></body></html>`;

test('bundled regular and bold fonts load before every HTML and SVG font override is normalized',{skip:!canRun,timeout:30000},async()=>{
  const scratch=await mkdtemp(path.join(os.tmpdir(),'html2pptx-font-test-'));let browser;
  try{
    browser=await launchBrowser(scratch,executablePath);const page=await browser.newPage();
    await page.setContent(html('Courier New'));await page.addStyleTag({content:await trustedFontCss()});
    await page.evaluate(normalizeDocumentFonts,FONT_FAMILY);
    const measured=await page.evaluate(()=>({
      faces:[...document.fonts].map(face=>({family:face.family,weight:face.weight,status:face.status})),
      styles:['heading','regular','bold','table-heading','table-body','svg-regular','svg-bold'].map(id=>{const s=getComputedStyle(document.getElementById(id));return{id,family:s.fontFamily,weight:s.fontWeight,italic:s.fontStyle};})
    }));
    for(const weight of ['400','700'])assert.ok(measured.faces.some(face=>face.family===FONT_FAMILY&&face.weight===weight&&face.status==='loaded'));
    for(const style of measured.styles)assert.equal(style.family.replaceAll('"',''),FONT_FAMILY);
    assert.equal(measured.styles.find(s=>s.id==='regular').weight,'400');assert.equal(measured.styles.find(s=>s.id==='regular').italic,'italic');
    for(const id of ['heading','bold','table-heading','svg-bold'])assert.ok(Number(measured.styles.find(s=>s.id===id).weight)>=700);
  }finally{await closeBrowser(browser);await rm(scratch,{recursive:true,force:true});}
});

test('uniform font conversion gives the same browser preview and editable geometry as Noto input',{skip:!canRun,timeout:60000},async()=>{
  const forced=await convertHtml(Buffer.from(html('Courier New')),{executablePath,captureHtml:true});
  const explicit=await convertHtml(Buffer.from(html(FONT_FAMILY)),{executablePath,captureHtml:true});
  assert.equal(forced.report.fontFamily,FONT_FAMILY);assert.equal(forced.report.fontPolicy,'uniform');
  assert.equal(forced.previews.length,1);assert.deepEqual(forced.previews[0],explicit.previews[0]);
  const first=await JSZip.loadAsync(forced.pptx),second=await JSZip.loadAsync(explicit.pptx);
  const slide=parse(await first.file('ppt/slides/slide1.xml').async('string'));
  const other=parse(await second.file('ppt/slides/slide1.xml').async('string'));
  const geometry=doc=>[...doc.getElementsByTagName('*')].filter(n=>['xfrm','off','ext','chOff','chExt','pt','tr','tcPr','gridCol'].includes(n.localName)).map(n=>[n.localName,Object.fromEntries([...n.attributes].map(a=>[a.name,a.value]))]);
  assert.deepEqual(geometry(slide),geometry(other));
  let fontFields=0,themeFontFields=0;
  for(const name of Object.keys(first.files).filter(n=>n.startsWith('ppt/')&&n.endsWith('.xml'))){
    const doc=parse(await first.file(name).async('string'));
    for(const tag of ['latin','ea','cs','font'])for(const font of find(doc,tag)){
      assert.equal(font.getAttribute('typeface'),FONT_FAMILY,`${name}: ${tag}`);fontFields++;if(name.startsWith('ppt/theme/'))themeFontFields++;
    }
  }
  assert.ok(fontFields>30);assert.ok(themeFontFields>10);
  const runs=find(slide,'r');
  for(const [text,bold] of [['通常本文',false],['強調本文',true],['部門',true],['開発',false],['SVG通常',false],['SVG強調',true]]){
    const run=runs.find(r=>find(r,'t').some(t=>t.textContent===text));assert.ok(run,`Missing editable text: ${text}`);
    assert.equal(find(run,'rPr')[0].getAttribute('b')==='1',bold,`${text} weight`);
  }
  assert.equal(Object.keys(first.files).some(n=>n.startsWith('ppt/fonts/')&&!first.files[n].dir),false);
});

test('generated pseudo-element text also receives the uniform conversion font',{skip:!canRun,timeout:30000},async()=>{
  const scratch=await mkdtemp(path.join(os.tmpdir(),'html2pptx-font-pseudo-'));let browser;
  try{
    browser=await launchBrowser(scratch,executablePath);const page=await browser.newPage();
    await page.setContent(`<style>.label::before{content:'前置き';font:700 20px 'Courier New'!important}</style><p class="label">本文</p>`);
    await page.addStyleTag({content:await trustedFontCss()});await page.evaluate(normalizeDocumentFonts,FONT_FAMILY);
    const family=await page.evaluate(()=>getComputedStyle(document.querySelector('.label'),'::before').fontFamily);
    assert.equal(family.replaceAll('"',''),FONT_FAMILY);
  }finally{await closeBrowser(browser);await rm(scratch,{recursive:true,force:true});}
});
