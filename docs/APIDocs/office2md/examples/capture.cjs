#!/usr/bin/env node
'use strict';
// Capture genuine local Functions responses and Playground output. No API mocks.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const { execFileSync, spawnSync } = require('node:child_process');
const { chromium } = require('playwright');
const base = process.env.OFFICE_EXAMPLE_BASE || 'http://localhost:7072';
assert(['localhost', '127.0.0.1'].includes(new URL(base).hostname), 'Run against a local Functions host.');
const root = path.resolve(__dirname, '../../../..');
const hash = data => crypto.createHash('sha256').update(data).digest('hex');
const cases = [
  { name: 'excel-complex', file: 'input.xlsx', sections: [0, 1, 5, 6] },
  { name: 'word-complex', file: 'input.docx', sections: [0] },
  { name: 'word-rag-flow', file: 'input.docx', sections: [0] },
  { name: 'powerpoint-complex', file: 'input.pptx', sections: [1, 2] },
  { name: 'powerpoint-rag-flow', file: 'input.pptx', sections: [0, 1, 2] },
];
const selectedCases = process.env.OFFICE_EXAMPLE_CASE
  ? cases.filter(sample => sample.name === process.env.OFFICE_EXAMPLE_CASE) : cases;
assert(selectedCases.length, 'OFFICE_EXAMPLE_CASE must match an example directory.');

