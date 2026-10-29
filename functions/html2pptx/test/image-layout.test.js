import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import JSZip from 'jszip';
import { DOMParser } from '@xmldom/xmldom';
import { convertHtml } from '../src/service.js';
import { configuration } from '../src/config.js';
const executablePath=process.env.HTML2PPTX_CHROMIUM_PATH||process.env.HTML2PPTX_TEST_BROWSER;
const canRun=Boolean(executablePath||process.platform==='linux'||existsSync('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'));
const find=(node,name)=>[...node.getElementsByTagName('*')].filter(n=>n.localName===name);
const parse=xml=>new DOMParser().parseFromString(xml,'application/xml');
const px=(node,name)=>Number(node.getAttribute(name))/9525;
const source=await readFile(new URL('../examples/product-dashboard.png',import.meta.url));
const img=(style)=>`<img src="data:image/png;base64,${source.toString('base64')}" style="position:absolute;display:block;${style}">`;
const doc=(content)=>`<style>body{margin:0}section{position:relative;width:1280px;height:720px;background:white}</style><section data-slide>${content}</section>`;
const near=(a,b)=>assert.ok(Math.abs(a-b)<.01,`${a} differs from ${b}`);
const crop=pic=>Object.fromEntries(['l','t','r','b'].map(k=>[k,Number(find(pic,'srcRect')[0].getAttribute(k))/100000]));
const transform=pic=>{const t=find(pic,'xfrm')[0],o=find(t,'off')[0],e=find(t,'ext')[0];return{x:px(o,'x'),y:px(o,'y'),width:px(e,'cx'),height:px(e,'cy'),rotation:Number(t.getAttribute('rot'))/60000,flipV:t.getAttribute('flipV')==='1'};};

test('original pictures preserve resize, contain/cover crop, object position, rounded clipping and shared media',{skip:!canRun,timeout:60000},async()=>{
  const content=[
    img('left:20px;top:20px;width:200px;height:100px'),
    img('left:260px;top:20px;width:200px;height:200px;object-fit:contain'),
    img('left:500px;top:20px;width:200px;height:200px;object-fit:cover'),
    img('left:740px;top:20px;width:200px;height:200px;object-fit:cover;object-position:right bottom'),
    img('left:20px;top:300px;width:200px;height:150px;border-radius:30px'),
    img('left:260px;top:300px;width:200px;height:200px;object-fit:contain;border-radius:50%;background:#ddeeff'),
    img('left:500px;top:300px;width:200px;height:150px;border-radius:10px 30px 40px 5px;border:4px solid #114466;padding:8px'),
  ].join('');
  const result=await convertHtml(Buffer.from(doc(content)),{executablePath});
  const zip=await JSZip.loadAsync(result.pptx),slide=parse(await zip.file('ppt/slides/slide1.xml').async('string')),pics=find(slide,'pic');
  assert.equal(pics.length,7);assert.equal(result.report.slides[0].images,7);
  const media=Object.keys(zip.files).filter(n=>n.startsWith('ppt/media/')&&!zip.files[n].dir);assert.equal(media.length,1);assert.deepEqual(await zip.file(media[0]).async('nodebuffer'),source);
  const ratio=source.readUInt32BE(16)/source.readUInt32BE(20);
  assert.ok(ratio>1);
  assert.deepEqual(transform(pics[0]),{x:20,y:20,width:200,height:100,rotation:0,flipV:false});
  near(transform(pics[1]).height,200/ratio);near(transform(pics[1]).y,20+(200-200/ratio)/2);
  near(crop(pics[2]).l,(1-1/ratio)/2);near(crop(pics[2]).r,(1-1/ratio)/2);
  near(crop(pics[3]).l,1-1/ratio);near(crop(pics[3]).r,0);
  for(const pic of pics.slice(4))assert.equal(find(pic,'custGeom').length,1);
  assert.equal(find(slide,'svgBlip').length,0);
});

