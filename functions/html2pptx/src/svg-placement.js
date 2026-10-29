/** Trusted browser hook: preserve a root SVG's complete viewport-to-screen transform. */
export function installSvgPlacementHook() {
  const ns='http://www.w3.org/2000/svg';
  const fail=message=>{throw new Error(`UNSUPPORTED_LAYOUT: ${message}`);};
  const properties=['fill','fill-opacity','fill-rule','stroke','stroke-opacity','stroke-width','stroke-dasharray','stroke-dashoffset',
    'stroke-linecap','stroke-linejoin','stroke-miterlimit','opacity','font-family','font-size','font-weight','font-style',
    'font-variant','font-stretch','letter-spacing','word-spacing','text-anchor','dominant-baseline','alignment-baseline',
    'text-decoration','text-decoration-line','text-decoration-style','text-decoration-color','visibility','display','paint-order',
    'vector-effect','clip-path','color','transform','transform-origin','transform-box','translate','rotate','scale','stop-color','stop-opacity',
    'marker-start','marker-mid','marker-end','white-space','direction','unicode-bidi','writing-mode','text-orientation',
    'overflow','overflow-x','overflow-y','x','y','width','height','cx','cy','r','rx','ry','d'];
  const inherited=new Set(['fill','fill-opacity','fill-rule','stroke','stroke-opacity','stroke-width','stroke-dasharray','stroke-dashoffset',
    'stroke-linecap','stroke-linejoin','stroke-miterlimit','font-family','font-size','font-weight','font-style','font-variant','font-stretch',
    'letter-spacing','word-spacing','text-anchor','visibility','color','marker-start','marker-mid','marker-end','white-space','direction']);
  const localReferences=value=>value.replace(/url\(\s*(["']?)(.*?)\1\s*\)/g,(_match,_quote,reference)=>{
    if(reference.startsWith('#'))return `url("${reference}")`;
    const resolved=new URL(reference,document.baseURI),current=new URL(document.URL);
    const fragment=resolved.hash;resolved.hash='';current.hash='';
    if(!fragment||resolved.href!==current.href)fail('SVG内の外部参照には対応していません。');
    return `url("${fragment}")`;
  });
  globalThis.__convertX2XSvgRender=(node,result,config,order,sortKey,parentOpacity)=>{
    if(node.namespaceURI!==ns||node.localName!=='svg'||!result)return result;
    const style=getComputedStyle(node),rect=node.getBoundingClientRect(),width=parseFloat(style.width),height=parseFloat(style.height);
    if(!Number.isFinite(width)||!Number.isFinite(height)||width<=0||height<=0)return result;
    for(let element=node;element;element=element.parentElement){
      const s=getComputedStyle(element);
      if(s.perspective!=='none'||s.transform!=='none'&&!new DOMMatrix(s.transform).is2D||s.rotate!=='none'&&!/^-?[\d.]+(?:deg|rad|grad|turn)$/.test(s.rotate))fail('SVGの3D配置には対応していません。');
    }
    if(style.boxShadow!=='none'||style.backgroundImage!=='none'||!['transparent','rgba(0, 0, 0, 0)'].includes(style.backgroundColor)
      ||['Top','Right','Bottom','Left'].some(side=>parseFloat(style['border'+side+'Width'])>0||parseFloat(style['padding'+side])>0))fail('SVGルートのCSS背景・枠・余白は外側のHTML要素で指定してください。');
    const screen=node.getScreenCTM();
    if(!screen||![screen.a,screen.b,screen.c,screen.d,screen.e,screen.f].every(Number.isFinite)||Math.abs(screen.a*screen.d-screen.b*screen.c)<1e-12)fail('SVGの配置を計算できませんでした。');
    const view=node.viewBox.baseVal,aspect=node.preserveAspectRatio.baseVal;
    let viewport=new DOMMatrix();
    if(view.width>0&&view.height>0){
      let sx=width/view.width,sy=height/view.height,ax=0,ay=0;
      if(aspect.align!==1){
        sx=sy=aspect.meetOrSlice===2?Math.max(sx,sy):Math.min(sx,sy);
        ax=((aspect.align-2)%3)/2;ay=Math.floor((aspect.align-2)/3)/2;
      }
      viewport=new DOMMatrix([sx,0,0,sy,(width-view.width*sx)*ax-view.x*sx,(height-view.height*sy)*ay-view.y*sy]);
    }
    const placement=new DOMMatrix([screen.a,screen.b,screen.c,screen.d,screen.e,screen.f]).multiply(viewport.inverse());
    placement.e-=rect.left;placement.f-=rect.top;
    const clone=node.cloneNode(true),sources=[node,...node.querySelectorAll('*')],targets=[clone,...clone.querySelectorAll('*')];
    const rules=[];
    const collectRules=entries=>{for(const rule of entries){if(rule.selectorText&&rule.style)rules.push(rule);if(rule.cssRules)collectRules(rule.cssRules);}};
    for(const sheet of document.styleSheets){try{collectRules(sheet.cssRules);}catch{fail('SVGのスタイルを読み取れませんでした。');}}
    const ownDeclaration=(source,property)=>{
      const inline=source.style.getPropertyValue(property)||source.getAttribute(property);
      if(inline)return !['inherit','unset'].includes(inline.trim());
      return rules.some(rule=>{try{return !!rule.style.getPropertyValue(property)&&source.matches(rule.selectorText);}catch{return false;}});
    };
    sources.forEach((source,i)=>{
      const computed=getComputedStyle(source),target=targets[i];
      if(computed.filter!=='none'||computed.maskImage!=='none'||computed.mixBlendMode!=='normal'||computed.backdropFilter!=='none')fail('SVGのフィルター・マスク・合成モードには対応していません。');
      if(computed.perspective!=='none'||computed.transform!=='none'&&!new DOMMatrix(computed.transform).is2D||computed.rotate!=='none'&&!/^-?[\d.]+(?:deg|rad|grad|turn)$/.test(computed.rotate))fail('SVGの3D配置には対応していません。');
      for(const property of properties){
        // A referenced definition inherits paint/font from <use>, not its original
        // <defs> context. Do not pin an implicit inherited value to that definition.
        if(source.closest('defs')&&inherited.has(property)&&!ownDeclaration(source,property))continue;
        const specified=source.style.getPropertyValue(property)||source.getAttribute(property);
        if(source.closest('defs')&&specified&&/currentcolor/i.test(specified)){target.style.setProperty(property,specified,'important');continue;}
        const value=computed.getPropertyValue(property);
        // A default CSS transform would override the SVG transform attribute that
        // the native converter creates when expanding a local <use> reference.
        if(['transform','translate','rotate','scale'].includes(property)&&value==='none')continue;
        if(property==='d'&&source.localName==='path'&&value&&value!=='none'){
          const parsed=value.match(/^path\((["'])(.*)\1\)$/);
          if(!parsed)fail('SVGのCSSパスを解決できませんでした。');
          target.setAttribute('d',parsed[2]);
        }
        if(value)target.style.setProperty(property,localReferences(value),'important');
      }
    });
    // Original SVG selectors must not style the newly-created wrapper svg/g.
    // Their computed drawing/geometry values have already been frozen above.
    for(const stylesheet of clone.querySelectorAll('style'))stylesheet.remove();
    // The complete root transform is now represented once by the enclosing group.
    // Keep its original viewBox/viewport so meet/slice and nonzero origins survive.
    clone.removeAttribute('transform');clone.setAttribute('x','0');clone.setAttribute('y','0');
    clone.setAttribute('width',String(width));clone.setAttribute('height',String(height));
    for(const [property,value]of Object.entries({transform:'none',translate:'none',rotate:'none',scale:'none',position:'static',margin:'0',width:`${width}px`,height:`${height}px`}))clone.style.setProperty(property,value,'important');
    // Chromium lays out CSS viewports in 1/64px steps. Round outward so the wrapper
    // viewBox is not subtly rescaled when measured again by the native SVG converter.
    const outerWidth=Math.ceil(rect.width*64)/64,outerHeight=Math.ceil(rect.height*64)/64;
    const outer=document.createElementNS(ns,'svg');
    outer.setAttribute('width',String(outerWidth));outer.setAttribute('height',String(outerHeight));outer.setAttribute('viewBox',`0 0 ${outerWidth} ${outerHeight}`);
    const group=document.createElementNS(ns,'g');
    group.setAttribute('transform',`matrix(${placement.a} ${placement.b} ${placement.c} ${placement.d} ${placement.e} ${placement.f})`);
    group.setAttribute('opacity',String(parentOpacity));group.append(clone);outer.append(group);
    const serialized=new XMLSerializer().serializeToString(outer);
    if(serialized.length>8*1024*1024)fail('SVGの展開サイズが上限を超えています。');
    const bytes=new TextEncoder().encode(serialized);let binary='';
    for(let i=0;i<bytes.length;i+=8192)binary+=String.fromCharCode(...bytes.subarray(i,i+8192));
    const unit=config.scale/96;
    return {items:[{type:'image',zIndex:sortKey.concat([0,-1]),domOrder:order,options:{
      x:config.offX+(rect.left-config.rootX)*unit,y:config.offY+(rect.top-config.rootY)*unit,
      w:outerWidth*unit,h:outerHeight*unit,rotate:0,data:'data:image/svg+xml;base64,'+btoa(binary)
    }}],stopRecursion:true};
  };
}
