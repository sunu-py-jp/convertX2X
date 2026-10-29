/**
 * Preserve the browser's line breaks and inline baselines using independently
 * editable text fragments. CSS line-height adds half-leading above/below a line;
 * DrawingML paragraph spacing does not have the same model. Measuring the final
 * browser layout avoids translating those two incompatible spacing models.
 *
 * Evaluated only in the trusted converter page, after installCssNativeHook.
 */
export function installTextLayoutHook() {
  const previous = globalThis.__convertX2XRender;
  if (typeof previous !== 'function') throw new Error('CSS render hook is required');
  const canvas = document.createElement('canvas'), context = canvas.getContext('2d');
  const number = value => Number.parseFloat(value) || 0;
  const styleKey = style => [style.fontFamily,style.fontSize,style.fontWeight,style.fontStyle,style.color,style.letterSpacing,style.textDecorationLine].join('|');

  globalThis.__convertX2XRender = (node,result,config,order,sortKey,parentOpacity) => {
    result = previous(node,result,config,order,sortKey,parentOpacity);
    if (!result?.items?.some(item=>item.type==='text') || node.namespaceURI !== 'http://www.w3.org/1999/xhtml' || ['UL','OL','TABLE','TD','TH'].includes(node.tagName)) return result;
    const original = result.items.find(item=>item.type==='text');
    // Some exporter fallbacks carry the box decoration on the text shape itself.
    // Keep that combined native shape until its decoration is split explicitly.
    if (original.options.fill || original.options.line || original.options.shadow) return result;
    const box = node.getBoundingClientRect();
    const transformations = [], chain = [];
    for (let element=node;element;element=element.parentElement) chain.unshift(element);
    let matrix = new DOMMatrix();
    for (const element of chain) {
      const style=getComputedStyle(element);let local=new DOMMatrix();
      if(style.rotate&&style.rotate!=='none'){
        const rotate=style.rotate;if(!/^-?[\d.]+(?:deg|rad|grad|turn)$/.test(rotate))return result;
        let degrees=number(rotate);if(rotate.endsWith('grad'))degrees*=.9;else if(rotate.endsWith('rad'))degrees*=180/Math.PI;else if(rotate.endsWith('turn'))degrees*=360;
        local=local.rotate(degrees);
      }
      if(style.scale&&style.scale!=='none'){const values=style.scale.split(/\s+/).map(Number);local=local.scale(values[0],values[1]??values[0]);}
      if(style.transform!=='none')local=local.multiply(new DOMMatrix(style.transform));
      matrix=matrix.multiply(local);
      for(const property of ['transform','rotate','scale','translate'])if(style[property]&&style[property]!=='none')
        transformations.push({element,property,value:element.style.getPropertyValue(property),priority:element.style.getPropertyPriority(property)});
    }
    matrix=globalThis.__convertX2XBox?.(node)?.matrix||matrix;
    const sx=Math.hypot(matrix.a,matrix.b),sy=Math.hypot(matrix.c,matrix.d);
    // The existing geometry validation owns unsupported affine text transforms.
    if (!sx || !sy || Math.abs(sx-sy)>.001 || Math.abs(matrix.a*matrix.c+matrix.b*matrix.d)>1e-5) return result;
    const angle=Math.atan2(matrix.b,matrix.a)*180/Math.PI;
    const fragments=[];
    try {
      // CSS transforms do not participate in layout. Removing them temporarily
      // exposes the untransformed Range boxes without changing wrapping.
      for(const {element,property} of transformations)element.style.setProperty(property,'none','important');
      const local=node.getBoundingClientRect();
      const walker=document.createTreeWalker(node,NodeFilter.SHOW_TEXT);
      let text,count=0;
      while((text=walker.nextNode())) {
        const owner=text.parentElement,style=getComputedStyle(owner);
        if(style.visibility!=='visible'||style.display==='none')continue;
        let opacity=1,decoration='',hidden=false;
        for(let element=owner;element;element=element.parentElement){
          const s=getComputedStyle(element);opacity*=Number(s.opacity);decoration+=' '+s.textDecorationLine;
          if(s.display==='none')hidden=true;
        }
        if(hidden||!opacity)continue;
        if(style.writingMode!=='horizontal-tb')return result;
        const link=owner.closest('a[href]')?.getAttribute('href');
        const key=styleKey(style)+'|'+opacity+'|'+decoration+'|'+(link||'');
        let offset=0,current;
        for(const {segment:character} of new Intl.Segmenter(undefined,{granularity:'grapheme'}).segment(text.nodeValue)) {
          if(++count>100000)throw new Error('UNSUPPORTED_LAYOUT: 文字の配置計測の上限を超えています。');
          const range=document.createRange();range.setStart(text,offset);offset+=character.length;range.setEnd(text,offset);
          const rect=range.getBoundingClientRect();
          if(rect.width<=0||rect.height<=0)continue;
          if(!current||current.key!==key||Math.abs(current.top-rect.top)>.1||Math.abs(current.height-rect.height)>.1||Math.abs(current.right-rect.left)>.25){
            current={key,text:character,left:rect.left,top:rect.top,right:rect.right,height:rect.height,style,opacity,decoration,link,inline:owner!==node};fragments.push(current);
          }else{current.text+=character;current.right=rect.right;}
        }
      }
      const unit=config.scale/96;
      const items=fragments.filter(fragment=>fragment.text.trim()).map(fragment=>{
        const {style}=fragment,fw=fragment.right-fragment.left,fh=fragment.height;
        const dx=fragment.left-local.left+fw/2-local.width/2,dy=fragment.top-local.top+fh/2-local.height/2;
        const cx=box.left+box.width/2+matrix.a*dx+matrix.c*dy,cy=box.top+box.height/2+matrix.b*dx+matrix.d*dy;
        context.fillStyle='#000000';context.fillStyle=style.color;
        // Canvas resolves computed CSS colors without accepting arbitrary XML.
        context.clearRect(0,0,1,1);context.fillRect(0,0,1,1);
        const [r,g,b,a]=context.getImageData(0,0,1,1).data;
        const color=[r,g,b].map(v=>v.toString(16).padStart(2,'0')).join('').toUpperCase();
        let highlight;
        if(fragment.inline){
          context.clearRect(0,0,1,1);context.fillStyle=style.backgroundColor;context.fillRect(0,0,1,1);
          const background=context.getImageData(0,0,1,1).data;
          if(background[3])highlight=[...background.slice(0,3)].map(v=>v.toString(16).padStart(2,'0')).join('').toUpperCase();
        }
        const textOptions={...(highlight?{highlight}:{}),fontFace:style.fontFamily.split(',')[0].trim().replace(/^["']|["']$/g,''),fontSize:number(style.fontSize)*.75*config.scale*sx,
          bold:number(style.fontWeight)>=600,italic:style.fontStyle==='italic',color,
          transparency:Math.round((1-fragment.opacity*a/255)*100),
          underline:fragment.decoration.includes('underline'),strike:fragment.decoration.includes('line-through'),
          ...(style.letterSpacing!=='normal'?{charSpacing:number(style.letterSpacing)*.75*config.scale*sx}:{}),
          ...(fragment.link&&/^(https?:|mailto:)/i.test(fragment.link)?{hyperlink:{url:fragment.link}}:{}),
          lineSpacingMultiple:1,paraSpaceBefore:0,paraSpaceAfter:0};
        return {type:'text',zIndex:original.zIndex,domOrder:original.domOrder,textParts:[{text:style.textTransform==='uppercase'?fragment.text.toLocaleUpperCase():style.textTransform==='lowercase'?fragment.text.toLocaleLowerCase():fragment.text,options:textOptions}],
          options:{x:config.offX+(cx-config.rootX-fw*sx/2)*unit,y:config.offY+(cy-config.rootY-fh*sy/2)*unit,w:fw*sx*unit,h:fh*sy*unit,
            margin:0,align:'left',valign:'middle',wrap:false,autoFit:false,rotate:(angle+360)%360}};
      });
      return {...result,items:result.items.flatMap(item=>item===original?items:item.type==='text'?[]:[item])};
    } finally {
      for(const {element,property,value,priority} of transformations){if(value)element.style.setProperty(property,value,priority);else element.style.removeProperty(property);}
    }
  };
}
