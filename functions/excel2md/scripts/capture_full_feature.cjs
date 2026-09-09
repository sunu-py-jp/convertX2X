#!/usr/bin/env node
// Capture an actual Excel conversion in the running Playground; no mock API or generated results.
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const { chromium } = require('playwright');

function options() {
  const values = { base: 'http://localhost:7072', timeout: '180000' };
  const args = process.argv.slice(2);
  if (args.includes('--help')) {
    console.log('Usage: node scripts/capture_full_feature.cjs --input FILE.xlsx --out DIRECTORY [--base http://localhost:7072] [--timeout 180000]');
    process.exit(0);
  }
  for (let index = 0; index < args.length; index += 2) {
    const key = args[index].slice(2);
    assert(args[index].startsWith('--') && ['input', 'out', 'base', 'timeout'].includes(key)
      && args[index + 1] && !args[index + 1].startsWith('--'), 'Invalid argument; use --help.');
    values[key] = args[index + 1];
  }
  assert(values.input && values.out, '--input and --out are required.');
  const base = new URL(values.base);
  assert(['http:', 'https:'].includes(base.protocol) && !base.username && !base.password && !base.search && !base.hash,
    '--base must be an HTTP(S) URL without credentials or query parameters.');
  const timeout = Number(values.timeout);
  assert(Number.isSafeInteger(timeout) && timeout >= 1000 && timeout <= 600000, '--timeout must be 1000–600000 milliseconds.');
  return { input: path.resolve(values.input), out: path.resolve(values.out), base: base.href.replace(/\/$/, ''), timeout };
}

