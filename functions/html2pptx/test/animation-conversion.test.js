import test from 'node:test';
import assert from 'node:assert/strict';
import {existsSync} from 'node:fs';
import {readFile} from 'node:fs/promises';
import JSZip from 'jszip';
import {DOMParser} from '@xmldom/xmldom';
import {convertHtml} from '../src/service.js';

const browser=process.env.HTML2PPTX_CHROMIUM_PATH||process.env.HTML2PPTX_TEST_BROWSER;
const canRun=Boolean(browser||process.platform==='linux'||existsSync('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'));
const nodes=(d,name)=>[...d.getElementsByTagName('*')].filter(n=>n.localName===name);
const parse=xml=>new DOMParser().parseFromString(xml,'application/xml');
const convert=html=>convertHtml(Buffer.from(html),{executablePath:browser,captureHtml:true});
const documentOf=async result=>parse(await (await JSZip.loadAsync(result.pptx)).file('ppt/slides/slide1.xml').async('string'));

test('arbitrary keyframe names become native timing tied to final editable groups',{skip:!canRun,timeout:60000},async()=>{
  const css=`.card{position:absolute;left:80px;top:120px;width:400px;height:180px;background:#dceaff;border:2px solid #245;border-radius:12px;padding:20px;box-sizing:border-box}
    .card h1{font-size:28px}.card p{font-size:18px}
    @keyframes arbitrary-introduction{from{opacity:0;transform:translateY(40px)}to{opacity:1;transform:translateY(0)}}`;
  const contents='<h1>編集できるカード</h1><p>背景と文字を一緒に表示</p>';
  const result=await convert(`<style>${css}.card{animation:arbitrary-introduction 800ms 200ms linear both}</style><section data-slide><div class="card">${contents}</div></section>`);
  assert.equal(result.report.animations.nativeEffects.length,1);
  assert.equal(result.report.animations.staticEffects.length,0);
  const doc=await documentOf(result);
  assert.equal(nodes(doc,'timing').length,1);
  const ids=nodes(doc,'cNvPr').map(n=>n.getAttribute('id'));
  assert.equal(new Set(ids).size,ids.length);
  const targets=nodes(doc,'spTgt').map(n=>n.getAttribute('spid'));
  assert.ok(targets.length>0);
  for(const id of targets)assert.ok(ids.includes(id),`dangling target ${id}`);
  assert.ok(nodes(doc,'animEffect').length>0);
  assert.ok(nodes(doc,'anim').length>0);
  assert.match(nodes(doc,'t').map(n=>n.textContent).join(''),/編集できるカード/);
  assert.equal(nodes(doc,'pic').length,0);
  const archive=await JSZip.loadAsync(result.pptx);
  const presentation=parse(await archive.file('ppt/presentation.xml').async('string'));
  const sequence=[...presentation.documentElement.childNodes].filter(n=>n.nodeType===1).map(n=>n.localName);
  if(sequence.includes('notesMasterIdLst'))assert.ok(sequence.indexOf('notesMasterIdLst')<sequence.indexOf('sldIdLst'));
  // Freezing an animation must not change the terminal geometry or add a wait.
  const reference=await convert(`<style>${css}</style><section data-slide><div class="card">${contents}</div></section>`);
  assert.deepEqual(result.previews[0],reference.previews[0]);
});

test('original pictures and SVG replacement groups keep animation ownership',{skip:!canRun,timeout:60000},async()=>{
  const png=await readFile(new URL('../examples/product-dashboard.png',import.meta.url));
  const result=await convert(`<style>@keyframes entry{from{opacity:0;transform:translateX(-30px)}to{opacity:1;transform:none}}
    img,svg{position:absolute;top:100px;animation:entry 1s linear both}img{left:80px;width:300px;height:200px}svg{left:500px;animation-delay:1s}</style>
    <section data-slide><img src="data:image/png;base64,${png.toString('base64')}"><svg width="200" height="200" viewBox="0 0 200 200"><circle cx="100" cy="100" r="80" fill="#58a"/><text x="80" y="105" fill="white">図形</text></svg></section>`);
  assert.equal(result.report.animations.nativeEffects.length,2);
  const doc=await documentOf(result),ids=new Set(nodes(doc,'cNvPr').map(n=>n.getAttribute('id')));
  assert.equal(nodes(doc,'pic').length,1);
  for(const t of nodes(doc,'spTgt'))assert.ok(ids.has(t.getAttribute('spid')));
  assert.match(nodes(doc,'t').map(n=>n.textContent).join(''),/図形/);
  assert.equal(nodes(doc,'svgBlip').length,0);
});

