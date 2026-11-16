import test from 'node:test';
import assert from 'node:assert/strict';
import {existsSync} from 'node:fs';
import {DOMParser} from '@xmldom/xmldom';
import puppeteer from 'puppeteer-core';
import {nativeSvgGroup,svgSceneToDrawingML} from '../src/svg-native.js';

const identity=[1,0,0,1,0,0];
const rectangle='M10 20L110 20L110 80L10 80Z';
const paint={fill:{color:'185D9A',alpha:0.65},stroke:null,strokeWidth:0,dash:[],linecap:'butt',linejoin:'miter'};
const all=(node,name)=>[...node.getElementsByTagName('*')].filter(element=>element.localName===name);
const parse=xml=>new DOMParser().parseFromString(xml,'application/xml');
const numbers=node=>Object.fromEntries([...node.attributes].map(attribute=>[attribute.name,Number(attribute.value)]));
function item(d=rectangle,matrix=identity,style=paint){
  const corners=[[10,20],[110,20],[110,80],[10,80]].map(([x,y])=>[matrix[0]*x+matrix[2]*y+matrix[4],matrix[1]*x+matrix[3]*y+matrix[5]]);
  const xs=corners.map(point=>point[0]),ys=corners.map(point=>point[1]);
  const bounds={x:Math.min(...xs),y:Math.min(...ys),width:Math.max(...xs)-Math.min(...xs),height:Math.max(...ys)-Math.min(...ys)};
  return {type:'path',d,matrix,bounds,style};
}
function convert(shape){
  const result=svgSceneToDrawingML({width:400,height:300,items:[shape]},{x:17000,y:23000,width:3810000,height:2857500,idStart:40});
  return {result,doc:parse(result.xml)};
}

test('exact fill-only rectangle paths become native rectangles with unchanged paint and placement',()=>{
  const cases=[
    ['clockwise',rectangle,identity],
    ['reversed winding','M10 80L110 80L110 20L10 20Z',identity],
    ['horizontal and vertical commands','M10 20H110V80H10Z',identity],
    ['relative commands','m10 20h100v60h-100z',identity],
    ['translated nonuniform scaling',rectangle,[2,0,0,3,15,5]],
    ['horizontal reflection',rectangle,[-2,0,0,3,240,5]],
    ['vertical reflection',rectangle,[1,0,0,-1,0,100]],
    ['quarter turn',rectangle,[0,1,-1,0,200,0]]
  ];
  for(const [label,d,matrix] of cases){
    const shape=item(d,matrix),{result,doc}=convert(shape);
    assert.deepEqual(all(doc,'prstGeom').map(node=>node.getAttribute('prst')),['rect'],label);
    assert.equal(all(doc,'custGeom').length,0,label);
    assert.equal(all(doc,'pic').length,0,label);
    assert.equal(result.counts.shapes,1,label);
    assert.equal(all(doc,'srgbClr')[0].getAttribute('val'),'185D9A',label);
    assert.equal(all(doc,'alpha')[0].getAttribute('val'),'65000',label);
    const [group,child]=all(doc,'xfrm');
    assert.deepEqual(numbers(all(group,'off')[0]),{x:17000,y:23000},label);
    assert.deepEqual(numbers(all(child,'off')[0]),{x:Math.round(shape.bounds.x*9525),y:Math.round(shape.bounds.y*9525)},label);
    assert.deepEqual(numbers(all(child,'ext')[0]),{cx:Math.round(shape.bounds.width*9525),cy:Math.round(shape.bounds.height*9525)},label);
    assert.equal(all(all(doc,'ln')[0],'noFill').length,1,label);
  }
});

test('rectangle path canonicalization preserves native gradients and alpha stops',()=>{
  const fill={type:'linearGradient',angle:90,stops:[{offset:0,color:'FFFFFF',alpha:0.25},{offset:1,color:'185D9A',alpha:0.8}]};
  const {result,doc}=convert(item(rectangle,identity,{...paint,fill}));
  assert.equal(all(doc,'prstGeom')[0].getAttribute('prst'),'rect');
  assert.equal(result.counts.gradients,1);
  assert.deepEqual(all(doc,'srgbClr').map(node=>node.getAttribute('val')),['FFFFFF','185D9A']);
  assert.deepEqual(all(doc,'alpha').map(node=>node.getAttribute('val')),['25000','80000']);
  assert.equal(all(doc,'lin')[0].getAttribute('ang'),'5400000');
});