async function main() {
  const browser = await chromium.launch({headless: true,
    ...(process.env.PLAYWRIGHT_CHANNEL ? {channel: process.env.PLAYWRIGHT_CHANNEL} : {})});
  try {
    for (const sample of selectedCases) {
      const directory = path.join(__dirname, sample.name);
      const inputPath = path.join(directory, sample.file);
      const input = await fs.readFile(inputPath);
      await fs.mkdir(path.join(directory, 'screenshots'), {recursive: true});
      const context = await browser.newContext({viewport:{width:1440,height:1000}, acceptDownloads:true});
      const page = await context.newPage();
      const errors = [], externalRequests = [];
      page.on('pageerror', error => errors.push(error.message));
      page.on('request', request => {
        if (/^https?:/.test(request.url()) && new URL(request.url()).origin !== new URL(base).origin)
          externalRequests.push(request.url());
      });
      await page.goto(`${base}/api/playground`);
      await page.waitForFunction(() => document.querySelector('#config-status')?.dataset.state === 'ready');
      await page.locator('#file-input').setInputFiles(inputPath);
      await page.locator('#mode-sync').check();
      const pending = page.waitForResponse(response => response.request().method() === 'POST'
        && new URL(response.url()).pathname.endsWith('/convert'), {timeout:180000});
      const started = Date.now();
      await page.locator('#submit-button').click();
      const response = await pending;
      assert.equal(response.status(), 200);
      await page.waitForFunction(() => !document.querySelector('#result-content').hidden, null, {timeout:180000});
      const elapsedMs = Date.now() - started;
      const downloading = page.waitForEvent('download');
      await page.locator('#download-archive').click();
      const download = await downloading;
      assert.equal(await download.failure(), null);
      await download.saveAs(path.join(directory, 'result.zip'));
      assert.equal(await page.locator('#error-message').isVisible(), false);
      assert.equal(await page.locator('#preview-notice').isVisible(), false);
      const images = page.locator('#preview img');
      for (let i=0; i<await images.count(); i++) {
        await images.nth(i).scrollIntoViewIfNeeded();
        await images.nth(i).evaluate(image => image.decode());
      }
      await page.locator('#preview').evaluate(el => {el.scrollTop=0;});
      await page.evaluate(() => window.scrollTo(0,0));
      const screenshots=[];
      await page.screenshot({path:path.join(directory,'screenshots/overview.png'),fullPage:true});
      screenshots.push('screenshots/overview.png');
      const sections = await page.locator('#preview').evaluate(preview => {
        const names=[]; let index=-1;
        for(const node of preview.children) {
          if(node.tagName==='H1'){index++; names.push(node.textContent);}
          // A page marker precedes its H1 and belongs to the upcoming section.
          node.dataset.captureSection=String(node.tagName==='P' && /^\[page \d+\]$/.test(node.textContent) ? index+1 : index);
        }
        return names;
      });
      async function screenshot(name, selector, section, warnings=false, cropHeight=null) {
        await page.evaluate(({section,warnings,cropHeight}) => {
          window.__originalStyles=[];
          const set=(el,prop,value)=>{
            window.__originalStyles.push([el,prop,el.style.getPropertyValue(prop),el.style.getPropertyPriority(prop)]);
            el.style.setProperty(prop,value,'important');
          };
          const preview=document.querySelector('#preview');
          if(section!==null) {
            set(preview,'max-height',cropHeight ? `${cropHeight}px` : 'none');
            set(preview,'overflow',cropHeight ? 'hidden' : 'visible');
            for(const img of preview.querySelectorAll('img')) set(img,'max-height','none');
            for(const node of preview.children)
              if(node.dataset.captureSection!==String(section)) set(node,'display','none');
          }
          if(warnings) {
            const list=document.querySelector('#warning-list');
            set(list,'max-height','none');set(list,'overflow','visible');
          }
        },{section,warnings,cropHeight});
        try {
          await page.locator(selector).screenshot({path:path.join(directory,'screenshots',name)});
          screenshots.push(`screenshots/${name}`);
        } finally {
          await page.evaluate(()=>{
            for(const [el,prop,value,priority] of window.__originalStyles.reverse())
              value ? el.style.setProperty(prop,value,priority) : el.style.removeProperty(prop);
            delete window.__originalStyles;
          });
        }
      }
      for(const section of sample.sections) {
        assert(section<sections.length);
        await screenshot(`section-${String(section+1).padStart(2,'0')}.png`,'#preview',section);
      }
      if(sample.name==='excel-complex') {
        await screenshot('tables-detail.png','#preview',1,false,790);
        await screenshot('group-detail.png','#preview',5,false,1000);
      }
      await page.locator('#tab-source').click();
      await screenshot('markdown-source.png','.result-panel',null);
      const markdown = await page.locator('#markdown-source').textContent();
      const reportText = await page.locator('#report-source').textContent();
      const report=JSON.parse(reportText);
      { // Every format uses JSON alt and structured diagram metadata.
        const rawMetadata = [...markdown.matchAll(/^!\[(.+)\]\(images\/[^)]+\)$/gm)]
          .map(match => JSON.parse(match[1]));
        const previewMetadata = await page.locator('#preview img').evaluateAll(images =>
          images.map(image => JSON.parse(image.alt)));
        assert.deepEqual(rawMetadata, previewMetadata);
        assert.deepEqual(rawMetadata, report.blocks.filter(block => block.metadata).map(block => block.metadata));
        assert(rawMetadata.length > 0 && rawMetadata.every(metadata =>
          !('rotation' in metadata) && !('origin' in metadata) && !('unit' in metadata)));
        const renderedText = (await page.locator('#preview').textContent()).replace(/\s+/g, ' ');
        for (const block of report.blocks.filter(block => Array.isArray(block.nodes))) {
          assert(Array.isArray(block.nodes) && Array.isArray(block.edges));
          for (const node of block.nodes) {
            // Text is ordinary Markdown, not just image alt or report data.
            if (node.text)
              assert(renderedText.includes(node.text.split('\n')[0].replace(/\s+/g, ' ')), node.id);
          }
        }
      }
      await page.locator('#tab-preview').click();
      if(report.warnings.length) {
        await page.locator('#warning-panel').evaluate(el=>{el.open=true;});
        await screenshot('warnings.png','#warning-panel',null,true);
      }
      // Replace only this case's generated output, removing assets from earlier conversion versions.
      await fs.rm(path.join(directory, 'output'), {recursive: true, force: true});
      // Extract only fixed safe ZIP member paths; preserve every original response byte separately.
      execFileSync('python3',['-c',
        'import pathlib,re,sys,zipfile\np=pathlib.Path(sys.argv[1]); out=p/"output"\nwith zipfile.ZipFile(p/"result.zip") as z:\n for n in z.namelist():\n  assert n in ("document.md","report.json") or re.fullmatch(r"images/[a-z]+-[0-9]+\\.[a-z0-9]{1,8}",n),n\n  f=out/n; f.parent.mkdir(parents=True,exist_ok=True); f.write_bytes(z.read(n))',directory]);
      assert.equal(await fs.readFile(path.join(directory,'output/document.md'),'utf8'),markdown);
      const extractedReport=JSON.parse(await fs.readFile(path.join(directory,'output/report.json'),'utf8'));
      assert.deepEqual(extractedReport,report);
      assert.deepEqual(errors,[]);assert.deepEqual(externalRequests,[]);
      const records=[];
      async function record(relative) {
        const data=await fs.readFile(path.join(directory,relative));
        records.push({path:relative,sizeBytes:data.length,sha256:hash(data)});
      }
      for(const relative of ['result.zip','output/document.md','output/report.json',...report.assets.map(a=>`output/${a.path}`),...screenshots])
        await record(relative);
      const run={
        capturedAt:new Date().toISOString(), mode:'actual local Functions HTTP through unmodified Playground',
        sourceCommit:execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim(),
        sourceWorkingTreeDirty:Boolean(execFileSync('git',['status','--porcelain'],{cwd:root,encoding:'utf8'}).trim()),
        runtime:{platform:os.platform(),architecture:os.arch(),node:process.version,
          java:spawnSync('java',['-version'],{encoding:'utf8'}).stderr.trim(),
          coreTools:execFileSync('func',['--version'],{encoding:'utf8'}).trim(),browser:browser.version()},
        input:{path:sample.file,sizeBytes:input.length,sha256:hash(input)},
        request:{method:'POST',path:new URL(response.url()).pathname+new URL(response.url()).search,
          contentType:response.request().headers()['content-type'],options:'default; no custom conversion options'},
        response:{status:response.status(),contentType:response.headers()['content-type'],elapsedMs},
        timingNote:'Local macOS measurement from submit click to completed Playground result (HTTP response, unzip and preview construction included). Not an Azure latency or throughput benchmark.',
        sectionCount:report.sectionCount,sectionTitles:sections,assetCount:report.assets.length,
        warningCount:report.warnings.length,warningCodes:[...new Set(report.warnings.map(w=>w.code))],
        informationCount:report.information.length,files:records,
        screenshotLayout:'Overview/source use normal Playground. Section screenshots expand the preview scroll region and image maximum height, and hide other sections. Excel tables-detail and group-detail crop the top 790 and 1000 CSS pixels; full sections are also included. Warnings expand the warning list. No original text, images or outputs are changed.',
        browserErrors:errors,externalBrowserRequests:externalRequests
      };
      await fs.writeFile(path.join(directory,'run.json'),JSON.stringify(run,null,2)+'\n');
      await context.close();
      console.log(`${sample.name}: HTTP 200, ${report.sectionCount} sections, ${report.assets.length} assets, ${report.warnings.length} warnings, ${elapsedMs} ms`);
    }
  } finally {await browser.close();}
}
main().catch(error=>{console.error(error);process.exitCode=1;});