test('unsupported finite animation freezes final appearance and infinite loop is explicit',{skip:!canRun,timeout:60000},async()=>{
  const result=await convert(`<style>@keyframes palette{from{background-color:red;border-radius:0}to{background-color:blue;border-radius:20px}}
    @keyframes forever{from{transform:translateX(0)}to{transform:translateX(100px)}}
    .box{position:absolute;left:80px;top:80px;width:200px;height:100px;animation:palette 20s linear}.loop{position:absolute;left:400px;top:80px;width:80px;height:80px;background:green;animation:forever 10s infinite}
    </style><section data-slide><div class="box"></div><div class="loop"></div></section>`);
  assert.equal(result.report.animations.nativeEffects.length,0);
  assert.ok(result.report.warnings.filter(w=>w.code==='ANIMATION_STATIC_FALLBACK').length>=2);
  const doc=await documentOf(result);
  assert.equal(nodes(doc,'timing').length,0);
  assert.ok(nodes(doc,'srgbClr').some(n=>n.getAttribute('val')?.toUpperCase()==='0000FF'));
  const reference=await convert(`<style>.box{position:absolute;left:80px;top:80px;width:200px;height:100px;background:blue;border-radius:20px}.loop{position:absolute;left:500px;top:80px;width:80px;height:80px;background:green}</style><section data-slide><div class="box"></div><div class="loop"></div></section>`);
  assert.deepEqual(result.previews[0],reference.previews[0]);
});

test('preset-looking class names alone never create animation',{skip:!canRun,timeout:30000},async()=>{
  const result=await convert('<section data-slide><h1 class="fade-in animate-duration-1000">静止した本文</h1></section>');
  assert.equal(result.report.animations.nativeEffects.length,0);
  assert.equal(nodes(await documentOf(result),'timing').length,0);
});

test('same-element fade and motion become one native editable owner with both source animations',{skip:!canRun,timeout:60000},async()=>{
  const css='.card{position:absolute;left:80px;top:120px;width:400px;height:180px;background:#dceaff;border:2px solid #245;border-radius:12px;padding:20px;box-sizing:border-box}.card h1{font-size:28px}.card p{font-size:18px}';
  const contents='<h1>複数の動きをまとめる</h1><p>背景・枠・文字が同じ対象として動く</p>';
  const keyframes='@keyframes enter{from{opacity:0}to{opacity:1}}@keyframes move{from{transform:translateY(40px)}to{transform:none}}';
  const result=await convert(`<style>${css}${keyframes}.card{animation:enter 800ms 200ms linear both,move 1500ms 500ms linear both}</style><section data-slide><div class="card">${contents}</div></section>`);
  assert.equal(result.report.animations.nativeEffects.length,1);
  assert.equal(result.report.animations.staticEffects.length,0);
  const effect=result.report.animations.nativeEffects[0];
  assert.deepEqual(effect.sourceAnimations,[
    {id:'animation-1',animationName:'enter',durationMs:800,delayMs:200},
    {id:'animation-2',animationName:'move',durationMs:1500,delayMs:500}
  ]);
  assert.equal(effect.id,'animation-1');
  const doc=await documentOf(result),owners=nodes(doc,'cNvPr').filter(node=>node.getAttribute('name').startsWith('CSS animation '));
  assert.equal(owners.length,1);
  assert.equal(owners[0].getAttribute('id'),effect.targetShapeId);
  assert.ok(nodes(owners[0].parentNode.parentNode,'sp').length>1,'background and editable text must share the one animation owner');
  assert.deepEqual([...new Set(nodes(doc,'spTgt').map(node=>node.getAttribute('spid')))],[effect.targetShapeId]);
  assert.equal(nodes(doc,'timing').length,1);
  assert.equal(nodes(doc,'animEffect').length,1);
  assert.ok(nodes(doc,'anim').length>0);
  assert.equal(nodes(doc,'pic').length,0);
  const reference=await convert(`<style>${css}</style><section data-slide><div class="card">${contents}</div></section>`);
  assert.deepEqual(result.previews[0],reference.previews[0]);
});

test('one unsupported member prevents all native motion on that element and preserves the final screenshot',{skip:!canRun,timeout:60000},async()=>{
  const css='.card{position:absolute;left:80px;top:120px;width:400px;height:180px;background:red;border-radius:12px;padding:20px;box-sizing:border-box}.card h1{font-size:28px}.card p{font-size:18px}';
  const contents='<h1>静止状態へまとめて切り替え</h1><p>移動だけを部分的に残さない</p>';
  const result=await convert(`<style>${css}@keyframes move{from{transform:translateX(40px)}to{transform:none}}@keyframes palette{from{background:red}to{background:blue}}.card{animation:move 1s linear both,palette 2s linear both}</style><section data-slide><div class="card">${contents}</div></section>`);
  assert.equal(result.report.animations.nativeEffects.length,0);
  assert.equal(result.report.animations.staticEffects.length,2);
  assert.ok(result.report.animations.staticEffects.every(effect=>effect.reason==='unsupported-properties'));
  assert.deepEqual(result.report.animations.staticEffects.map(effect=>effect.animationName),['move','palette']);
  const doc=await documentOf(result);
  assert.equal(nodes(doc,'timing').length,0);
  assert.equal(nodes(doc,'cNvPr').filter(node=>node.getAttribute('name').startsWith('CSS animation ')).length,0);
  assert.ok(nodes(doc,'srgbClr').some(node=>node.getAttribute('val')?.toUpperCase()==='0000FF'));
  const reference=await convert(`<style>${css}.card{background:blue}</style><section data-slide><div class="card">${contents}</div></section>`);
  assert.deepEqual(result.previews[0],reference.previews[0]);
});
