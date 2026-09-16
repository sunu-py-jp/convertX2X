(() => {
  'use strict';
  const base = new URL('../', document.currentScript.src);
  const current = document.body.dataset.page || 'home';
  const url = path => new URL(path, base).href;
  const icons = {
    search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4.5 4.5"/>',
    home: '<path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1Z"/><path d="M9 21v-8h6v8"/>',
    image: '<rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8" cy="8" r="1.4"/><path d="m3 17 5-5 4 4 4-7 5 7"/>',
    office: '<path d="M14 3H5v18h14V8Z"/><path d="M14 3v5h5M8 12h8M8 16h6"/>',
    audio: '<path d="M11 5 6 9H3v6h3l5 4Z"/><path d="M15 8a6 6 0 0 1 0 8M18 5a10 10 0 0 1 0 14"/>',
    chevron: '<path d="m9 5 7 7-7 7"/>',
    menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
    close: '<path d="m5 5 14 14M5 19 19 5"/>',
    copy: '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M15 8V4H4v11h4"/>'
  };
  const svg = (name, cls = '') => `<svg class="${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${icons[name]}</svg>`;
  const groups = [
    {id: 'images', title: 'PowerPoint / PDF → 画像', subtitle: 'PPTX · PPT · PDF', icon: 'image', folder: 'ppt-pdf-to-images', items: [
      ['overview', '機能概要・出力形式', 'index.html', 'png jpeg zip ページ スライド 画像'],
      ['http', '同期HTTPで変換', 'http.html', 'post convert width format page curl ファイル'],
      ['jobs', '非同期HTTP・結果取得', 'jobs.html', 'jobs get post result status ジョブ 状態'],
      ['queue', 'Queueから直接依頼', 'queue.html', 'json blob manifest storage 外部システム images'],
      ['settings', '設定・上限・エラー', 'settings.html', '環境変数 制限 認証 capabilities config playground 413 503']
    ]},
    {id: 'office', title: 'Office → Markdown', subtitle: 'XLSX · XLS · DOCX · PPTX', icon: 'office', folder: 'office2md', items: [
      ['overview', '機能概要・変換ルール', 'index.html', 'excel word powerpoint md 表 図形 取消線 座標'],
      ['http', '同期HTTPで変換', 'http.html', 'post convert curl ファイル zip markdown'],
      ['jobs', '非同期HTTP・成果物取得', 'jobs.html', 'jobs get post result report archive images status ジョブ 状態'],
      ['queue', 'Queueから直接依頼', 'queue.html', 'json blob storage 外部システム'],
      ['settings', '設定・上限・エラー', 'settings.html', '環境変数 制限 認証 capabilities config playground 413 503']
    ]},
    {id: 'movie', title: 'Movie → AAC', subtitle: 'MOVIE · AUDIO · M4A', icon: 'audio', folder: 'movie2audio', items: [
      ['overview', '機能概要・出力形式', 'index.html', 'movie2audio 動画 音声 aac m4a mp4 ffmpeg stream copy 再エンコード'],
      ['http', 'ファイルを送って抽出', 'http.html', 'post convert curl upload 動画 ファイル audio mp4'],
      ['url', 'HTTPS URLから抽出', 'url.html', 'post convert-url json download 許可ホスト allowlist ssrf url_fetch_disabled'],
      ['jobs', '非同期HTTP・音声取得', 'jobs.html', 'jobs jobs-url get post result status ジョブ 状態 非同期 m4a'],
      ['queue', 'Queueから直接依頼', 'queue.html', 'json blob storage 外部システム 登録名 非同期'],
      ['storage', 'SASでBlobへ保存', 'storage.html', 'post convert-to-blob sas storage output upload multipart 保存 書き込み'],
      ['settings', '設定・上限・エラー', 'settings.html', '環境変数 制限 認証 capabilities config playground ffmpeg 413 422 503']
    ]}
  ];
  const activeGroup = groups.find(group => current.startsWith(`${group.id}-`));
  document.getElementById('site-header').innerHTML = `<header class="site-header"><a class="brand" href="${url('index.html')}" aria-label="convertX2X API Docs トップ"><span class="brand-mark">X²</span><span class="brand-name">convertX2X</span></a><div class="header-context"><span>API Docs</span><i class="header-divider" aria-hidden="true"></i><span class="header-current">${activeGroup ? activeGroup.title : 'はじめに'}</span></div><div class="header-right"><span class="live-dot" aria-hidden="true"></span>HTTP &amp; QUEUE</div><button class="menu-toggle" type="button" aria-label="メニューを開く" aria-expanded="false" aria-controls="sidebar">${svg('menu')}</button></header>`;
  const sidebar = document.getElementById('sidebar');
  sidebar.innerHTML = `<div class="search-box"><label class="sr-only" for="nav-search">API項目を検索</label>${svg('search')}<input id="nav-search" type="search" placeholder="API項目を検索…" autocomplete="off" spellcheck="false"><kbd aria-hidden="true">/</kbd></div><nav aria-label="機能別API"><a class="nav-home" href="${url('index.html')}"${current === 'home' ? ' aria-current="page"' : ''}>${svg('home')}はじめに</a><p class="nav-label">API REFERENCE</p>${groups.map(group => `<details class="nav-group" data-group="${group.id}" open><summary>${svg(group.icon, 'group-icon')}<span>${group.title}<span class="group-subtitle">${group.subtitle}</span></span>${svg('chevron', 'chevron')}</summary><div class="nav-items">${group.items.map(item => `<a class="nav-link" data-search="${group.title} ${group.subtitle} ${item[1]} ${item[3]}" href="${url(`${group.folder}/${item[2]}`)}"${current === `${group.id}-${item[0]}` ? ' aria-current="page"' : ''}>${item[1]}</a>`).join('')}</div></details>`).join('')}<p class="nav-empty" hidden>一致する項目がありません。</p><span id="search-status" class="sr-only" role="status"></span></nav><div class="sidebar-footer"><a href="${url('../README.md')}">開発者マニュアル ↗</a><span>実装済みAPIのリファレンス</span><span>Updated September 15, 2026</span></div>`;

  const search = document.getElementById('nav-search');
  const details = [...sidebar.querySelectorAll('.nav-group')];
  const navLinks = [...sidebar.querySelectorAll('.nav-link')];
  let searching = false;
  let savedOpen = [];
  for (const group of details) {
    try {
      const preference = sessionStorage.getItem(`apidocs-nav-${group.dataset.group}`);
      if (preference !== null && group.dataset.group !== activeGroup?.id) group.open = preference === 'open';
    } catch (_) { /* Storage is optional, including for file://. */ }
    group.addEventListener('toggle', () => {
      if (!searching) {
        try { sessionStorage.setItem(`apidocs-nav-${group.dataset.group}`, group.open ? 'open' : 'closed'); } catch (_) {}
      }
    });
  }
  search.addEventListener('input', () => {
    const terms = search.value.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean);
    if (terms.length && !searching) savedOpen = details.map(group => group.open);
    const wasSearching = searching;
    searching = terms.length > 0;
    let count = 0;
    for (const link of navLinks) {
      link.hidden = !terms.every(term => link.dataset.search.toLocaleLowerCase().includes(term));
      if (!link.hidden) count++;
    }
    for (const [index, group] of details.entries()) {
      group.hidden = ![...group.querySelectorAll('.nav-link')].some(link => !link.hidden);
      if (searching) group.open = true;
      else if (wasSearching) group.open = savedOpen[index];
    }
    sidebar.querySelector('.nav-empty').hidden = count > 0;
    document.getElementById('search-status').textContent = searching ? `${count}件の項目が見つかりました` : '';
  });

  const toggle = document.querySelector('.menu-toggle');
  const overlay = document.getElementById('menu-overlay');
  const mobile = matchMedia('(max-width: 900px)');
  const setMenu = (open, restoreFocus = false) => {
    const visible = open && mobile.matches;
    sidebar.classList.toggle('is-open', visible);
    document.body.classList.toggle('menu-open', visible);
    overlay.hidden = !visible;
    toggle.setAttribute('aria-expanded', String(visible));
    toggle.setAttribute('aria-label', visible ? 'メニューを閉じる' : 'メニューを開く');
    toggle.innerHTML = svg(visible ? 'close' : 'menu');
    sidebar.inert = mobile.matches && !visible;
    document.getElementById('main-content').inert = visible;
    if (visible) search.focus({preventScroll: true});
    else if (restoreFocus) toggle.focus();
  };
  toggle.addEventListener('click', () => setMenu(toggle.getAttribute('aria-expanded') !== 'true', true));
  overlay.addEventListener('click', () => setMenu(false, true));
  mobile.addEventListener('change', () => setMenu(false));
  setMenu(false);
  sidebar.querySelectorAll('a').forEach(link => link.addEventListener('click', () => setMenu(false)));
  document.addEventListener('keydown', event => {
    const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(event.target.tagName) || event.target.isContentEditable;
    if (event.key === '/' && !typing && !event.metaKey && !event.ctrlKey && !event.altKey) {
      event.preventDefault();
      if (mobile.matches) setMenu(true);
      search.focus({preventScroll: true});
    }
    if (event.key === 'Escape') {
      if (document.body.classList.contains('menu-open')) setMenu(false, true);
      else if (event.target === search && search.value) { search.value = ''; search.dispatchEvent(new Event('input')); }
    }
    if (event.key === 'Tab' && document.body.classList.contains('menu-open')) {
      const candidates = [toggle, ...sidebar.querySelectorAll('input, summary, a')].filter(el => el.getClientRects().length && !el.closest('[hidden]'));
      const first = candidates[0], last = candidates[candidates.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }
  });

  const toc = document.getElementById('page-toc');
  const headings = [...document.querySelectorAll('.article h2[id]')];
  headings.forEach(heading => {
    const link = document.createElement('a');
    link.href = `#${heading.id}`;
    link.textContent = heading.textContent;
    toc?.append(link);
    const permalink = document.createElement('a');
    permalink.className = 'section-link';
    permalink.href = `#${heading.id}`;
    permalink.setAttribute('aria-label', `${heading.textContent}へのリンク`);
    permalink.textContent = '#';
    heading.append(permalink);
  });
  const tocLinks = toc ? [...toc.querySelectorAll('a')] : [];
  const setCurrentSection = id => tocLinks.forEach(link => link.classList.toggle('active', link.hash === `#${id}`));
  if (headings.length) setCurrentSection(location.hash.slice(1) || headings[0].id);
  if ('IntersectionObserver' in window) {
    const observer = new IntersectionObserver(entries => {
      for (const entry of entries) if (entry.isIntersecting) setCurrentSection(entry.target.id);
    }, {rootMargin: '-85px 0px -65% 0px'});
    headings.forEach(heading => observer.observe(heading));
  }
  addEventListener('hashchange', () => setCurrentSection(location.hash.slice(1)));

  const copyText = async text => {
    if (navigator.clipboard && isSecureContext) {
      try { await navigator.clipboard.writeText(text); return; } catch (_) { /* Fall back for local files. */ }
    }
    const previousFocus = document.activeElement;
    const textarea = document.createElement('textarea');
    textarea.className = 'copy-fallback';
    textarea.value = text;
    document.body.append(textarea);
    textarea.select();
    const success = document.execCommand('copy');
    textarea.remove();
    previousFocus?.focus();
    if (!success) throw new Error('Clipboard unavailable');
  };
  document.querySelectorAll('.article pre').forEach(pre => {
    const wrapper = document.createElement('div');
    wrapper.className = 'code-block';
    const toolbar = document.createElement('div');
    toolbar.className = 'code-toolbar';
    const label = document.createElement('span');
    label.className = 'code-language';
    const raw = pre.textContent;
    label.textContent = pre.querySelector('code')?.className.match(/language-([\w]+)/)?.[1]?.toUpperCase() || (raw.trim().startsWith('{') ? 'JSON' : /curl|python3|export|BASE_URL=/.test(raw) ? 'SHELL' : 'EXAMPLE');
    const copy = document.createElement('button');
    copy.type = 'button';
    copy.className = 'copy-button';
    copy.setAttribute('aria-label', 'コードをコピー');
    copy.innerHTML = `${svg('copy')}<span>コピー</span>`;
    const status = document.createElement('p');
    status.className = 'copy-status';
    status.setAttribute('role', 'status');
    status.hidden = true;
    let reset;
    copy.addEventListener('click', async () => {
      clearTimeout(reset);
      try {
        await copyText(raw);
        copy.querySelector('span').textContent = 'コピー済み';
        status.textContent = 'コードをコピーしました。';
      } catch (_) {
        status.textContent = 'コピーできませんでした。コードを選択してコピーしてください。';
      }
      status.hidden = false;
      reset = setTimeout(() => { copy.querySelector('span').textContent = 'コピー'; status.hidden = true; }, 3500);
    });
    toolbar.append(label, copy);
    pre.before(wrapper);
    wrapper.append(toolbar, pre, status);
  });
  document.querySelectorAll('.article table').forEach(table => {
    if (!table.parentElement.classList.contains('table-wrap')) {
      const wrap = document.createElement('div');
      wrap.className = 'table-wrap';
      table.before(wrap);
      wrap.append(table);
    }
    const wrap = table.parentElement;
    wrap.tabIndex = 0;
    wrap.setAttribute('role', 'region');
    wrap.setAttribute('aria-label', table.caption?.textContent || 'API仕様の表（横スクロール可能）');
  });
})();
