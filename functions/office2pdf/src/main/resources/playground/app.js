(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  let config, objectUrls = [];
  const base = new URL('./', location.href);
  const endpoint = path => new URL(path, base);
  const release = () => { objectUrls.forEach(URL.revokeObjectURL); objectUrls = []; };
  const url = blob => { const value = URL.createObjectURL(blob); objectUrls.push(value); return value; };
  const keyHeaders = () => $('key').value ? { 'x-functions-key': $('key').value } : {};
  const size = value => value < 1024 * 1024 ? `${Math.ceil(value / 1024)} KiB` : `${(value / 1024 / 1024).toFixed(1)} MiB`;
  function showError(message) { $('error').textContent = message; $('error').hidden = false; $('status').textContent = '変換できませんでした'; }
  async function responseBody(response, maximum) {
    const bytes = new Uint8Array(await response.arrayBuffer());
    if (bytes.length > maximum) throw new Error('応答が設定上限を超えました。');
    if (!response.ok) {
      let message = `HTTP ${response.status}`;
      try { const body = JSON.parse(new TextDecoder().decode(bytes)); message = `${body.error?.code || message}: ${body.error?.message || ''}`; } catch { }
      throw new Error(message);
    }
    return bytes;
  }
  async function getJson(target, maximum = 65536) {
    const response = await fetch(target, { headers: keyHeaders(), cache: 'no-store' });
    return JSON.parse(new TextDecoder().decode(await responseBody(response, maximum)));
  }
  function checkedJobUrl(value, id) {
    const target = new URL(value, location.origin);
    if (target.origin !== location.origin || !target.pathname.endsWith(`/jobs/${id}`)) throw new Error('ジョブURLが不正です。');
    return target;
  }
  async function displayPdf(bytes, filename, report) {
    if (new TextDecoder('ascii').decode(bytes.slice(0, 5)) !== '%PDF-') throw new Error('PDFではない応答を受信しました。');
    release();
    const blob = new Blob([bytes], { type: 'application/pdf' }), target = url(blob);
    $('preview').src = target; $('preview').hidden = false; $('empty').hidden = true;
    $('pdf-download').href = target; $('pdf-download').download = filename.replace(/\.[^.]+$/, '') + '.pdf'; $('pdf-download').hidden = false;
    if (report) {
      const reportBlob = new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' });
      $('report-download').href = url(reportBlob); $('report-download').download = 'report.json'; $('report-download').hidden = false;
      $('detail').textContent = `${report.output?.pageCount ?? '—'} ページ · 警告 ${report.warnings?.length ?? 0} 件`;
    }
    $('status').textContent = '変換が完了しました';
  }
  async function run(file, mode) {
    const target = endpoint(mode); target.searchParams.set('filename', file.name);
    const response = await fetch(target, { method: 'POST', headers: { ...keyHeaders(), 'Content-Type': 'application/octet-stream' }, body: file });
    if (mode === 'convert') {
      const bytes = await responseBody(response, config.maxOutputBytes);
      await displayPdf(bytes, file.name); $('detail').textContent = `${size(bytes.length)} · 同期HTTP`; return;
    }
    const accepted = JSON.parse(new TextDecoder().decode(await responseBody(response, 65536)));
    const id = accepted.job?.id, statusUrl = checkedJobUrl(accepted.statusUrl, id);
    $('job').textContent = `Job: ${id}`; $('job').hidden = false;
    for (;;) {
      await new Promise(resolve => setTimeout(resolve, 1500));
      const current = await getJson(statusUrl);
      $('detail').textContent = `ジョブ状態: ${current.job.status}`;
      if (current.job.status === 'failed') throw new Error(`${current.job.errorCode}: ${current.job.errorMessage}`);
      if (current.job.status !== 'succeeded') continue;
      const result = await fetch(new URL(current.resultUrl, location.origin), { headers: keyHeaders(), cache: 'no-store' });
      const bytes = await responseBody(result, config.maxOutputBytes);
      const report = await getJson(new URL(current.reportUrl, location.origin), config.maxOutputBytes);
      await displayPdf(bytes, file.name, report); return;
    }
  }
  $('file').addEventListener('change', () => { const file = $('file').files[0]; $('file-name').textContent = file?.name || 'Officeファイルを選択'; });
  $('form').addEventListener('submit', async event => {
    event.preventDefault(); $('error').hidden = true; $('submit').disabled = true; $('status').textContent = '変換しています…'; $('detail').textContent = 'ファイルを処理しています。';
    try {
      const file = $('file').files[0]; if (!file) throw new Error('ファイルを選択してください。');
      if (file.size > config.maxInputBytes) throw new Error(`入力上限は${size(config.maxInputBytes)}です。`);
      await run(file, new FormData(event.currentTarget).get('mode'));
    } catch (failure) { showError(failure.message || '変換に失敗しました。'); }
    finally { $('submit').disabled = false; }
  });
  fetch(endpoint('playground/config'), { cache: 'no-store' }).then(response => response.json()).then(value => {
    config = value; $('async').disabled = !value.asyncEnabled;
    $('limits').textContent = `入力 ${size(value.maxInputBytes)} · 出力 ${size(value.maxOutputBytes)} · ページ ${value.maxPages || '無制限'}`;
  }).catch(() => showError('公開設定を取得できませんでした。'));
  addEventListener('beforeunload', release);
})();
