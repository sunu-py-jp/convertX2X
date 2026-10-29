/** Trusted browser hook: turn CSS geometry into generic SVG paths before native DrawingML export. */
export function installCssNativeHook() {
  const ns='http://www.w3.org/2000/svg';
  const esc=v=>String(v).replaceAll('&','&amp;').replaceAll('"','&quot;').replaceAll('<','&lt;');
  const num=v=>parseFloat(v)||0;
  const fail=message=>{throw new Error('UNSUPPORTED_LAYOUT: '+message);};
  const transparent=c=>c==='transparent'||/rgba\([^)]*,\s*0\s*\)$/.test(c);
  const size=(s,axis)=>{
    const horizontal=axis==='width';
    return num(s[axis])+(s.boxSizing==='border-box'?0:horizontal?num(s.paddingLeft)+num(s.paddingRight)+num(s.borderLeftWidth)+num(s.borderRightWidth):num(s.paddingTop)+num(s.paddingBottom)+num(s.borderTopWidth)+num(s.borderBottomWidth));
  };
  const matrixFor=node=>{
    let m=new DOMMatrix();
    for(let e=node;e;e=e.parentElement){
      const s=getComputedStyle(e);let local=new DOMMatrix();
      if(s.rotate&&s.rotate!=='none'){
        if(!/^-?[\d.]+(?:deg|rad|grad|turn)$/.test(s.rotate))fail('3Dの回転には対応していません。');
        let angle=parseFloat(s.rotate);if(s.rotate.endsWith('grad'))angle*=.9;else if(s.rotate.endsWith('rad'))angle*=180/Math.PI;else if(s.rotate.endsWith('turn'))angle*=360;
        local=local.rotate(angle);
      }
      if(s.scale&&s.scale!=='none'){const values=s.scale.split(/\s+/).map(Number);local=local.scale(values[0],values[1]??values[0]);}
      if(s.transform!=='none')local=local.multiply(new DOMMatrix(s.transform));
      m=local.multiply(m);
    }
    return m;
  };
  globalThis.__convertX2XBox=node=>{
    const style=getComputedStyle(node),matrix=matrixFor(node),rect=node.getBoundingClientRect();
    const width=size(style,'width')||node.offsetWidth,height=size(style,'height')||node.offsetHeight;
    return {matrix,rect,width,height,sx:Math.hypot(matrix.a,matrix.b),sy:Math.hypot(matrix.c,matrix.d),angle:Math.atan2(matrix.b,matrix.a)*180/Math.PI};
  };
  const radii=(s,w,h)=>{
    const r=['TopLeft','TopRight','BottomRight','BottomLeft'].map(c=>{
      const p=s['border'+c+'Radius'].split(/\s+/);return [length(p[0],w),length(p[1]??p[0],h)];
    });
    const f=Math.min(1,w/(r[0][0]+r[1][0]||1),w/(r[3][0]+r[2][0]||1),h/(r[0][1]+r[3][1]||1),h/(r[1][1]+r[2][1]||1));
    return r.map(p=>p.map(n=>n*f));
  };
  const length=(s,extent)=>{
    s=String(s).trim();if(!/^-?(?:\d+\.?\d*|\.\d+)(?:px|%)?$/.test(s))fail('複合的な図形座標を解決できませんでした: '+s);
    return num(s)*(s.endsWith('%')?extent/100:1);
  };
  const rounded=(x,y,w,h,r)=>{
    const [tl,tr,br,bl]=r;const arc=(rx,ry,px,py)=>rx>0&&ry>0?`A${rx},${ry} 0 0 1 ${px},${py}`:`L${px},${py}`;
    return `M${x+tl[0]},${y}H${x+w-tr[0]}${arc(...tr,x+w,y+tr[1])}V${y+h-br[1]}${arc(...br,x+w-br[0],y+h)}H${x+bl[0]}${arc(...bl,x,y+h-bl[1])}V${y+tl[1]}${arc(...tl,x+tl[0],y)}Z`;
  };
  const imageLayouts=new WeakMap(),blurredImages=new Map();let generatedImagePixels=0;
  globalThis.__convertX2XImageLayout=(node,marker,limits)=>{
    const s=getComputedStyle(node),box=globalThis.__convertX2XBox(node),m=box.matrix;
    const {width:w,height:h,rect,sx,sy}=box;
    if(!node.naturalWidth||!node.naturalHeight)fail('画像を読み込めませんでした。');
    if(!m.is2D||Math.abs(m.a*m.c+m.b*m.d)>1e-6*sx*sy||!sx||!sy)fail('画像のせん断・3D変形には対応していません。');
    if(s.clipPath!=='none'||s.boxShadow!=='none')fail('画像のclip-path・影には対応していません。');
    const blurMatch=s.filter.match(/^blur\((\d+(?:\.\d+)?|\.\d+)px\)$/),blur=blurMatch?Number(blurMatch[1]):0;
    if(s.filter!=='none'&&!blurMatch)fail('画像のfilterは単独のblur(px)のみ対応しています。');
    for(let p=node.parentElement;p;p=p.parentElement){
      const ps=getComputedStyle(p),pr=p.getBoundingClientRect();
      if(ps.filter!=='none')fail('親要素に適用したfilterには対応していません。画像自身にblurを指定してください。');
      if(ps.clipPath!=='none')fail('親要素のclip-pathで切り抜かれる画像には対応していません。');
      if((ps.overflowX!=='visible'&&(rect.left<pr.left-.5||rect.right>pr.right+.5))||(ps.overflowY!=='visible'&&(rect.top<pr.top-.5||rect.bottom>pr.bottom+.5)))fail('親要素からはみ出して切り抜かれる画像には対応していません。object-fitを使用してください。');
      if((ps.overflowX!=='visible'||ps.overflowY!=='visible')&&radii(ps,pr.width,pr.height).some(p=>p.some(v=>v>0)))fail('角丸の親要素による画像の切り抜きには対応していません。画像自身にborder-radiusを指定してください。');
    }
    const inset=['Left','Top','Right','Bottom'].map(side=>num(s['border'+side+'Width'])+num(s['padding'+side]));
    const [left,top,right,bottom]=inset,cw=w-left-right,ch=h-top-bottom;
    if(cw<=0||ch<=0)return null;
    let rw=cw,rh=ch;
    if(s.objectFit!=='fill'){
      const ratio=s.objectFit==='none'?1:s.objectFit==='cover'?Math.max(cw/node.naturalWidth,ch/node.naturalHeight):Math.min(cw/node.naturalWidth,ch/node.naturalHeight,s.objectFit==='scale-down'?1:Infinity);
      rw=node.naturalWidth*ratio;rh=node.naturalHeight*ratio;
    }
    const value=(raw,free)=>{
      const keywords={left:'0%',top:'0%',center:'50%',right:'100%',bottom:'100%'};raw=keywords[raw]||raw;
      if(/^[-+]?(?:\d*\.)?\d+(?:px|%)?$/.test(raw))return parseFloat(raw)*(raw.endsWith('%')?free/100:1);
      if(raw.startsWith('calc(')&&raw.endsWith(')')){
        const expr=raw.slice(5,-1).replaceAll(' ','');const terms=expr.match(/[+-]?(?:\d*\.)?\d+(?:px|%)/g);
        if(terms&&terms.join('')===expr)return terms.reduce((sum,term)=>sum+value(term,free),0);
      }
      fail('画像のobject-positionを解決できませんでした。');
    };
    const parts=s.objectPosition.match(/calc\([^)]*\)|[^\s]+/g)||['50%','50%'];
    let px,py;
    if(parts.length<=2){px=value(parts[0],cw-rw);py=value(parts[1]||'50%',ch-rh);}
    else{
      const axes={};for(let i=0;i<parts.length;i++){
        const edge=parts[i],axis=['left','right'].includes(edge)?'x':['top','bottom'].includes(edge)?'y':null;
        if(!axis||axes[axis]!==undefined)fail('画像のobject-positionを解決できませんでした。');
        const free=axis==='x'?cw-rw:ch-rh,offset=i+1<parts.length&&!['left','right','top','bottom','center'].includes(parts[i+1])?value(parts[++i],free):0;
        axes[axis]=['right','bottom'].includes(edge)?free-offset:offset;
      }px=axes.x??(cw-rw)/2;py=axes.y??(ch-rh)/2;
    }
    const vx=Math.max(0,px),vy=Math.max(0,py),vw=Math.min(cw,px+rw)-vx,vh=Math.min(ch,py+rh)-vy;
    if(vw<=0||vh<=0){imageLayouts.set(node,null);return null;}
    const cx=left+vx+vw/2-w/2,cy=top+vy+vh/2-h/2;
    const width=vw*sx,height=vh*sy;
    let opacity=1;for(let p=node;p;p=p.parentElement)opacity*=Number(getComputedStyle(p).opacity);
    if(opacity<.9999&&(!transparent(s.backgroundColor)||s.backgroundImage!=='none'))fail('画像と画像自身の背景をまとめて透過する表示には対応していません。背景を別要素にしてください。');
    const corners=radii(s,w,h).map(([rx,ry],i)=>[Math.max(0,rx-(i===0||i===3?left:right)),Math.max(0,ry-(i<2?top:bottom))]);
    const result={marker,x:rect.left+rect.width/2+m.a*cx+m.c*cy-width/2,y:rect.top+rect.height/2+m.b*cx+m.d*cy-height/2,width,height,
      rotation:(Math.atan2(m.b,m.a)*180/Math.PI+360)%360,flipV:m.a*m.d-m.b*m.c<0,
      crop:{l:(vx-px)/rw,t:(vy-py)/rh,r:(px+rw-vx-vw)/rw,b:(py+rh-vy-vh)/rh},
      clip:{x:vx,y:vy,width:vw,height:vh,boxWidth:cw,boxHeight:ch,radii:corners},opacity,alt:node.alt,source:node.src};
    if(blur>0){
      if(!transparent(s.backgroundColor)||s.backgroundImage!=='none'||['Top','Right','Bottom','Left'].some(side=>num(s['border'+side+'Width'])>0))fail('ぼかす画像自身の背景・枠線には対応していません。別要素として配置してください。');
      const pad=Math.ceil(4*blur),baseW=Math.ceil(cw),baseH=Math.ceil(ch),outW=baseW+pad*2,outH=baseH+pad*2;
      if(!Number.isSafeInteger(outW)||!Number.isSafeInteger(outH)||outW>32767||outH>32767||baseW*baseH>limits.maxImagePixels||outW*outH>limits.maxImagePixels)fail('ぼかした画像の画素数が上限を超えています。');
      const signature=JSON.stringify([cw,ch,rw,rh,px,py,corners,blur]);
      let variants=blurredImages.get(node.src);if(!variants){variants=new Map();blurredImages.set(node.src,variants);}
      let source=variants.get(signature);
      if(!source){
        generatedImagePixels+=baseW*baseH+outW*outH;if(generatedImagePixels>limits.maxTotalImagePixels)fail('ぼかした画像の総画素数が上限を超えています。');
        const base=document.createElement('canvas');base.width=baseW;base.height=baseH;
        const ctx=base.getContext('2d');ctx.clip(new Path2D(rounded(0,0,cw,ch,corners)));ctx.drawImage(node,px,py,rw,rh);
        const output=document.createElement('canvas');output.width=outW;output.height=outH;
        const target=output.getContext('2d');target.filter=`blur(${blur}px)`;target.drawImage(base,pad,pad);
        source=output.toDataURL('image/png');base.width=base.height=output.width=output.height=1;
        if(source.length>limits.maxOutputBytes*4/3+100)fail('ぼかした画像のサイズが上限を超えています。');
        variants.set(signature,source);
      }
      const centerX=left-pad+outW/2-w/2,centerY=top-pad+outH/2-h/2;
      result.x=rect.left+rect.width/2+m.a*centerX+m.c*centerY-outW*sx/2;
      result.y=rect.top+rect.height/2+m.b*centerX+m.d*centerY-outH*sy/2;
      result.width=outW*sx;result.height=outH*sy;result.source=source;result.blur=blur;
      result.crop={l:0,t:0,r:0,b:0};result.clip={x:0,y:0,width:outW,height:outH,boxWidth:outW,boxHeight:outH,radii:[[0,0],[0,0],[0,0],[0,0]]};
      const centerScreen={x:result.x+result.width/2,y:result.y+result.height/2},rx=(Math.abs(m.a)*outW+Math.abs(m.c)*outH)/2,ry=(Math.abs(m.b)*outW+Math.abs(m.d)*outH)/2;
      for(let p=node.parentElement;p;p=p.parentElement){const ps=getComputedStyle(p),pr=p.getBoundingClientRect();if((ps.overflowX!=='visible'&&(centerScreen.x-rx<pr.left-.5||centerScreen.x+rx>pr.right+.5))||(ps.overflowY!=='visible'&&(centerScreen.y-ry<pr.top-.5||centerScreen.y+ry>pr.bottom+.5)))fail('ぼかしの余白が親要素で切り抜かれる画像には対応していません。');}
    }
    imageLayouts.set(node,result);return result;
  };
  const pathLength=d=>{const p=document.createElementNS(ns,'path');p.setAttribute('d',d);return p.getTotalLength();};
  const dashPattern=(style,width,length,closed)=>{
    if(style==='dotted'){
      const dots=Math.max(2,Math.ceil((closed?length:length+width)/(2*width)));
      return `stroke-dasharray="0 ${length/(closed?dots:dots-1)}" stroke-linecap="round"`;
    }
    if(style!=='dashed')return '';
    const count=Math.max(1,Math.ceil(length/(3*width))),u=length/(3*count-(closed?0:1));
    return `stroke-dasharray="${2*u} ${u}"`;
  };
  const clipGeometry=(clip,w,h)=>{
    if(clip==='none')return null;
    const m=clip.match(/^(polygon|circle|ellipse|inset|path)\((.*)\)$/);if(!m)fail('このCSSクリッピングの座標を解決できませんでした。');
    const [,kind,body]=m;
    if(kind==='polygon'){
      const parts=body.split(',').map(p=>p.trim());let rule='nonzero';if(['evenodd','nonzero'].includes(parts[0]))rule=parts.shift();
      const pts=parts.map(p=>{const a=p.split(/\s+/);if(a.length!==2)fail('多角形の座標を解決できませんでした。');return [length(a[0],w),length(a[1],h)];});
      if(pts.length<3||pts.length>10000)fail('多角形の頂点数が範囲外です。');return {path:pts.map((p,i)=>(i?'L':'M')+p.join(',')).join('')+'Z',rule};
    }
    if(kind==='path'){
      const p=body.match(/^(?:(evenodd|nonzero)\s*,\s*)?(["'])(.*)\2$/);if(!p||p[3].length>200000)fail('CSS pathを解決できませんでした。');return {path:p[3],rule:p[1]||'nonzero'};
    }
    if(kind==='circle'||kind==='ellipse'){
      const [rad,pos='50% 50%']=body.split(/\s+at\s+/),xy=pos.trim().split(/\s+/);
      // Avoid eagerly parsing CSS keywords through nullish-expression argument evaluation.
      const coord=(v,extent)=>['left','top'].includes(v)?0:v==='center'?extent/2:['right','bottom'].includes(v)?extent:length(v,extent);
      const cx=coord(xy[0],w),cy=coord(xy[1]??'50%',h),rs=rad.trim().split(/\s+/);
      const radius=(v,a,b,extent)=>v==='closest-side'?Math.min(a,b):v==='farthest-side'?Math.max(a,b):length(v,extent);
      const rx=kind==='circle'?(rs[0]==='farthest-side'?Math.max(cx,cy,w-cx,h-cy):radius(rs[0]||'closest-side',Math.min(cx,cy),Math.min(w-cx,h-cy),Math.hypot(w,h)/Math.SQRT2)):radius(rs[0]||'closest-side',cx,w-cx,w);
      const ry=kind==='circle'?rx:radius(rs[1]||rs[0]||'closest-side',cy,h-cy,h);
      return {path:`M${cx-rx},${cy}A${rx},${ry} 0 1 0 ${cx+rx},${cy}A${rx},${ry} 0 1 0 ${cx-rx},${cy}Z`,rule:'nonzero'};
    }
    const [offset,round]=body.split(/\s+round\s+/),v=offset.trim().split(/\s+/);
    const expanded=[v[0],v[1]??v[0],v[2]??v[0],v[3]??v[1]??v[0]],d=expanded.map((p,i)=>length(p,i%2?w:h));
    if(round&&round.split(/\s+/).length!==1)fail('複数半径のinsetクリッピングには対応していません。');
    const r=round?length(round,Math.min(w,h)):0;
    return {path:rounded(d[3],d[0],Math.max(0,w-d[1]-d[3]),Math.max(0,h-d[0]-d[2]),Array.from({length:4},()=>[r,r])),rule:'nonzero'};
  };
  function gradient(value,w,h){
    const match=value.match(/^linear-gradient\((.*)\)$/);if(!match)fail('複数または未対応の背景グラデーションです。');
    const parts=[];let depth=0,start=0;
    for(let i=0;i<match[1].length;i++){const c=match[1][i];if(c==='(')depth++;if(c===')')depth--;if(c===','&&depth===0){parts.push(match[1].slice(start,i).trim());start=i+1;}}
    parts.push(match[1].slice(start).trim());let angle=180;
    if(/^to\s/.test(parts[0])){
      const words=parts.shift().split(/\s+/),dx=words.includes('right')?1:words.includes('left')?-1:0,dy=words.includes('bottom')?1:words.includes('top')?-1:0;
      angle=Math.atan2(dx*(dy?h:1),-dy*(dx?w:1))*180/Math.PI;
    }else if(/^-?[\d.]+(?:deg|rad|turn|grad)$/.test(parts[0])){const raw=parts.shift();angle=parseFloat(raw)*(raw.endsWith('grad')?.9:raw.endsWith('rad')?180/Math.PI:raw.endsWith('turn')?360:1);}
    const dx=Math.sin(angle*Math.PI/180),dy=-Math.cos(angle*Math.PI/180),len=Math.abs(w*dx)+Math.abs(h*dy);
    const stops=parts.flatMap(part=>{
      const m=part.match(/^(.*?)(?:\s+(-?[\d.]+(?:%|px)))(?:\s+(-?[\d.]+(?:%|px)))?$/),color=m?m[1]:part;
      if(!CSS.supports('color',color))fail('グラデーションの色を解決できませんでした。');
      return m?[m[2],m[3]].filter(Boolean).map(pos=>({color,offset:length(pos,len)/len})):[{color,offset:null}];
    });
    if(stops.length<2||stops.length>256)fail('グラデーションの色数が範囲外です。');
    stops[0].offset??=0;stops.at(-1).offset??=1;
    let previous=stops[0].offset;
    for(let i=1;i<stops.length;i++)if(stops[i].offset!==null){stops[i].offset=Math.max(previous,stops[i].offset);previous=stops[i].offset;}
    for(let i=1;i<stops.length;i++)if(stops[i].offset===null){let j=i;while(stops[j].offset===null)j++;const begin=stops[i-1].offset,end=stops[j].offset;for(let k=i;k<j;k++)stops[k].offset=begin+(end-begin)*(k-i+1)/(j-i+1);i=j;}
    if(stops.some(stop=>stop.offset<0||stop.offset>1))fail('範囲外のグラデーション停止位置には対応していません。');
    return `<defs><linearGradient id="css-fill" gradientUnits="userSpaceOnUse" x1="${w/2-dx*len/2}" y1="${h/2-dy*len/2}" x2="${w/2+dx*len/2}" y2="${h/2+dy*len/2}">${stops.map(s=>`<stop offset="${s.offset}" stop-color="${esc(s.color)}"/>`).join('')}</linearGradient></defs>`;
  }
  function decoration(node,s,w,h,m,rect,opacity){
    const clip=clipGeometry(s.clipPath,w,h),r=radii(s,w,h);
    const sides=['Top','Right','Bottom','Left'].map(side=>({width:num(s['border'+side+'Width']),style:s['border'+side+'Style'],color:s['border'+side+'Color']}));
    const hasBorder=sides.some(b=>b.width>0&&!transparent(b.color)&&!['none','hidden'].includes(b.style));
    const bg=!transparent(s.backgroundColor),hasGradient=s.backgroundImage!=='none';
    if((bg||hasGradient)&&s.backgroundClip!=='border-box')fail('背景の描画範囲を変更するCSSには対応していません。');
    if(hasGradient&&(!['auto','auto auto'].includes(s.backgroundSize)||s.backgroundPosition!=='0% 0%'))fail('背景のサイズ・位置を変更するCSSには対応していません。');
    if(s.boxShadow!=='none'&&clip)fail('切り抜きと影の組合せには対応していません。');
    if(clip&&hasBorder)fail('切り抜いたCSS要素の枠線には対応していません。SVGの輪郭として表現できます。');
    if(clip&&node.childElementCount)fail('子要素を含むクリッピングには対応していません。');
    if(clip&&node.textContent.trim()){
      const points=[[0,0],[w,0],[w,h],[0,h]].map(([x,y])=>({x:m.a*x+m.c*y,y:m.b*x+m.d*y}));
      const originX=rect.left-Math.min(...points.map(p=>p.x)),originY=rect.top-Math.min(...points.map(p=>p.y));
      const inverse=m.inverse(),ctx=document.createElement('canvas').getContext('2d'),path=new Path2D(clip.path);
      for(const child of node.childNodes)if(child.nodeType===3&&child.textContent.trim()){
        const range=document.createRange();range.selectNodeContents(child);
        for(const r of range.getClientRects())for(const [x,y]of [[r.left,r.top],[r.right,r.top],[r.right,r.bottom],[r.left,r.bottom]]){
          const point=new DOMPoint(x-originX,y-originY).matrixTransform(inverse);
          if(!ctx.isPointInPath(path,point.x,point.y,clip.rule))fail('輪郭で切り抜かれる文字には対応していません。');
        }
      }
    }
    if(!bg&&!hasBorder&&!hasGradient||s.boxShadow!=='none')return null;
    const local=rounded(0,0,w,h,r);let content=bg?`<path d="${esc(clip?.path||local)}" fill="${esc(s.backgroundColor)}" fill-rule="${clip?.rule||'nonzero'}"/>`:'';
    if(hasGradient)content+=gradient(s.backgroundImage,w,h)+`<path d="${esc(clip?.path||local)}" fill="url(#css-fill)" fill-rule="${clip?.rule||'nonzero'}"/>`;
    if(hasBorder){
      const uniform=sides.every(b=>b.width===sides[0].width&&b.color===sides[0].color&&b.style===sides[0].style);
      if(uniform){
        const b=sides[0],half=b.width/2,inner=r.map(p=>p.map(v=>Math.max(0,v-half)));
        const d=rounded(half,half,Math.max(0,w-b.width),Math.max(0,h-b.width),inner);
        const dash=dashPattern(b.style,b.width,pathLength(d),true);
        if(!['solid','dashed','dotted','double'].includes(b.style))fail('立体的なCSS枠線には対応していません。');
        if(b.style==='double'){
          for(const inset of [b.width/6,b.width*5/6])content+=`<path d="${esc(rounded(inset,inset,w-2*inset,h-2*inset,r.map(p=>p.map(v=>Math.max(0,v-inset)))))}" fill="none" stroke="${esc(b.color)}" stroke-width="${b.width/3}"/>`;
        }else content+=`<path d="${esc(d)}" fill="none" stroke="${esc(b.color)}" stroke-width="${b.width}" ${dash}/>`;
      }else{
        if(r.some(p=>p.some(v=>v>0)))fail('角丸と辺ごとに異なる枠線の組合せには対応していません。');
        const [t,rr,b,l]=sides.map(b=>b.width);
        const polys=[[[0,0],[w,0],[w-rr,t],[l,t]],[[w,0],[w,h],[w-rr,h-b],[w-rr,t]],[[w,h],[0,h],[l,h-b],[w-rr,h-b]],[[0,h],[0,0],[l,t],[l,h-b]]];
        const lines=[[l,t/2,w-rr,t/2],[w-rr/2,t,w-rr/2,h-b],[w-rr,h-b/2,l,h-b/2],[l/2,h-b,l/2,t]];
        sides.forEach((side,i)=>{
          if(!side.width||transparent(side.color)||['none','hidden'].includes(side.style))return;
          if(side.style==='solid')content+=`<path d="${polys[i].map((p,j)=>(j?'L':'M')+p.join(',')).join('')}Z" fill="${esc(side.color)}"/>`;
          else if(['dashed','dotted'].includes(side.style)){
            const p=[...lines[i]],length=Math.hypot(p[2]-p[0],p[3]-p[1]);
            if(side.style==='dotted'&&length>side.width){const dx=(p[2]-p[0])/length*side.width/2,dy=(p[3]-p[1])/length*side.width/2;p[0]+=dx;p[1]+=dy;p[2]-=dx;p[3]-=dy;}
            const dash=dashPattern(side.style,side.width,Math.hypot(p[2]-p[0],p[3]-p[1]),false);
            content+=`<path d="M${p[0]},${p[1]}L${p[2]},${p[3]}" fill="none" stroke="${esc(side.color)}" stroke-width="${side.width}" ${dash}/>`;
          }else fail('このCSS枠線の組合せには対応していません。');
        });
      }
    }
    const points=[[0,0],[w,0],[w,h],[0,h]].map(([x,y])=>({x:m.a*x+m.c*y,y:m.b*x+m.d*y}));
    const minX=Math.min(...points.map(p=>p.x)),minY=Math.min(...points.map(p=>p.y));
    return `<svg xmlns="${ns}" width="${rect.width}" height="${rect.height}" viewBox="0 0 ${rect.width} ${rect.height}"><g opacity="${opacity}" transform="matrix(${m.a} ${m.b} ${m.c} ${m.d} ${-minX} ${-minY})">${content}</g></svg>`;
  }
  globalThis.__convertX2XRender=(node,result,config,order,sortKey,parentOpacity)=>{
    if(node.namespaceURI===ns&&node.localName==='svg')return globalThis.__convertX2XSvgRender(node,result,config,order,sortKey,parentOpacity);
    if(node.nodeType!==1||node.namespaceURI!== 'http://www.w3.org/1999/xhtml'||!result)return result;
    const s=getComputedStyle(node),rect=node.getBoundingClientRect();
    if(rect.width<.5||rect.height<.5)return result;
    const m=matrixFor(node),w=size(s,'width')||node.offsetWidth,h=size(s,'height')||node.offsetHeight;
    if(!w||!h)return result;
    const sx=Math.hypot(m.a,m.b),sy=Math.hypot(m.c,m.d),dot=m.a*m.c+m.b*m.d;
    const shear=Math.abs(dot)>1e-5;
    const transformed=Math.abs(m.a-1)>1e-6||Math.abs(m.b)>1e-6||Math.abs(m.c)>1e-6||Math.abs(m.d-1)>1e-6;
    const unit=config.scale/96,angle=Math.atan2(m.b,m.a)*180/Math.PI;
    if(node.tagName==='IMG'){
      const image=imageLayouts.get(node);
      if(!image)return {items:[],stopRecursion:true};
      const items=[];
      const svg=decoration(node,s,w,h,m,rect,image.opacity);
      if(svg){const bytes=new TextEncoder().encode(svg);let binary='';for(let i=0;i<bytes.length;i+=8192)binary+=String.fromCharCode(...bytes.subarray(i,i+8192));items.push({type:'image',zIndex:sortKey.concat([-Infinity]),domOrder:order,options:{x:config.offX+(rect.left-config.rootX)*unit,y:config.offY+(rect.top-config.rootY)*unit,w:rect.width*unit,h:rect.height*unit,data:'data:image/svg+xml;base64,'+btoa(binary)}});}
      // The finalizer replaces this tiny transport image with the unchanged
      // original. Repeated multi-megabyte images never enter the upstream ZIP.
      const placeholder='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==';
      items.push({type:'image',zIndex:sortKey.concat([0,-1]),domOrder:image.marker,options:{x:config.offX+(image.x-config.rootX)*unit,y:config.offY+(image.y-config.rootY)*unit,w:image.width*unit,h:image.height*unit,rotate:image.rotation,flipV:image.flipV,data:placeholder}});
      return {items,stopRecursion:true};
    }
    const hasText=(result.items||[]).some(i=>i.type==='text');
    if(hasText&&(shear||Math.abs(sx-sy)>.001))fail('文字を含む斜体・縦横比の変形は編集可能な文字として保持できません。');
    if(transformed&&!shear&&m.a*m.d-m.b*m.c>0){
      for(const item of result.items||[]){
        if(['text','shape'].includes(item.type)||node.tagName==='IMG'&&item.type==='image'){
          Object.assign(item.options,{x:config.offX+(rect.left+rect.width/2-config.rootX-w*sx/2)*unit,y:config.offY+(rect.top+rect.height/2-config.rootY-h*sy/2)*unit,w:w*sx*unit,h:h*sy*unit,rotate:(angle+360)%360});
          if(item.type==='text'&&Math.abs(sx-1)>.001){
            for(const run of item.textParts||[])for(const key of ['fontSize','lineSpacing','charSpacing','paraSpaceAfter','paraSpaceBefore'])if(typeof run.options?.[key]==='number')run.options[key]*=sx;
            if(Array.isArray(item.options.margin))item.options.margin=item.options.margin.map(v=>v*sx);
          }
        }
      }
    }
    // Tables, raster pictures and SVGs retain their own content pipeline.
    if(['TABLE','TD','TH','TR'].includes(node.tagName))return result;
    const opacity=parentOpacity*(Number(s.opacity)||0);
    const svg=decoration(node,s,w,h,m,rect,opacity);
    if(!svg)return result;
    if(shear&&(result.items||[]).some(i=>i.type==='text'))fail('斜体変形した文字は編集可能な文字として保持できません。');
    const kept=(result.items||[]).filter(item=>item.type==='text');
    for(const item of kept){delete item.options.fill;item.options.line=null;item.options.shape='rect';delete item.options.rectRadius;}
    const bytes=new TextEncoder().encode(svg);let binary='';for(let i=0;i<bytes.length;i+=8192)binary+=String.fromCharCode(...bytes.subarray(i,i+8192));
    kept.unshift({type:'image',zIndex:sortKey.concat([-Infinity]),domOrder:order,options:{x:config.offX+(rect.left-config.rootX)*unit,y:config.offY+(rect.top-config.rootY)*unit,w:rect.width*unit,h:rect.height*unit,data:'data:image/svg+xml;base64,'+btoa(binary)}});
    return {items:kept,stopRecursion:result.stopRecursion};
  };
}
