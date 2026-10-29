import { readFile,writeFile,mkdir,copyFile,access,mkdtemp,rm } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath,pathToFileURL } from 'node:url';
import * as parse5 from 'parse5';
import { prepareInput } from '../src/input.js';
import { convertHtml } from '../src/service.js';
import { launchBrowser,securedPage,trustedFontCss,closeBrowser } from '../src/browser.js';
import { FONT_FAMILY,normalizeDocumentFonts } from '../src/fonts.js';
import { poolDocumentImages,restoreDocumentImages } from '../src/image-sources.js';

const ROOT=fileURLToPath(new URL('../',import.meta.url));
const PYTHON=String.raw`
import sys,json,math
from pathlib import Path
from PIL import Image,ImageChops,ImageStat,ImageFilter,ImageDraw,ImageFont
config=json.load(sys.stdin)
out=Path(config['output']);out.mkdir(parents=True,exist_ok=True)
threshold=config.get('threshold',12);edge_threshold=config.get('edgeThreshold',40)
font=ImageFont.load_default()
try:
 font=ImageFont.truetype(config['font'],20)
except (KeyError,OSError): pass

def rgb(file):
 image=Image.open(file).convert('RGBA')
 background=Image.new('RGBA',image.size,'white')
 return Image.alpha_composite(background,image).convert('RGB')

def count(mask): return sum(mask.histogram()[1:])
def maximum_channels(image):
 channels=image.split()
 return ImageChops.lighter(ImageChops.lighter(channels[0],channels[1]),channels[2])
def metrics(a,b):
 diff=ImageChops.difference(a,b);maximum=maximum_channels(diff)
 changed=maximum.point(lambda p:255 if p>threshold else 0)
 stat=ImageStat.Stat(diff)
 ae=a.convert('L').filter(ImageFilter.FIND_EDGES);be=b.convert('L').filter(ImageFilter.FIND_EDGES)
 # FIND_EDGES preserves border pixels; erase that one-pixel border consistently.
 for edge in [ae,be]:
  d=ImageDraw.Draw(edge);d.rectangle((0,0,edge.width-1,edge.height-1),outline=0,width=1)
 ae=ae.point(lambda p:255 if p>edge_threshold else 0)
 be=be.point(lambda p:255 if p>edge_threshold else 0)
 union=ImageChops.lighter(ae,be);edge_delta=ImageChops.difference(ae,be)
 source_edges=count(ae);target_edges=count(be);union_count=count(union)
 total=a.width*a.height
 missing=ImageChops.subtract(ae,be);extra=ImageChops.subtract(be,ae)
 hist=maximum.histogram()
 return {
  'pixels':total,'changedPixels':count(changed),'changedPixelRatio':count(changed)/total,
  'meanAbsoluteChannelError':sum(stat.mean)/3,
  'rootMeanSquareChannelError':math.sqrt(sum(x*x for x in stat.rms)/3),
  'maxChannelError':max((i for i,n in enumerate(hist) if n),default=0),
  'sourceEdgePixels':source_edges,'pptxEdgePixels':target_edges,
  'edgeMismatchPixels':count(edge_delta),'edgeMismatchRatio':count(edge_delta)/union_count if union_count else 0,
  'missingSourceEdgeRatio':count(missing)/source_edges if source_edges else 0,
  'additionalPptxEdgePixels':count(extra),
 },maximum,changed,edge_delta

results=[]
for pair in config['pairs']:
 a=rgb(pair['source']);b=rgb(pair['target'])
 if a.size!=b.size: raise ValueError('Source and target dimensions must match exactly')
 result,maximum,mask,edge_delta=metrics(a,b)
 prefix=str(pair.get('slide',len(results)+1)).zfill(2)
 # Difference intensity is amplified for inspection, without changing metrics.
 intensity=maximum.point(lambda p:min(255,p*4))
 heat=Image.merge('RGB',(Image.new('L',a.size,255),ImageChops.invert(intensity),ImageChops.invert(intensity)))
 heat.save(out/f'diff-{prefix}.png')
 edges=Image.merge('RGB',(edge_delta,Image.new('L',a.size,0),Image.new('L',a.size,0)))
 edges.save(out/f'edge-diff-{prefix}.png')
 blend=Image.blend(a,b,.5);blend.save(out/f'overlay-{prefix}.png')
 comparison=Image.new('RGB',(a.width*2,a.height*2+88),'#e8edf2')
 for image,label,x,y in [(a,'SOURCE HTML / CHROMIUM',0,0),(b,config.get('targetLabel','GENERATED PPTX'),a.width,0),(heat,'PIXEL DIFFERENCE (intensity x4)',0,a.height+44),(blend,'50 / 50 OVERLAY',a.width,a.height+44)]:
  ImageDraw.Draw(comparison).text((x+18,y+9),label,font=font,fill='#203c54')
  comparison.paste(image,(x,y+44))
 comparison.save(out/f'comparison-{prefix}.png')
 panels=[]
 for region in pair.get('regions',[]):
  x,y,w,h=[int(region[k]) for k in ['x','y','width','height']]
  if min(x,y)<0 or min(w,h)<=0 or x+w>a.width or y+h>a.height: raise ValueError('Comparison region is outside the image')
  value,*_=metrics(a.crop((x,y,x+w,y+h)),b.crop((x,y,x+w,y+h)))
  panels.append({'id':region['id'],'label':region.get('label',region['id']),'bounds':{'x':x,'y':y,'width':w,'height':h},**value})
 results.append({'slide':pair.get('slide',len(results)+1),'width':a.width,'height':a.height,**result,'regions':panels,
  'images':{'source':Path(pair['source']).name,'pptx':Path(pair['target']).name,'difference':f'diff-{prefix}.png','edgeDifference':f'edge-diff-{prefix}.png','overlay':f'overlay-{prefix}.png','comparison':f'comparison-{prefix}.png'}})
json.dump(results,sys.stdout,ensure_ascii=False)
`;

