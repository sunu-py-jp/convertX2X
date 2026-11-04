import { createHash } from 'node:crypto';
import * as parse5 from 'parse5';
import { prepareInput } from './input.js';
import { ConversionError } from './errors.js';
import { launchBrowser, securedPage, trustedFontCss, closeBrowser } from './browser.js';
import { finalizePptx } from './pptx.js';
import { installCssNativeHook } from './css-native.js';
import { exporterSource } from './exporter.js';
import { materializePseudoElements } from './pseudo-elements.js';
import { installTextLayoutHook } from './text-layout.js';
import { installSvgPlacementHook } from './svg-placement.js';
import { FONT_FAMILY, normalizeDocumentFonts } from './fonts.js';
import { poolDocumentImages, restoreDocumentImages } from './image-sources.js';
import { prepareAnimations, freezeAnimations } from './animations.js';
import { installAnimationMappingHook } from './animation-mapping.js';

const CSP="default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; frame-src about: data: blob:; connect-src 'none'; base-uri 'none'; form-action 'none'";

export async function convertInWorker(input,{filename,limits,scratch,executablePath,captureHtml=false,onBrowser}) {
  const started=Date.now();
  const prepared=await prepareInput(input,{filename,limits});
  let browser;
  try {
    browser=await launchBrowser(scratch,executablePath);
    onBrowser?.(browser.process()?.pid);
    const page=await securedPage(browser,prepared.width,prepared.height);
    const fonts=await trustedFontCss();
    const trusted=`<meta http-equiv="Content-Security-Policy" content="${CSP}"><style>@layer convertX2XAnimationHold{*,*::before,*::after{animation-play-state:paused!important;transition-property:none!important}}${fonts}html,body{margin:0;padding:0}html{background:white;color-scheme:light}body{font-family:'${FONT_FAMILY}',sans-serif}[data-slide]{box-sizing:border-box;width:${prepared.width}px!important;height:${prepared.height}px!important;position:relative;flex-shrink:0}*,*::before,*::after{caret-color:transparent!important}</style>`;
    // Insert through the HTML parser: head attributes and quoted '>' must not bypass CSP/fonts.
    const document=prepared.document||parse5.parse(prepared.html);
    // A reused 3MB picture can otherwise produce a 100MB CDP HTML command. Keep
    // validated image bytes in one pool and assign them after the small DOM loads.
    const sourceImages=poolDocumentImages(document);
    prepared.html='';
    const head=document.childNodes.find(n=>n.tagName==='html').childNodes.find(n=>n.tagName==='head');
    const injected=parse5.parseFragment(trusted).childNodes;
    for(const node of injected)node.parentNode=head;
    head.childNodes.unshift(...injected);
    await page.setContent(parse5.serialize(document),{waitUntil:'domcontentloaded',timeout:10000});
    await page.evaluate(restoreDocumentImages,sourceImages);
    await page.evaluate(normalizeDocumentFonts, FONT_FAMILY);
    await page.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(image=>image.decode().catch(()=>{})));});
    const animations=await page.evaluate(prepareAnimations).catch(error=>{
      if(error.message?.startsWith('UNSUPPORTED_ANIMATION:'))throw new ConversionError('UNSUPPORTED_ANIMATION',error.message.split('\n')[0].replace(/^UNSUPPORTED_ANIMATION:\s*/,''),422);
      throw error;
    });
    const previews=[];
    if(captureHtml)for(const root of await page.$$('[data-slide]'))previews.push(Buffer.from(await root.screenshot({type:'png'})));
    await page.evaluate(materializePseudoElements).catch(error=>{
      if(error.message?.startsWith('UNSUPPORTED_LAYOUT:'))throw new ConversionError('UNSUPPORTED_LAYOUT',error.message.split('\n')[0].slice(20,500),422);
      throw error;
    });
    await page.evaluate(freezeAnimations);
    await page.evaluate(installCssNativeHook);
    await page.evaluate(installSvgPlacementHook);
    await page.evaluate(installTextLayoutHook);
    await page.evaluate(installAnimationMappingHook);
    const scan=await page.evaluate((imageLimits)=>{
      const slides=[...document.querySelectorAll('[data-slide]')];
      const issues=[],imageWarnings=[];
      const imageSources=[],sourceIndexes=new Map();
      const normalLineHeights=new Map();
      const tableLayout=table=>{
        const tableStyle=getComputedStyle(table),spacing=tableStyle.borderSpacing.split(' ').map(parseFloat);
        const gapX=tableStyle.borderCollapse==='collapse'?0:(spacing[0]||0)/2;
        const gapY=tableStyle.borderCollapse==='collapse'?0:(spacing[1]||spacing[0]||0)/2;
        const lineHeight=style=>{
          if(style.lineHeight!=='normal')return parseFloat(style.lineHeight);
          const key=[style.fontFamily,style.fontSize,style.fontWeight,style.fontStyle,style.fontStretch].join('|');
          if(!normalLineHeights.has(key)){
            const host=document.createElement('span');host.style.cssText='all:initial;position:fixed;visibility:hidden;left:0;top:0;';
            const span=document.createElement('span');span.textContent='M';span.style.cssText='display:inline-block;line-height:normal;';
            for(const property of ['fontFamily','fontSize','fontWeight','fontStyle','fontStretch'])span.style[property]=style[property];
            host.attachShadow({mode:'closed'}).append(span);document.body.append(host);
            normalLineHeights.set(key,span.getBoundingClientRect().height);host.remove();
          }
          return normalLineHeights.get(key);
        };
        return {height:table.getBoundingClientRect().height,rowHeights:[...table.rows].map(row=>row.getBoundingClientRect().height),
          cells:[...table.rows].map(row=>[...row.cells].map(cell=>{const style=getComputedStyle(cell);return {
            margins:[parseFloat(style.paddingTop)+gapY,parseFloat(style.paddingRight)+gapX,parseFloat(style.paddingBottom)+gapY,parseFloat(style.paddingLeft)+gapX],
            lineHeight:lineHeight(style),normalLineHeight:style.lineHeight==='normal',fontSize:parseFloat(style.fontSize),rowSpan:cell.rowSpan,colSpan:cell.colSpan};}))};
      };
      const visible=element=>{if(getComputedStyle(element).visibility!=='visible')return false;for(let e=element;e;e=e.parentElement){const s=getComputedStyle(e);if(s.display==='none'||s.opacity==='0')return false;}return true;};
      const clipped=(rect,element)=>{for(let e=element;e;e=e.parentElement){const s=getComputedStyle(e),r=e.getBoundingClientRect();if(['hidden','clip','auto','scroll'].includes(s.overflowX)&&(rect.left<r.left-2||rect.right>r.right+2))return true;if(['hidden','clip','auto','scroll'].includes(s.overflowY)&&(rect.top<r.top-2||rect.bottom>r.bottom+2))return true;}return false;};
      const walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);
      for(let node=walker.nextNode();node;node=walker.nextNode()) {
        const element=node.parentElement;
        if(!node.textContent.trim()||!element||element.closest('[data-slide]')||element.closest('style,title')||!visible(element))continue;
        const range=document.createRange();range.selectNodeContents(node);
        if(range.getBoundingClientRect().width>0)issues.push({code:'CONTENT_OUTSIDE_SLIDES',element:element.tagName});
      }
      for(const element of document.querySelectorAll('img,svg'))if(!element.closest('[data-slide]')&&visible(element)&&element.getBoundingClientRect().width>0)issues.push({code:'CONTENT_OUTSIDE_SLIDES',element:element.tagName});
      for(const element of document.body.querySelectorAll('*')) {
        if(element.closest('[data-slide]')||element.querySelector('[data-slide]')||!visible(element)||element.getBoundingClientRect().width===0)continue;
        const s=getComputedStyle(element);
        if(!['rgba(0, 0, 0, 0)','transparent'].includes(s.backgroundColor)||s.backgroundImage!=='none'||parseFloat(s.borderTopWidth)>0||parseFloat(s.borderLeftWidth)>0)issues.push({code:'CONTENT_OUTSIDE_SLIDES',element:element.tagName});
      }
      const layouts=slides.map((root,index)=>{
        const rr=root.getBoundingClientRect();
        const background=document.createElement('canvas').getContext('2d'),ancestors=[];
        background.fillStyle='white';background.fillRect(0,0,1,1);
        for(let e=root.parentElement;e;e=e.parentElement)ancestors.unshift(e);
        for(const ancestor of ancestors){const style=getComputedStyle(ancestor);background.fillStyle=style.backgroundColor;background.fillRect(0,0,1,1);}
        const backgroundColor=[...background.getImageData(0,0,1,1).data].slice(0,3).map(v=>v.toString(16).padStart(2,'0')).join('').toUpperCase();
        const elements=[root,...root.querySelectorAll('*')];
        let imageCount=0;const images=[];
        const fonts=new Set();
        for(const element of elements) {
          const s=getComputedStyle(element),r=element.getBoundingClientRect();
          if(!visible(element))continue;
          if(Number(s.opacity)>0&&Number(s.opacity)<1&&element.children.length){
            const paint=[];
            for(const child of [element,...element.querySelectorAll('*')]){
              if(!visible(child))continue;const style=getComputedStyle(child);
              const svg=child.namespaceURI==='http://www.w3.org/2000/svg';
              if(svg&&child.closest('defs,marker,clipPath,linearGradient,radialGradient,title,desc'))continue;
              const ownText=!svg&&[...child.childNodes].some(n=>n.nodeType===3&&n.textContent.trim());
              const svgShape=svg&&['rect','circle','ellipse','line','polyline','polygon','path','text','use'].includes(child.localName);
              const hasStroke=svgShape&&style.stroke!=='none'&&parseFloat(style.strokeOpacity)>0&&parseFloat(style.strokeWidth)>0;
              const hasFill=svgShape&&child.localName!=='line'&&style.fill!=='none'&&parseFloat(style.fillOpacity)>0;
              if(svgShape&&(hasFill||hasStroke||child.localName==='use')||child.tagName==='IMG'||ownText||!['transparent','rgba(0, 0, 0, 0)'].includes(style.backgroundColor)||['Top','Right','Bottom','Left'].some(side=>parseFloat(style['border'+side+'Width'])>0)){
                const bounds=child.getBoundingClientRect(),matrix=hasStroke?child.getScreenCTM():null;
                const padding=hasStroke?parseFloat(style.strokeWidth)*Math.max(Math.hypot(matrix.a,matrix.b),Math.hypot(matrix.c,matrix.d))/2:0;
                paint.push({left:bounds.left-padding,top:bounds.top-padding,right:bounds.right+padding,bottom:bounds.bottom+padding});
                // A local use can hide several painted children from this DOM walk.
                // Conservatively reject that composite under group opacity too.
                if(svg&&child.localName==='use'){
                  const href=child.getAttribute('href')||child.getAttributeNS('http://www.w3.org/1999/xlink','href'),target=href?.startsWith('#')?document.getElementById(href.slice(1)):null;
                  if(target&&target.querySelectorAll('rect,circle,ellipse,line,polyline,polygon,path,text,use').length>1)paint.push(paint[paint.length-1]);
                }
              }
            }
            if(paint.length>128||paint.some((a,i)=>paint.slice(i+1).some(b=>Math.min(a.right,b.right)>Math.max(a.left,b.left)+.1&&Math.min(a.bottom,b.bottom)>Math.max(a.top,b.top)+.1)))issues.push({code:'UNSUPPORTED_LAYOUT',slide:index+1,element:element.tagName,message:'親のopacityで複数の重なった要素をまとめて透過する表示には対応していません。各要素にopacityを指定してください。'});
          }
          if(!r.width||!r.height)continue;
          if(element.parentElement&&getComputedStyle(element.parentElement).visibility==='hidden')issues.push({code:'UNSUPPORTED_LAYOUT',slide:index+1,element:element.tagName});
          if(element.closest('svg')&&element.closest('svg')!==element) continue;
          if(r.left<rr.left-2||r.top<rr.top-2||r.right>rr.right+2||r.bottom>rr.bottom+2) issues.push({code:'SLIDE_OVERFLOW',slide:index+1,element:element.tagName});
          if(element.namespaceURI==='http://www.w3.org/2000/svg'){if(s.filter!=='none'||s.maskImage!=='none')issues.push({code:'UNSUPPORTED_SVG',slide:index+1,element:element.tagName});continue;}
          if(s.transform!=='none'&&!s.transform.startsWith('matrix3d')) {
            const m=new DOMMatrix(s.transform),sx=Math.hypot(m.a,m.b),sy=Math.hypot(m.c,m.d),dot=m.a*m.c+m.b*m.d;
            const rotated=Math.abs(m.b)>0.0001||Math.abs(m.c)>0.0001;
            if(m.a*m.d-m.b*m.c<0 && element.textContent.trim())issues.push({code:'UNSUPPORTED_LAYOUT',slide:index+1,element:element.tagName});
          }
          for(const child of element.childNodes)if(child.nodeType===3&&child.textContent.trim()) {
            const range=document.createRange();range.selectNodeContents(child);
            for(const textRect of range.getClientRects())if(textRect.left<rr.left-2||textRect.top<rr.top-2||textRect.right>rr.right+2||textRect.bottom>rr.bottom+2||clipped(textRect,element))issues.push({code:'SLIDE_OVERFLOW',slide:index+1,element:element.tagName});
          }
          if(element.tagName==='TABLE')for(let e=element;e&&e!==root.parentElement;e=e.parentElement){const transform=getComputedStyle(e).transform;if(transform!=='none'){const m=new DOMMatrix(transform);if(Math.abs(m.a-1)>.001||Math.abs(m.b)>.001||Math.abs(m.c)>.001||Math.abs(m.d-1)>.001)issues.push({code:'UNSUPPORTED_TABLE',slide:index+1,element:'TABLE'});}}
          if(s.filter!=='none'&&!(element.tagName==='IMG'&&/^blur\((\d+(?:\.\d+)?|\.\d+)px\)$/.test(s.filter))||s.backdropFilter!=='none'||s.mixBlendMode!=='normal'||s.maskImage!=='none'||s.transform.startsWith('matrix3d')) issues.push({code:'UNSUPPORTED_LAYOUT',slide:index+1,element:element.tagName});
          if(s.backgroundImage!=='none' && !s.backgroundImage.startsWith('linear-gradient(')) issues.push({code:'UNSUPPORTED_LAYOUT',slide:index+1,element:element.tagName});
          for(const pseudo of ['::before','::after']) {
            const p=getComputedStyle(element,pseudo);
            if(p.content!=='none'&&p.content!=='normal'&&p.display!=='none') issues.push({code:'UNSUPPORTED_PSEUDO_ELEMENT',slide:index+1,element:element.tagName});
          }
          if(element.tagName==='IMG') {
            try{
              const image=globalThis.__convertX2XImageLayout(element,1000000000+images.length,imageLimits);
              if(image){
                if(image.blur)imageWarnings.push({code:'IMAGE_BLUR_APPLIED',slide:index+1,message:'画像のぼかしをPNGに反映しました。PPTX上でぼかし量を変更することはできません。'});
                if(!sourceIndexes.has(image.source)){sourceIndexes.set(image.source,imageSources.length);imageSources.push(image.source);}
                const {source,...placement}=image;imageCount++;images.push({...placement,sourceIndex:sourceIndexes.get(source),x:image.x-rr.left,y:image.y-rr.top});
              }
            }catch(error){issues.push({code:'UNSUPPORTED_LAYOUT',slide:index+1,element:'IMG',message:error.message});}
          }
          if([...element.childNodes].some(n=>n.nodeType===3&&n.textContent.trim())) fonts.add(s.fontFamily);
        }
        return {imageCount,images,backgroundColor,fonts:[...fonts],tables:[...root.querySelectorAll('table')].filter(t=>visible(t)&&t.getBoundingClientRect().width>0).map(tableLayout)};
      });
      return {layouts,imageSources,imageWarnings,issues:issues.slice(0,20)};
    },{maxImagePixels:limits.maxImagePixels,maxTotalImagePixels:limits.maxTotalImagePixels,maxOutputBytes:limits.maxOutputBytes});
    if(scan.issues.length) throw new ConversionError(scan.issues[0].code,'スライド外の内容、はみ出し、または未対応の装飾があります。',422,scan.issues);
    // Input JavaScript is rejected by the parser and CSP; only the trusted exporter is evaluated via CDP.
    await page.setJavaScriptEnabled(true);
    const cdp=await page.createCDPSession();
    const inject=await cdp.send('Runtime.evaluate',{expression:await exporterSource(),returnByValue:false,awaitPromise:true});
    if(inject.exceptionDetails) throw new ConversionError('EXPORT_FAILED','変換ライブラリを起動できませんでした。',500);
    await cdp.detach();
    const raw=await page.evaluate(async({width,height,maxOutputBytes})=>{
      const blob=await globalThis.domToPptx.exportToPptx([...document.querySelectorAll('[data-slide]')],{
        fileName:'presentation.pptx',skipDownload:true,autoEmbedFonts:false,svgAsVector:true,width:width/96,height:height/96});
      if(!(blob instanceof Blob)||blob.size>maxOutputBytes) return null;
      const bytes=new Uint8Array(await blob.arrayBuffer());
      let binary=''; for(let i=0;i<bytes.length;i+=8192) binary+=String.fromCharCode(...bytes.subarray(i,i+8192));
      return btoa(binary);
    },{width:prepared.width,height:prepared.height,maxOutputBytes:limits.maxOutputBytes}).catch(error=>{
      if(error.message?.startsWith('UNSUPPORTED_LAYOUT:'))throw new ConversionError('UNSUPPORTED_LAYOUT',error.message.split('\n')[0].slice(20,500),422);
      throw error;
    });
    if(!raw) throw new ConversionError('OUTPUT_LIMIT','PPTXのサイズが上限を超えています。',413);
    const animationOrders=await page.evaluate(()=>globalThis.__convertX2XAnimationOrders||[]);
    const result=await finalizePptx(Buffer.from(raw,'base64'),page,{layouts:scan.layouts,imageSources:scan.imageSources,limits,
      animationEffects:animations.effects,animationOrders,width:prepared.width,height:prepared.height});
    return {pptx:result.pptx,previews,report:{version:1,converter:'html2pptx',slideCount:result.slides.length,
      width:prepared.width,height:prepared.height,slides:result.slides,elapsedMs:Date.now()-started,
      outputBytes:result.pptx.length,sha256:createHash('sha256').update(result.pptx).digest('hex'),
      animations:{policy:'native-or-static',nativeEffects:result.nativeEffects,staticEffects:[...(animations.staticEffects||[]),...result.fallbackEffects]},
      warnings:[...prepared.warnings,...animations.warnings,...result.warnings,...scan.imageWarnings,{code:'FONTS_NOT_EMBEDDED',message:'PPTXにはフォントを埋め込んでいません。閲覧環境のフォントにより表示が変わる場合があります。'}],
      runtime:{node:process.version,chromium:await browser.version()},
      fontFamily:FONT_FAMILY,fontPolicy:'uniform',imagePolicy:'original-images-only',svgPolicy:'editable-native-shapes-and-text'}};
  } finally {await closeBrowser(browser);}
}
