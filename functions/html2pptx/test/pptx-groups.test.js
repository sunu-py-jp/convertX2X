import test from 'node:test';
import assert from 'node:assert/strict';
import {DOMParser,XMLSerializer} from '@xmldom/xmldom';
import {collapseSingleShapeGroup} from '../src/pptx-groups.js';

const P='http://schemas.openxmlformats.org/presentationml/2006/main';
const A='http://schemas.openxmlformats.org/drawingml/2006/main';
const all=(node,name)=>[...node.getElementsByTagName('*')].filter(element=>element.localName===name);
const xml=node=>new XMLSerializer().serializeToString(node);
const attributes=values=>Object.entries(values).map(([key,value])=>`${key}="${value}"`).join(' ');
const parentDefaults={x:1000000,y:2000000,cx:4000000,cy:3000000,chX:700000,chY:900000,chCx:4000000,chCy:3000000};
const childDefaults={x:1200000,y:1400000,cx:600000,cy:400000};
function transform(values,group=false){
  const {x,y,cx,cy,chX,chY,chCx,chCy,...orientation}=values;
  return `<a:xfrm ${attributes(orientation)}><a:off x="${x}" y="${y}"/><a:ext cx="${cx}" cy="${cy}"/>${group?`<a:chOff x="${chX}" y="${chY}"/><a:chExt cx="${chCx}" cy="${chCy}"/>`:''}</a:xfrm>`;
}
function fixture(parent={},child={},extra=''){
  const shape=`<p:sp><p:nvSpPr><p:cNvPr id="7" name="Editable label"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>${transform({...childDefaults,...child})}<a:custGeom><a:avLst/><a:gdLst/><a:ahLst/><a:cxnLst/><a:rect l="0" t="0" r="r" b="b"/><a:pathLst><a:path w="600000" h="400000"><a:moveTo><a:pt x="0" y="0"/></a:moveTo><a:lnTo><a:pt x="600000" y="400000"/></a:lnTo></a:path></a:pathLst></a:custGeom><a:solidFill><a:srgbClr val="185D9A"/></a:solidFill></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang="ja-JP"/><a:t>編集できる文字</a:t></a:r></a:p></p:txBody></p:sp>`;
  return new DOMParser().parseFromString(`<p:grpSp xmlns:p="${P}" xmlns:a="${A}"><p:nvGrpSpPr><p:cNvPr id="6" name="SVG group"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr>${transform({...parentDefaults,...parent},true)}</p:grpSpPr>${shape}${extra}</p:grpSp>`,'application/xml').documentElement;
}
function readTransform(node){
  const values={};
  for(const name of ['off','ext','chOff','chExt']){
    const element=all(node,name)[0];
    if(element)values[name]=Object.fromEntries([...element.attributes].map(attribute=>[attribute.name,Number(attribute.value)]));
  }
  values.rotation=Number(node.getAttribute('rot')||0)/60000*Math.PI/180;
  values.flipH=['1','true'].includes(node.getAttribute('flipH'));
  values.flipV=['1','true'].includes(node.getAttribute('flipV'));
  return values;
}
// Independently apply each OOXML transform to points: child-coordinate mapping,
// reflection about the bounding-box center, then clockwise rotation in y-down space.
function project(point,transform,group=false){
  const {off,ext,chOff,chExt,rotation,flipH,flipV}=transform;
  let [x,y]=point;
  if(group){x=(x-chOff.x)*ext.cx/chExt.cx;y=(y-chOff.y)*ext.cy/chExt.cy;}
  x-=ext.cx/2;y-=ext.cy/2;
  if(flipH)x=-x;if(flipV)y=-y;
  return [off.x+ext.cx/2+x*Math.cos(rotation)-y*Math.sin(rotation),off.y+ext.cy/2+x*Math.sin(rotation)+y*Math.cos(rotation)];
}
function content(node){
  return [node.nodeName,[...node.attributes||[]].filter(attribute=>!attribute.name.startsWith('xmlns')).map(attribute=>[attribute.name,attribute.value]),[...node.childNodes].map(child=>child.nodeType===1?content(child):child.nodeValue)];
}

