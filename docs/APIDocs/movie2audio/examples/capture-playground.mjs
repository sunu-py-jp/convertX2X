#!/usr/bin/env node
// Fresh screenshots of the actual Playground. Requires Playwright and Chrome.
import { writeFile, readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const here = fileURLToPath(new URL('.', import.meta.url));
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const context = await browser.newContext({ viewport: { width: 1360, height: 1080 }, deviceScaleFactor: 1, locale: 'ja-JP' });
const page = await context.newPage();
const failures = [];
page.on('pageerror', error => failures.push(error.message));
const records = [];
for (const [name, input, asyncMode, errorCode] of [
  ['basic-playground', 'basic-input.mp4', false, null],
  ['complex-playground', 'complex-input.mp4', true, null],
  ['error-playground', 'first-pcm-second-aac.mkv', false, 'UNSUPPORTED_AUDIO_CODEC'],
]) {
  await page.goto('http://localhost:7073/api/playground', { waitUntil: 'networkidle' });
  await page.locator('#convert-button').waitFor({ state: 'visible' });
  if (asyncMode) await page.locator('#async-mode').check();
  await page.locator('#video-file').setInputFiles(here + input);
  await page.locator('#convert-button').click();
  if (errorCode) {
    await page.waitForFunction(code => document.querySelector('#status').textContent.includes(code), errorCode);
    records.push({ case: name, input, mode: 'sync', errorCode, visibleMessage: await page.locator('#status').textContent(), screenshot: name + '.png' });
  } else {
    await page.locator('#audio-result').waitFor({ state: 'visible', timeout: 120000 });
    await page.waitForFunction(() => document.querySelector('#audio-player').readyState >= 1);
    const media = await page.locator('#audio-player').evaluate(audio => ({ durationSeconds: audio.duration, readyState: audio.readyState, error: audio.error?.code ?? null }));
    if (!Number.isFinite(media.durationSeconds) || media.error) throw new Error('Returned M4A must be playable.');
    await page.locator('#audio-player').evaluate(async audio => { await audio.play(); });
    await page.waitForFunction(() => document.querySelector('#audio-player').currentTime > 0);
    await page.locator('#audio-player').evaluate(audio => { audio.pause(); audio.currentTime = 0; });
    const pendingDownload = page.waitForEvent('download');
    await page.locator('#download-link').click();
    const download = await pendingDownload;
    const audio = await readFile(await download.path());
    const digest = createHash('sha256').update(audio).digest('hex');
    const reference = await readFile(here + (asyncMode ? 'complex-audio.m4a' : 'basic-audio.m4a'));
    if (digest !== createHash('sha256').update(reference).digest('hex')) throw new Error('UI output differs from the verified HTTP output.');
    records.push({ case: name, input, mode: asyncMode ? 'async' : 'sync', media, audioBytes: audio.length, audioSha256: digest, actualPlaybackStarted: true, sameAsVerifiedHttpOutput: true, visibleMessage: await page.locator('#status').textContent(), jobInfo: await page.locator('#job-info').textContent(), screenshot: name + '.png' });
  }
  await page.screenshot({ path: here + name + '.png', fullPage: true });
}
if (failures.length) throw new Error(failures.join('\n'));
await writeFile(here + 'browser-run.json', JSON.stringify({ capturedAt: new Date().toISOString(), browser: browser.version(), viewport: { width: 1360, height: 1080 }, realFunctionsHost: 'http://localhost:7073', pageErrors: failures, cases: records }, null, 2) + '\n');
await browser.close();
console.log(JSON.stringify(records, null, 2));
