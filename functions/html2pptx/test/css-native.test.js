import test from 'node:test';
import assert from 'node:assert/strict';
import {existsSync} from 'node:fs';
import {readFile} from 'node:fs/promises';
import JSZip from 'jszip';
import {DOMParser} from '@xmldom/xmldom';
import {convertHtml} from '../src/service.js';
import {exporterSource} from '../src/exporter.js';
const browser=process.env.HTML2PPTX_CHROMIUM_PATH||process.env.HTML2PPTX_TEST_BROWSER;
const skip=!(browser||process.platform==='linux'||existsSync('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'));
const all=(d,name)=>[...d.getElementsByTagName('*')].filter(n=>n.localName===name);
const parse=s=>new DOMParser().parseFromString(s,'application/xml');
test('the pinned exporter retains its license and adds only trusted conversion hook',async()=>{
  const source=await exporterSource();assert.ok(source.includes('__convertX2XRender(node,result'));assert.ok(source.includes('domToPptx'));assert.equal(source,await exporterSource());
});
test('ordinary CSS polygons, a border triangle, gradient clipping and affine geometry become editable paths',{skip,timeout:30000},async()=>{
  const html=`<style>.shape{position:absolute;top:120px;width:160px;height:140px;background:#2574ab}.triangle{left:40px;clip-path:polygon(50% 0%,100% 100%,0 100%)}.concave{left:250px;clip-path:polygon(0 0,100% 0,65% 50%,100% 100%,0 100%,25% 50%)}.circle{left:470px;background:linear-gradient(120deg,#123,#9cd);clip-path:circle(60px at 50% 50%)}.border{left:700px;width:0;height:0;background:none;border-left:70px solid transparent;border-right:70px solid transparent;border-bottom:130px solid #48a}.affine{left:950px;transform:skew(23deg) rotate(14deg);border-radius:20px 40px 10px 30px}</style><section data-slide><div class="shape triangle"></div><div class="shape concave"></div><div class="shape circle"></div><div class="shape border"></div><div class="shape affine"></div></section>`;
  const result=await convertHtml(Buffer.from(html),{executablePath:browser});const zip=await JSZip.loadAsync(result.pptx);
  const doc=parse(await zip.file('ppt/slides/slide1.xml').async('string'));
  assert.equal(all(doc,'pic').length,0);assert.ok(all(doc,'custGeom').length>=5);assert.ok(all(doc,'gradFill').length>=1);
  assert.equal(Object.keys(zip.files).filter(n=>/^ppt\/media\//.test(n)&&!zip.files[n].dir).length,0);
});
test('parent rotation and fractional angles are propagated to editable text',{skip,timeout:30000},async()=>{
  const result=await convertHtml(Buffer.from('<div style="position:absolute;left:200px;top:160px;width:300px;height:120px;transform:rotate(17.25deg);background:#def"><p style="margin:0;width:300px;height:120px;font-size:24px">回転した文字</p></div>'),{executablePath:browser});
  const zip=await JSZip.loadAsync(result.pptx),doc=parse(await zip.file('ppt/slides/slide1.xml').async('string'));
  const shape=all(doc,'sp').find(s=>all(s,'t').some(t=>t.textContent.includes('回転した文字')));assert.ok(shape);
  const angle=Number(all(shape,'xfrm')[0].getAttribute('rot'))/60000;assert.ok(Math.abs(angle-17.25)<.001,angle);
});
test('clipping that would silently remove text is rejected',{skip,timeout:30000},async()=>{
  await assert.rejects(convertHtml(Buffer.from('<div style="margin:50px;width:220px;height:80px;background:blue;color:white;clip-path:circle(20px at 50% 50%)">this sentence crosses the circular clipping edge</div>'),{executablePath:browser}),e=>e.code==='UNSUPPORTED_LAYOUT');
});
test('visual fixture converts every slide with native geometry and only three original raster pictures',{skip,timeout:40000},async()=>{
  const result=await convertHtml(await readFile(new URL('../examples/visual-fidelity.html',import.meta.url)),{executablePath:browser});
  assert.equal(result.report.slideCount,5);assert.equal(result.report.slides.reduce((n,s)=>n+s.images,0),3);
  const zip=await JSZip.loadAsync(result.pptx);assert.ok(!Object.keys(zip.files).some(n=>n.endsWith('.svg')));
  const fourth=parse(await zip.file('ppt/slides/slide4.xml').async('string'));assert.ok(all(fourth,'custGeom').length>=6);
});
test('CSS pseudo-element arrowheads are native objects without duplicate output',{skip,timeout:30000},async()=>{
  const html='<style>.arrow{position:absolute;left:180px;top:180px;width:180px;height:4px;background:#246}.arrow::after{content:"";position:absolute;right:-16px;top:-8px;width:0;height:0;border-left:16px solid #246;border-top:10px solid transparent;border-bottom:10px solid transparent}.dot::before{content:"";position:absolute;left:30px;top:100px;width:30px;height:30px;background:#d68;border-radius:50%}</style><div class="arrow"></div><div class="dot"></div>';
  const result=await convertHtml(Buffer.from(html),{executablePath:browser});
  assert.equal(result.report.slides[0].images,0);
  assert.equal(result.report.slides[0].svg.shapes,3);
});
test('raster picture inherits parent rotation and retains original image bytes',{skip,timeout:30000},async()=>{
  const png=await readFile(new URL('../examples/product-dashboard.png',import.meta.url));
  const html=`<div style="position:absolute;left:180px;top:180px;width:240px;height:140px;transform:rotate(30deg)"><img style="width:240px" src="data:image/png;base64,${png.toString('base64')}"></div>`;
  const result=await convertHtml(Buffer.from(html),{executablePath:browser});const zip=await JSZip.loadAsync(result.pptx),doc=parse(await zip.file('ppt/slides/slide1.xml').async('string'));
  const pic=all(doc,'pic')[0];assert.ok(Math.abs(Number(all(pic,'xfrm')[0].getAttribute('rot'))-1800000)<=2); // Browser matrix coefficients are rounded.
  const rels=parse(await zip.file('ppt/slides/_rels/slide1.xml.rels').async('string'));
  const rid=all(pic,'blip')[0].getAttribute('r:embed'),rel=all(rels,'Relationship').find(r=>r.getAttribute('Id')===rid);
  assert.ok(rel);assert.deepEqual(await zip.file('ppt/'+rel.getAttribute('Target').replace('../','')).async('nodebuffer'),png);
});
