'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const form = $('convert-form');
  const button = $('convert-button');
  const status = $('status');
  const api = new URL('.', location.href);
  const ASYNC_WAIT_MS = 15 * 60 * 1000;
  let settings;
  let objectUrl;
  let busy = false;
  let activeController;
  const size = bytes => {
    const divisor = bytes < 1024 ? 1 : bytes < 1048576 ? 1024 : 1048576;
    const unit = divisor === 1 ? 'B' : divisor === 1024 ? 'KiB' : 'MiB';
    return `${(bytes / divisor).toLocaleString('ja-JP', { maximumFractionDigits: 1 })} ${unit}`;
  };
  const selectedSource = () => form.elements.source.value;
  const selectedMode = () => form.elements.mode.value;
  const report = (message, isError = false) => {
    status.textContent = message;
    status.classList.toggle('error', isError);
  };
  function updateSource() {
    const isUrl = selectedSource() === 'url';
    const isAsync = selectedMode() === 'async';
    $('file-field').hidden = isUrl;
    $('url-field').hidden = !isUrl;
    $('async-mode').disabled = busy || !settings?.asyncEnabled;
    $('url-hint').textContent = settings?.urlEnabled
      ? '管理者が許可したホストの動画ファイルURLを指定します。Webページ・リダイレクトには対応しません。'
      : 'URL入力は無効です。管理者がCONVERSION_URL_ALLOWED_HOSTSを設定すると利用できます。';
    $('mode-hint').textContent = isAsync
      ? 'Storageへ動画を保存し、順番が来たら抽出します。完了後にこの画面で音声を取得します。'
      : settings?.asyncEnabled
        ? '処理を終えた音声を、そのまま受け取ります。'
        : '同期で音声を受け取ります。Storage接続の設定後にキュー実行も選べます。';
    button.disabled = busy || !settings || (isUrl && !settings.urlEnabled) || (isAsync && !settings.asyncEnabled);
  }
  function clearResult() {
    $('audio-player').pause();
    $('audio-player').removeAttribute('src');
    $('audio-player').load();
    if (objectUrl) URL.revokeObjectURL(objectUrl);
    objectUrl = undefined;
    $('download-link').removeAttribute('href');
    $('audio-result').hidden = true;
    $('empty-result').hidden = false;
    $('job-info').hidden = true;
    $('job-info').textContent = '';
  }
  async function checkedResponse(response) {
    if (response.ok) return response;
    let code = `HTTP ${response.status}`;
    let message = response.status === 401 || response.status === 403 ? 'Function keyまたはアクセス設定を確認してください。' : '処理を完了できませんでした。';
    if ((response.headers.get('Content-Type') || '').includes('application/json')) {
      const value = await response.json();
      if (value?.error?.code) code = String(value.error.code);
      if (value?.error?.message) message = String(value.error.message);
    }
    const error = new Error(`${code}: ${message}`);
    error.code = code;
    error.httpStatus = response.status;
    throw error;
  }
  function expectedJobUrl(value, id, result = false) {
    if (typeof value !== 'string') throw new Error('ジョブの取得先が応答にありません。');
    const target = new URL(value, location.href);
    const expected = new URL(`jobs/${id}${result ? '/result' : ''}`, api);
    if (target.href !== expected.href || target.origin !== location.origin || target.username || target.password) {
      throw new Error('想定外のジョブ取得先を受信しました。');
    }
    return expected;
  }
  function validateJob(value, id) {
    if (!value?.job || typeof value.job.id !== 'string' || !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value.job.id)) {
      throw new Error('ジョブの応答形式が正しくありません。');
    }
    if (id && value.job.id !== id) throw new Error('別のジョブの応答を受信しました。');
    if (!['queued', 'running', 'succeeded', 'failed'].includes(value.job.status)) throw new Error('ジョブの状態が正しくありません。');
    expectedJobUrl(value.statusUrl, value.job.id);
    return value;
  }
  const delay = (milliseconds, signal) => new Promise((resolve, reject) => {
    if (signal.aborted) return reject(new DOMException('Aborted', 'AbortError'));
    const aborted = () => { clearTimeout(timer); reject(new DOMException('Aborted', 'AbortError')); };
    const timer = setTimeout(() => { signal.removeEventListener('abort', aborted); resolve(); }, milliseconds);
    signal.addEventListener('abort', aborted, { once: true });
  });
  async function awaitJob(initial, key, signal) {
    let value = validateJob(initial);
    const id = value.job.id;
    const statusUrl = expectedJobUrl(value.statusUrl, id);
    $('job-info').textContent = `ジョブID: ${id}`;
    $('job-info').hidden = false;
    const headers = key ? { 'x-functions-key': key } : {};
    while (true) {
      if (value.job.status === 'failed') {
        throw new Error(`${value.job.errorCode || 'JOB_FAILED'}: ${value.job.errorMessage || '音声の抽出に失敗しました。'}`);
      }
      if (value.job.status === 'succeeded') {
        report('抽出が完了しました。音声を取得しています…');
        try {
          return await checkedResponse(await fetch(expectedJobUrl(value.resultUrl, id, true), {
            headers, cache: 'no-store', redirect: 'error', credentials: 'same-origin', signal
          }));
        } catch (error) {
          if (error.httpStatus !== 503 || error.code !== 'CONVERSION_BUSY') throw error;
          report('音声の取得準備を待っています…');
          await delay(3000, signal);
          continue;
        }
      }
      report(value.job.status === 'running' ? 'キューのジョブで音声を抽出しています…' : '受付が完了しました。キューで順番を待っています…');
      await delay(3000, signal);
      try {
        const response = await checkedResponse(await fetch(statusUrl, {
          headers, cache: 'no-store', redirect: 'error', credentials: 'same-origin', signal
        }));
        value = validateJob(await response.json(), id);
      } catch (error) {
        if (error.httpStatus !== 503 || error.code !== 'CONVERSION_BUSY') throw error;
      }
    }
  }
  form.addEventListener('change', event => {
    if (event.target.name === 'source' || event.target.name === 'mode') updateSource();
  });
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (busy || !settings) return;
    const isUrl = selectedSource() === 'url';
    const isAsync = selectedMode() === 'async';
    const file = $('video-file').files[0];
    const inputUrl = $('video-url').value.trim();
    if (!isUrl && !file) return report('動画ファイルを選択してください。', true);
    if (!isUrl && file.size === 0) return report('ファイルが空です。', true);
    if (!isUrl && file.size > settings.maxInputBytes) return report(`入力上限は${size(settings.maxInputBytes)}です。`, true);
    if (isUrl && !settings.urlEnabled) return report('URL入力は無効です。', true);
    if (isUrl && !inputUrl) return report('動画ファイルのURLを入力してください。', true);
    if (isAsync && !settings.asyncEnabled) return report('キュー実行は無効です。', true);
    busy = true;
    updateSource();
    clearResult();
    report(isAsync ? '動画をStorageへ保存して、ジョブを登録しています…' : isUrl ? '動画を取得して、音声を抽出しています…' : '動画を送信して、音声を抽出しています…');
    const started = performance.now();
    const controller = new AbortController();
    activeController = controller;
    const timeout = setTimeout(() => controller.abort(), isAsync ? ASYNC_WAIT_MS : (settings.timeoutSeconds + 20) * 1000);
    try {
      const headers = { 'Content-Type': isUrl ? 'application/json' : 'application/octet-stream' };
      const key = $('function-key').value.trim();
      if (key) headers['x-functions-key'] = key;
      const endpoint = isAsync ? (isUrl ? 'jobs-url' : 'jobs') : (isUrl ? 'convert-url' : 'convert');
      const requestUrl = new URL(endpoint, api);
      if (isAsync && !isUrl) requestUrl.searchParams.set('filename', file.name);
      let response = await checkedResponse(await fetch(requestUrl, {
        method: 'POST', headers, body: isUrl ? JSON.stringify({ url: inputUrl }) : file,
        cache: 'no-store', redirect: 'error', credentials: 'same-origin', signal: controller.signal
      }));
      if (isAsync) {
        if (response.status !== 202 || !(response.headers.get('Content-Type') || '').includes('application/json')) throw new Error('ジョブ受付の応答形式が正しくありません。');
        response = await awaitJob(await response.json(), key, controller.signal);
      }
      if (!(response.headers.get('Content-Type') || '').startsWith('audio/mp4')) throw new Error('想定外の形式のレスポンスを受信しました。');
      const blob = await response.blob();
      if (blob.size === 0 || blob.size > settings.maxOutputBytes) throw new Error('音声のサイズが出力上限の範囲外です。');
      objectUrl = URL.createObjectURL(blob);
      $('audio-player').src = objectUrl;
      $('download-link').href = objectUrl;
      $('result-meta').textContent = `${size(blob.size)} · AAC / stream copy`;
      $('empty-result').hidden = true;
      $('audio-result').hidden = false;
      report(`${((performance.now() - started) / 1000).toFixed(1)}秒で完了しました（${isAsync ? 'キュー待機・' : ''}送受信を含む）。`);
    } catch (error) {
      report(error.name === 'AbortError'
        ? isAsync ? '画面の待機時間（15分）を超えました。受付済みジョブは継続します。表示されたジョブIDで状態APIを確認できます。'
          : '応答の待機時間を超えました。処理段階によってはサーバー側の期限まで処理が続く場合があります。'
        : error.message, true);
    } finally {
      clearTimeout(timeout);
      activeController = undefined;
      busy = false;
      updateSource();
    }
  });
  window.addEventListener('pagehide', () => { activeController?.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl); });
  fetch(new URL('playground/config', api), { cache: 'no-store', redirect: 'error' })
    .then(response => { if (!response.ok) throw new Error('設定を取得できませんでした。'); return response.json(); })
    .then(value => {
      if (!Number.isFinite(value.maxInputBytes) || !Number.isFinite(value.maxOutputBytes) || !Number.isFinite(value.timeoutSeconds) || typeof value.asyncEnabled !== 'boolean') throw new Error('設定の形式が正しくありません。');
      settings = value;
      $('limits').textContent = `入力${size(settings.maxInputBytes)}まで · 出力${size(settings.maxOutputBytes)}まで · 1処理${settings.timeoutSeconds}秒まで`;
      updateSource();
    }).catch(error => { $('limits').textContent = '設定を読み込めませんでした。'; report(error.message, true); });
})();
