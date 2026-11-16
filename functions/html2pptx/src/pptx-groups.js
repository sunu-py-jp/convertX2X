const P='http://schemas.openxmlformats.org/presentationml/2006/main';
const A='http://schemas.openxmlformats.org/drawingml/2006/main';
const children=node=>Array.from(node?.childNodes||[]).filter(n=>n.nodeType===1);
const child=(node,ns,name)=>children(node).find(n=>n.namespaceURI===ns&&n.localName===name);
const num=(node,name)=>Number(node?.getAttribute(name)||0);
const flipped=(node,name)=>['1','true'].includes(node?.getAttribute(name));

// Our SVG exporter uses groups even for a single native rectangle. Apple's
// multi-slide Quick Look preview can reuse such a group's rendered resource on
// later slides. A single shape needs no group: compose its transform instead.
// Do not flatten multi-shape diagrams or general scaled DrawingML groups.
export function collapseSingleShapeGroup(group){
  if(group?.namespaceURI!==P||group.localName!=='grpSp')return group;
  const objects=children(group).filter(n=>!['nvGrpSpPr','grpSpPr'].includes(n.localName));
  if(objects.length!==1||objects[0].namespaceURI!==P||objects[0].localName!=='sp')return group;
  const shape=objects[0],properties=child(group,P,'grpSpPr');
  if(children(properties).some(n=>n.namespaceURI!==A||n.localName!=='xfrm'))return group;
  const outer=child(properties,A,'xfrm'),inner=child(child(shape,P,'spPr'),A,'xfrm');
  const off=child(outer,A,'off'),ext=child(outer,A,'ext'),chOff=child(outer,A,'chOff'),chExt=child(outer,A,'chExt');
  const shapeOff=child(inner,A,'off'),shapeExt=child(inner,A,'ext');
  if(!off||!ext||!chOff||!chExt||!shapeOff||!shapeExt)return group;
  const w=num(ext,'cx'),h=num(ext,'cy');
  if(!(w>0&&h>0)||w!==num(chExt,'cx')||h!==num(chExt,'cy'))return group;
  const flipH=flipped(outer,'flipH'),flipV=flipped(outer,'flipV');
  const sx=flipH?-1:1,sy=flipV?-1:1,angle=num(outer,'rot')/60000*Math.PI/180;
  const dx=(num(shapeOff,'x')+num(shapeExt,'cx')/2-num(chOff,'x')-w/2)*sx;
  const dy=(num(shapeOff,'y')+num(shapeExt,'cy')/2-num(chOff,'y')-h/2)*sy;
  shapeOff.setAttribute('x',String(Math.round(num(off,'x')+w/2+dx*Math.cos(angle)-dy*Math.sin(angle)-num(shapeExt,'cx')/2)));
  shapeOff.setAttribute('y',String(Math.round(num(off,'y')+h/2+dx*Math.sin(angle)+dy*Math.cos(angle)-num(shapeExt,'cy')/2)));
  const rotation=((num(outer,'rot')+sx*sy*num(inner,'rot'))%21600000+21600000)%21600000;
  if(rotation)inner.setAttribute('rot',String(rotation));else inner.removeAttribute('rot');
  for(const [name,flip] of [['flipH',flipH],['flipV',flipV]]){
    if(flip!==flipped(inner,name))inner.setAttribute(name,'1');else inner.removeAttribute(name);
  }
  return shape;
}