test('collapsing a translated singleton preserves native geometry, formatting and text',()=>{
  const group=fixture(),before=all(group,'sp')[0];
  const preserved=['nvSpPr','custGeom','solidFill','txBody'].map(name=>content(all(before,name)[0]));
  const shape=collapseSingleShapeGroup(group);
  assert.equal(shape.localName,'sp');
  assert.equal(shape.namespaceURI,P);
  const transformed=readTransform(all(shape,'xfrm')[0]);
  assert.deepEqual(transformed.off,{x:1500000,y:2500000});
  assert.deepEqual(transformed.ext,{cx:600000,cy:400000});
  assert.deepEqual(['nvSpPr','custGeom','solidFill','txBody'].map(name=>content(all(shape,name)[0])),preserved);
  assert.equal(all(shape,'pic').length,0);
});

test('mixed parent and child rotations and reflections preserve every shape corner',()=>{
  for(const parentAngle of [0,37,90,180])for(const childAngle of [-22,0,71])for(let flips=0;flips<16;flips++){
    const parent={rot:parentAngle*60000,flipH:flips&1?'true':'false',flipV:flips&2?'1':'0'};
    const child={rot:childAngle*60000,flipH:flips&4?'1':'0',flipV:flips&8?'true':'false'};
    const group=fixture(parent,child);
    const parentTransform=readTransform(all(group,'xfrm')[0]);
    const childTransform=readTransform(all(all(group,'sp')[0],'xfrm')[0]);
    const shape=collapseSingleShapeGroup(group);
    assert.equal(shape.localName,'sp');
    const combined=readTransform(all(shape,'xfrm')[0]);
    for(const [x,y] of [[0,0],[1,0],[1,1],[0,1]]){
      const expected=project(project([x*childTransform.ext.cx,y*childTransform.ext.cy],childTransform),parentTransform,true);
      const actual=project([x*combined.ext.cx,y*combined.ext.cy],combined);
      const delta=Math.hypot(expected[0]-actual[0],expected[1]-actual[1]);
      assert.ok(delta<1.5,`parent=${parentAngle}, child=${childAngle}, flips=${flips}, corner=${x},${y}: ${JSON.stringify({expected,actual,delta})}`);
    }
  }
});

test('unsupported groups are returned intact without mutation',()=>{
  const cases=[
    ['multiple shapes',fixture({}, {},'<p:sp><p:nvSpPr/><p:spPr/></p:sp>')],
    ['shape and picture',fixture({}, {},'<p:pic><p:nvPicPr/><p:spPr/></p:pic>')],
    ['shape and nested group',fixture({}, {},'<p:grpSp><p:nvGrpSpPr/><p:grpSpPr/></p:grpSp>')],
    ['uniform scaling',fixture({chCx:2000000,chCy:1500000})],
    ['nonuniform scaling',fixture({chCx:2000000})],
    ['zero extent',fixture({cx:0,chCx:0})],
    ['negative extent',fixture({cy:-3000000,chCy:-3000000})]
  ];
  const missingTransform=fixture();
  const transformNode=all(missingTransform,'xfrm')[0];transformNode.parentNode.removeChild(transformNode);
  cases.push(['missing group transform',missingTransform]);
  const pictureOnly=fixture();
  const shape=all(pictureOnly,'sp')[0];shape.parentNode.replaceChild(pictureOnly.ownerDocument.createElementNS(P,'p:pic'),shape);
  cases.push(['picture only',pictureOnly]);
  for(const [label,group] of cases){
    const before=xml(group);
    assert.equal(collapseSingleShapeGroup(group),group,label);
    assert.equal(xml(group),before,`${label} must not be mutated`);
  }
});