test('nonrectangular, compound, curved and open paths retain their custom geometry',()=>{
  const cases=[
    ['bowtie','M10 20L110 80L110 20L10 80Z'],
    ['triangle','M10 20L110 20L10 80Z'],
    ['multiple subpaths',rectangle+'M30 40H50V60H30Z'],
    ['curve','M10 20C30 20 90 20 110 20L110 80L10 80Z'],
    ['almost rectangle','M10 20L110 20L109.999 80L10 80Z'],
    ['duplicate corner','M10 20L110 20L110 20L10 80Z'],
    ['open perimeter','M10 20L110 20L110 80L10 80']
  ];
  for(const [label,d] of cases){
    const {doc}=convert(item(d));
    assert.equal(all(doc,'prstGeom').length,0,label);
    assert.equal(all(doc,'custGeom').length,1,label);
    assert.equal(all(doc,'pic').length,0,label);
  }
  const angle=Math.PI/12;
  const {doc}=convert(item(rectangle,[Math.cos(angle),Math.sin(angle),-Math.sin(angle),Math.cos(angle),40,0]));
  assert.equal(all(doc,'prstGeom').length,0,'rotated non-axis-aligned rectangle');
  assert.equal(all(doc,'custGeom').length,1);
});

test('visible strokes retain path order and dash phase instead of becoming presets',()=>{
  const stroke={color:'123456',alpha:0.8};
  for(const [label,extra] of [
    ['solid',{}],
    ['standard dash',{dash:[4,4],dashRaw:[4,4]}],
    ['offset dash',{dash:[6,4],dashRaw:[6,4],dashOffset:3}]
  ]){
    const {doc}=convert(item('M110 80H10V20H110Z',identity,{...paint,stroke,strokeWidth:2,...extra}));
    assert.equal(all(doc,'prstGeom').length,0,label);
    assert.equal(all(doc,'custGeom').length,1,label);
    assert.ok(all(doc,'srgbClr').some(node=>node.getAttribute('val')==='123456'),label);
    if(label==='standard dash')assert.equal(all(doc,'prstDash')[0].getAttribute('val'),'sysDash');
    if(label==='offset dash')assert.ok(all(doc,'moveTo').length>4,'offset dashes must retain their separate path pieces');
    else assert.deepEqual(numbers(all(all(doc,'moveTo')[0],'pt')[0]),{x:952500,y:571500},'path start must stay at the bottom-right corner');
  }
});

const executablePath=process.env.HTML2PPTX_CHROMIUM_PATH||process.env.HTML2PPTX_TEST_BROWSER||(existsSync('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome')?'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome':undefined);
test('a thin nonuniformly transformed line becomes an editable native rectangle outline',{skip:!executablePath,timeout:30000},async()=>{
  const browser=await puppeteer.launch({executablePath,headless:true});
  try{
    const page=await browser.newPage();await page.setContent('<html><body></body></html>');
    const svg='<svg xmlns="http://www.w3.org/2000/svg" width="200" height="40"><line x1="10" y1="10" x2="90" y2="10" stroke="#185d9a" stroke-opacity="0.6" stroke-width="0.5" transform="scale(2 1)"/></svg>';
    const result=await nativeSvgGroup({page,svg,width:1905000,height:381000});
    const doc=parse(result.xml);
    assert.deepEqual(all(doc,'prstGeom').map(node=>node.getAttribute('prst')),['rect']);
    assert.equal(all(doc,'custGeom').length,0);
    assert.equal(all(doc,'pic').length,0);
    assert.equal(result.counts.shapes,1);
    assert.equal(all(doc,'srgbClr')[0].getAttribute('val'),'185D9A');
    assert.equal(all(doc,'alpha')[0].getAttribute('val'),'60000');
    const transform=all(all(doc,'spPr')[0],'xfrm')[0];
    assert.deepEqual(numbers(all(transform,'off')[0]),{x:190500,y:92869});
    const extent=numbers(all(transform,'ext')[0]);
    assert.equal(extent.cx,1524000);
    assert.ok(Math.abs(extent.cy-0.5*9525)<=0.5,'half-pixel stroke height must be preserved to the nearest EMU');
  }finally{await browser.close();}
});