test('rotation, reflections, transformed parents and same-position opacity remain independent editable pictures',{skip:!canRun,timeout:60000},async()=>{
  const content=img('left:70px;top:70px;width:200px;height:100px;transform:rotate(30deg)')+
    img('left:350px;top:70px;width:200px;height:100px;transform:scaleX(-1)')+
    img('left:650px;top:70px;width:200px;height:100px;transform:scaleY(-1)')+
    `<div style="position:absolute;left:500px;top:250px;width:250px;height:200px;transform:rotate(20deg)">${img('left:0;top:0;width:200px;height:100px;transform:rotate(10deg)')}</div>`+
    img('left:70px;top:360px;width:200px;height:100px;opacity:.4')+img('left:70px;top:360px;width:200px;height:100px;opacity:.7');
  const result=await convertHtml(Buffer.from(doc(content)),{executablePath}),zip=await JSZip.loadAsync(result.pptx),slide=parse(await zip.file('ppt/slides/slide1.xml').async('string')),pics=find(slide,'pic');
  assert.equal(pics.length,6);
  const transforms=pics.map(transform);near(transforms[0].rotation,30);assert.equal(transforms[1].flipV,true);near(transforms[1].rotation,180);assert.equal(transforms[2].flipV,true);near(transforms[2].rotation,0);
  near(transforms[3].rotation,30);near(transforms[3].width,200);near(transforms[3].height,100);
  const angle=20*Math.PI/180;near(transforms[3].x+100,625-25*Math.cos(angle)+50*Math.sin(angle));near(transforms[3].y+50,350-25*Math.sin(angle)-50*Math.cos(angle));
  assert.deepEqual(pics.slice(4).map(p=>Number(find(p,'alphaModFix')[0].getAttribute('amt'))),[40000,70000]);
  assert.equal(Object.keys(zip.files).filter(n=>n.startsWith('ppt/media/')&&!zip.files[n].dir).length,1);
});

test('shared transport pictures resolve to distinct original images and deduplicate across slides',{skip:!canRun,timeout:60000},async()=>{
  const second=await readFile(new URL('../examples/image-pattern-photo.png',import.meta.url));
  const firstTag=img('left:40px;top:40px;width:200px;height:100px');
  const secondTag=`<img alt="写真 &amp; 確認" src="data:image/png;base64,${second.toString('base64')}" style="position:absolute;left:40px;top:40px;width:200px;height:100px;opacity:.6">`;
  const result=await convertHtml(Buffer.from(doc(firstTag+secondTag)+`<section data-slide>${secondTag+firstTag}</section>`),{executablePath});
  const zip=await JSZip.loadAsync(result.pptx);
  assert.equal(Object.keys(zip.files).filter(n=>n.startsWith('ppt/media/')&&!zip.files[n].dir).length,2);
  for(let slideIndex=1;slideIndex<=2;slideIndex++){
    const slide=parse(await zip.file(`ppt/slides/slide${slideIndex}.xml`).async('string'));
    const rels=parse(await zip.file(`ppt/slides/_rels/slide${slideIndex}.xml.rels`).async('string'));
    const pictures=find(slide,'pic');assert.equal(pictures.length,2);
    for(const [i,picture] of pictures.entries()){
      const rid=find(picture,'blip')[0].getAttribute('r:embed'),rel=find(rels,'Relationship').find(r=>r.getAttribute('Id')===rid);
      const data=await zip.file('ppt/'+rel.getAttribute('Target').replace('../','')).async('nodebuffer');
      assert.deepEqual(data,slideIndex===1?(i===0?source:second):(i===0?second:source));
    }
    assert.ok(pictures.some(p=>find(p,'cNvPr')[0].getAttribute('descr')==='写真 & 確認'));
  }
});

test('unsupported image shear, perspective, filter, shadow and ancestor clipping fail explicitly',{skip:!canRun,timeout:60000},async()=>{
  for(const content of [img('left:100px;top:100px;width:200px;height:100px;transform:skewX(15deg)'),img('left:100px;top:100px;width:200px;height:100px;transform:perspective(500px) rotateY(20deg)'),img('left:100px;top:100px;width:200px;height:100px;filter:grayscale(1)'),img('left:100px;top:100px;width:200px;height:100px;box-shadow:2px 2px 4px #333'),`<div style="position:absolute;left:100px;top:100px;width:100px;height:100px;overflow:hidden">${img('left:0;top:0;width:200px;height:100px')}</div>`])await assert.rejects(convertHtml(Buffer.from(doc(content)),{executablePath}),e=>e.code==='UNSUPPORTED_LAYOUT');
});

