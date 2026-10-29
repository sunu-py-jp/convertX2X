import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import JSZip from 'jszip';
import { DOMParser } from '@xmldom/xmldom';
import { convertHtml } from '../src/service.js';
const executablePath=process.env.HTML2PPTX_CHROMIUM_PATH||process.env.HTML2PPTX_TEST_BROWSER;
const canRun=Boolean(executablePath||process.platform==='linux'||existsSync('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'));
const find=(node,name)=>[...node.getElementsByTagName('*')].filter(n=>n.localName===name);
test('native tables retain collapsed padding, measured line spacing, row spans and full frame height',{skip:!canRun,timeout:60000},async()=>{
  const html=`<style>table{border-collapse:collapse;font-family:'Noto Sans JP';font-size:16px}th,td{padding:13px 16px;border:1px solid #bbb}th{font-weight:700} .spaced{line-height:24px} .separate{border-collapse:separate;border-spacing:4px 6px;margin-top:20px}</style>
    <table><tr><th rowspan="2">区分</th><th colspan="2">売上</th></tr><tr><th>前年</th><th>当年</th></tr><tr><td>A</td><td>12</td><td>14</td></tr></table>
    <table class="separate"><tr><td class="spaced">line one<br>line two</td><td>通常</td></tr></table>`;
  const result=await convertHtml(Buffer.from(html),{executablePath});
  const zip=await JSZip.loadAsync(result.pptx),doc=new DOMParser().parseFromString(await zip.file('ppt/slides/slide1.xml').async('string'),'application/xml');
  const tables=find(doc,'tbl');assert.equal(tables.length,2);
  const rows=find(tables[0],'tr');assert.equal(rows.length,3);
  const rowHeight=Number(rows[0].getAttribute('h'));
  assert.ok(rowHeight>27*9525);
  for(const row of rows)assert.equal(Number(row.getAttribute('h')),rowHeight);
  const cells=find(tables[0],'tc').filter(c=>c.getAttribute('hMerge')!=='1'&&c.getAttribute('vMerge')!=='1');
  assert.equal(cells[0].getAttribute('rowSpan'),'2');assert.equal(cells[1].getAttribute('gridSpan'),'2');
  for(const cell of cells){const properties=find(cell,'tcPr')[0];assert.equal(Number(properties.getAttribute('marT')),13*9525);assert.equal(Number(properties.getAttribute('marB')),13*9525);assert.equal(Number(properties.getAttribute('marL')),16*9525);const linePx=Number(find(cell,'lnSpc')[0].firstChild.getAttribute('val'))/75;assert.ok(Math.abs(rowHeight/9525-(linePx+27))<.02);}
  let frame=tables[0].parentNode;while(frame.localName!=='graphicFrame')frame=frame.parentNode;
  assert.equal(Number(find(find(frame,'xfrm')[0],'ext')[0].getAttribute('cy')),rows.length*rowHeight+9525);
  const separateCells=find(tables[1],'tc'),padding=find(separateCells[0],'tcPr')[0];
  assert.equal(Number(padding.getAttribute('marT')),16*9525); // 13px padding + half of the actual 6px spacing.
  assert.equal(Number(padding.getAttribute('marL')),18*9525);
  assert.equal(Number(find(separateCells[0],'lnSpc')[0].firstChild.getAttribute('val')),1800);
});