function run(command,args,{input,timeout=120000,cwd=ROOT,env}={}) {
  return new Promise((resolve,reject)=>{
    const child=spawn(command,args,{cwd,env,stdio:['pipe','pipe','pipe']});
    let stdout='',stderr='',settled=false;
    const finish=(error,value)=>{if(settled)return;settled=true;clearTimeout(timer);error?reject(error):resolve(value);};
    const timer=setTimeout(()=>{child.kill('SIGKILL');finish(new Error(`${path.basename(command)} exceeded the diagnostic deadline`));},timeout);
    child.on('error',error=>finish(error));
    child.stdout.on('data',chunk=>{stdout+=chunk;if(stdout.length>8*1024*1024){child.kill('SIGKILL');finish(new Error('Diagnostic output exceeded limit'));}});
    child.stderr.on('data',chunk=>{stderr=(stderr+chunk).slice(-12000);});
    child.on('exit',code=>code===0?finish(null,{stdout,stderr}):finish(new Error(`${path.basename(command)} exited with ${code}: ${stderr.slice(-2000)}`)));
    child.stdin.end(input??'');
  });
}

export async function compareImages({pairs,output,threshold=12,edgeThreshold=40,python=process.env.PYTHON||'python3',targetLabel='GENERATED PPTX'}) {
  if(!Number.isInteger(threshold)||threshold<0||threshold>255)throw new Error('threshold must be an integer from 0 to 255');
  const result=await run(python,['-c',PYTHON],{input:JSON.stringify({pairs,output,threshold,edgeThreshold,targetLabel,font:path.join(ROOT,'assets/fonts/NotoSansJP-Regular.ttf')})});
  return JSON.parse(result.stdout);
}
const nodes=root=>[root,...(root.childNodes||[]).flatMap(nodes)];
const attribute=(node,name)=>node.attrs?.find(a=>a.name===name)?.value;
function metadata(input) {
  const document=typeof input==='string'?parse5.parse(input):input;
  const meta=nodes(document).find(n=>n.tagName==='meta'&&attribute(n,'name')==='visual-comparison-regions');
  const value=meta?JSON.parse(attribute(meta,'content')):{version:1,slides:[]};
  return value;
}
function oneSlide(input,index) {
  const document=typeof input==='string'?parse5.parse(input):input;
  const slides=nodes(document).filter(n=>n.attrs?.some(a=>a.name==='data-slide'));
  const parents=new Map();
  try {
    for(const [i,slide] of slides.entries())if(i!==index&&slide.parentNode) {
      const parent=slide.parentNode;
      if(!parents.has(parent))parents.set(parent,parent.childNodes);
      parent.childNodes=parent.childNodes.filter(n=>n!==slide);
    }
    return parse5.serialize(document);
  } finally {for(const [parent,children] of parents)parent.childNodes=children;}
}
async function sourcePreviews(input,width,height,output) {
  const scratch=await mkdtemp(path.join(os.tmpdir(),'html2pptx-visual-'));
  let browser;
  try {
    browser=await launchBrowser(scratch,process.env.HTML2PPTX_CHROMIUM_PATH);
    const page=await securedPage(browser,width,height);
    const document=typeof input==='string'?parse5.parse(input):input;
    const head=nodes(document).find(n=>n.tagName==='head');
    const trusted=parse5.parseFragment(`<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:"><style>${await trustedFontCss()}html,body{margin:0;padding:0}html{background:white;color-scheme:light}body{font-family:'Noto Sans JP',sans-serif}[data-slide]{box-sizing:border-box;width:${width}px!important;height:${height}px!important;position:relative;flex-shrink:0}</style>`).childNodes;
    const originalHead=head.childNodes;
    const originalImages=nodes(document).filter(n=>n.tagName==='img').map(n=>[n,n.attrs.map(a=>({...a}))]);
    const sources=poolDocumentImages(document);
    try {
      head.childNodes=[...trusted,...originalHead];for(const node of trusted)node.parentNode=head;
      await page.setContent(parse5.serialize(document),{waitUntil:'domcontentloaded',timeout:10000});
    } finally {
      head.childNodes=originalHead;
      for(const [node,attributes] of originalImages)node.attrs=attributes;
    }
    await page.evaluate(restoreDocumentImages,sources);
    await page.evaluate(normalizeDocumentFonts,FONT_FAMILY);
    await page.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(i=>i.decode().catch(()=>{})));});
    let index=0;
    for(const slide of await page.$$('[data-slide]'))await slide.screenshot({type:'png',path:path.join(output,`html-${String(++index).padStart(2,'0')}.png`)});
    return {slides:index,chromium:await browser.version()};
  } finally {await closeBrowser(browser);await rm(scratch,{recursive:true,force:true});}
}
async function classpath(options) {
  if(options.classpathFile)return (await readFile(options.classpathFile,'utf8')).trim();
  if(process.env.HTML2PPTX_POI_CLASSPATH)return process.env.HTML2PPTX_POI_CLASSPATH;
  for(const name of ['ppt-pdf-to-images','office2md']) {
    const filename=path.resolve(ROOT,`../${name}/target/license-runtime-classpath.txt`);
    try{return (await readFile(filename,'utf8')).trim();}catch{}
  }
  throw new Error('Apache POI classpath is required: set HTML2PPTX_POI_CLASSPATH or --classpath-file. Build an existing Java module first if using this repository.');
}
async function javaCommand(options) {
  if(options.java)return options.java;
  if(process.env.JAVA_HOME)return path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java');
  const mac='/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java';
  try{await access(mac);return mac;}catch{return 'java';}
}
async function renderWithJava(pptx,output,width,height,options) {
  return run(await javaCommand(options),['-Djava.awt.headless=true','-Duser.language=en','-Duser.country=US','--class-path',await classpath(options),path.join(ROOT,'scripts/render-preview.java'),path.join(ROOT,'assets/fonts'),pptx,output,String(width),String(height)],{timeout:180000});
}
async function renderPptx(pptx,output,width,height,options) {
  if(options.renderer==='poi') {
    await renderWithJava(pptx,output,width,height,options);
    const info={target:'Apache POI XSLF / Java2D',strokeControl:'PURE',fontFamily:'Noto Sans JP'};
    await writeFile(path.join(output,'renderer.json'),JSON.stringify(info,null,2)+'\n');
    return info;
  }
  const scratch=await mkdtemp(path.join(os.tmpdir(),'html2pptx-libreoffice-'));
  try {
    const escape=value=>value.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('"','&quot;');
    const fontConfig=path.join(scratch,'fonts.conf');
    await writeFile(fontConfig,`<?xml version="1.0"?><fontconfig><dir>${escape(path.join(ROOT,'assets/fonts'))}</dir><cachedir>${escape(path.join(scratch,'font-cache'))}</cachedir></fontconfig>`);
    await run(options.soffice||process.env.SOFFICE||'soffice',[
      `-env:UserInstallation=${pathToFileURL(path.join(scratch,'profile')).href}`,'--headless','--convert-to','pdf:impress_pdf_Export','--outdir',output,pptx,
    ],{timeout:180000,env:{...process.env,FONTCONFIG_FILE:fontConfig,FONTCONFIG_PATH:scratch}});
    const pdf=path.join(output,path.basename(pptx,'.pptx')+'.pdf');
    const bytes=await readFile(pdf);
    const fonts=[...new Set([...bytes.toString('latin1').matchAll(/\/BaseFont\s*\/([^\s<>[\]()/]+)/g)].map(m=>m[1].replace(/^[A-Z]{6}\+/,'')))];
    await renderWithJava(pdf,output,width,height,options);
    const info={target:'LibreOffice Impress → PDF → PDFBox',fontConfig:'isolated bundled Noto Sans JP',pdfFonts:fonts,
      fontsVerified:fonts.length>0&&fonts.every(font=>font.startsWith('NotoSansJP'))};
    if(options.includePoi!==false) {
      try {await renderWithJava(pptx,path.join(output,'poi'),width,height,options);info.secondary='Apache POI XSLF / Java2D (stroke PURE)';}
      catch(error){info.secondaryError=safeError(error);}
    }
    await writeFile(path.join(output,'renderer.json'),JSON.stringify(info,null,2)+'\n');
    return info;
  } finally {await rm(scratch,{recursive:true,force:true});}
}
async function saveConversion(result,directory) {
  await mkdir(directory,{recursive:true});
  await writeFile(path.join(directory,'presentation.pptx'),result.pptx);
  await writeFile(path.join(directory,'report.json'),JSON.stringify(result.report,null,2)+'\n');
  for(const [index,image] of result.previews.entries())await writeFile(path.join(directory,`html-${String(index+1).padStart(2,'0')}.png`),image);
}
const safeError=error=>({code:error.code||'DIAGNOSTIC_FAILED',message:error.message});
const htmlEscape=value=>String(value).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('"','&quot;');
function comparisonPage(report) {
  let content='';
  for(const slide of report.slides) {
    const m=slide.metrics;
    content+=`<section><h2>${String(slide.slide).padStart(2,'0')} · ${htmlEscape(slide.title)}</h2>`;
    if(!m){content+=`<p class="error">${slide.converted?'PPTXの比較用描画ができませんでした':'変換できませんでした'}：${htmlEscape(slide.error?.code)} — ${htmlEscape(slide.error?.message)}</p><a href="html-${String(slide.slide).padStart(2,'0')}.png"><img src="html-${String(slide.slide).padStart(2,'0')}.png" alt="入力HTML"></a></section>`;continue;}
    content+=`<p>差分ピクセル ${(m.changedPixelRatio*100).toFixed(2)}% · 平均絶対誤差 ${m.meanAbsoluteChannelError.toFixed(2)} / 255 · 輪郭の不一致 ${(m.edgeMismatchRatio*100).toFixed(2)}%</p><a href="${m.images.comparison}"><img src="${m.images.comparison}" alt="入力HTML・PPTX描画・ピクセル差分・重ね合わせ"></a><p><a href="${m.images.source}">入力HTML</a> · <a href="${m.images.pptx}">生成PPTXの描画</a> · <a href="${m.images.difference}">差分</a> · <a href="${m.images.edgeDifference}">輪郭差分</a> · <a href="${m.images.overlay}">重ね合わせ</a></p>`;
    if(m.regions.length){content+='<table><thead><tr><th>パネル</th><th>差分ピクセル</th><th>平均誤差 / 255</th><th>輪郭の不一致</th></tr></thead><tbody>';for(const region of m.regions)content+=`<tr><td>${htmlEscape(region.label)}</td><td>${(region.changedPixelRatio*100).toFixed(2)}%</td><td>${region.meanAbsoluteChannelError.toFixed(2)}</td><td>${(region.edgeMismatchRatio*100).toFixed(2)}%</td></tr>`;content+='</tbody></table>';}
    if(slide.poiMetrics){const p=slide.poiMetrics;content+=`<details><summary>参考：Apache POIによる描画（線の正規化を無効化）</summary><p>差分 ${(p.changedPixelRatio*100).toFixed(2)}% · 平均誤差 ${p.meanAbsoluteChannelError.toFixed(2)} / 255。描画器固有の未対応を変換そのものの欠落と混同しないための参考値です。</p><a href="poi/${p.images.comparison}"><img src="poi/${p.images.comparison}" alt="POIによる比較"></a></details>`;}
    content+='</section>';
  }
  return `<!doctype html><html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>HTML / PPTX ピクセル比較</title><style>body{font-family:system-ui,sans-serif;background:#f4f7fa;color:#213b51;max-width:1200px;margin:32px auto;padding:0 24px;line-height:1.8}h1{font-size:30px}section{background:#fff;padding:24px;border:1px solid #d5e0e9;border-radius:12px;margin:28px 0}img{max-width:100%;height:auto;background:white}table{border-collapse:collapse;width:100%;font-size:14px}td,th{text-align:left;border-bottom:1px solid #dce5ec;padding:9px}a{color:#1768a4}.error{color:#a63e52}code{background:#e5ecf3;padding:3px 6px}</style></head><body><h1>HTML / PPTX ピクセル比較</h1><p>入力はChromium、変換後PPTXは${htmlEscape(report.renderers.target)}で描画しました。PowerPointアプリそのもののスクリーンショットではありません。文字のアンチエイリアスやフォント描画も差分に含み、ピクセル全一致を合格条件にはしていません。</p><p>全体変換：<strong>${report.fullConversion.ok?'成功':'失敗（スライド別に診断）'}</strong>。${report.threshold}を超える最大チャンネル差を「差分ピクセル」とします。画像の位置合わせ・自動縮尺補正は行いません。ヒートマップだけは差分強度を4倍にしています。</p><p><a href="metrics.json">全計測値</a> · <a href="source.html">入力HTML</a>${report.fullConversion.ok?' · <a href="presentation.pptx">生成PPTX</a>':''}</p>${content}</body></html>`;
}

