(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const base = new URL('./', location.href), decoder = new TextDecoder();
  const endpoint = path => new URL(path, base);
  let config, objectUrls = [];
  const release = () => { objectUrls.forEach(URL.revokeObjectURL); objectUrls = []; };
  const url = blob => { const value = URL.createObjectURL(blob); objectUrls.push(value); return value; };
  const keyHeaders = () => $('key').value ? { 'x-functions-key': $('key').value } : {};
  const size = value => value < 1024 * 1024 ? `${Math.ceil(value / 1024)} KiB` : `${(value / 1024 / 1024).toFixed(1)} MiB`;
  function showError(message) { $('error').textContent = message; $('error').hidden = false; $('status').textContent = '変換できませんでした'; }
  function reset() {
    release(); $('images').replaceChildren(); $('preview').removeAttribute('src'); $('error').textContent = '';
    for (const id of ['preview', 'pdf-preview', 'pdf-hint', 'images', 'zip-download', 'pdf-download', 'report-download', 'report-detail', 'warnings', 'error', 'job']) $(id).hidden = true;
    $('pdf-preview').open = false; $('pdf-download').removeAttribute('href');
    $('empty').hidden = false;
  }
  async function responseBody(response, maximum) {
    const bytes = new Uint8Array(await response.arrayBuffer());
    if (bytes.length > maximum) throw new Error('応答が設定上限を超えました。');
    if (!response.ok) {
      let message = `HTTP ${response.status}`;
      try {
        const body = JSON.parse(decoder.decode(bytes));
        message = `${body.error?.code || message}: ${body.error?.message || ''}`;
        if (body.error?.details) showReport(body.error.details);
      } catch { }
      throw new Error(message);
    }
    return bytes;
  }
  async function getJson(target, maximum = 65536) {
    const response = await fetch(target, { headers: keyHeaders(), cache: 'no-store' });
    return JSON.parse(decoder.decode(await responseBody(response, maximum)));
  }
  function checkedJobUrl(value, id, suffix = '') {
    const target = new URL(value, location.origin);
    if (!/^[0-9a-f-]{36}$/i.test(id) || target.origin !== location.origin || target.pathname !== `${base.pathname}jobs/${id}${suffix}`
        || target.search || target.hash) throw new Error('ジョブURLが不正です。');
    return target;
  }
  function showReport(report) {
    $('report-text').textContent = JSON.stringify(report, null, 2); $('report-detail').hidden = false;
    $('report-download').href = url(new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' }));
    $('report-download').download = 'report.json'; $('report-download').hidden = false;
    const warnings = [];
    function collect(value) {
      if (!value || typeof value !== 'object') return;
      if (Array.isArray(value)) { value.forEach(collect); return; }
      if (value.status === 'skipped' || value.included === false) {
        const label = [value.sheet || value.sheetName, value.range].filter(Boolean).join(' ');
        warnings.push(`${label || '範囲'}: ${value.message || value.reason || value.reasonCode || '出力を見送りました'}`);
      }
      for (const [key, item] of Object.entries(value)) {
        if (key === 'warnings' && Array.isArray(item)) item.forEach(warning => warnings.push(typeof warning === 'string' ? warning : warning.message || warning.code || JSON.stringify(warning)));
        else collect(item);
      }
    }
    collect(report);
    $('warnings').replaceChildren();
    if (warnings.length) {
      const heading = document.createElement('strong'); heading.textContent = '出力を見送った範囲・警告'; $('warnings').append(heading);
      const list = document.createElement('ul');
      [...new Set(warnings)].forEach(warning => { const item = document.createElement('li'); item.textContent = warning; list.append(item); });
      $('warnings').append(list); $('warnings').hidden = false;
    }
  }
  // The server produces ordinary ZIP files. Read their central directory, including data-descriptor entries.
  async function unzip(bytes) {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength), files = new Map();
    let end = -1;
    for (let offset = bytes.length - 22; offset >= Math.max(0, bytes.length - 65557); offset--) {
      if (view.getUint32(offset, true) === 0x06054b50 && offset + 22 + view.getUint16(offset + 20, true) === bytes.length) { end = offset; break; }
    }
    if (end < 0 || view.getUint16(end + 4, true) || view.getUint16(end + 6, true)) throw new Error('ZIPの形式を読み取れませんでした。');
    const count = view.getUint16(end + 10, true); let cursor = view.getUint32(end + 16, true), total = 0;
    if (count > 10000) throw new Error('ZIP内のファイル数が多すぎます。');
    for (let index = 0; index < count; index++) {
      if (cursor + 46 > end || view.getUint32(cursor, true) !== 0x02014b50) throw new Error('ZIPの一覧が不正です。');
      const flags = view.getUint16(cursor + 8, true), method = view.getUint16(cursor + 10, true);
      const packed = view.getUint32(cursor + 20, true), expanded = view.getUint32(cursor + 24, true);
      const nameLength = view.getUint16(cursor + 28, true), extraLength = view.getUint16(cursor + 30, true), commentLength = view.getUint16(cursor + 32, true);
      const local = view.getUint32(cursor + 42, true), name = decoder.decode(bytes.subarray(cursor + 46, cursor + 46 + nameLength));
      cursor += 46 + nameLength + extraLength + commentLength; total += expanded;
      if (cursor > end || total > config.maxOutputBytes || flags & 1 || files.has(name)
          || !/^(report\.json|document\.pdf|pages\/page-\d{3,}\.png)$/.test(name)) throw new Error('ZIPに不正なファイルが含まれています。');
      if (local + 30 > bytes.length || view.getUint32(local, true) !== 0x04034b50) throw new Error('ZIPのファイルが不正です。');
      const start = local + 30 + view.getUint16(local + 26, true) + view.getUint16(local + 28, true);
      if (start + packed > bytes.length) throw new Error('ZIPのファイルが途中で切れています。');
      let result = bytes.slice(start, start + packed);
      if (method === 8) {
        if (typeof DecompressionStream === 'undefined') throw new Error('このブラウザーではプレビューできません。ZIPを保存して確認してください。');
        const stream = new Blob([result]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
        result = new Uint8Array(await new Response(stream).arrayBuffer());
      } else if (method !== 0) throw new Error('このZIPの圧縮方式はプレビューできません。');
      if (result.length !== expanded) throw new Error('ZIPのサイズが一致しません。');
      files.set(name, result);
    }
    return files;
  }
  async function displayArchive(bytes, filename) {
    $('zip-download').href = url(new Blob([bytes], { type: 'application/zip' }));
    $('zip-download').download = filename.replace(/\.[^.]+$/, '') + '.zip'; $('zip-download').hidden = false;
    const files = await unzip(bytes);
    if (!files.has('report.json')) throw new Error('変換レポートがありません。');
    showReport(JSON.parse(decoder.decode(files.get('report.json'))));
    let pages = 0;
    for (const [name, data] of files) {
      if (name === 'document.pdf') {
        const target = url(new Blob([data], { type: 'application/pdf' }));
        $('preview').src = target; $('preview').hidden = false; $('pdf-preview').hidden = false; $('pdf-hint').hidden = false;
        $('pdf-download').href = target; $('pdf-download').download = filename.replace(/\.[^.]+$/, '') + '.pdf'; $('pdf-download').hidden = false;
      }
      else if (name.startsWith('pages/')) {
        const figure = document.createElement('figure'), image = document.createElement('img'), caption = document.createElement('a');
        image.src = url(new Blob([data], { type: 'image/png' })); image.alt = name; image.loading = 'lazy';
        caption.href = image.src; caption.download = name.split('/').pop(); caption.textContent = `${caption.download} を保存`;
        figure.append(caption, image); $('images').append(figure); $('images').hidden = false; pages++;
      }
    }
    $('empty').hidden = true; $('status').textContent = '変換が完了しました';
    $('detail').textContent = `${size(bytes.length)}${pages ? ` · PNG ${pages} ページ` : ' · PDF'} · 詳細は変換レポートで確認できます`;
  }
  async function run(file, data) {
    const mode = data.get('mode'), target = endpoint(mode); target.searchParams.set('filename', file.name);
    target.searchParams.set('format', data.get('format'));
    if ($('sheet').value) target.searchParams.set('sheet', $('sheet').value);
    if ($('range').value.trim()) target.searchParams.set('range', $('range').value.trim());
    const response = await fetch(target, { method: 'POST', headers: { ...keyHeaders(), 'Content-Type': 'application/octet-stream' }, body: file });
    if (mode === 'convert') { await displayArchive(await responseBody(response, config.maxOutputBytes), file.name); return; }
    const accepted = JSON.parse(decoder.decode(await responseBody(response, 65536)));
    const id = accepted.job?.id, statusUrl = checkedJobUrl(accepted.statusUrl, id);
    $('job').textContent = `Job: ${id}`; $('job').hidden = false;
    for (let attempt = 0; attempt < 1200; attempt++) {
      await new Promise(resolve => setTimeout(resolve, 1500));
      const current = await getJson(statusUrl); $('detail').textContent = `ジョブ状態: ${current.job.status}`;
      if (current.job.status === 'failed') {
        if (Object.keys(current.job.errorDetails || {}).length) showReport(current.job.errorDetails);
        throw new Error(`${current.job.errorCode}: ${current.job.errorMessage}`);
      }
      if (current.job.status !== 'succeeded') continue;
      const result = await fetch(checkedJobUrl(current.resultUrl, id, '/result'), { headers: keyHeaders(), cache: 'no-store' });
      await displayArchive(await responseBody(result, config.maxOutputBytes), file.name); return;
    }
    throw new Error('ジョブの待機を終了しました。表示中のJob IDから後で状態を確認できます。');
  }
  $('file').addEventListener('change', () => { $('file-name').textContent = $('file').files[0]?.name || 'Excelファイルを選択'; });
  $('form').addEventListener('submit', async event => {
    event.preventDefault(); reset(); $('submit').disabled = true; $('status').textContent = '変換しています…'; $('detail').textContent = 'ファイルを処理しています。';
    try {
      const file = $('file').files[0]; if (!file) throw new Error('ファイルを選択してください。');
      if (!/\.(xlsx|xls)$/i.test(file.name)) throw new Error('XLSXまたはXLSファイルを選択してください。');
      if (file.size > config.maxInputBytes) throw new Error(`入力上限は${size(config.maxInputBytes)}です。`);
      if ($('range').value.trim() && !$('sheet').value) throw new Error('セル範囲を指定する場合はシート名を入力してください。');
      await run(file, new FormData(event.currentTarget));
    } catch (failure) { showError(failure.message || '変換に失敗しました。'); }
    finally { $('submit').disabled = false; }
  });
  getJson(endpoint('playground/config')).then(value => {
    config = value; $('async').disabled = !value.asyncEnabled; $('submit').disabled = false;
    const policy = value.renderPolicy;
    $('limits').textContent = `入力 ${size(value.maxInputBytes)} · 最大 ${policy.maxWidthPixels} × ${policy.maxHeightPixels} px · 最小文字 ${policy.minTextPixels} px`;
  }).catch(() => showError('公開設定を取得できませんでした。'));
  addEventListener('beforeunload', release);
})();