const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
async function main() {
  const args = options();
  assert(/\.xlsx$/i.test(args.input), 'The full-feature input must be an .xlsx file.');
  const input = await fs.readFile(args.input);
  assert(input.length > 0, 'Input workbook is empty.');
  await fs.mkdir(args.out, { recursive: true });
  const screenshotDirectory = path.join(args.out, 'screenshots');
  await fs.mkdir(screenshotDirectory, { recursive: true });
  const pageErrors = [], externalRequests = [], failedResponses = [];
  let browser;
  try {
    browser = await chromium.launch({ headless: true,
      ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}) });
    const context = await browser.newContext({ viewport: { width: 1440, height: 1024 }, acceptDownloads: true });
    const page = await context.newPage();
    page.setDefaultTimeout(20000);
    page.on('pageerror', error => pageErrors.push(error.message));
    page.on('request', request => {
      if (!request.url().startsWith('blob:') && new URL(request.url()).origin !== new URL(args.base).origin)
        externalRequests.push(request.url());
    });
    page.on('response', response => {
      if (response.status() >= 400) failedResponses.push({ status: response.status(), url: response.url() });
    });
    await page.goto(`${args.base}/api/playground`);
    await page.waitForFunction(() => document.querySelector('#config-status')?.dataset.state !== 'loading');
    assert.equal(await page.locator('#config-status').getAttribute('data-state'), 'ready', 'Playground capabilities did not load.');
    await page.locator('#file-input').setInputFiles(args.input);
    await page.locator('#mode-sync').check();
    const submitted = page.waitForResponse(response => response.request().method() === 'POST'
      && new URL(response.url()).pathname.endsWith('/convert'), { timeout: args.timeout });
    const started = Date.now();
    await page.locator('#submit-button').click();
    const response = await submitted;
    assert.equal(response.status(), 200, `Actual conversion returned HTTP ${response.status()}.`);
    assert.equal(response.request().headers()['content-type'], 'application/octet-stream');
    await page.waitForFunction(() => !document.querySelector('#result-content').hidden
      || !document.querySelector('#error-message').hidden, null, { timeout: args.timeout });
    assert.equal(await page.locator('#error-message').isVisible(), false,
      (await page.locator('#error-message').textContent()) || 'Playground rejected the result.');
    assert.equal(await page.locator('#preview-notice').isVisible(), false, 'The Playground preview was truncated.');
    assert.equal(await page.evaluate(() => window.__fixtureInjected), undefined, 'Input HTML executed in the Playground.');
    assert.equal(await page.locator('#preview script,#preview iframe,#preview [onerror]').count(), 0);

    // Load the real, locally generated images even when they start below the scroll viewport.
    const images = page.locator('#preview img');
    for (let index = 0; index < await images.count(); index++) {
      await images.nth(index).scrollIntoViewIfNeeded();
      await images.nth(index).evaluate(async image => { await image.decode(); });
    }
    await page.locator('#preview').evaluate(element => { element.scrollTop = 0; });
    await page.evaluate(() => window.scrollTo(0, 0));
    const elapsedMs = Date.now() - started;

    async function download(selector, filename) {
      const pending = page.waitForEvent('download');
      await page.locator(selector).click();
      const downloaded = await pending;
      assert.equal(await downloaded.failure(), null, `${filename} download failed.`);
      const destination = path.join(args.out, filename);
      await downloaded.saveAs(destination);
      return fs.readFile(destination);
    }
    const archive = await download('#download-archive', 'ui-result.zip');
    const markdownBytes = await download('#download-markdown', 'ui-document.md');
    const reportBytes = await download('#download-report', 'ui-report.json');
    assert.equal(archive.subarray(0, 4).toString('hex'), '504b0304', 'Downloaded archive is not a ZIP.');
    const markdown = markdownBytes.toString('utf8'), conversionReport = JSON.parse(reportBytes);
    assert(markdown.startsWith('# '), 'Downloaded Markdown has no sheet heading.');
    assert.equal(conversionReport.specVersion, 1);
    assert(Array.isArray(conversionReport.assets) && Array.isArray(conversionReport.warnings));
    assert(conversionReport.assets.every(asset => /^images\/(?:image|diagram)-\d+\.[a-z0-9]+$/.test(asset.path)),
      'Result contains an unexpected output image path.');
    assert.equal(await page.locator('#markdown-source').textContent(), markdown, 'Markdown source differs from the UI download.');

    const screenshots = [];
    async function capture(name, locator, layout = {}) {
      // CSSOM properties work with the app's real CSP; do not disable CSP or inject a style tag.
      await page.evaluate(settings => {
        window.__captureStyles = [];
        const set = (element, property, value) => {
          window.__captureStyles.push([element, property, element.style.getPropertyValue(property), element.style.getPropertyPriority(property)]);
          element.style.setProperty(property, value, 'important');
        };
        if (settings.expand) {
          const preview = document.querySelector('#preview');
          set(preview, 'max-height', 'none'); set(preview, 'overflow', 'visible');
          for (const image of preview.querySelectorAll('img')) set(image, 'max-height', 'none');
          if (getComputedStyle(preview).maxHeight !== 'none') throw new Error('Could not expand the preview for evidence capture.');
        }
        if (settings.sheet !== undefined) {
          for (const node of document.querySelector('#preview').children)
            if (node.dataset.captureSheet !== String(settings.sheet)) set(node, 'display', 'none');
        }
        if (settings.warnings) {
          const list = document.querySelector('#warning-list');
          set(list, 'max-height', 'none'); set(list, 'overflow', 'visible');
        }
      }, layout);
      try {
        await locator.screenshot({ path: path.join(screenshotDirectory, name) });
        screenshots.push(`screenshots/${name}`);
      } finally {
        await page.evaluate(() => {
          for (const [element, property, value, priority] of window.__captureStyles.reverse()) {
            if (value) element.style.setProperty(property, value, priority); else element.style.removeProperty(property);
          }
          delete window.__captureStyles;
        });
      }
    }
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: path.join(screenshotDirectory, 'playground-overview.png'), fullPage: true });
    screenshots.push('screenshots/playground-overview.png');
    await page.locator('#tab-source').click();
    await capture('markdown-source.png', page.locator('.result-panel'));
    await page.locator('#tab-preview').click();
    await page.locator('#warning-panel').evaluate(element => { element.open = true; });
    await capture('warnings.png', page.locator('#warning-panel'), { warnings: true });

    // Evidence-only layout overrides: expand scrolling regions and isolate original sheet nodes.
    // No production asset, text, image, or conversion result is changed.
    const sheets = await page.locator('#preview').evaluate(preview => {
      const output = []; let current = -1;
      for (const node of preview.children) {
        if (node.tagName === 'H1') {
          current++;
          output.push({ title: node.textContent, tables: 0, images: [] });
        }
        node.dataset.captureSheet = String(current);
        if (current < 0) continue;
        output[current].tables += node.querySelectorAll('table').length;
        for (const image of node.querySelectorAll('img')) output[current].images.push({
          alt: image.alt, width: image.naturalWidth, height: image.naturalHeight,
          decoded: image.complete && image.naturalWidth > 0,
        });
      }
      return output;
    });
    assert(sheets.length > 0 && sheets.every(sheet => sheet.images.every(image => image.decoded)), 'A preview image did not decode.');
    await capture('full-result-preview-expanded.png', page.locator('#preview'), { expand: true });
    for (let index = 0; index < sheets.length; index++) {
      const filename = `sheet-${String(index + 1).padStart(2, '0')}.png`;
      await capture(filename, page.locator('#preview'), { expand: true, sheet: index });
      sheets[index].screenshot = `screenshots/${filename}`;
    }
    await page.locator('#preview').evaluate(element => { element.scrollTop = 0; });
    await page.evaluate(() => window.scrollTo(0, 0));
    assert.deepEqual(pageErrors, [], 'Browser JavaScript errors occurred.');
    assert.deepEqual(externalRequests, [], 'The Playground attempted an external request.');
    assert.deepEqual(failedResponses, [], 'An HTTP request failed.');
    assert.equal(await page.evaluate(() => window.__fixtureInjected), undefined, 'Input HTML executed during capture.');
    const warningCodes = [...new Set(conversionReport.warnings.map(warning => warning.code))];
    const browserReport = {
      mode: 'actual Functions API through Playground', capturedAt: new Date().toISOString(), base: args.base,
      input: { filename: path.basename(args.input), sizeBytes: input.length, sha256: sha(input) }, elapsedMs,
      downloads: [
        { path: 'ui-result.zip', sizeBytes: archive.length, sha256: sha(archive) },
        { path: 'ui-document.md', sizeBytes: markdownBytes.length, sha256: sha(markdownBytes) },
        { path: 'ui-report.json', sizeBytes: reportBytes.length, sha256: sha(reportBytes) },
      ],
      sheetCount: conversionReport.sheetCount, previewSheets: sheets,
      imageCount: sheets.reduce((count, sheet) => count + sheet.images.length, 0),
      assetCount: conversionReport.assets.length, warningCount: conversionReport.warnings.length, warningCodes,
      unsupportedChartWarnings: conversionReport.warnings.filter(warning => /CHART|GRAPHIC_FRAME/.test(warning.code)),
      screenshots,
      screenshotLayout: 'Overview and Markdown use the normal UI. Full-result and per-sheet previews expand scroll regions and image height; per-sheet captures temporarily hide other sheet nodes. Warnings expand the warning list. Original text, images, and downloaded files are unchanged.',
      inputHtmlExecuted: false, pageErrors, externalRequests, failedResponses,
    };
    await fs.writeFile(path.join(args.out, 'browser-report.json'), `${JSON.stringify(browserReport, null, 2)}\n`);
    console.log(`PASS actual Playground conversion: ${sheets.length} sheet previews, ${browserReport.imageCount} image placements, ${warningCodes.length} warning kinds; artifacts: ${args.out}`);
  } finally {
    if (browser) await browser.close();
  }
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
