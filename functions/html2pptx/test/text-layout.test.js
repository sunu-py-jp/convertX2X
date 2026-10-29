import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {launchBrowser,trustedFontCss,closeBrowser} from '../src/browser.js';
import {installTextLayoutHook} from '../src/text-layout.js';

test('measured text fragments preserve browser lines, mixed sizes and inherited rotation',async()=>{
  const scratch=await mkdtemp(path.join(os.tmpdir(),'html2pptx-text-layout-'));let browser;
  try {
    browser=await launchBrowser(scratch,process.env.HTML2PPTX_CHROMIUM_PATH);const page=await browser.newPage();
    await page.setViewport({width:1280,height:720});
    await page.setContent(`<style>${await trustedFontCss()}*{box-sizing:border-box}body{margin:0;font-family:'Noto Sans JP'}section{width:1280px;height:720px;position:relative}.text{position:absolute;font-size:24px;width:260px;line-height:1.7}</style><section data-slide><div id="lines" class="text" style="left:50px;top:60px">上段<br><span style="font-size:30px;color:rgba(120,40,90,.5);background:#ffff00">下段</span></div><div style="position:absolute;left:500px;top:100px;rotate:25deg;scale:1.15;translate:12px 7px"><div id="rotated" class="text" style="position:relative">回転した文字<br>第2行</div></div><div id="link" class="text" style="left:60px;top:360px;text-transform:uppercase"><a href="https://example.com" style="text-decoration:underline">a  b</a></div></section>`);
    await page.evaluate(()=>document.fonts.ready);
    await page.evaluate(()=>{globalThis.__convertX2XRender=(_node,result)=>result;});
    await page.evaluate(installTextLayoutHook);
    const output=await page.evaluate(()=>{
      return ['lines','rotated','link'].map(id=>{
        const node=document.getElementById(id),root=node.closest('section').getBoundingClientRect();
        const originalTransform=['transform','rotate','scale','translate'].map(p=>node.parentElement.style[p]).join('|');
        const measured=[];const walker=document.createTreeWalker(node,NodeFilter.SHOW_TEXT);let text;
        while(text=walker.nextNode()){const range=document.createRange();range.selectNode(text);const r=range.getBoundingClientRect();measured.push({x:r.x+r.width/2,y:r.y+r.height/2});}
        const result=globalThis.__convertX2XRender(node,{items:[{type:'text',domOrder:1,zIndex:[0],textParts:[{text:'original'}],options:{}}],stopRecursion:true},{scale:1,offX:0,offY:0,rootX:root.x,rootY:root.y},1,[0],1);
        return {id,result,measured,transformRestored:['transform','rotate','scale','translate'].map(p=>node.parentElement.style[p]).join('|')===originalTransform};
      });
    });
    const lines=output[0].result.items;
    assert.equal(lines.length,2);assert.deepEqual(lines.map(x=>x.textParts[0].text),['上段','下段']);
    assert.equal(lines[0].textParts[0].options.fontSize,18);
    assert.equal(lines[1].textParts[0].options.fontSize,22.5);
    assert.equal(lines[1].textParts[0].options.highlight,'FFFF00');
    assert.ok(lines[1].options.y>lines[0].options.y+.4);
    assert.ok(Math.abs(lines[1].textParts[0].options.transparency-50)<=1);
    for(const item of lines){assert.equal(item.options.valign,'middle');assert.equal(item.options.wrap,false);assert.equal(item.options.autoFit,false);assert.equal(item.textParts[0].options.lineSpacing,undefined);}
    for(const [i,item] of output[1].result.items.entries()){
      assert.ok(Math.abs(item.options.rotate-25)<.001);
      assert.ok(Math.abs((item.options.x+item.options.w/2)*96-output[1].measured[i].x)<.05);
      assert.ok(Math.abs((item.options.y+item.options.h/2)*96-output[1].measured[i].y)<.05);
    }
    assert.ok(output.every(o=>o.transformRestored));
    const link=output[2].result.items[0].textParts[0];
    assert.equal(link.text,'A B');assert.equal(link.options.hyperlink.url,'https://example.com');assert.equal(link.options.underline,true);
  } finally {await closeBrowser(browser);await rm(scratch,{recursive:true,force:true});}
});