test('blur affects only its picture, keeps a transparent halo, and shares identical processed results',{skip:!canRun,timeout:60000},async()=>{
  const blurred=position=>img(`left:${position}px;top:100px;width:200px;height:100px;filter:blur(4px)`);
  const content=blurred(100)+blurred(400)+img('left:700px;top:100px;width:200px;height:100px')+'<p style="position:absolute;left:100px;top:120px;font-size:24px">鮮明な注釈</p>';
  const result=await convertHtml(Buffer.from(doc(content)),{executablePath}),zip=await JSZip.loadAsync(result.pptx),slide=parse(await zip.file('ppt/slides/slide1.xml').async('string')),pics=find(slide,'pic');
  assert.equal(pics.length,3);assert.ok(find(slide,'t').some(t=>t.textContent==='鮮明な注釈'));
  assert.equal(result.report.warnings.filter(w=>w.code==='IMAGE_BLUR_APPLIED').length,2);
  const first=transform(pics[0]);near(first.x,84);near(first.y,84);near(first.width,232);near(first.height,132);
  const rels=parse(await zip.file('ppt/slides/_rels/slide1.xml.rels').async('string'));
  const targets=pics.map(p=>find(rels,'Relationship').find(r=>r.getAttribute('Id')===find(p,'blip')[0].getAttribute('r:embed')).getAttribute('Target'));
  assert.equal(targets[0],targets[1]);assert.notEqual(targets[0],targets[2]);
  const bytes=await zip.file('ppt/'+targets[0].replace('../','')).async('nodebuffer');assert.equal(bytes.readUInt32BE(16),232);assert.equal(bytes.readUInt32BE(20),132);assert.notDeepEqual(bytes,source);
  await assert.rejects(convertHtml(Buffer.from(doc(img('left:100px;top:100px;width:200px;height:100px;filter:blur(10000px)'))),{executablePath}),e=>e.code==='UNSUPPORTED_LAYOUT');
  await assert.rejects(convertHtml(Buffer.from(doc(img('left:100px;top:50px;width:1000px;height:500px;filter:blur(4px)')+img('left:100px;top:50px;width:1001px;height:500px;filter:blur(4px)'))),{executablePath,limits:{...configuration(),maxTotalImagePixels:1300000}}),e=>e.code==='UNSUPPORTED_LAYOUT'&&e.details.some(d=>/総画素/.test(d.message)));
});

test('group opacity rejects overlapping composites while preserving a single image parent',{skip:!canRun,timeout:60000},async()=>{
  const single=`<div style="opacity:.5">${img('left:100px;top:100px;width:200px;height:100px')}</div>`;
  const result=await convertHtml(Buffer.from(doc(single)),{executablePath}),zip=await JSZip.loadAsync(result.pptx),slide=parse(await zip.file('ppt/slides/slide1.xml').async('string'));
  assert.equal(find(slide,'alphaModFix')[0].getAttribute('amt'),'50000');
  const overlap=`<div style="opacity:.5">${img('left:100px;top:100px;width:200px;height:100px')}${img('left:150px;top:100px;width:200px;height:100px')}</div>`;
  const background=`<div style="position:absolute;left:100px;top:100px;width:200px;height:100px;background:white;opacity:.5">${img('left:0;top:0;width:200px;height:100px')}</div>`;
  for(const content of [overlap,background])await assert.rejects(convertHtml(Buffer.from(doc(content)),{executablePath}),e=>e.code==='UNSUPPORTED_LAYOUT');
});

test('SVG group opacity counts painted shapes and strokes without counting the viewport or definitions twice',{skip:!canRun,timeout:60000},async()=>{
  const svg=body=>`<svg xmlns="http://www.w3.org/2000/svg" width="400" height="300" style="position:absolute;left:100px;top:100px;opacity:.5">${body}</svg>`;
  const single='<defs><rect id="unused" width="300" height="200"/></defs><rect x="20" y="20" width="100" height="100" fill="red"/>';
  const result=await convertHtml(Buffer.from(doc(svg(single))),{executablePath}),zip=await JSZip.loadAsync(result.pptx),slide=parse(await zip.file('ppt/slides/slide1.xml').async('string'));
  assert.ok(find(slide,'alpha').some(n=>n.getAttribute('val')==='50000'));
  for(const content of [
    svg('<rect x="20" y="20" width="100" height="100" fill="red"/><rect x="70" y="20" width="100" height="100" fill="blue"/>'),
    svg('<line x1="20" y1="60" x2="180" y2="60" stroke="red" stroke-width="8"/><line x1="100" y1="20" x2="100" y2="120" stroke="blue" stroke-width="8"/>'),
    '<svg xmlns="http://www.w3.org/2000/svg" width="400" height="300"><g opacity=".5"><rect x="20" y="20" width="100" height="100"/><circle cx="120" cy="70" r="50"/></g></svg>',
  ])await assert.rejects(convertHtml(Buffer.from(doc(content)),{executablePath}),e=>e.code==='UNSUPPORTED_LAYOUT');
});
