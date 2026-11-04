import JSZip from 'jszip';
import { DOMParser, XMLSerializer } from '@xmldom/xmldom';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { nativeSvgGroup } from './svg-native.js';
import { ConversionError } from './errors.js';
import { FONT_FAMILY } from './fonts.js';
import { applyNativeAnimations } from './pptx-animations.js';

const P='http://schemas.openxmlformats.org/presentationml/2006/main';
const A='http://schemas.openxmlformats.org/drawingml/2006/main';
const R='http://schemas.openxmlformats.org/officeDocument/2006/relationships';
const SVG='http://schemas.microsoft.com/office/drawing/2016/SVG/main';
const parse = text => new DOMParser().parseFromString(text,'application/xml');
const serialize = node => new XMLSerializer().serializeToString(node);
const list = (node,ns,name) => Array.from(node.getElementsByTagNameNS(ns,name));
const attrNum = (node,name) => Number(node?.getAttribute(name)||0);
const fail = (code,message) => {throw new ConversionError(code,message,422);};

// Intersect a rounded content box with the visible object-fit rectangle. Native
// picture geometry keeps the source bitmap intact, including transparent corners.
function pictureGeometry(doc,clip){
  const w=clip.boxWidth,h=clip.boxHeight;
  if(!clip.radii.some(pair=>pair.some(radius=>radius>0)))return null;
  let points=[];
  const centers=[[clip.radii[0][0],clip.radii[0][1]],[w-clip.radii[1][0],clip.radii[1][1]],[w-clip.radii[2][0],h-clip.radii[2][1]],[clip.radii[3][0],h-clip.radii[3][1]]];
  for(let corner=0;corner<4;corner++){
    const [rx,ry]=clip.radii[corner],radius=Math.max(rx,ry),count=Math.max(1,Math.ceil(Math.PI/2/Math.acos(Math.max(-1,1-.025/Math.max(radius,.025)))));
    const [cx,cy]=centers[corner],start=Math.PI+corner*Math.PI/2;
    for(let i=0;i<=count;i++){const angle=start+i/count*Math.PI/2;points.push([cx+rx*Math.cos(angle),cy+ry*Math.sin(angle)]);}
  }
  for(const [axis,bound,sign] of [[0,clip.x,1],[0,clip.x+clip.width,-1],[1,clip.y,1],[1,clip.y+clip.height,-1]]){
    const next=[];
    for(let i=0;i<points.length;i++){
      const a=points[i],b=points[(i+1)%points.length],insideA=(a[axis]-bound)*sign>=-1e-8,insideB=(b[axis]-bound)*sign>=-1e-8;
      if(insideA)next.push(a);
      if(insideA!==insideB){const t=(bound-a[axis])/(b[axis]-a[axis]);next.push([a[0]+t*(b[0]-a[0]),a[1]+t*(b[1]-a[1])]);}
    }points=next;
  }
  const pt=([x,y])=>`<a:pt x="${Math.round((x-clip.x)*9525)}" y="${Math.round((y-clip.y)*9525)}"/>`;
  const commands=points.map((p,i)=>`<a:${i?'lnTo':'moveTo'}>${pt(p)}</a:${i?'lnTo':'moveTo'}>`).join('');
  return doc.importNode(parse(`<a:custGeom xmlns:a="${A}"><a:avLst/><a:gdLst/><a:ahLst/><a:cxnLst/><a:rect l="0" t="0" r="r" b="b"/><a:pathLst><a:path w="${Math.max(1,Math.round(clip.width*9525))}" h="${Math.max(1,Math.round(clip.height*9525))}" stroke="0">${commands}${points.length?'<a:close/>':''}</a:path></a:pathLst></a:custGeom>`).documentElement,true);
}