export async function visualCompare(input,output,options={}) {
  const buffer=await readFile(input),filename=path.basename(input);
  const prepared=await prepareInput(buffer,{filename});
  const meta=metadata(prepared.document||prepared.html);
  await mkdir(output,{recursive:true});
  await writeFile(path.join(output,'source.html'),prepared.html);
  const source=await sourcePreviews(prepared.document||prepared.html,prepared.width,prepared.height,output);
  if(prepared.document)prepared.html='';
  let fullConversion,fullResult;
  const perSlide=[],rendererRuns=[];
  try {fullResult=await convertHtml(buffer,{filename,captureHtml:true});}
  catch(error){fullConversion={ok:false,error:safeError(error)};}
  if(fullResult) {
    await saveConversion(fullResult,output);
    fullConversion={ok:true,report:'report.json',pptx:'presentation.pptx'};
    try {
      rendererRuns.push(await renderPptx(path.join(output,'presentation.pptx'),output,prepared.width,prepared.height,options));
      for(let i=1;i<=source.slides;i++)perSlide.push({slide:i,converted:true,rendered:true});
    } catch(error) {
      for(let i=1;i<=source.slides;i++)perSlide.push({slide:i,converted:true,rendered:false,error:safeError(error)});
    }
  } else {
    for(let i=1;i<=source.slides;i++) {
      const directory=path.join(output,'isolated',`slide-${String(i).padStart(2,'0')}`);
      const entry={slide:i,converted:false,rendered:false};perSlide.push(entry);
      try {
        const isolated=oneSlide(prepared.document||prepared.html,i-1);
        const result=await convertHtml(Buffer.from(isolated),{filename:'input.html',captureHtml:true});
        await saveConversion(result,directory);
        await writeFile(path.join(directory,'source.html'),isolated);
        entry.converted=true;entry.pptx=path.relative(output,path.join(directory,'presentation.pptx'));
        rendererRuns.push(await renderPptx(path.join(directory,'presentation.pptx'),directory,prepared.width,prepared.height,options));
        await copyFile(path.join(directory,'pptx-01.png'),path.join(output,`pptx-${String(i).padStart(2,'0')}.png`));
        try {
          await mkdir(path.join(output,'poi'),{recursive:true});
          await copyFile(path.join(directory,'poi/pptx-01.png'),path.join(output,'poi',`pptx-${String(i).padStart(2,'0')}.png`));
        } catch { /* A secondary renderer may be unavailable. */ }
        entry.rendered=true;
      }catch(error){entry.error=safeError(error);}
    }
  }
  const pairs=perSlide.filter(s=>s.rendered).map(s=>({slide:s.slide,source:path.join(output,`html-${String(s.slide).padStart(2,'0')}.png`),target:path.join(output,`pptx-${String(s.slide).padStart(2,'0')}.png`),regions:meta.slides.find(m=>m.slide===s.slide)?.regions||[]}));
  const metrics=await compareImages({pairs,output,threshold:options.threshold??12,edgeThreshold:options.edgeThreshold??40,python:options.python,targetLabel:options.renderer==='poi'?'GENERATED PPTX / APACHE POI':'GENERATED PPTX / LIBREOFFICE'});
  const poiPairs=[];
  if(options.renderer!=='poi'&&options.includePoi!==false) {
    for(const pair of pairs) {
      const target=path.join(output,'poi',`pptx-${String(pair.slide).padStart(2,'0')}.png`);
      try{await access(target);poiPairs.push({...pair,target});}catch{}
    }
  }
  const poiMetrics=poiPairs.length?await compareImages({pairs:poiPairs,output:path.join(output,'poi'),threshold:options.threshold??12,edgeThreshold:options.edgeThreshold??40,python:options.python,targetLabel:'GENERATED PPTX / APACHE POI'}):[];
  const report={version:1,sourceFile:filename,sourceSha256:createHash('sha256').update(buffer).digest('hex'),
    renderers:{source:source.chromium,target:options.renderer==='poi'?'Apache POI XSLF / Java2D':'LibreOffice Impress → PDF → PDFBox',fontConfig:'isolated bundled Noto Sans JP',runs:rendererRuns},
    caveat:'This measures Chromium versus the selected renderer of the generated PPTX; LibreOffice uses an isolated bundled-font configuration. It is not a PowerPoint application screenshot or a guarantee of pixel equality.',
    threshold:options.threshold??12,edgeThreshold:options.edgeThreshold??40,
    metricDefinitions:{changedPixelRatio:'max(abs(R,G,B difference)) > threshold, divided by image pixels',meanAbsoluteChannelError:'mean absolute error across RGB channels, from 0 to 255',edgeMismatchRatio:'XOR of thresholded grayscale FIND_EDGES masks, divided by their union; no geometric registration'},
    fullConversion,slides:perSlide.map(s=>({...s,title:meta.slides.find(m=>m.slide===s.slide)?.title||`Slide ${s.slide}`,metrics:metrics.find(m=>m.slide===s.slide),poiMetrics:poiMetrics.find(m=>m.slide===s.slide)}))};
  await writeFile(path.join(output,'metrics.json'),JSON.stringify(report,null,2)+'\n');
  await writeFile(path.join(output,'index.html'),comparisonPage(report));
  return report;
}
async function main() {
  const args=process.argv.slice(2),positional=[],options={};
  for(let i=0;i<args.length;i++) {
    if(args[i]==='--classpath-file')options.classpathFile=args[++i];
    else if(args[i]==='--java')options.java=args[++i];
    else if(args[i]==='--python')options.python=args[++i];
    else if(args[i]==='--threshold')options.threshold=Number(args[++i]);
    else if(args[i]==='--renderer')options.renderer=args[++i];
    else if(args[i]==='--soffice')options.soffice=args[++i];
    else if(args[i]==='--no-poi')options.includePoi=false;
    else if(args[i]==='--help'){console.log('node scripts/visual-compare.mjs [input.html] [output-directory] [--renderer libreoffice|poi] [--soffice executable] [--classpath-file file] [--java executable] [--python executable] [--threshold 12] [--no-poi]\nRequires Node 24, Python/Pillow, Java 21 with POI/PDFBox, and LibreOffice for the default renderer.');return;}
    else if(args[i].startsWith('--'))throw new Error(`Unknown option: ${args[i]}`);
    else positional.push(args[i]);
  }
  if(options.renderer&&!['libreoffice','poi'].includes(options.renderer))throw new Error('renderer must be libreoffice or poi');
  const input=path.resolve(positional[0]||path.join(ROOT,'examples/visual-fidelity.html'));
  const output=path.resolve(positional[1]||path.join(ROOT,'output/visual-comparison'));
  const report=await visualCompare(input,output,options);
  console.log(JSON.stringify({output,fullConversion:report.fullConversion,slides:report.slides.map(s=>({slide:s.slide,converted:s.converted,error:s.error,changedPixelRatio:s.metrics?.changedPixelRatio,meanAbsoluteChannelError:s.metrics?.meanAbsoluteChannelError,edgeMismatchRatio:s.metrics?.edgeMismatchRatio}))},null,2));
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))await main();
