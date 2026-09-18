#!/usr/bin/env node
// Default: isolated mock API tests. --base URL --fixtures DIR also checks a running real Functions host.
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const http = require('node:http');
const zlib = require('node:zlib');
const crypto = require('node:crypto');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '..');
const args = process.argv.slice(2);
const arg = name => { const at = args.indexOf(name); return at < 0 ? null : args[at + 1]; };
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const crcTable = Array.from({ length: 256 }, (_, n) => { for (let bit = 0; bit < 8; bit++) n = n & 1 ? 0xedb88320 ^ n >>> 1 : n >>> 1; return n >>> 0; });
const crc = bytes => { let n = 0xffffffff; for (const byte of bytes) n = crcTable[(n ^ byte) & 255] ^ n >>> 8; return (n ^ 0xffffffff) >>> 0; };
function zip(files, extra = '') {
  const locals = [], entries = []; let offset = 0;
  for (const [name, raw] of [...files, ...(extra ? [[extra, Buffer.from('unexpected')]] : [])]) {
    const filename = Buffer.from(name), data = zlib.deflateRawSync(raw), local = Buffer.alloc(30), central = Buffer.alloc(46);
    local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x800, 6); local.writeUInt16LE(8, 8); local.writeUInt32LE(crc(raw), 14); local.writeUInt32LE(data.length, 18); local.writeUInt32LE(raw.length, 22); local.writeUInt16LE(filename.length, 26);
    central.writeUInt32LE(0x02014b50); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6); central.writeUInt16LE(0x800, 8); central.writeUInt16LE(8, 10); central.writeUInt32LE(crc(raw), 16); central.writeUInt32LE(data.length, 20); central.writeUInt32LE(raw.length, 24); central.writeUInt16LE(filename.length, 28); central.writeUInt32LE(offset, 42);
    locals.push(local, filename, data); entries.push(central, filename); offset += local.length + filename.length + data.length;
  }
  const end = Buffer.alloc(22), directory = Buffer.concat(entries); end.writeUInt32LE(0x06054b50); end.writeUInt16LE(entries.length / 2, 8); end.writeUInt16LE(entries.length / 2, 10); end.writeUInt32LE(directory.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, directory, end]);
}
function png() {
  const chunk = (name, data) => { const type = Buffer.from(name), head = Buffer.alloc(4), tail = Buffer.alloc(4); head.writeUInt32BE(data.length); tail.writeUInt32BE(crc(Buffer.concat([type, data]))); return Buffer.concat([head, type, data, tail]); };
  const header = Buffer.alloc(13); header.writeUInt32BE(1); header.writeUInt32BE(1, 4); header[8] = 8; header[9] = 2;
  return Buffer.concat([Buffer.from('89504e470d0a1a0a', 'hex'), chunk('IHDR', header), chunk('IDAT', zlib.deflateSync(Buffer.from([0, 40, 120, 70]))), chunk('IEND', Buffer.alloc(0))]);
}
async function main() {
  const work = arg('--out') || await fs.mkdtemp(path.join(os.tmpdir(), 'office2md-browser-'));
  await fs.mkdir(work, { recursive: true });
  let base = arg('--base'), server, mode = 'normal', polled = 0;
  const external = [], pageErrors = [], requests = [], report = [];
  const limits = { asyncEnabled: true, supportedFormats: ['xlsx', 'xls', 'docx', 'pptx'], maxInputBytes: 20971520, maxSections: 0, maxReadItems: 0, maxTableCells: 0, maxMarkdownBytes: 20971520, maxImages: 0, maxImageBytes: 20971520, maxOutputBytes: 104857600, maxShapes: 0, maxGroupDepth: 16, maxImagePixels: 20000000 };
  const id = '3b6e6d68-41bc-4c78-b186-a8d6bb75d449', jobPath = `/api/jobs/${id}`;
  const image = png(), md = Buffer.from('# 営業資料\n\n## 詳細\n\n- 箇条書き\n  - 子項目\n\n日本語の**太字**と<strong> 記号！ </strong><br>次の行\n\n|  |  |\n| --- | --- |\n| **商品** | 金額 |\n| りんご\\|みかん | 100 |\n\n![長方形、時計回り30度、図内の文字、基準=スライド左上、外接矩形 X=20pt、Y=100pt、幅=120pt、高さ=40pt](images/image-0001.png)\n\n本文[^footnote-1]\n\n[^footnote-1]: 脚注の内容\n\n[公式サイト](https://example.com)\n\n<script>window.pwned=1</script>\n![外部](https://external.invalid/image.png)\n[危険](javascript:alert%281%29)\n&lt;img src=x onerror=alert(1)&gt;\n');
  const manifest = Buffer.from(JSON.stringify({ specVersion: 2, sectionCount: 1, assets: [{ path: 'images/image-0001.png', contentType: 'image/png', sizeBytes: image.length, sha256: sha(image) }], warnings: [{ code: 'BORDER_AMBIGUOUS', section: '営業資料', range: 'A8:B9', message: '不完全な罫線を本文として残しました。' }] }));
  const files = new Map([['document.md', md], ['report.json', manifest], ['images/image-0001.png', image]]);
  const archive = zip(files);
  const manyAssets = Array.from({ length: 1201 }, (_, index) => ({ path: `images/image-${String(index + 1).padStart(4, '0')}.png`, contentType: 'image/png', sizeBytes: image.length, sha256: sha(image) }));
  const manyManifest = Buffer.from(JSON.stringify({ ...JSON.parse(manifest), assets: manyAssets }));
  const manyArchive = zip(new Map([['document.md', md], ['report.json', manyManifest], ...manyAssets.map(asset => [asset.path, image])]));
  if (!base) {
    server = http.createServer(async (req, res) => {
      const url = new URL(req.url, 'http://localhost'); requests.push({ pathname: url.pathname, search: url.search, key: req.headers['x-functions-key'], type: req.headers['content-type'] });
      const send = (status, type, body, headers = {}) => { res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store', ...headers }); res.end(body); };
      const json = (status, data, headers) => send(status, 'application/json', JSON.stringify(data), headers);
      if (url.pathname === '/api/capabilities') return mode === 'config-fail' ? json(503, {}) : json(200, { ...limits, ...(mode === 'limited' ? { maxSections: 50, maxImages: 200, maxShapes: 1000 } : {}), asyncEnabled: mode !== 'disabled' });
      if (url.pathname === '/api/convert' && req.method === 'POST') {
        req.resume(); if (mode === 'slow') return setTimeout(() => send(200, 'application/zip', archive), 1000);
        if (mode === 'api-error') return json(422, { error: { code: 'INVALID_WORKBOOK', message: '変換できないExcelです。' } });
        return send(200, 'application/zip', mode === 'bad-path' ? zip(files, '../outside') : mode === 'duplicate' ? zip([...files, ['document.md', md]]) : mode === 'many-assets' ? manyArchive : archive);
      }
      if (url.pathname === '/api/jobs' && req.method === 'POST') { req.resume(); polled = 0; return json(202, { job: { id, status: 'queued' }, statusUrl: mode === 'unsafe-url' ? `https://external.invalid${jobPath}` : jobPath }, { 'Retry-After': '0.5' }); }
      if (url.pathname === jobPath) { polled++; return json(200, { job: { id, status: polled === 1 ? 'running' : 'succeeded' }, statusUrl: jobPath, resultUrl: `${jobPath}/result`, reportUrl: `${jobPath}/report`, archiveUrl: `${jobPath}/archive`, assetsBaseUrl: `${jobPath}/images/` }, { 'Retry-After': '0.5' }); }
      if (url.pathname === `${jobPath}/result`) return send(200, 'text/markdown', md);
      if (url.pathname === `${jobPath}/report`) return send(200, 'application/json', mode === 'many-assets' ? manyManifest : manifest);
      if (url.pathname.startsWith(`${jobPath}/images/image-`) && url.pathname.endsWith('.png')) return send(200, 'image/png', image);
      if (url.pathname === `${jobPath}/archive`) return send(200, 'application/zip', mode === 'many-assets' ? manyArchive : archive);
      const asset = url.pathname === '/api/playground' ? 'index.html' : url.pathname.startsWith('/api/playground/assets/') ? url.pathname.split('/').pop() : '';
      if (['index.html', 'style.css', 'app.js'].includes(asset)) return send(200, asset.endsWith('.js') ? 'text/javascript' : asset.endsWith('.css') ? 'text/css' : 'text/html', await fs.readFile(path.join(root, 'src/main/resources/playground', asset)), { 'Content-Security-Policy': "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' blob:; base-uri 'none'; form-action 'none'", 'X-Content-Type-Options': 'nosniff' });
      send(404, 'text/plain', 'Not found');
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); base = `http://127.0.0.1:${server.address().port}`;
  }
  const mock = Boolean(server);
  let browser;
  try {
    browser = await chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : {}) });
    const context = await browser.newContext({ viewport: { width: 1440, height: 1024 } });
    const page = await context.newPage(); page.setDefaultTimeout(15000);
    page.on('pageerror', error => pageErrors.push(error.message));
    page.on('request', request => { if (!request.url().startsWith(base) && !request.url().startsWith('blob:')) external.push(request.url()); });
    async function ready() { await page.goto(`${base}/api/playground`); await page.waitForFunction(() => document.querySelector('#config-status').dataset.state !== 'loading'); }
    async function run(extension = 'xlsx', async = false) {
      await page.locator('#file-input').setInputFiles(mock ? { name: `sample.${extension}`, mimeType: 'application/octet-stream', buffer: Buffer.from('fixture') } : path.join(arg('--fixtures') || '/tmp/office2md-fixtures', `sample.${extension}`));
      await page.locator(async ? '#mode-async' : '#mode-sync').check(); await page.locator('#submit-button').click();
      await page.locator('#result-content').waitFor({ state: 'visible', timeout: 120000 });
      assert.equal(await page.locator('#error-message').isVisible(), false);
      assert((await page.locator('#preview h1').count()) > 0);
      if (mock) assert.equal(await page.locator('#preview h2').count(), 1);
      else if (extension === 'docx') assert((await page.locator('#preview h2').count()) > 0);
      else assert.equal(await page.locator('#preview h2').count(), 0);
      if (mock) {
        assert.equal(await page.locator('#preview ul li').count(), 2);
        assert.equal(await page.locator('#preview sup a').getAttribute('href'), '#office-note-footnote-1');
        assert((await page.locator('#office-note-footnote-1').textContent()).includes('脚注の内容'));
        assert.equal(await page.locator('#preview img').first().getAttribute('alt'), '長方形、時計回り30度、図内の文字、基準=スライド左上、外接矩形 X=20pt、Y=100pt、幅=120pt、高さ=40pt');
      }
      assert((await page.locator('#preview table').count()) > 0);
      await page.waitForFunction(() => [...document.querySelectorAll('#preview img')].every(image => image.complete && image.naturalWidth > 0));
      assert((await page.locator('#markdown-source').textContent()).startsWith('# '));
      report.push(`${mock ? 'mock' : 'live'} ${extension} ${async ? 'async' : 'sync'} preview`);
    }
    await ready(); await page.screenshot({ path: path.join(work, 'playground-desktop.png'), fullPage: true });
    await run('xlsx');
    await page.screenshot({ path: path.join(work, 'playground-preview.png'), fullPage: true });
    await page.locator('#tab-source').click(); assert.equal(await page.locator('#source-panel').isVisible(), true); await page.locator('#tab-preview').click();
    const event = page.waitForEvent('download'); await page.locator('#download-archive').click(); const download = await event;
    await download.saveAs(path.join(work, 'sync-result.zip')); assert.equal((await fs.readFile(path.join(work, 'sync-result.zip'))).subarray(0, 4).toString('hex'), '504b0304');
    await run('xls');
    for (const extension of ['docx', 'pptx']) {
      await run(extension);
      await page.screenshot({ path: path.join(work, `playground-${extension}.png`), fullPage: true });
      if (!mock) {
        assert((await page.locator('#preview img').count()) > 0);
        assert((await page.locator('#markdown-source').textContent()).includes('時計回り'));
        assert((await page.locator('#markdown-source').textContent()).includes('外接矩形'));
        assert((await page.locator('#markdown-source').textContent()).includes('基準='));
        const evt = page.waitForEvent('download'); await page.locator('#download-archive').click();
        await (await evt).saveAs(path.join(work, `${extension}-result.zip`));
      }
    }
    if (!(await page.locator('#mode-async').isDisabled())) {
      await run('xlsx', true); await page.screenshot({ path: path.join(work, 'playground-async.png'), fullPage: true });
      const evt = page.waitForEvent('download'); await page.locator('#download-archive').click(); await (await evt).saveAs(path.join(work, 'async-result.zip'));
      await run('xls', true);
      await run('docx', true); await run('pptx', true);
    }
    if (mock) {
      assert.equal((await page.locator('#file-hint').textContent()).includes('0 シート'), false);
      mode = 'many-assets';
      for (const async of [false, true]) {
        await run('pptx', async);
        assert.equal(JSON.parse(await page.locator('#report-source').textContent()).assets.length, 1201);
        report.push(`1201 assets accepted with unlimited counts (${async ? 'async' : 'sync'})`);
      }
      mode = 'limited'; await ready();
      assert((await page.locator('#file-hint').textContent()).includes('50 シート／スライド'));
      await run('xlsx');
      mode = 'normal'; await ready();
      await page.locator('.auth-settings summary').click(); await page.locator('#function-key').fill('test-header-only-secret'); await run('xlsx', true);
      for (const request of requests.filter(request => request.key)) { assert.equal(request.key, 'test-header-only-secret'); assert.equal(request.search.includes('test-header-only-secret'), false); }
      assert(requests.some(request => request.pathname.endsWith('/images/image-0001.png') && request.key));
      assert(requests.filter(request => request.pathname === '/api/jobs').every(request => request.type === 'application/octet-stream'));
      assert.equal(await page.locator('#preview script,#preview iframe,#preview [onerror]').count(), 0);
      assert.equal(await page.evaluate(() => window.pwned), undefined);
      assert.equal(await page.locator('#preview a[href^="javascript:"]').count(), 0);
      assert.equal(await page.locator('#preview img').count(), 1);
      await page.waitForFunction(() => document.querySelector('#preview img').naturalWidth === 1);
      assert.equal(await page.locator('#preview > p strong,#preview table strong').count(), 3);
      assert.equal(await page.locator('#preview td').first().textContent(), '商品');
      assert((await page.locator('#preview').textContent()).includes('りんご|みかん'));
      assert((await page.locator('#warning-list').textContent()).includes('A8:B9'));
      assert.equal(await page.evaluate(() => localStorage.length + sessionStorage.length), 0);
      report.push('hostile markup and external image URLs blocked; host key stays in headers');
      for (const failure of ['bad-path', 'duplicate', 'api-error', 'unsafe-url']) {
        mode = failure; await page.locator('#file-input').setInputFiles({ name: 'sample.xlsx', mimeType: 'application/octet-stream', buffer: Buffer.from('fixture') }); await page.locator(failure === 'unsafe-url' ? '#mode-async' : '#mode-sync').check(); await page.locator('#submit-button').click(); await page.locator('#error-message').waitFor(); assert.equal(await page.locator('#result-content').isVisible(), false); report.push(`reject ${failure}`);
      }
      mode = 'slow'; await page.locator('#file-input').setInputFiles({ name: 'sample.xlsx', mimeType: 'application/octet-stream', buffer: Buffer.from('fixture') }); await page.locator('#mode-sync').check(); await page.locator('#submit-button').click(); await page.locator('#cancel-button').click(); await page.waitForFunction(() => document.querySelector('#status-title').textContent.includes('停止')); assert.equal(await page.locator('#result-content').isVisible(), false);
      mode = 'disabled'; await ready(); assert.equal(await page.locator('#mode-async').isDisabled(), true);
      await page.locator('#file-input').setInputFiles({ name: 'bad.pdf', mimeType: 'application/pdf', buffer: Buffer.from('no') }); await page.locator('#error-message').waitFor(); assert.equal(await page.locator('#submit-button').isDisabled(), true);
      mode = 'config-fail'; await ready(); assert.equal(await page.locator('#config-status').getAttribute('data-state'), 'error');
      mode = 'normal'; await page.locator('#retry-config').click(); await page.waitForFunction(() => document.querySelector('#config-status').dataset.state === 'ready'); assert.equal(await page.locator('#error-message').isVisible(), false);
      await ready(); await run();
    }
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: path.join(work, 'playground-mobile.png'), fullPage: true });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    assert.deepEqual(external, []); assert.deepEqual(pageErrors, []);
    report.push('no external fetch; mobile no horizontal overflow');
    await fs.writeFile(path.join(work, 'browser-report.json'), JSON.stringify({ mode: mock ? 'mock API' : 'real Functions API', checks: report }, null, 2));
    console.log(`PASS ${mock ? 'mock' : 'live'} browser checks; artifacts: ${work}`);
  } finally { if (browser) await browser.close(); if (server) { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); } }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