export async function finalizePptx(buffer,page,{layouts,imageSources=[],limits,animationEffects=[],animationOrders=[],width,height}) {
  const zip=await JSZip.loadAsync(buffer,{checkCRC32:true});
  const names=Object.keys(zip.files).filter(n=>/^ppt\/slides\/slide\d+\.xml$/.test(n)).sort((a,b)=>Number(a.match(/(\d+)\.xml$/)[1])-Number(b.match(/(\d+)\.xml$/)[1]));
  if(names.length!==layouts.length) fail('OUTPUT_INVALID','スライド数が入力と一致しません。');
  const report=[],warnings=[],nativeEffects=[],fallbackEffects=[];
  const originalMedia=new Map();
  let totalShapes=0;
  for(const [index,name] of names.entries()) {
    const doc=parse(await zip.file(name).async('string'));
    const effects=animationEffects.filter(effect=>effect.slide===index+1);
    const targets=new Map(effects.map(effect=>[effect.id,[]]));
    // Read exporter ownership before SVGs become native groups and pictures are
    // renamed. IDs are not stable yet: retain object references, never old IDs.
    if(effects.length){
      const tree=list(doc,P,'spTree')[0];
      for(const object of Array.from(tree.childNodes).filter(n=>n.nodeType===1&&['sp','pic','graphicFrame','grpSp','cxnSp'].includes(n.localName))){
        const property=list(object,P,'cNvPr')[0],name=property?.getAttribute('name')||'';
        const raw=name.match(/__dom_(\d+)__type_/),clean=name.match(/^(?:TextBox|Picture|Table|Line|Shape) (\d+)$/);
        const order=raw?Number(raw[1]):clean?Number(clean[1])-1:null;
        if(order===null)continue;
        for(const effect of effects)if(animationOrders[index]?.[effect.id]?.includes(order))targets.get(effect.id).push(object);
      }
    }
    const backgroundColor=layouts[index].backgroundColor;
    if(/^[A-F0-9]{6}$/.test(backgroundColor||'')){
      const slide=list(doc,P,'cSld')[0];
      for(const old of list(slide,P,'bg'))old.parentNode.removeChild(old);
      const background=parse(`<p:bg xmlns:p="${P}" xmlns:a="${A}"><p:bgPr><a:solidFill><a:srgbClr val="${backgroundColor}"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>`);
      slide.insertBefore(doc.importNode(background.documentElement,true),slide.firstChild);
    }
    const relName=path.posix.join(path.posix.dirname(name),'_rels',path.posix.basename(name)+'.rels');
    const rels=parse(await zip.file(relName).async('string'));
    const relationNodes=Array.from(rels.documentElement.childNodes).filter(n=>n.nodeType===1);
    const relations=new Map(relationNodes.map(n=>[n.getAttribute('Id'),n]));
    const imageRelations=new Map();let nextRelationId=Math.max(0,...relationNodes.map(n=>Number(n.getAttribute('Id').match(/^rId(\d+)$/)?.[1]||0)))+1;
    let nextId=Math.max(1,...list(doc,P,'cNvPr').map(n=>attrNum(n,'id')))+1;
    let convertedSvgCount=0;
    const svgCounts={};
    for(const pic of list(doc,P,'pic')) {
      const svg=list(pic,SVG,'svgBlip')[0];
      if(!svg) continue;
      const relation=relations.get(svg.getAttributeNS(R,'embed'));
      if(!relation) fail('OUTPUT_INVALID','SVGの関連情報がありません。');
      const media=path.posix.normalize(path.posix.join(path.posix.dirname(name),relation.getAttribute('Target')));
      const source=zip.file(media);
      if(!source) fail('OUTPUT_INVALID','SVGのデータがありません。');
      const transform=list(pic,A,'xfrm')[0];
      const off=list(transform,A,'off')[0],ext=list(transform,A,'ext')[0];
      // Cropped SVGs require different viewport semantics; do not silently distort them.
      const crop=list(pic,A,'srcRect')[0];
      if(crop && ['l','r','t','b'].some(n=>attrNum(crop,n)!==0)) fail('UNSUPPORTED_SVG','SVGの切り抜きには対応していません。');
      const result=await nativeSvgGroup({page,svg:await source.async('string'),x:attrNum(off,'x'),y:attrNum(off,'y'),width:attrNum(ext,'cx'),height:attrNum(ext,'cy'),idStart:nextId,fontFamily:FONT_FAMILY});
      const wrapper=parse(`<root xmlns:p="${P}" xmlns:a="${A}" xmlns:r="${R}">${result.xml}</root>`);
      const group=Array.from(wrapper.documentElement.childNodes).find(n=>n.nodeType===1);
      if(!group) fail('OUTPUT_INVALID','SVGの図形を生成できませんでした。');
      const outerTransform=list(group,A,'xfrm')[0];
      for(const attribute of ['rot','flipH','flipV']) if(transform.hasAttribute(attribute)) outerTransform.setAttribute(attribute,transform.getAttribute(attribute));
      const replacement=doc.importNode(group,true);
      pic.parentNode.replaceChild(replacement,pic);
      for(const objects of targets.values()){const at=objects.indexOf(pic);if(at>=0)objects[at]=replacement;}
      nextId=result.nextId; convertedSvgCount++;
      warnings.push(...(result.warnings||[]).map(w=>({...w,slide:index+1})));
      for(const [key,value] of Object.entries(result.counts||{})) svgCounts[key]=(svgCounts[key]||0)+value;
    }
    // dom-to-pptx writes zero row heights. Preserve browser table row geometry.
    const tables=list(doc,A,'tbl');
    for(const [tableIndex,table] of tables.entries()) {
      const layout=layouts[index].tables[tableIndex];
      if(!layout) fail('OUTPUT_INVALID','表の数が入力と一致しません。');
      const rows=list(table,A,'tr');
      if(rows.length!==layout.rowHeights.length) fail('UNSUPPORTED_TABLE','表の行構造を保持できませんでした。');
      rows.forEach((row,j)=>row.setAttribute('h',String(Math.max(1,Math.round(layout.rowHeights[j]*9525)))));
      // Row h is a minimum in Office renderers. Extra border-spacing margins and
      // default font leading otherwise enlarge even explicitly measured rows.
      for(const [rowIndex,row] of rows.entries()){
        const cells=list(row,A,'tc').filter(cell=>!['1','true'].includes(cell.getAttribute('hMerge'))&&!['1','true'].includes(cell.getAttribute('vMerge')));
        const source=layout.cells?.[rowIndex];
        if(!source)continue;
        if(cells.length!==source.length)fail('UNSUPPORTED_TABLE','表のセル構造を保持できませんでした。');
        cells.forEach((cell,cellIndex)=>{
          const measured=source[cellIndex],properties=list(cell,A,'tcPr')[0];
          if(properties)for(const [i,attribute] of ['marT','marR','marB','marL'].entries())properties.setAttribute(attribute,String(Math.max(0,Math.round(measured.margins[i]*9525))));
          // A mixed-size normal line needs the renderer's font-aware minimum.
          const maxFont=Math.max(0,...list(cell,A,'rPr').map(run=>attrNum(run,'sz')));
          if(!(measured.lineHeight>0)||measured.normalLineHeight&&maxFont>measured.fontSize*75+.5)return;
          for(const paragraph of list(cell,A,'p')){
            let pPr=list(paragraph,A,'pPr')[0];
            if(!pPr){pPr=doc.createElementNS(A,'a:pPr');paragraph.insertBefore(pPr,paragraph.firstChild);}
            for(const old of list(pPr,A,'lnSpc'))pPr.removeChild(old);
            const line=doc.createElementNS(A,'a:lnSpc'),points=doc.createElementNS(A,'a:spcPts');
            points.setAttribute('val',String(Math.round(measured.lineHeight*75)));line.appendChild(points);pPr.insertBefore(line,pPr.firstChild);
          }
        });
      }
      let frame=table.parentNode;while(frame&&!(frame.namespaceURI===P&&frame.localName==='graphicFrame'))frame=frame.parentNode;
      const frameTransform=frame&&list(frame,P,'xfrm')[0],frameSize=frameTransform&&list(frameTransform,A,'ext')[0];
      if(frameSize&&layout.height>0)frameSize.setAttribute('cy',String(Math.round(layout.height*9525)));
    }
    const remainingPictures=list(doc,P,'pic').length;
    if(remainingPictures!==layouts[index].imageCount) fail('UNSUPPORTED_LAYOUT','元画像以外の画像化、または画像の欠落を検出しました。装飾・画像の配置を確認してください。');
    for(const [pictureIndex,pic] of list(doc,P,'pic').entries()) {
      const transform=list(pic,A,'xfrm')[0],off=list(transform,A,'off')[0],ext=list(transform,A,'ext')[0];
      const pictureName=list(pic,P,'cNvPr')[0]?.getAttribute('name')||'';
      const transport=pictureName.match(/__dom_(\d+)__type_image/),renamed=pictureName.match(/^Picture (\d+)$/);
      const marker=transport?Number(transport[1]):renamed?Number(renamed[1])-1:null;
      const matches=(layouts[index].images||[]).filter(image=>image.marker===marker);
      if(!matches.length)fail('UNSUPPORTED_LAYOUT','画像の配置を入力と対応付けできませんでした。');
      const image=matches[0],blip=list(pic,A,'blip')[0],properties=list(pic,P,'cNvPr')[0];
      properties.setAttribute('name','Picture '+(pictureIndex+1));
      if(image.alt)properties.setAttribute('descr',image.alt);else properties.removeAttribute('descr');
      off.setAttribute('x',String(Math.round(image.x*9525)));off.setAttribute('y',String(Math.round(image.y*9525)));
      ext.setAttribute('cx',String(Math.round(image.width*9525)));ext.setAttribute('cy',String(Math.round(image.height*9525)));
      transform.removeAttribute('flipH');transform.removeAttribute('flipV');transform.removeAttribute('rot');
      if(image.flipV)transform.setAttribute('flipV','1');if(image.rotation)transform.setAttribute('rot',String(Math.round(image.rotation*60000)));
      const blipFill=list(pic,P,'blipFill')[0];
      for(const old of list(blipFill,A,'srcRect'))old.parentNode.removeChild(old);
      const crop=doc.createElementNS(A,'a:srcRect');
      for(const [key,value] of Object.entries(image.crop))crop.setAttribute(key,String(Math.round(Math.max(0,Math.min(1,value))*100000)));
      blipFill.insertBefore(crop,list(blipFill,A,'stretch')[0]||null);
      const geometry=pictureGeometry(doc,image.clip);
      if(geometry){const spPr=list(pic,P,'spPr')[0],old=[...list(spPr,A,'prstGeom'),...list(spPr,A,'custGeom')][0];if(old)spPr.replaceChild(geometry,old);else spPr.appendChild(geometry);}
      if(image.opacity<.9999){
        const alpha=doc.createElementNS(A,'a:alphaModFix');alpha.setAttribute('amt',String(Math.round(image.opacity*100000)));blip.appendChild(alpha);
      }
      {
        const data=imageSources[image.sourceIndex]?.match(/^data:image\/(png|jpeg);base64,([A-Za-z0-9+/=]+)$/);
        if(data){
          const extension=data[1]==='jpeg'?'jpeg':'png',bytes=Buffer.from(data[2],'base64'),key=extension+':'+createHash('sha256').update(bytes).digest('hex');
          const media=originalMedia.get(key)||`ppt/media/original-${index+1}-${pictureIndex+1}.${extension}`;
          if(!originalMedia.has(key)){zip.file(media,bytes);originalMedia.set(key,media);}
          let relationId=imageRelations.get(media);
          if(!relationId){
            relationId='rId'+nextRelationId++;const relation=rels.createElementNS(rels.documentElement.namespaceURI,'Relationship');
            relation.setAttribute('Id',relationId);relation.setAttribute('Type',R+'/image');relation.setAttribute('Target','../media/'+path.posix.basename(media));
            rels.documentElement.appendChild(relation);imageRelations.set(media,relationId);
          }
          blip.setAttributeNS(R,'r:embed',relationId);
          const types=parse(await zip.file('[Content_Types].xml').async('string'));
          if(!Array.from(types.documentElement.childNodes).some(n=>n.nodeType===1&&n.getAttribute('Extension')===extension)){
            const item=types.createElementNS(types.documentElement.namespaceURI,'Default');item.setAttribute('Extension',extension);item.setAttribute('ContentType','image/'+data[1]);types.documentElement.appendChild(item);zip.file('[Content_Types].xml',serialize(types));
          }
        }else fail('OUTPUT_INVALID','元画像のデータを取得できませんでした。');
      }
    }
    // The upstream exporter can reuse shape IDs for table graphicFrames.
    // Assign all static objects unique IDs, including group roots, and remap connections.
    const properties=list(doc,P,'cNvPr');
    const mappings=new Map(),duplicates=new Set();
    properties.forEach((node,j)=>{const old=node.getAttribute('id');if(mappings.has(old))duplicates.add(old);mappings.set(old,String(j+1));node.setAttribute('id',String(j+1));});
    for(const connection of [...list(doc,A,'stCxn'),...list(doc,A,'endCxn')]) {
      const old=connection.getAttribute('id');
      if(duplicates.has(old)||!mappings.has(old))fail('OUTPUT_INVALID','接続先の図形IDを確定できません。');
      connection.setAttribute('id',mappings.get(old));
    }
    // Ignore upstream class-name guesses. Only validated browser animation plans
    // may create timing, using final IDs after every geometry replacement.
    for(const node of [...list(doc,P,'timing'),...list(doc,P,'transition')])node.parentNode.removeChild(node);
    const motion=applyNativeAnimations({document:doc,effects,targets,slideWidthPx:width,slideHeightPx:height});
    nativeEffects.push(...motion.nativeEffects);fallbackEffects.push(...motion.fallbackEffects);warnings.push(...motion.warnings);
    const count={slide:index+1,textBoxes:list(doc,P,'txBody').length,shapes:list(doc,P,'sp').length,
      tables:tables.length,images:remainingPictures,convertedSvgCount,svg:svgCounts};
    totalShapes+=count.shapes+count.tables+count.images+motion.groupsCreated;
    if(totalShapes>limits.maxShapes) fail('OUTPUT_LIMIT','生成する要素数が上限を超えています。');
    report.push(count);
    const xml=serialize(doc);
    const used=new Set([...xml.matchAll(/r:(?:embed|link|id)="([^"]+)"/g)].map(m=>m[1]));
    for(const relation of relationNodes) {
      if(relation.getAttribute('Type').endsWith('/image')&&!used.has(relation.getAttribute('Id'))) relation.parentNode.removeChild(relation);
    }
    zip.file(name,xml); zip.file(relName,serialize(rels));
  }
  const usedMedia=new Set();
  for(const name of Object.keys(zip.files).filter(n=>n.endsWith('.rels'))) {
    const relations=parse(await zip.file(name).async('string'));
    for(const relation of Array.from(relations.documentElement.childNodes).filter(n=>n.nodeType===1)) {
      const target=relation.getAttribute('Target');
      if(relation.getAttribute('TargetMode')==='External') {
        if(!relation.getAttribute('Type').endsWith('/hyperlink')||!/^(https?:|mailto:)/i.test(target)) fail('OUTPUT_INVALID','許可されていない外部参照が生成されました。');
      } else if(relation.getAttribute('Type').endsWith('/image')) {
        usedMedia.add(path.posix.normalize(path.posix.join(path.posix.dirname(path.posix.dirname(name)),target)));
      }
    }
  }
  for(const name of Object.keys(zip.files).filter(n=>n.startsWith('ppt/media/')&&!zip.files[n].dir)) if(!usedMedia.has(name)) zip.remove(name);
  if(Object.keys(zip.files).some(n=>/^ppt\/media\/.*\.svg$/i.test(n))) fail('OUTPUT_INVALID','編集不能なSVG画像が残っています。');
  // The upstream package places notesMasterIdLst after sldIdLst, contrary to
  // PresentationML's child sequence. Keep the complete package schema-valid.
  const presentation=parse(await zip.file('ppt/presentation.xml').async('string'));
  const notes=list(presentation,P,'notesMasterIdLst')[0];
  if(notes){
    const following=Array.from(presentation.documentElement.childNodes).find(n=>n.nodeType===1&&['handoutMasterIdLst','sldIdLst','sldSz','notesSz'].includes(n.localName));
    presentation.documentElement.insertBefore(notes,following||null);
    zip.file('ppt/presentation.xml',serialize(presentation));
  }
  // Measurement already uses the bundled font. Also align theme/master defaults
  // so table text and new text entered during editing do not fall back to Arial.
  for(const name of Object.keys(zip.files).filter(n=>n.startsWith('ppt/')&&n.endsWith('.xml'))) {
    const doc=parse(await zip.file(name).async('string'));
    const fonts=['latin','ea','cs','font'].flatMap(tag=>list(doc,A,tag));
    if(!fonts.length)continue;
    for(const font of fonts)font.setAttribute('typeface',FONT_FAMILY);
    zip.file(name,serialize(doc));
  }
  const output=await zip.generateAsync({type:'nodebuffer',compression:'DEFLATE',compressionOptions:{level:6}});
  if(output.length>limits.maxOutputBytes) fail('OUTPUT_LIMIT','PPTXのサイズが上限を超えています。');
  return {pptx:output,slides:report,warnings,nativeEffects,fallbackEffects};
}
