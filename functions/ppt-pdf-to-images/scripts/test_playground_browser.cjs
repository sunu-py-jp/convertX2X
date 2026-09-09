#!/usr/bin/env node
// Called by test_playground_e2e.py; Playwright is a development-only dependency.
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require('playwright');

async function main() {
  const [base, disabledBase, work] = process.argv.slice(2);
  assert(base && disabledBase && work, 'Expected enabled URL, disabled URL, and fixture/output directory');
  const browser = await chromium.launch({ headless: true,
    ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}) });
  const errors = [];
  const requests = [];
  const report = [];
  const context = await browser.newContext({ viewport: { width: 1440, height: 1024 } });
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => {
    if (message.type() === 'error' && /content security policy|violates.*directive|refused to (load|execute|apply)/i.test(message.text())) {
      errors.push(message.text());
    }
  });
  page.on('request', request => requests.push({
    url: request.url(), method: request.method(), headers: request.headers(),
  }));
  page.setDefaultTimeout(15000);

  async function ready(target = base) {
    const response = await page.goto(`${target}/api/playground`);
    assert.equal(response.status(), 200);
    await page.locator('#submit-button').waitFor({ state: 'visible' });
    await page.waitForFunction(() => document.querySelector('#config-status').dataset.state !== 'loading');
    const config = await (await page.request.get(`${target}/api/playground/config`)).json();
    return config;
  }

  async function run({ extension, format = 'png', scope = 'single', mode = 'sync', pageNumber = '1', output }) {
    await page.locator('#file-input').setInputFiles(path.join(work, `example.${extension}`));
    await page.locator(`#mode-${mode}`).check();
    await page.locator('#format').selectOption(format);
    await page.locator('#scope').selectOption(scope);
    if (scope === 'single') await page.locator('#page').fill(pageNumber);
    const started = Date.now();
    const submitted = page.waitForResponse(response =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === `/api/${mode === 'sync' ? 'convert' : 'jobs'}`);
    await page.locator('#submit-button').click();
    const response = await submitted;
    assert.equal(response.status(), mode === 'sync' ? 200 : 202);
    assert.equal(new URL(response.url()).searchParams.has('width'), false, 'Use the original slide/page size');
    if (mode === 'async') {
      // Chrome may evict response bodies from its inspector cache after downloads.
      // Verify acceptance via its HTTP contract; the UI must consume JSON and poll.
      assert(response.headers().location.startsWith('/api/jobs/'));
    }
    await page.locator('#download').waitFor({ state: 'visible', timeout: 90000 });
    assert.equal(await page.locator('#error-message').isVisible(), false);
    const downloadEvent = page.waitForEvent('download');
    await page.locator('#download').click();
    const download = await downloadEvent;
    assert.equal(await download.failure(), null);
    const target = path.join(work, output);
    await download.saveAs(target);
    const bytes = await fs.readFile(target);
    if (scope === 'all') {
      assert.equal(bytes.subarray(0, 4).toString('hex'), '504b0304');
      assert.equal(await page.locator('#preview').isVisible(), false);
    } else {
      assert.equal(await page.locator('#preview').isVisible(), true);
      // Each fixture is 400 x 200 points; automatic output uses 96 pixels/inch.
      await page.waitForFunction(() => document.querySelector('#preview').naturalWidth === 533);
      assert.equal(await page.locator('#preview').evaluate(image => image.naturalHeight), 267);
      if (format === 'png') {
        assert.equal(bytes.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
        assert.equal(bytes.readUInt32BE(16), 533);
        assert.equal(bytes.readUInt32BE(20), 267);
      } else assert.equal(bytes.subarray(0, 3).toString('hex'), 'ffd8ff');
    }
    report.push({ mode, extension, format, scope, bytes: bytes.length, elapsedSeconds: (Date.now() - started) / 1000 });
    console.log(`PASS browser ${mode}: ${extension} -> ${scope === 'all' ? 'ZIP' : format} (${bytes.length} bytes)`);
  }

  try {
    const canonical = await page.request.get(`${base}/api/playground/`, { maxRedirects: 0 });
    assert.equal(canonical.status(), 302);
    assert.equal(canonical.headers().location, '/api/playground');
    const unknownAsset = await page.request.get(`${base}/api/playground/assets/unknown`);
    assert.equal(unknownAsset.status(), 404);
    for (const [name, type] of [['app.js', 'text/javascript'], ['style.css', 'text/css']]) {
      const asset = await page.request.get(`${base}/api/playground/assets/${name}`);
      assert.equal(asset.status(), 200);
      assert(asset.headers()['content-type'].startsWith(type));
      assert.equal(asset.headers()['x-content-type-options'], 'nosniff');
      assert(asset.headers()['content-security-policy'].includes("default-src 'none'"));
    }
    const enabledConfig = await ready();
    assert.deepEqual(Object.keys(enabledConfig).sort(), ['asyncEnabled', 'maxInputBytes', 'maxPages']);
    assert.equal(enabledConfig.asyncEnabled, true);
    assert.equal(await page.locator('#mode-async').isDisabled(), false);
    assert.equal(await page.locator('#mode-sync').isChecked(), true);
    assert.equal(await page.locator('#scope').inputValue(), 'all');
    assert.equal(await page.locator('#page-field').isVisible(), false);
    assert.equal(await page.locator('#page').isDisabled(), true);
    assert.equal(await page.locator('#width').count(), 0);
    await page.screenshot({ path: path.join(work, 'playground-desktop.png'), fullPage: true });

    const dummyKey = 'playground-local-smoke-key';
    await page.locator('.auth-settings summary').click();
    await page.locator('#function-key').fill(dummyKey);
    await run({ extension: 'pdf', output: 'sync-pdf.png' });
    await page.screenshot({ path: path.join(work, 'playground-preview.png'), fullPage: true });
    await run({ extension: 'ppt', format: 'jpeg', pageNumber: '2', output: 'sync-ppt.jpg' });
    await run({ extension: 'pptx', scope: 'all', output: 'sync-pptx.zip' });
    await run({ extension: 'pdf', mode: 'async', scope: 'all', output: 'async-pdf.zip' });

    const apiRequests = requests.filter(request => /\/api\/(convert|jobs)(\?|\/|$)/.test(new URL(request.url).pathname + new URL(request.url).search));
    assert(apiRequests.some(request => request.method === 'GET' && /\/jobs\/[^/]+$/.test(new URL(request.url).pathname)), 'Expected job status polling');
    assert(apiRequests.some(request => /\/jobs\/[^/]+\/result$/.test(new URL(request.url).pathname)), 'Expected async result retrieval');
    assert(apiRequests.every(request => request.headers['x-functions-key'] === dummyKey), 'API calls must send the provided key in a header');
    assert(apiRequests.every(request => !request.url.includes(dummyKey)), 'Keys must stay out of URLs');
    assert.equal(await page.evaluate(key => Object.values(localStorage).concat(Object.values(sessionStorage)).some(value => value.includes(key)), dummyKey), false);

    await page.locator('#file-input').setInputFiles(path.join(work, 'example.pdf'));
    await page.locator('#mode-sync').check();
    await page.locator('#scope').selectOption('single');
    await page.locator('#page').fill('3');
    await page.locator('#submit-button').click();
    await page.locator('#error-message').waitFor({ state: 'visible' });
    assert((await page.locator('#error-message').innerText()).length > 0);
    assert.equal(await page.locator('#download').isVisible(), false);
    assert.equal(await page.locator('#submit-button').isDisabled(), false);
    console.log('PASS browser API error display and recovery');

    await page.reload();
    await page.locator('#function-key').waitFor({ state: 'attached' });
    assert.equal(await page.locator('#function-key').inputValue(), '');

    const disabledConfig = await ready(disabledBase);
    assert.deepEqual(Object.keys(disabledConfig).sort(), ['asyncEnabled', 'maxInputBytes', 'maxPages']);
    assert.equal(disabledConfig.asyncEnabled, false);
    await page.waitForFunction(() => document.querySelector('#mode-async').disabled);
    assert.equal(await page.locator('#mode-sync').isChecked(), true);
    await run({ extension: 'pdf', output: 'disabled-sync-pdf.png' });
    console.log('PASS browser async-disabled mode and synchronous conversion');

    await page.setViewportSize({ width: 390, height: 844 });
    await ready();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true, 'Mobile layout overflows horizontally');
    await page.screenshot({ path: path.join(work, 'playground-mobile.png'), fullPage: true });
    assert.deepEqual(errors, []);
    await fs.writeFile(path.join(work, 'browser-report.json'), JSON.stringify(report, null, 2) + '\n');
    console.log('PASS browser responsive layout, ephemeral key handling, and no JavaScript errors');
  } finally {
    await browser.close();
  }
}

main().catch(error => { console.error(error); process.exitCode = 1; });
