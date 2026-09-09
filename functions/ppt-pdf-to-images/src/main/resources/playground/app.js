'use strict';

(() => {
  const $ = (id) => document.getElementById(id);
  const apiPrefix = window.location.pathname.replace(/\/playground\/?$/, '');
  const endpoint = (path) => new URL(`${apiPrefix}/${path}`, window.location.origin);
  let config = null;
  let selectedFile = null;
  let active = null;
  let resultUrl = null;

  const errorMessages = {
    ASYNC_DISABLED: '非同期機能が無効です。Storage の接続設定を確認してください。',
    INPUT_TOO_LARGE: 'ファイルサイズがサーバーの上限を超えています。',
    EMPTY_INPUT: 'ファイルが空です。別のファイルを選んでください。',
    INVALID_FILENAME: 'ファイル名を短くするなどして、もう一度お試しください。',
    INVALID_OPTIONS: '画像形式、ページ番号を確認してください。',
    JOB_NOT_FOUND: 'ジョブが見つかりません。もう一度変換を実行してください。',
    INTERNAL_ERROR: 'サーバーでエラーが発生しました。時間をおいて、もう一度お試しください。'
  };

  function sizeLabel(bytes) {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  function setStatus(state, title, detail) {
    $('status-bar').dataset.state = state;
    $('status-title').textContent = title;
    $('status-detail').textContent = detail;
  }

  function showError(message, key = '') {
    // Never echo a key even if an upstream error happens to contain it.
    $('error-message').textContent = key ? message.split(key).join('[キー]') : message;
    $('error-message').hidden = false;
  }

  function clearResult() {
    $('error-message').hidden = true;
    $('error-message').textContent = '';
    $('result-content').hidden = true;
    $('preview-wrap').hidden = true;
    $('preview').removeAttribute('src');
    $('download').removeAttribute('href');
    $('job-info').hidden = true;
    $('job-info').textContent = '';
    if (resultUrl) URL.revokeObjectURL(resultUrl);
    resultUrl = null;
    $('result-empty').hidden = false;
    $('empty-title').textContent = 'ここにプレビューが表示されます';
    $('empty-detail').textContent = '1 ページを画像で確認。全ページは ZIP でまとめて保存。';
  }

  function updateControls() {
    const allPages = $('scope').value === 'all';
    $('controls').disabled = Boolean(active);
    $('mode-async').disabled = !config?.asyncEnabled;
    $('page').disabled = allPages;
    $('page-field').hidden = allPages;
    $('scope').closest('.output-row').classList.toggle('all-pages', allPages);
    $('output-hint').textContent = allPages
      ? '各ページを画像にして ZIP にまとめます。プレビューは 1 ページ指定で利用できます。'
      : '指定ページを画像で返します。ページ番号は 1 から始まります。';
    $('submit-button').disabled = Boolean(active) || !config || !selectedFile;
    $('cancel-area').hidden = !active;
    $('mode-hint').textContent = $('mode-async').checked
      ? 'Queue に登録し、完了するまでジョブの状態を確認します。'
      : 'リクエストを送信し、変換の完了を待ちます。';
  }

  async function loadConfig() {
    $('retry-config').disabled = true;
    $('config-status').dataset.state = 'loading';
    $('config-status').textContent = '接続確認中';
    $('config-message').textContent = '設定を取得しています';
    try {
      const key = $('function-key').value.trim();
      const response = await fetch(endpoint('playground/config'), {
        cache: 'no-store', redirect: 'error', credentials: 'same-origin',
        headers: key ? { 'x-functions-key': key } : {}
      });
      if (!response.ok) throw new Error(`設定を取得できませんでした（HTTP ${response.status}）。`);
      const data = await response.json();
      if (typeof data.asyncEnabled !== 'boolean' ||
          !['maxInputBytes', 'maxPages'].every((key) => Number.isSafeInteger(data[key]) && data[key] > 0)) {
        throw new Error('サーバー設定の応答形式が正しくありません。');
      }
      config = data;
      $('page').max = String(config.maxPages);
      $('file-hint').textContent = `PPT / PPTX / PDF · 最大 ${sizeLabel(config.maxInputBytes)} / ${config.maxPages} ページ`;
      $('config-status').dataset.state = 'ready';
      $('config-status').textContent = '接続済み';
      $('config-message').textContent = config.asyncEnabled ? '同期 / 非同期を利用できます' : '同期のみ · 非同期は Storage 設定時に有効';
      $('retry-config').hidden = true;
      if (!config.asyncEnabled) $('mode-sync').checked = true;
      if (selectedFile && selectedFile.size > config.maxInputBytes) selectFile(selectedFile);
      else if ($('status-bar').dataset.state === 'error' && !selectedFile) {
        $('error-message').hidden = true;
        setStatus('idle', '準備できています', 'ファイルを選んで、変換をはじめましょう。');
      }
    } catch (error) {
      config = null;
      $('config-status').dataset.state = 'error';
      $('config-status').textContent = '接続できません';
      $('config-message').textContent = 'Functions の起動を確認してください';
      $('retry-config').hidden = false;
      setStatus('error', '設定を取得できませんでした', '接続後に変換を実行できます。');
      showError(error instanceof TypeError ? 'サーバーに接続できません。Functions を起動してから「再接続」を押してください。' : error.message);
    } finally {
      $('retry-config').disabled = false;
      updateControls();
    }
  }

  function selectFile(file) {
    if (active) return;
    // Keep the File in memory so choosing the same file again still fires change.
    $('file-input').value = '';
    clearResult();
    $('elapsed').hidden = true;
    selectedFile = null;
    $('dropzone').classList.remove('has-file');
    $('selected-name').textContent = 'ファイルをドロップ';
    $('selected-meta').textContent = 'またはクリックしてファイルを選択';
    let invalid = '';
    if (!/\.(pptx?|pdf)$/i.test(file.name)) invalid = 'PPT、PPTX、PDF のいずれかを選んでください。';
    else if (file.size === 0) invalid = 'ファイルが空です。別のファイルを選んでください。';
    else if (config && file.size > config.maxInputBytes) invalid = `ファイルサイズの上限は ${sizeLabel(config.maxInputBytes)} です。`;
    if (invalid) {
      $('file-input').value = '';
      setStatus('error', 'ファイルを確認してください', '対応するファイルを選び直してください。');
      showError(invalid);
    } else {
      selectedFile = file;
      $('dropzone').classList.add('has-file');
      $('selected-name').textContent = file.name;
      $('selected-meta').textContent = `${sizeLabel(file.size)} · クリックして変更`;
      setStatus('idle', '変換の準備ができました', '設定を選んで「画像に変換」を押してください。');
    }
    updateControls();
  }

  function checkedUrl(raw, kind) {
    if (typeof raw !== 'string' || !raw) throw new Error('ジョブの応答に URL がありません。');
    const url = new URL(raw, window.location.href);
    const jobPath = `${apiPrefix}/jobs/`;
    const tail = url.pathname.startsWith(jobPath) ? url.pathname.slice(jobPath.length) : '';
    const allowed = kind === 'result' ? /^[A-Za-z0-9-]+\/result$/ : /^[A-Za-z0-9-]+$/;
    if (url.origin !== window.location.origin || url.username || url.password || url.search || url.hash || !allowed.test(tail)) {
      throw new Error('サーバーから安全に利用できないジョブ URL が返されました。');
    }
    return url;
  }

  async function apiFetch(url, run, options = {}) {
    const headers = new Headers(options.headers);
    if (run.key) headers.set('x-functions-key', run.key);
    const response = await fetch(url, { ...options, headers, signal: run.controller.signal,
      cache: 'no-store', redirect: 'error', credentials: 'same-origin' });
    if (!response.ok) {
      let message = `リクエストに失敗しました（HTTP ${response.status}）。`;
      if (response.status === 401 || response.status === 403) {
        message = '認証に失敗しました。「認証キー」に有効な Function App のホストキーを入力してください。';
      } else {
        let body;
        try { body = await response.json(); } catch { /* The host may return a non-JSON error. */ }
        if (body?.error?.code) {
          const code = String(body.error.code).slice(0, 100);
          message = `${errorMessages[code] || String(body.error.message || message).slice(0, 500)}\n${code}`;
        }
      }
      throw new Error(message);
    }
    return response;
  }

  function retryDelay(response) {
    const value = response.headers.get('Retry-After');
    if (value) {
      const seconds = Number(value);
      const milliseconds = Number.isFinite(seconds) ? seconds * 1000 : Date.parse(value) - Date.now();
      if (Number.isFinite(milliseconds)) return Math.max(500, Math.min(2147483647, milliseconds));
    }
    return 3000;
  }

  function wait(milliseconds, signal) {
    return new Promise((resolve, reject) => {
      if (signal.aborted) { reject(new DOMException('Aborted', 'AbortError')); return; }
      const abort = () => { clearTimeout(timer); reject(new DOMException('Aborted', 'AbortError')); };
      const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve(); }, milliseconds);
      signal.addEventListener('abort', abort, { once: true });
    });
  }

  async function runAsync(url, run) {
    let response = await apiFetch(url, run, { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: run.file });
    let data = await response.json();
    const statusUrl = checkedUrl(data.statusUrl || response.headers.get('Location'), 'status');
    while (true) {
      run.controller.signal.throwIfAborted();
      const job = data.job;
      if (!job || typeof job.id !== 'string' || !['queued', 'running', 'succeeded', 'failed'].includes(job.status)) {
        throw new Error('ジョブの応答形式が正しくありません。');
      }
      $('job-info').textContent = `JOB ${job.id}`;
      $('job-info').hidden = false;
      if (job.status === 'failed') {
        throw new Error(`${errorMessages[job.errorCode] || String(job.errorMessage || '変換ジョブに失敗しました。').slice(0, 500)}${job.errorCode ? `\n${String(job.errorCode).slice(0, 100)}` : ''}`);
      }
      if (job.status === 'succeeded') {
        setStatus('busy', '結果を取得しています', '変換が完了しました。画像をダウンロードしています。');
        return apiFetch(checkedUrl(data.resultUrl, 'result'), run);
      }
      setStatus('busy', job.status === 'queued' ? 'Queue で待機中' : '画像に変換しています',
        job.status === 'queued' ? 'ジョブを受け付けました。処理の開始を待っています。' : 'サーバーで処理中です。完了までこの画面で待機できます。');
      await wait(retryDelay(response), run.controller.signal);
      response = await apiFetch(statusUrl, run);
      data = await response.json();
    }
  }

  async function showResult(response, run) {
    const contentType = (response.headers.get('Content-Type') || '').split(';')[0].trim().toLowerCase();
    const expectedType = run.scope === 'all' ? 'application/zip' : `image/${run.format}`;
    if (contentType !== expectedType) throw new Error('変換結果のファイル形式が正しくありません。');
    const blob = await response.blob();
    run.controller.signal.throwIfAborted();
    if (!blob.size) throw new Error('空の変換結果が返されました。');
    resultUrl = URL.createObjectURL(blob);
    const fallback = `${run.file.name.replace(/\.[^.]+$/, '')}${run.scope === 'single' ? `-page-${run.page}` : ''}.${run.scope === 'all' ? 'zip' : run.format === 'jpeg' ? 'jpg' : 'png'}`;
    const disposition = response.headers.get('Content-Disposition') || '';
    const match = /filename="([^"]+)"/i.exec(disposition);
    const filename = (match ? match[1] : fallback).replace(/[\\/\x00-\x1f\x7f]/g, '_');
    if (run.scope === 'single') {
      $('preview').src = resultUrl;
      try { await $('preview').decode(); } catch { throw new Error('返された画像を読み込めませんでした。'); }
      run.controller.signal.throwIfAborted();
      $('preview-wrap').hidden = false;
    }
    $('zip-preview').hidden = run.scope !== 'all';
    $('download').href = resultUrl;
    $('download').download = filename;
    $('result-name').textContent = filename;
    const pageCount = response.headers.get('X-Page-Count');
    const pageText = run.scope === 'single' ? `${run.page} ページ目` : /^\d+$/.test(pageCount || '') ? `全 ${pageCount} ページ` : '全ページ';
    $('result-meta').textContent = `${pageText} · ${run.format.toUpperCase()}${run.scope === 'all' ? ' / ZIP' : ''} · ${sizeLabel(blob.size)}`;
    $('result-empty').hidden = true;
    $('result-content').hidden = false;
    setStatus('success', '変換が完了しました', run.scope === 'single' ? '画像をプレビューして、ダウンロードできます。' : '全ページの画像を ZIP でダウンロードできます。');
  }

  $('convert-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    if (active || !config || !selectedFile || !$('convert-form').reportValidity()) return;
    const run = { controller: new AbortController(), file: selectedFile, key: $('function-key').value.trim(),
      format: $('format').value, scope: $('scope').value, page: Number($('page').value), start: performance.now() };
    const asyncMode = $('mode-async').checked;
    if (asyncMode && !config.asyncEnabled) return;
    active = run;
    clearResult();
    updateControls();
    $('elapsed').hidden = false;
    const tick = () => { $('elapsed').textContent = `${((performance.now() - run.start) / 1000).toFixed(1)} 秒`; };
    tick();
    const timer = setInterval(tick, 100);
    setStatus('busy', asyncMode ? 'ジョブを送信しています' : '画像に変換しています',
      asyncMode ? 'ファイルをアップロードし、Queue に登録します。' : 'ファイルをアップロードし、変換の完了を待っています。');
    $('empty-title').textContent = '変換結果を待っています';
    $('empty-detail').textContent = 'ファイルの内容やページ数によって、時間がかかる場合があります。';
    const url = endpoint(asyncMode ? 'jobs' : 'convert');
    url.searchParams.set('filename', run.file.name);
    url.searchParams.set('format', run.format);
    if (run.scope === 'single') url.searchParams.set('page', String(run.page));
    try {
      const response = asyncMode ? await runAsync(url, run) : await apiFetch(url, run, {
        method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: run.file
      });
      await showResult(response, run);
    } catch (error) {
      const aborted = run.controller.signal.aborted;
      // Remove any incomplete image and release its object URL.
      if (resultUrl) { URL.revokeObjectURL(resultUrl); resultUrl = null; }
      $('preview').removeAttribute('src');
      $('download').removeAttribute('href');
      $('result-content').hidden = true;
      $('result-empty').hidden = false;
      $('empty-title').textContent = aborted ? '待機を停止しました' : '変換結果を取得できませんでした';
      $('empty-detail').textContent = aborted ? 'サーバー上の処理は続いている場合があります。' : '設定やファイルを確認して、もう一度お試しください。';
      setStatus(aborted ? 'idle' : 'error', aborted ? '待機を停止しました' : '変換に失敗しました',
        aborted ? 'この画面の通信を停止しました。ジョブの取り消しは行っていません。' : '下のエラー内容を確認してください。');
      if (!aborted) showError(error instanceof TypeError
        ? 'サーバーと通信できませんでした。接続・Functions の起動状態を確認してください。サーバーの処理が続いている場合があります。'
        : error.message, run.key);
    } finally {
      clearInterval(timer);
      tick();
      run.key = '';
      if (active === run) active = null;
      updateControls();
    }
  });

  $('file-input').addEventListener('change', (event) => {
    if (event.target.files.length) selectFile(event.target.files[0]);
  });
  ['dragenter', 'dragover'].forEach((name) => $('dropzone').addEventListener(name, (event) => {
    event.preventDefault();
    if (!active) $('dropzone').classList.add('dragover');
  }));
  $('dropzone').addEventListener('dragleave', (event) => {
    if (!$('dropzone').contains(event.relatedTarget)) $('dropzone').classList.remove('dragover');
  });
  $('dropzone').addEventListener('drop', (event) => {
    event.preventDefault();
    $('dropzone').classList.remove('dragover');
    if (active) return;
    if (event.dataTransfer.files.length !== 1) { showError('ファイルは 1 つずつ選んでください。'); return; }
    selectFile(event.dataTransfer.files[0]);
  });
  // Prevent a dropped file outside the target from navigating away from the tool.
  window.addEventListener('dragover', (event) => event.preventDefault());
  window.addEventListener('drop', (event) => event.preventDefault());
  $('scope').addEventListener('change', updateControls);
  document.querySelectorAll('input[name="mode"]').forEach((radio) => radio.addEventListener('change', updateControls));
  $('cancel-button').addEventListener('click', () => active?.controller.abort());
  $('retry-config').addEventListener('click', loadConfig);
  window.addEventListener('pagehide', () => {
    active?.controller.abort();
    if (resultUrl) URL.revokeObjectURL(resultUrl);
    $('function-key').value = '';
  });
  updateControls();
  loadConfig();
})();
