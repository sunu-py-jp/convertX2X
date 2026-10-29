import chromium, { inflate } from '@sparticuz/chromium';
import puppeteer from 'puppeteer-core';
import { launch as launchProcess, CDP_WEBSOCKET_ENDPOINT_REGEX } from '@puppeteer/browsers';
import { mkdir, writeFile, readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { FONT_FAMILY } from './fonts.js';

const ROOT = fileURLToPath(new URL('../', import.meta.url));
export { FONT_FAMILY };
let fontStyles;
const managedProcesses = new Set();
const browserProcesses = new WeakMap();
let processObserver = () => {};
let shutdownRequested = false;

// Each conversion runs in its own worker. Observe creation before waiting for CDP
// so its parent can also stop a browser that never finishes starting.
export function observeBrowserProcesses(observer) { processObserver = observer; }
export function stopManagedBrowsers() {
  shutdownRequested = true;
  for (const handle of managedProcesses) {
    if (handle.nodeProcess.exitCode === null && handle.nodeProcess.signalCode === null) {
      try { handle.kill(); } catch { /* The parent retains the active PID as a fallback. */ }
    }
  }
}
async function closeProcess(handle) {
  if (!handle?.nodeProcess.pid) return;
  let timer;
  try {
    await Promise.race([handle.close().catch(() => {}),new Promise(resolve => { timer = setTimeout(resolve,1500); })]);
  } finally { clearTimeout(timer); }
}
export async function trustedFontCss() {
  fontStyles ??= Promise.all(['Regular','Bold'].map(async (weight,i) => {
    const font = await readFile(path.join(ROOT, `assets/fonts/NotoSansJP-${weight}.ttf`));
    return `@font-face{font-family:'${FONT_FAMILY}';font-weight:${i ? 700 : 400};src:url(data:font/ttf;base64,${font.toString('base64')})}`;
  })).then(parts => parts.join(''));
  return fontStyles;
}
export function browserEnvironment(scratch) {
  return {PATH: process.platform==='win32'?process.env.PATH:'/usr/local/bin:/usr/bin:/bin', HOME: scratch, TMPDIR: scratch,
    ...(process.platform==='win32'?{SystemRoot:process.env.SystemRoot,TEMP:scratch,TMP:scratch}:{}),
    LD_LIBRARY_PATH: `${os.tmpdir()}/al2023/lib:${os.tmpdir()}`,
    FONTCONFIG_PATH: path.join(scratch, 'fontconfig')};
}
export async function launchBrowser(scratch, executablePath) {
  if (shutdownRequested) throw new Error('Browser startup cancelled');
  let args = ['--disable-background-networking','--disable-component-update','--disable-sync',
    '--no-first-run','--disable-breakpad','--metrics-recording-only','--disable-extensions',
    '--host-resolver-rules=MAP * ~NOTFOUND'];
  if (process.platform === 'linux' && !executablePath) {
    if (process.arch !== 'x64') throw new Error('Bundled Chromium requires Linux x64');
    chromium.setGraphicsMode = false;
    executablePath = await chromium.executablePath();
    const bin = path.join(path.dirname(fileURLToPath(import.meta.resolve('@sparticuz/chromium'))), '../bin');
    await inflate(path.join(bin, 'al2023.tar.br'));
    const removed = new Set(['--no-sandbox','--disable-setuid-sandbox','--disable-web-security','--single-process',
      '--no-zygote','--allow-running-insecure-content','--disable-site-isolation-trials','--enable-unsafe-swiftshader',"--headless='shell'"]);
    args = [...chromium.args.filter(a => !removed.has(a) && !a.startsWith('--disable-features=')), ...args];
  } else if (!executablePath) {
    executablePath = ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
      'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
      'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'].find(existsSync);
    if (!executablePath) throw new Error('Set HTML2PPTX_CHROMIUM_PATH to a local Chrome/Edge executable');
  }
  await mkdir(path.join(scratch, 'fontconfig'), {recursive:true});
  const xmlEscape = s => s.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('"','&quot;');
  await writeFile(path.join(scratch,'fontconfig/fonts.conf'), `<?xml version="1.0"?><fontconfig><dir>${xmlEscape(path.join(ROOT,'assets/fonts'))}</dir><cachedir>${xmlEscape(path.join(scratch,'font-cache'))}</cachedir></fontconfig>`);
  if (shutdownRequested) throw new Error('Browser startup cancelled');
  const launchArgs = await puppeteer.defaultArgs({headless:true,args:[...new Set(args)],userDataDir:path.join(scratch,'profile')});
  if (shutdownRequested) throw new Error('Browser startup cancelled');
  launchArgs.push('--remote-debugging-port=0');
  const handle = launchProcess({executablePath,args:launchArgs,env:browserEnvironment(scratch),
    handleSIGINT:false,handleSIGTERM:false,handleSIGHUP:false,dumpio:false,logger:()=>()=>{}});
  const proc = handle.nodeProcess;
  managedProcesses.add(handle);
  if (proc.pid) {
    processObserver({browserPid:proc.pid});
    proc.once('exit',()=>{
      managedProcesses.delete(handle);
      processObserver({browserClosedPid:proc.pid});
    });
  }
  try {
    const endpoint=await handle.waitForLineOutput(CDP_WEBSOCKET_ENDPOINT_REGEX,20000);
    if (shutdownRequested) throw new Error('Browser startup cancelled');
    const browser=await puppeteer.connect({browserWSEndpoint:endpoint,protocolTimeout:30000,defaultViewport:null});
    // connect() does not own the process; retain the handle for deterministic close.
    browser.process=()=>proc;
    browserProcesses.set(browser,handle);
    return browser;
  } catch(error) {
    await closeProcess(handle);
    if (!proc.pid) managedProcesses.delete(handle);
    throw error;
  }
}
export async function securedPage(browser, width, height) {
  const page = await browser.newPage();
  await page.setViewport({width,height,deviceScaleFactor:1});
  await page.setJavaScriptEnabled(false);
  await page.setBypassServiceWorker(true);
  await page.setOfflineMode(true);
  await page.setRequestInterception(true);
  page.on('request', r => { void (/^(data:|blob:)/.test(r.url()) ? r.continue() : r.abort('blockedbyclient')).catch(()=>{}); });
  const cdp = await page.createCDPSession();
  await cdp.send('Network.setBlockedURLs',{urls:['http://*','https://*','ws://*','wss://*','ftp://*','file://*']});
  await cdp.send('Browser.setDownloadBehavior',{behavior:'deny'});
  await cdp.detach();
  return page;
}
export async function closeBrowser(browser) {
  if (!browser) return;
  const proc = browser.process();
  let timer;
  try { await Promise.race([browser.close().catch(()=>{}),new Promise(resolve=>{timer=setTimeout(resolve,1500);})]); }
  finally {
    clearTimeout(timer);
    const managed=browserProcesses.get(browser);
    if (managed) {
      await closeProcess(managed);
      browserProcesses.delete(browser);
    } else if (proc && proc.exitCode === null && proc.signalCode === null) {
      try { process.kill(-proc.pid,'SIGKILL'); } catch { try {proc.kill('SIGKILL');} catch {} }
    }
  }
}
