'use strict';

(() => {
  const $ = id => document.getElementById(id);
  const prefix = location.pathname.replace(/\/playground\/?$/, '');
  const endpoint = path => new URL(`${prefix}/${path}`, location.origin);
  const assetPath = /^images\/(?:image|diagram)-[0-9]{4,}\.[a-z0-9]{1,8}$/;
  const MiB = 1024 * 1024;
  const previewCharacters = 200000;
  let config = null, selectedFile = null, active = null, result = null;
  const objectUrls = new Set();
  const crcTable = Uint32Array.from({ length: 256 }, (_, n) => {
    for (let bit = 0; bit < 8; bit++) n = n & 1 ? 0xedb88320 ^ n >>> 1 : n >>> 1;
    return n >>> 0;
  });
  const sizeLabel = n => n < 1024 ? `${n} B` : n < MiB ? `${(n / 1024).toFixed(1)} KiB` : `${(n / MiB).toFixed(1)} MiB`;
  const check = (condition, message = '変換結果の形式が正しくありません。') => { if (!condition) throw new Error(message); };
  const alive = run => { if (run?.controller.signal.aborted) throw new DOMException('Stopped', 'AbortError'); };
  const blobUrl = blob => { const url = URL.createObjectURL(blob); objectUrls.add(url); return url; };
  const budget = () => ({ total: Math.min(config.maxOutputBytes, 100 * MiB), markdown: Math.min(config.maxMarkdownBytes, 20 * MiB), image: Math.min(config.maxImageBytes, 20 * MiB), images: Math.min(config.maxImages + config.maxShapes, 1200) });

  function status(state, title, detail = '') {
    $('status-bar').dataset.state = state;
    $('status-title').textContent = title;
    $('status-detail').textContent = detail;
  }
  function errorMessage(error, key = '') {
    const message = error instanceof TypeError ? '通信できませんでした。Functionsの起動と接続を確認してください。' : String(error.message || error);
    $('error-message').textContent = key ? message.split(key).join('[キー]') : message;
    $('error-message').hidden = false;
  }
  function resetResult() {
    result = null;
    for (const url of objectUrls) URL.revokeObjectURL(url);
    objectUrls.clear();
    for (const id of ['preview', 'markdown-source', 'report-source', 'warning-list']) $(id).replaceChildren();
    for (const id of ['download-markdown', 'download-report']) $(id).removeAttribute('href');
    $('result-content').hidden = true;
    $('result-empty').hidden = false;
    $('error-message').hidden = true;
    $('job-info').hidden = true;
    $('preview-notice').hidden = true;
  }
  function controls() {
    $('controls').disabled = Boolean(active);
    $('mode-async').disabled = !config?.asyncEnabled;
    $('submit-button').disabled = Boolean(active) || !config || !selectedFile;
    $('download-archive').disabled = Boolean(active);
    $('retry-config').disabled = Boolean(active);
    $('cancel-area').hidden = !active;
    $('mode-hint').textContent = $('mode-async').checked ? 'Queueへ登録し、完了後にMarkdown・画像・変換情報を取得します。' : '変換の完了を待ち、結果をZIPで受け取ります。';
  }
  async function readStream(stream, limit, run) {
    const reader = stream.getReader(), chunks = [];
    let size = 0;
    const abort = () => { reader.cancel().catch(() => {}); };
    run?.controller.signal.addEventListener('abort', abort, { once: true });
    try {
      while (true) {
        alive(run);
        const item = await reader.read();
        alive(run);
        if (item.done) break;
        size += item.value.length;
        check(size <= limit, '結果がPlaygroundの表示・取得上限を超えています。APIから結果を取得してください。');
        chunks.push(item.value);
      }
    } finally {
      run?.controller.signal.removeEventListener('abort', abort);
      await reader.cancel().catch(() => {});
      reader.releaseLock();
    }
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
    return bytes;
  }
  async function responseBytes(response, limit, run) {
    const length = response.headers.get('Content-Length');
    check(!length || Number(length) <= limit, '結果がPlaygroundの取得上限を超えています。');
    return readStream(response.body, limit, run);
  }
  const utf8 = bytes => new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  async function apiFetch(url, run, options = {}) {
    const headers = new Headers(options.headers);
    if (run.key) headers.set('x-functions-key', run.key);
    const response = await fetch(url, { ...options, headers, signal: run.controller.signal, redirect: 'error', credentials: 'same-origin', cache: 'no-store' });
    if (!response.ok) {
      if ([401, 403].includes(response.status)) throw new Error('認証に失敗しました。認証キーに有効なFunction Appのホストキーを入力してください。');
      let data;
      try { data = JSON.parse(utf8(await responseBytes(response, 65536, run))); } catch { /* Host errors may not be JSON. */ }
      const message = data?.error?.message;
      throw new Error(typeof message === 'string' ? message.slice(0, 500) : `リクエストに失敗しました（HTTP ${response.status}）。`);
    }
    return response;
  }
  function checkedJobUrl(raw, id, part = '') {
    check(typeof raw === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(id), 'ジョブの応答が正しくありません。');
    const url = new URL(raw, location.href);
    check(url.origin === location.origin && !url.username && !url.password && !url.search && !url.hash && url.pathname === `${prefix}/jobs/${id}${part}`, '安全に利用できない結果URLが返されました。');
    return url;
  }
  function retryDelay(response) {
    const raw = response.headers.get('Retry-After');
    if (!raw) return 3000;
    const delay = Number.isFinite(Number(raw)) ? Number(raw) * 1000 : Date.parse(raw) - Date.now();
    return Number.isFinite(delay) ? Math.max(500, Math.min(delay, 2147483647)) : 3000;
  }
  function wait(ms, run) {
    return new Promise((resolve, reject) => {
      alive(run);
      const abort = () => { clearTimeout(timer); reject(new DOMException('Stopped', 'AbortError')); };
      const timer = setTimeout(() => { run.controller.signal.removeEventListener('abort', abort); resolve(); }, ms);
      run.controller.signal.addEventListener('abort', abort, { once: true });
    });
  }

  // Read only our fixed ZIP artifact paths. Central-directory sizes bound streaming inflation.
  async function unzip(bytes, run) {
    const limits = budget(), view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const u16 = at => view.getUint16(at, true), u32 = at => view.getUint32(at, true);
    let end = -1;
    for (let at = bytes.length - 22; at >= Math.max(0, bytes.length - 65557); at--) {
      if (u32(at) === 0x06054b50 && at + 22 + u16(at + 20) === bytes.length) { end = at; break; }
    }
    check(end >= 0, 'ZIPを読み取れませんでした。');
    const count = u16(end + 10), central = u32(end + 16);
    check(!u16(end + 4) && !u16(end + 6) && u16(end + 8) === count && count >= 2 && count <= limits.images + 2 && central + u32(end + 12) === end);
    const files = new Map(), ranges = [];
    let at = central, total = 0;
    for (let index = 0; index < count; index++) {
      alive(run);
      check(at + 46 <= end && u32(at) === 0x02014b50);
      const flags = u16(at + 8), method = u16(at + 10), crc = u32(at + 16), compressed = u32(at + 20), size = u32(at + 24);
      const nameLength = u16(at + 28), extraLength = u16(at + 30), commentLength = u16(at + 32), local = u32(at + 42);
      check(at + 46 + nameLength + extraLength + commentLength <= end && !u16(at + 34) && !(flags & 1) && [0, 8].includes(method));
      const name = utf8(bytes.subarray(at + 46, at + 46 + nameLength));
      check((['document.md', 'report.json'].includes(name) || assetPath.test(name)) && !files.has(name), 'ZIPに不正な成果物パスがあります。');
      total += size;
      const individual = name === 'document.md' ? limits.markdown : name === 'report.json' ? 20 * MiB : limits.image;
      check(size <= individual && total <= limits.total, 'ZIPの展開サイズがPlaygroundの上限を超えています。');
      check(local + 30 <= central && u32(local) === 0x04034b50 && u16(local + 8) === method && u16(local + 6) === flags);
      const localName = u16(local + 26), start = local + 30 + localName + u16(local + 28);
      check(start <= central && start + compressed <= central && utf8(bytes.subarray(local + 30, local + 30 + localName)) === name);
      ranges.push([local, start + compressed]);
      const packed = bytes.subarray(start, start + compressed);
      let data;
      if (method === 0) data = packed.slice();
      else {
        check(typeof DecompressionStream !== 'undefined', 'ZIPのプレビューに対応した最新のChrome・Edge・Safariを使用してください。');
        data = await readStream(new Blob([packed]).stream().pipeThrough(new DecompressionStream('deflate-raw')), size, run);
      }
      check(data.length === size);
      let actualCrc = 0xffffffff;
      for (const byte of data) actualCrc = crcTable[(actualCrc ^ byte) & 255] ^ actualCrc >>> 8;
      check(((actualCrc ^ 0xffffffff) >>> 0) === crc, 'ZIP内のファイルが破損しています。');
      files.set(name, data);
      at += 46 + nameLength + extraLength + commentLength;
    }
    ranges.sort((a, b) => a[0] - b[0]);
    check(at === end && ranges.every((range, index) => !index || range[0] >= ranges[index - 1][1]) && files.has('document.md') && files.has('report.json'));
    return files;
  }
  function parseReport(bytes) {
    const report = JSON.parse(utf8(bytes));
    check(report && report.specVersion === 1 && Array.isArray(report.assets) && Array.isArray(report.warnings));
    check(report.assets.length <= budget().images);
    const names = new Set();
    for (const asset of report.assets) {
      check(asset && assetPath.test(asset.path) && !names.has(asset.path) && Number.isSafeInteger(asset.sizeBytes) && asset.sizeBytes >= 0 && asset.sizeBytes <= budget().image && /^[a-f0-9]{64}$/.test(asset.sha256));
      names.add(asset.path);
    }
    return report;
  }
  async function checkAsset(asset, bytes) {
    check(bytes && bytes.length === asset.sizeBytes, '画像・添付ファイルのサイズが変換情報と一致しません。');
    const digest = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), n => n.toString(16).padStart(2, '0')).join('');
    check(digest === asset.sha256, '画像・添付ファイルが変換情報と一致しません。');
  }
  function imageType(bytes) {
    if (bytes.length >= 24 && bytes[0] === 137 && bytes[1] === 80 && bytes[2] === 78 && bytes[3] === 71 && bytes[4] === 13 && bytes[5] === 10 && bytes[6] === 26 && bytes[7] === 10) {
      const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
      return { type: 'image/png', width: view.getUint32(16), height: view.getUint32(20) };
    }
    if (bytes[0] === 255 && bytes[1] === 216) {
      for (let at = 2; at + 9 < bytes.length;) {
        if (bytes[at++] !== 255) break;
        while (bytes[at] === 255) at++;
        const marker = bytes[at++];
        if (marker === 0xda || marker === 0xd9) break;
        const length = bytes[at] * 256 + bytes[at + 1];
        if (length < 2 || at + length > bytes.length) break;
        if ([0xc0, 0xc1, 0xc2].includes(marker)) return { type: 'image/jpeg', height: bytes[at + 3] * 256 + bytes[at + 4], width: bytes[at + 5] * 256 + bytes[at + 6] };
        at += length;
      }
    }
    return null;
  }

  // This deliberately supports only generated headings, paragraphs, tables and inline markup.
  // All other HTML stays text; images resolve exclusively to validated local result assets.
  const decoded = value => value.replace(/&(?:amp|lt|gt);/g, token => ({ '&amp;': '&', '&lt;': '<', '&gt;': '>' })[token]);
  function inline(parent, source, assets, depth = 0) {
    if (depth > 8) { parent.append(document.createTextNode(decoded(source))); return; }
    let text = '';
    const flush = () => { if (text) { parent.append(document.createTextNode(decoded(text))); text = ''; } };
    for (let i = 0; i < source.length;) {
      if (source[i] === '\\' && i + 1 < source.length) { text += source[i + 1]; i += 2; continue; }
      if (source.startsWith('<br>', i)) { flush(); parent.append(document.createElement('br')); i += 4; continue; }
      const bold = source.startsWith('**', i) ? ['**', '**'] : source.startsWith('<strong>', i) ? ['<strong>', '</strong>'] : null;
      if (bold) {
        let end = source.indexOf(bold[1], i + bold[0].length);
        while (end >= 0 && source[end - 1] === '\\') end = source.indexOf(bold[1], end + 1);
        if (end >= 0) { flush(); const node = document.createElement('strong'); inline(node, source.slice(i + bold[0].length, end), assets, depth + 1); parent.append(node); i = end + bold[1].length; continue; }
      }
      const image = source.startsWith('![', i), link = source[i] === '[';
      if (image || link) {
        const begin = i + (image ? 2 : 1);
        let end = begin;
        for (; end < source.length; end++) { if (source[end] === '\\') end++; else if (source[end] === ']' && source[end + 1] === '(') break; }
        const close = source.indexOf(')', end + 2);
        if (end < source.length && close >= 0) {
          flush();
          const label = source.slice(begin, end), destination = source.slice(end + 2, close), asset = assets.get(destination);
          if (image) {
            if (asset?.image) { const node = document.createElement('img'); node.src = asset.url; node.alt = decoded(label.replace(/\\(.)/g, '$1')); node.loading = 'lazy'; parent.append(node); }
            else { const node = document.createElement('span'); node.className = 'missing-image'; node.textContent = `[画像を表示できません: ${decoded(label)}]`; parent.append(node); }
          } else {
            let href = asset?.url;
            if (!href && !/[\u0000-\u0020\u007f\\]/.test(destination)) {
              try { const url = new URL(destination); if (['https:', 'http:', 'mailto:'].includes(url.protocol) && !url.username && !url.password) href = url.href; } catch { /* Relative links must be listed assets. */ }
            }
            if (href) { const node = document.createElement('a'); node.href = href; if (asset) node.download = destination.split('/').pop(); else { node.target = '_blank'; node.rel = 'noopener noreferrer'; } inline(node, label, assets, depth + 1); parent.append(node); }
            else inline(parent, label, assets, depth + 1);
          }
          i = close + 1; continue;
        }
      }
      text += source[i++];
    }
    flush();
  }
  function tableCells(line) {
    const cells = []; let value = '';
    for (let i = 1; i < line.length - 1; i++) {
      if (line[i] === '\\' && i + 1 < line.length - 1) { value += line[i] + line[++i]; continue; }
      if (line[i] === '|') { cells.push(value.trim()); value = ''; } else value += line[i];
    }
    cells.push(value.trim()); return cells;
  }
  function renderMarkdown(markdown, assets) {
    const root = document.createDocumentFragment(), lines = markdown.slice(0, previewCharacters).split('\n');
    let nodes = 0;
    for (let i = 0; i < lines.length && nodes < 10000;) {
      if (!lines[i].trim()) { i++; continue; }
      if (lines[i].startsWith('# ')) { const h = document.createElement('h1'); inline(h, lines[i++].slice(2), assets); root.append(h); nodes++; continue; }
      if (lines[i].startsWith('|') && lines[i].endsWith('|') && /^\|(?:\s*:?-+:?\s*\|)+$/.test(lines[i + 1] || '')) {
        const wrapper = document.createElement('div'); wrapper.className = 'table-scroll'; const table = document.createElement('table'); wrapper.append(table);
        const appendRow = (line, cellType) => { const row = document.createElement('tr'); for (const value of tableCells(line)) { if (++nodes > 10000) break; const cell = document.createElement(cellType); inline(cell, value, assets); row.append(cell); } table.append(row); };
        appendRow(lines[i], 'th'); i += 2;
        while (i < lines.length && lines[i].startsWith('|') && lines[i].endsWith('|') && nodes < 10000) appendRow(lines[i++], 'td');
        root.append(wrapper); continue;
      }
      const p = document.createElement('p');
      do { if (p.childNodes.length) p.append(document.createElement('br')); inline(p, lines[i++].replace(/ {2}$/, ''), assets); } while (i < lines.length && lines[i].trim() && !lines[i].startsWith('# ') && !lines[i].startsWith('|'));
      root.append(p); nodes++;
    }
    if (markdown.length > previewCharacters || nodes >= 10000) notice('プレビューは先頭の一部を表示しています。全体はMarkdownまたはZIPを保存して確認してください。');
    $('preview').replaceChildren(root);
  }
  function notice(message) { $('preview-notice').textContent = message; $('preview-notice').hidden = false; }
  async function showResult(files, report, archive, job, run) {
    alive(run);
    const assets = new Map();
    check(files.size === report.assets.length + 2, '成果物一覧と変換情報が一致しません。');
    for (const asset of report.assets) {
      const bytes = files.get(asset.path);
      await checkAsset(asset, bytes); alive(run);
      const image = imageType(bytes);
      const safeImage = image && image.type === asset.contentType && image.width > 0 && image.height > 0 && image.width * image.height <= Math.min(config.maxImagePixels, 20000000);
      assets.set(asset.path, { url: blobUrl(new Blob([bytes], { type: safeImage ? image.type : 'application/octet-stream' })), image: Boolean(safeImage) });
    }
    const markdown = utf8(files.get('document.md'));
    $('markdown-source').textContent = markdown.slice(0, previewCharacters);
    $('report-source').textContent = utf8(files.get('report.json')).slice(0, previewCharacters);
    renderMarkdown(markdown, assets);
    if (markdown.length > previewCharacters || files.get('report.json').length > previewCharacters) notice('画面表示は先頭20万文字までです。全体は各ファイルを保存して確認してください。');
    $('warning-summary').textContent = `警告 ${report.warnings.length} 件`;
    $('warning-panel').open = report.warnings.length > 0;
    for (const warning of report.warnings.slice(0, 300)) {
      const li = document.createElement('li'); li.textContent = [warning.sheet, warning.range, warning.code, warning.message].filter(value => typeof value === 'string').join(' · '); $('warning-list').append(li);
    }
    if (report.warnings.length > 300) { const li = document.createElement('li'); li.textContent = '残りの警告はreport.jsonで確認してください。'; $('warning-list').append(li); }
    $('result-meta').textContent = `${report.sheetCount ?? '—'} シート · ${report.assets.length} 添付`;
    $('download-markdown').href = blobUrl(new Blob([files.get('document.md')], { type: 'text/markdown;charset=utf-8' }));
    $('download-report').href = blobUrl(new Blob([files.get('report.json')], { type: 'application/json' }));
    result = { archiveUrl: archive ? blobUrl(new Blob([archive], { type: 'application/zip' })) : null, job };
    $('result-empty').hidden = true; $('result-content').hidden = false;
    status('success', '変換が完了しました', report.warnings.length ? '警告を確認してから、結果を保存してください。' : 'Markdownと画像を確認できます。ZIPにはすべての成果物が含まれます。');
  }
  async function convert(run) {
    check(run.file.size <= config.maxInputBytes, `ファイルサイズの上限は${sizeLabel(config.maxInputBytes)}です。`);
    const mode = $('mode-async').checked ? 'jobs' : 'convert';
    const url = endpoint(mode); url.searchParams.set('filename', run.file.name);
    let response = await apiFetch(url, run, { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: run.file });
    if (mode === 'convert') {
      const bytes = await responseBytes(response, budget().total + MiB, run), files = await unzip(bytes, run);
      const report = parseReport(files.get('report.json'));
      await showResult(files, report, bytes, null, run); return;
    }
    let data = JSON.parse(utf8(await responseBytes(response, 65536, run))), id = data.job?.id;
    const statusUrl = checkedJobUrl(data.statusUrl, id);
    $('job-info').textContent = `Job: ${id}`; $('job-info').hidden = false;
    while (true) {
      alive(run); check(data.job?.id === id);
      const state = data.job.status;
      if (state === 'failed') throw new Error(data.job.errorMessage || '非同期変換に失敗しました。');
      if (state === 'succeeded') break;
      check(['queued', 'running'].includes(state), '不明なジョブ状態が返されました。');
      status('busy', state === 'queued' ? '順番を待っています' : 'Excelを変換しています', '完了後にMarkdown・画像・変換情報を取得します。');
      await wait(retryDelay(response), run);
      response = await apiFetch(statusUrl, run);
      data = JSON.parse(utf8(await responseBytes(response, 65536, run)));
    }
    const job = { id, archiveUrl: checkedJobUrl(data.archiveUrl, id, '/archive') };
    const base = checkedJobUrl(data.assetsBaseUrl, id, '/images/');
    const reportBytes = await responseBytes(await apiFetch(checkedJobUrl(data.reportUrl, id, '/report'), run), 20 * MiB, run);
    const report = parseReport(reportBytes), files = new Map([['report.json', reportBytes]]);
    let total = reportBytes.length;
    const md = await responseBytes(await apiFetch(checkedJobUrl(data.resultUrl, id, '/result'), run), budget().markdown, run);
    total += md.length; check(total <= budget().total);
    files.set('document.md', md);
    for (const asset of report.assets) {
      check(total + asset.sizeBytes <= budget().total, '成果物の合計がPlaygroundの取得上限を超えています。');
      const bytes = await responseBytes(await apiFetch(new URL(asset.path.slice('images/'.length), base), run), Math.min(budget().image, asset.sizeBytes), run);
      total += bytes.length; files.set(asset.path, bytes);
    }
    await showResult(files, report, null, job, run);
  }
  async function perform(action, clear = true) {
    if (active) return;
    if (clear) resetResult(); else $('error-message').hidden = true;
    const run = { controller: new AbortController(), file: selectedFile, key: $('function-key').value.trim() };
    active = run; controls();
    const start = performance.now(); $('elapsed').hidden = false;
    const timer = setInterval(() => { $('elapsed').textContent = `${((performance.now() - start) / 1000).toFixed(1)} 秒`; }, 100);
    status('busy', clear ? 'Excelを送信しています' : 'ZIPを取得しています', '通信中です。');
    try { await action(run); }
    catch (error) {
      if (clear) { for (const url of objectUrls) URL.revokeObjectURL(url); objectUrls.clear(); result = null; $('result-content').hidden = true; }
      if (error.name === 'AbortError') status('idle', '待機を停止しました', 'サーバーで受け付け済みのジョブは継続します。');
      else { status('error', '処理を完了できませんでした', 'エラー内容を確認してください。'); errorMessage(error, run.key); }
    } finally { clearInterval(timer); run.key = ''; if (active === run) active = null; controls(); }
  }
  async function loadConfig() {
    $('retry-config').disabled = true;
    $('config-status').dataset.state = 'loading';
    try {
      const response = await fetch(endpoint('capabilities'), { cache: 'no-store', redirect: 'error', credentials: 'same-origin', signal: AbortSignal.timeout(15000) });
      check(response.ok, `設定を取得できませんでした（HTTP ${response.status}）。`);
      const data = JSON.parse(utf8(await responseBytes(response, 65536)));
      check(typeof data.asyncEnabled === 'boolean' && ['maxInputBytes', 'maxSheets', 'maxMarkdownBytes', 'maxImages', 'maxImageBytes', 'maxOutputBytes', 'maxImagePixels', 'maxShapes'].every(key => Number.isSafeInteger(data[key]) && data[key] > 0));
      config = data; $('config-status').dataset.state = 'ready'; $('config-status').textContent = '接続済み';
      $('config-message').textContent = config.asyncEnabled ? '同期 / 非同期を利用できます' : '同期のみ · 非同期はStorage設定で有効';
      $('file-hint').textContent = `XLSX / XLS · 最大 ${sizeLabel(config.maxInputBytes)} / ${config.maxSheets} シート`;
      $('retry-config').hidden = true;
      $('error-message').hidden = true;
      if (selectedFile) selectFile(selectedFile);
      if (!config.asyncEnabled) $('mode-sync').checked = true;
    } catch (error) {
      config = null; $('config-status').dataset.state = 'error'; $('config-status').textContent = '接続できません'; $('config-message').textContent = 'Functionsの起動を確認してください'; $('retry-config').hidden = false; errorMessage(error);
    } finally { $('retry-config').disabled = false; controls(); }
  }
  function selectFile(file) {
    if (active) return;
    resetResult(); selectedFile = null; $('file-input').value = ''; $('elapsed').hidden = true;
    $('dropzone').classList.remove('has-file'); $('selected-name').textContent = 'Excelをドロップ'; $('selected-meta').textContent = 'またはクリックしてファイルを選択';
    try {
      check(/\.xlsx?$/i.test(file.name), 'XLSXまたはXLSファイルを選んでください。');
      check(file.size > 0, 'ファイルが空です。');
      check(!config || file.size <= config.maxInputBytes, `ファイルサイズの上限は${sizeLabel(config?.maxInputBytes || 20 * MiB)}です。`);
      selectedFile = file; $('selected-name').textContent = file.name; $('selected-meta').textContent = `${sizeLabel(file.size)} · クリックして変更`; $('dropzone').classList.add('has-file'); status('idle', '変換の準備ができました', '「Markdownに変換」を押してください。');
    } catch (error) { status('error', 'ファイルを確認してください'); errorMessage(error); }
    controls();
  }
  function selectTab(name) {
    for (const tab of ['preview', 'source', 'report']) { const selected = tab === name; $(`tab-${tab}`).setAttribute('aria-selected', String(selected)); $(`tab-${tab}`).tabIndex = selected ? 0 : -1; $(tab === 'preview' ? 'preview' : `${tab}-panel`).hidden = !selected; }
  }
  $('convert-form').addEventListener('submit', event => { event.preventDefault(); if (config && selectedFile) perform(convert); });
  $('file-input').addEventListener('change', event => { if (event.target.files[0]) selectFile(event.target.files[0]); });
  $('cancel-button').addEventListener('click', () => active?.controller.abort());
  $('retry-config').addEventListener('click', loadConfig);
  for (const id of ['mode-sync', 'mode-async']) $(id).addEventListener('change', controls);
  for (const name of ['preview', 'source', 'report']) {
    $(`tab-${name}`).addEventListener('click', () => selectTab(name));
    $(`tab-${name}`).addEventListener('keydown', event => { const tabs = ['preview', 'source', 'report']; if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) { event.preventDefault(); const index = tabs.indexOf(name); const next = event.key === 'Home' ? 0 : event.key === 'End' ? 2 : (index + (event.key === 'ArrowRight' ? 1 : 2)) % 3; selectTab(tabs[next]); $(`tab-${tabs[next]}`).focus(); } });
  }
  $('download-archive').addEventListener('click', () => {
    if (!result || active) return;
    const download = url => { const link = document.createElement('a'); link.href = url; link.download = 'document.zip'; document.body.append(link); link.click(); link.remove(); };
    if (result.archiveUrl) { download(result.archiveUrl); return; }
    const current = result;
    perform(async run => { const bytes = await responseBytes(await apiFetch(current.job.archiveUrl, run), budget().total + MiB, run); alive(run); current.archiveUrl = blobUrl(new Blob([bytes], { type: 'application/zip' })); download(current.archiveUrl); status('success', 'ZIPを保存しました', 'Markdown・変換情報・画像が含まれます。'); }, false);
  });
  for (const name of ['dragenter', 'dragover']) $('dropzone').addEventListener(name, event => { event.preventDefault(); if (!active) $('dropzone').classList.add('dragover'); });
  for (const name of ['dragleave', 'drop']) $('dropzone').addEventListener(name, event => { event.preventDefault(); $('dropzone').classList.remove('dragover'); });
  $('dropzone').addEventListener('drop', event => { if (event.dataTransfer.files.length === 1) selectFile(event.dataTransfer.files[0]); else if (!active) errorMessage(new Error('一度に1ファイルを選んでください。')); });
  document.addEventListener('dragover', event => event.preventDefault());
  document.addEventListener('drop', event => event.preventDefault());
  window.addEventListener('pagehide', () => { active?.controller.abort(); for (const url of objectUrls) URL.revokeObjectURL(url); });
  loadConfig();
})();
