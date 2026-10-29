import test from 'node:test';
import assert from 'node:assert/strict';
import {existsSync} from 'node:fs';
import {mkdtemp,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {launchBrowser,closeBrowser,securedPage} from '../src/browser.js';
import {installSvgPlacementHook} from '../src/svg-placement.js';
import {extractSvgScene,nativeSvgGroup} from '../src/svg-native.js';
import {prepareInput} from '../src/input.js';
import {convertHtml} from '../src/service.js';
import JSZip from 'jszip';

const executablePath=process.env.HTML2PPTX_CHROMIUM_PATH||process.env.HTML2PPTX_TEST_BROWSER;
const skip=!(executablePath||process.platform==='linux'||existsSync('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'));
async function pageFor(t,html) {
  const scratch=await mkdtemp(path.join(os.tmpdir(),'html2pptx-svg-placement-'));
  const browser=await launchBrowser(scratch,executablePath);
  t.after(async()=>{await closeBrowser(browser);await rm(scratch,{recursive:true,force:true});});
  const page=await securedPage(browser,900,600);
  await page.setContent((await prepareInput(Buffer.from(html))).html);
  await page.evaluate(installSvgPlacementHook);
  return page;
}
function fixture(parentTransform='',rootTransform='',extra='') {
  return `<style>body{margin:0}.parent{position:absolute;left:250px;top:200px;width:240px;height:160px;transform:${parentTransform||'none'}}svg.diagram{display:block;width:240px;height:160px;transform:${rootTransform||'none'};transform-origin:30% 70%}#target{fill:#3478a1;stroke:#421;stroke-width:2}</style><div class="parent"><svg class="diagram" xmlns="http://www.w3.org/2000/svg" width="240" height="160" viewBox="10 20 200 100"><rect id="target" x="30" y="40" width="100" height="50"/>${extra}</svg></div>`;
}
async function normalized(page) {
  return page.evaluate(()=>{
    const root=document.querySelector('svg.diagram'),target=root.querySelector('#target'),rect=root.getBoundingClientRect(),box=target.getBBox(),matrix=target.getScreenCTM();
    const points=[[box.x,box.y],[box.x+box.width,box.y],[box.x+box.width,box.y+box.height],[box.x,box.y+box.height]].map(([x,y])=>new DOMPoint(x,y).matrixTransform(matrix));
    const expected={x:Math.min(...points.map(p=>p.x))-rect.left,y:Math.min(...points.map(p=>p.y))-rect.top,width:Math.max(...points.map(p=>p.x))-Math.min(...points.map(p=>p.x)),height:Math.max(...points.map(p=>p.y))-Math.min(...points.map(p=>p.y))};
    const item=globalThis.__convertX2XSvgRender(root,{items:[],job:()=>{}},{offX:0,offY:0,rootX:0,rootY:0,scale:1},1,[0],1).items[0];
    return {svg:decodeURIComponent(escape(atob(item.options.data.split(',')[1]))),expected,options:item.options};
  });
}
for(const [name,parentTransform,rootTransform]of [
  ['parent rotation','rotate(17.25deg)',''],
  ['parent uniform scale','scale(1.35)',''],
  ['parent anisotropic scale','scale(1.4,.8)',''],
  ['parent skew','skewX(23deg)',''],
  ['root rotation','','rotate(17.25deg)'],
  ['root anisotropic scale','','scale(1.4,.8)'],
  ['combined parent and root transforms','rotate(21deg) scale(1.1)','translate(17px,-9px) rotate(-13.25deg)'],
])test(`SVG ${name} retains browser geometry exactly once`,{skip,timeout:15000},async t=>{
  const page=await pageFor(t,fixture(parentTransform,rootTransform));
  const normalizedSvg=await normalized(page);
  const scene=await page.evaluate(extractSvgScene,normalizedSvg.svg);
  const shape=scene.items.find(i=>i.type==='rect');assert.ok(shape);
  for(const key of ['x','y','width','height'])assert.ok(Math.abs(shape.bounds[key]-normalizedSvg.expected[key])<.002,`${key}: ${shape.bounds[key]} vs ${normalizedSvg.expected[key]}`);
  assert.equal(normalizedSvg.options.rotate,0);
  const native=await nativeSvgGroup({page,svg:normalizedSvg.svg,x:0,y:0,width:scene.width*9525,height:scene.height*9525});
  assert.ok(native.counts.shapes>=1);assert.equal(native.counts.images,0);
});
test('SVG CSS root rotation produces editable shapes and text through the complete converter',{skip,timeout:30000},async()=>{
  const result=await convertHtml(Buffer.from(fixture('rotate(11deg)','rotate(6.25deg)','<text x="40" y="70" font-size="12">Editable</text>')),{executablePath});
  const zip=await JSZip.loadAsync(result.pptx),xml=await zip.file('ppt/slides/slide1.xml').async('string');
  assert.equal(result.report.slides[0].images,0);assert.ok(xml.includes('Editable'));
  const rotations=[...xml.matchAll(/rot="([^"]+)"/g)].map(m=>Number(m[1])/60000);
  assert.ok(rotations.some(angle=>Math.abs(angle-17.25)<.001),JSON.stringify(rotations));
});
test('local use keeps inherited paint while explicit definition paint stays explicit',{skip,timeout:15000},async t=>{
  const extra='<defs><path id="icon" d="M0 0H12V12H0Z"/><path id="fixed" fill="#000000" d="M0 0H12V12H0Z"/></defs><use href="#icon" x="150" y="50" fill="#ff0000"/><use href="#fixed" x="175" y="50" fill="#ff0000"/>';
  const page=await pageFor(t,fixture('rotate(12deg)','',extra));
  const data=await normalized(page),scene=await page.evaluate(extractSvgScene,data.svg);
  const paints=scene.items.filter(i=>i.type==='path').map(i=>i.style.fill.color);
  assert.deepEqual(paints,['FF0000','000000']);
});
test('CSS effects on SVG children are rejected instead of lost during serialization',{skip,timeout:15000},async t=>{
  const page=await pageFor(t,fixture().replace('#target{','#target{filter:blur(2px);'));
  await assert.rejects(normalized(page),/UNSUPPORTED_LAYOUT:.*SVG/);
});
test('SVG-local selectors cannot override the new normalization wrapper',{skip,timeout:15000},async t=>{
  const html=fixture('rotate(17deg)').replace('<rect id="target"','<style>svg{width:240px!important;height:160px!important}g{transform:translate(3px,4px);opacity:.5}rect{rx:6px;x:35px}</style><rect id="target"');
  const page=await pageFor(t,html),data=await normalized(page),scene=await page.evaluate(extractSvgScene,data.svg);
  assert.ok(!data.svg.includes('<style'));
  const shape=scene.items.find(i=>i.type==='rect');
  for(const key of ['x','y','width','height'])assert.ok(Math.abs(shape.bounds[key]-data.expected[key])<.002,`${key}: ${shape.bounds[key]} vs ${data.expected[key]}`);
  assert.equal(shape.rx,6);assert.equal(shape.style.fill.alpha,1);
});
