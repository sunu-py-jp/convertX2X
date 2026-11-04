import path from 'node:path';
import { TextDecoder } from 'node:util';
import * as parse5 from 'parse5';
import * as cssTree from 'css-tree';
import yauzl from 'yauzl';
import { imageSize } from 'image-size';
import { ConversionError } from './errors.js';

export const DEFAULT_INPUT_LIMITS = Object.freeze({
  maxInputBytes: 20 * 1024 * 1024,
  maxUnpackedBytes: 100 * 1024 * 1024,
  maxFiles: 500,
  maxDomNodes: 50_000,
  maxDomDepth: 64,
  maxCssBytes: 2 * 1024 * 1024,
  maxDimension: 4096,
  minDimension: 64,
  maxImagePixels: 20_000_000,
  maxTotalImagePixels: 80_000_000,
  maxSlides: 50,
});

const HTML = 'http://www.w3.org/1999/xhtml';
const SVG = 'http://www.w3.org/2000/svg';
const HTML_TAGS = new Set(('html head title meta style link body div section article header footer main aside nav span p h1 h2 h3 h4 h5 h6 a strong b em i u s del small sub sup br hr ul ol li dl dt dd blockquote pre code figure figcaption img table thead tbody tfoot tr td th colgroup col abbr bdi bdo cite q time address wbr mark kbd samp var').split(' '));
const SVG_TAGS = new Set(('svg g defs rect circle ellipse line polyline polygon path text tspan linearGradient radialGradient stop marker use clipPath title desc style').split(' '));
const LINK_PROTOCOLS = new Set(['https:', 'http:', 'mailto:']);
const CSS_AT_RULES = new Set(['media', 'supports', 'layer', 'container', 'page', 'charset', 'keyframes', '-webkit-keyframes']);
const CSS_UNSAFE_FUNCTIONS = new Set(['expression', 'paint', 'image', 'image-set', '-webkit-image-set', 'cross-fade', '-webkit-cross-fade', 'element', '-moz-element', '-webkit-canvas', 'src']);
const CSS_URL_ATTRIBUTES = new Set(['fill', 'stroke', 'marker', 'marker-start', 'marker-mid', 'marker-end', 'clip-path', 'cursor']);
const ACTIVE_ATTRIBUTES = new Set(['srcdoc', 'srcset', 'imagesrcset', 'ping', 'background', 'action', 'formaction', 'form', 'manifest', 'codebase', 'archive', 'data', 'poster']);
const ARCHIVE_EXTENSIONS = new Set(['.html', '.htm', '.css', '.png', '.jpg', '.jpeg', '.svg']);
const decoder = new TextDecoder('utf-8', { fatal: true });

function fail(code, message, status = 422) { throw new ConversionError(code, message, status); }
function limit(condition, message) { if (condition) fail('INPUT_LIMIT_EXCEEDED', message, 413); }
function utf8(bytes) {
  try { return decoder.decode(bytes); }
  catch { return fail('INVALID_UTF8', 'HTML・CSS・SVGはUTF-8で保存してください。', 400); }
}
function attr(node, name) { return node.attrs?.find(a => a.name.toLowerCase() === name); }
function get(node, name) { return attr(node, name)?.value; }
function set(node, name, value) {
  const previous = attr(node, name);
  if (previous) previous.value = value;
  else (node.attrs ??= []).push({ name, value });
}
function plainAttributeName(a) { return a.name.toLowerCase(); }
function textContent(node) { return (node.childNodes ?? []).map(n => n.nodeName === '#text' ? n.value : textContent(n)).join(''); }
function setText(node, value) { node.childNodes = [{ nodeName: '#text', value, parentNode: node }]; }

function archiveName(name) {
  if (typeof name !== 'string' || !name || /[\\\u0000-\u001f\u007f]/u.test(name) || name.startsWith('/') || /^[a-z]:/iu.test(name)) {
    fail('INVALID_ARCHIVE_PATH', 'ZIP内のファイル名に安全でないパスがあります。', 400);
  }
  const parts = name.replace(/\/$/u, '').split('/');
  if (parts.some(p => !p || p === '.' || p === '..') || name !== name.normalize('NFC')) {
    fail('INVALID_ARCHIVE_PATH', 'ZIP内のファイル名に安全でないパスがあります。', 400);
  }
  return name;
}

async function unpackZip(bytes, limits) {
  const zip = await new Promise((resolve, reject) => {
    yauzl.fromBuffer(bytes, { lazyEntries: true, validateEntrySizes: true, strictFileNames: true }, (error, value) => error ? reject(new ConversionError('INVALID_ZIP', 'ZIPファイルを読み取れません。', 400)) : resolve(value));
  });
  const files = new Map();
  const names = new Set();
  let entries = 0;
  let unpackedBytes = 0;
  return new Promise((resolve, reject) => {
    let finished = false;
    const abort = error => {
      if (finished) return;
      finished = true;
      zip.close();
      reject(error instanceof ConversionError ? error : new ConversionError('INVALID_ZIP', 'ZIPファイルを読み取れません。', 400));
    };
    zip.on('error', abort);
    zip.on('end', () => { if (!finished) { finished = true; resolve({ files, unpackedBytes }); } });
    zip.on('entry', entry => {
      (async () => {
        limit(++entries > limits.maxFiles, 'ZIP内のファイル数が上限を超えています。');
        const name = archiveName(entry.fileName);
        const normalized = name.replace(/\/$/u, '').toLocaleLowerCase('en-US');
        if (names.has(normalized)) fail('DUPLICATE_ARCHIVE_PATH', 'ZIP内に重複するファイル名があります。', 400);
        names.add(normalized);
        const mode = (entry.externalFileAttributes >>> 16) & 0xf000;
        if (mode && mode !== 0x8000 && mode !== 0x4000) fail('UNSUPPORTED_ZIP_ENTRY', 'ZIP内のリンクや特殊ファイルは使用できません。', 400);
        if ((entry.generalPurposeBitFlag & 1) || ![0, 8].includes(entry.compressionMethod)) fail('UNSUPPORTED_ZIP_ENTRY', '暗号化または未対応の圧縮形式を含むZIPです。', 400);
        if (name.endsWith('/')) { zip.readEntry(); return; }
        if (!ARCHIVE_EXTENSIONS.has(path.posix.extname(name).toLowerCase())) fail('UNSUPPORTED_ASSET', 'ZIPにはHTML・CSS・PNG・JPEG・SVGだけを含めてください。');
        limit(entry.uncompressedSize > limits.maxUnpackedBytes - unpackedBytes, 'ZIPの展開サイズが上限を超えています。');
        const stream = await new Promise((ok, no) => zip.openReadStream(entry, (error, s) => error ? no(error) : ok(s)));
        const chunks = [];
        for await (const chunk of stream) {
          unpackedBytes += chunk.length;
          limit(unpackedBytes > limits.maxUnpackedBytes, 'ZIPの展開サイズが上限を超えています。');
          chunks.push(chunk);
        }
        files.set(name, Buffer.concat(chunks));
        zip.readEntry();
      })().catch(abort);
    });
    zip.readEntry();
  });
}

function localReference(reference, base) {
  if (!reference || /[\u0000-\u0020\u007f\\]/u.test(reference) || reference.startsWith('/') || /^[a-z][a-z\d+.-]*:/iu.test(reference)) {
    fail('EXTERNAL_RESOURCE_FORBIDDEN', '外部URL・絶対パス・特殊なURLは取得しません。素材をZIP内の相対パスで指定してください。');
  }
  let decoded;
  try { decoded = decodeURIComponent(reference); }
  catch { return fail('INVALID_RESOURCE_PATH', '素材のパスが不正です。', 400); }
  if (/[?#\u0000-\u001f\u007f\\]/u.test(decoded) || decoded.startsWith('/') || /^[a-z][a-z\d+.-]*:/iu.test(decoded)) fail('INVALID_RESOURCE_PATH', '素材のパスが不正です。', 400);
  const resolved = path.posix.normalize(path.posix.join(path.posix.dirname(base), decoded));
  if (resolved === '..' || resolved.startsWith('../') || resolved.startsWith('/')) fail('INVALID_RESOURCE_PATH', '素材はZIPの外部を参照できません。', 400);
  return resolved;
}

function readRaster(bytes, limits) {
  let dimensions;
  try { dimensions = imageSize(bytes); }
  catch { return fail('INVALID_IMAGE', 'PNGまたはJPEGの画像データを読み取れません。'); }
  if (!['png', 'jpg'].includes(dimensions.type)) fail('UNSUPPORTED_IMAGE', '画像はPNGまたはJPEGを使用してください。');
  if (!dimensions.width || !dimensions.height || !Number.isSafeInteger(dimensions.width * dimensions.height)) fail('INVALID_IMAGE', '画像の寸法が不正です。');
  limit(dimensions.width * dimensions.height > limits.maxImagePixels, '画像の画素数が上限を超えています。');
  return { bytes, mime: dimensions.type === 'png' ? 'image/png' : 'image/jpeg', dimensions };
}

function imageResource(reference, base, state, allowSvg = false) {
  let bytes;
  let kind;
  let sourcePath = base;
  if (/^data:/iu.test(reference)) {
    const match = /^data:(image\/(?:png|jpeg|svg\+xml));base64,([A-Za-z\d+/]*={0,2})$/iu.exec(reference);
    if (!match || match[2].length % 4 !== 0) fail('INVALID_IMAGE_DATA_URI', '画像のdata URIはPNG・JPEG（またはSVG画像）のbase64形式で指定してください。');
    bytes = Buffer.from(match[2], 'base64');
    kind = match[1].toLowerCase();
    if (bytes.toString('base64') !== match[2]) fail('INVALID_IMAGE_DATA_URI', '画像のbase64データが不正です。');
  } else {
    sourcePath = localReference(reference, base);
    bytes = state.files.get(sourcePath);
    if (!bytes) fail('ASSET_NOT_FOUND', '参照された素材がZIP内にありません。');
    kind = path.posix.extname(sourcePath).toLowerCase() === '.svg' ? 'image/svg+xml' : undefined;
  }
  if (kind === 'image/svg+xml') {
    if (!allowSvg) fail('UNSUPPORTED_SVG_IMAGE', '背景画像としてのSVGは未対応です。HTML内のsvg要素として配置してください。');
    state.expandedResourceBytes += bytes.length;
    limit(state.expandedResourceBytes > state.limits.maxUnpackedBytes, '素材を展開したHTMLのサイズが上限を超えています。');
    return { svg: utf8(bytes), sourcePath };
  }
  const result = readRaster(bytes, state.limits);
  state.imagePixels += result.dimensions.width * result.dimensions.height;
  limit(state.imagePixels > state.limits.maxTotalImagePixels, '画像の合計画素数が上限を超えています。');
  if (kind && result.mime !== kind) fail('INVALID_IMAGE', '画像の形式とdata URIの種類が一致しません。');
  state.expandedResourceBytes += Math.ceil(bytes.length / 3) * 4;
  limit(state.expandedResourceBytes > state.limits.maxUnpackedBytes, '素材を展開したHTMLのサイズが上限を超えています。');
  return { ...result, dataUri: `data:${result.mime};base64,${bytes.toString('base64')}`, sourcePath };
}

function css(source, context, base, state, countBytes = true) {
  if (countBytes) state.cssBytes += Buffer.byteLength(source);
  limit(state.cssBytes > state.limits.maxCssBytes, 'CSSの合計サイズが上限を超えています。');
  let tree;
  try { tree = cssTree.parse(source, { context, parseCustomProperty: true, onParseError() { fail('INVALID_CSS', 'CSSを構文解析できません。', 400); } }); }
  catch (error) { if (error instanceof ConversionError) throw error; return fail('INVALID_CSS', 'CSSを構文解析できません。', 400); }
  cssTree.walk(tree, node => {
    if (node.type === 'Raw') fail('UNSUPPORTED_CSS', '構文解析できないCSS指定があります。');
    if (node.type === 'Atrule' && !CSS_AT_RULES.has(node.name.toLowerCase())) fail('UNSUPPORTED_CSS', '外部CSSの読み込み・Webフォントなど、未対応のCSS規則は使用できません。');
    if (node.type === 'Function' && (node.name.includes('\\') || CSS_UNSAFE_FUNCTIONS.has(node.name.toLowerCase()))) fail('UNSUPPORTED_CSS', '対応していないCSS関数が含まれています。');
    if (node.type === 'Declaration' && (node.property.includes('\\') || /^(?:behavior|-moz-binding)$/iu.test(node.property))) fail('UNSUPPORTED_CSS', '動的なCSS指定は使用できません。');
    if (node.type === 'Url') {
      if (/^#[A-Za-z_][\w:.-]*$/u.test(node.value)) return;
      node.value = imageResource(node.value, base, state).dataUri;
    }
  });
  // CSS loaded from a ZIP becomes an HTML style element. Escape '<' so a CSS
  // string containing </style> cannot close that element when HTML is reparsed.
  return cssTree.generate(tree).replaceAll('<', '\\3c ');
}

function validateLink(value) {
  if (value.startsWith('#')) return;
  let url;
  try { url = new URL(value); }
  catch { return fail('UNSUPPORTED_LINK', 'リンクはhttp・https・mailto、またはページ内の参照を指定してください。'); }
  if (!LINK_PROTOCOLS.has(url.protocol) || url.username || url.password) fail('UNSUPPORTED_LINK', '使用できない種類のリンクです。');
}

function svgFromImage(image, result) {
  const fragment = parse5.parseFragment(result.svg);
  const elements = fragment.childNodes.filter(n => n.tagName);
  if (elements.length !== 1 || elements[0].namespaceURI !== SVG || elements[0].tagName !== 'svg') fail('INVALID_SVG', 'SVG素材にsvg要素がありません。');
  const svg = elements[0];
  for (const name of ['width', 'height', 'id', 'class', 'style']) {
    if (get(image, name) !== undefined) set(svg, name, get(image, name));
  }
  svg.parentNode = image.parentNode;
  const siblings = image.parentNode.childNodes;
  siblings[siblings.indexOf(image)] = svg;
  return svg;
}

async function validateDocument(document, base, state) {
  const walk = async (node, depth, nodeBase) => {
    limit(++state.domNodes > state.limits.maxDomNodes, 'HTMLの要素数が上限を超えています。');
    limit(depth > state.limits.maxDomDepth, 'HTMLの階層が上限を超えています。');
    if (node.tagName) {
      const isSvg = node.namespaceURI === SVG;
      const allowed = isSvg ? SVG_TAGS : HTML_TAGS;
      if (![HTML, SVG].includes(node.namespaceURI) || !allowed.has(node.tagName)) fail(isSvg ? 'UNSUPPORTED_SVG' : 'UNSUPPORTED_HTML', '対応していない要素が含まれています。スクリプト・フォーム・埋め込みコンテンツ等は使用できません。');
      for (const a of node.attrs ?? []) {
        const name = plainAttributeName(a);
        if (name.startsWith('on') || ACTIVE_ATTRIBUTES.has(name) || name === 'contenteditable' || name === 'autofocus') fail('ACTIVE_CONTENT_FORBIDDEN', 'スクリプトのイベント属性や動的な属性は使用できません。');
        if (name.startsWith('data-') && name !== 'data-slide') fail('UNSUPPORTED_HTML_ATTRIBUTE', '独自のdata属性はdata-slideだけを使用できます。');
        if (isSvg && ['filter', 'mask'].includes(name) && a.value !== 'none') fail('UNSUPPORTED_SVG', 'SVGのfilter・maskは編集可能な図形への変換に対応していません。');
        if (name === 'style') a.value = css(a.value, 'declarationList', nodeBase, state);
        else if (CSS_URL_ATTRIBUTES.has(name)) a.value = css(a.value, 'value', nodeBase, state);
        else if (name === 'href') {
          if (node.tagName === 'a' && !isSvg) validateLink(a.value);
          else if (node.tagName === 'link' && !isSvg) { /* Validated when replacing its stylesheet below. */ }
          else if (node.tagName === 'use' && isSvg && /^#[A-Za-z_][\w:.-]*$/u.test(a.value)) { /* Local SVG references only. */ }
          else fail('EXTERNAL_RESOURCE_FORBIDDEN', 'SVG等の外部参照は使用できません。');
        } else if (name === 'src' && node.tagName !== 'img') fail('EXTERNAL_RESOURCE_FORBIDDEN', '素材の読み込み先を指定できるのは画像だけです。');
        else if (name === 'xml:base' || (a.prefix === 'xml' && name === 'base')) fail('EXTERNAL_RESOURCE_FORBIDDEN', '外部参照の基準URLは指定できません。');
      }
      if (node.tagName === 'meta') {
        if (get(node, 'http-equiv')) fail('ACTIVE_CONTENT_FORBIDDEN', 'HTTP動作を変更するmeta要素は使用できません。');
        const name = get(node, 'name')?.toLowerCase();
        if (name === 'pptx-slide-width' || name === 'pptx-slide-height') {
          if (state.meta.has(name)) fail('INVALID_SLIDE_SIZE', 'スライド寸法のmeta要素が重複しています。', 400);
          state.meta.set(name, get(node, 'content'));
        }
      }
      if (node.tagName === 'link') {
        if (get(node, 'rel')?.toLowerCase() !== 'stylesheet' || !get(node, 'href')) fail('UNSUPPORTED_HTML', 'link要素はZIP内のCSS参照だけに使用できます。');
        const stylesheet = localReference(get(node, 'href'), nodeBase);
        if (path.posix.extname(stylesheet).toLowerCase() !== '.css') fail('UNSUPPORTED_ASSET', 'スタイルシートはCSSファイルで指定してください。');
        const bytes = state.files.get(stylesheet);
        if (!bytes) fail('ASSET_NOT_FOUND', '参照されたCSSがZIP内にありません。');
        const source = css(utf8(bytes), 'stylesheet', stylesheet, state);
        node.tagName = 'style'; node.nodeName = 'style';
        node.attrs = node.attrs.filter(a => ['media', 'title'].includes(a.name));
        setText(node, source);
      } else if (node.tagName === 'style') {
        setText(node, css(textContent(node), 'stylesheet', nodeBase, state));
      } else if (node.tagName === 'img') {
        const source = get(node, 'src');
        if (!source) fail('INVALID_IMAGE', '画像にはsrc属性が必要です。');
        const result = imageResource(source, nodeBase, state, true);
        if (result.svg) {
          node = svgFromImage(node, result);
          await walk(node, depth, result.sourcePath);
          return;
        }
        set(node, 'src', result.dataUri);
        // Eager loading is deterministic and does not depend on viewport scrolling.
        set(node, 'loading', 'eager');
      }
      if (attr(node, 'data-slide')) {
        limit(++state.slideCount > state.limits.maxSlides, 'スライド数が上限を超えています。');
        for (let p = node.parentNode; p; p = p.parentNode) if (attr(p, 'data-slide')) fail('NESTED_SLIDES', 'data-slide要素は入れ子にできません。');
      }
    }
    for (const child of [...(node.childNodes ?? [])]) await walk(child, depth + 1, nodeBase);
  };
  await walk(document, 0, base);
}

/** Resolve only packaged resources. Returned HTML contains no requestable resource URLs. */
export async function prepareInput(buffer, { filename = 'input.html', limits = {} } = {}) {
  const settings = { ...DEFAULT_INPUT_LIMITS, ...limits };
  for (const key of Object.keys(DEFAULT_INPUT_LIMITS)) if (!Number.isSafeInteger(settings[key]) || settings[key] <= 0) fail('INVALID_CONFIGURATION', '入力上限の設定が不正です。', 500);
  if (typeof filename !== 'string' || filename.length > 1024) fail('INVALID_FILENAME', '入力ファイル名が不正です。', 400);
  if (!(Buffer.isBuffer(buffer) || buffer instanceof Uint8Array)) fail('INVALID_INPUT', '入力ファイルのバイト列を指定してください。', 400);
  const bytes = Buffer.isBuffer(buffer) ? buffer : Buffer.from(buffer);
  limit(bytes.length > settings.maxInputBytes, '入力ファイルが上限サイズを超えています。');
  if (!bytes.length) fail('EMPTY_INPUT', 'HTMLまたはZIPファイルを指定してください。', 400);
  const extension = path.extname(filename).toLowerCase();
  let files = new Map();
  let unpackedBytes = bytes.length;
  let base = 'index.html';
  let source;
  if (extension === '.zip') {
    ({ files, unpackedBytes } = await unpackZip(bytes, settings));
    if (files.has('index.html') && files.has('index.htm')) fail('AMBIGUOUS_ENTRYPOINT', 'ZIP直下のindex.htmlまたはindex.htmは1つだけにしてください。', 400);
    base = files.has('index.html') ? 'index.html' : 'index.htm';
    if (!files.has(base)) fail('HTML_ENTRYPOINT_NOT_FOUND', 'ZIP直下にindex.htmlまたはindex.htmを配置してください。', 400);
    source = utf8(files.get(base));
  } else if (['.html', '.htm'].includes(extension)) source = utf8(bytes);
  else fail('UNSUPPORTED_INPUT', 'HTML・HTM・ZIPファイルに対応しています。', 415);
  const document = parse5.parse(source);
  const state = { limits: settings, files, cssBytes: 0, imagePixels: 0, expandedResourceBytes: 0, domNodes: 0, slideCount: 0, meta: new Map() };
  await validateDocument(document, base, state);
  if (!state.slideCount) {
    const htmlElement = document.childNodes.find(n => n.tagName === 'html');
    const body = htmlElement.childNodes.find(n => n.tagName === 'body');
    set(body, 'data-slide', '');
  }
  const widthMeta = state.meta.get('pptx-slide-width');
  const heightMeta = state.meta.get('pptx-slide-height');
  if ((widthMeta === undefined) !== (heightMeta === undefined)) fail('INVALID_SLIDE_SIZE', 'スライドの幅と高さは両方指定してください。', 400);
  const dimension = (value, fallback) => {
    if (value === undefined) value = String(fallback);
    if (!/^\d+$/u.test(value)) fail('INVALID_SLIDE_SIZE', 'スライド寸法は正の整数で指定してください。', 400);
    const number = Number(value);
    if (!Number.isSafeInteger(number) || number < settings.minDimension || number > settings.maxDimension) fail('INVALID_SLIDE_SIZE', 'スライド寸法が許容範囲外です。', 400);
    return number;
  };
  const width = dimension(widthMeta, 1280);
  const height = dimension(heightMeta, 720);
  const html = parse5.serialize(document);
  // Reused data URIs can expand a small ZIP into large markup; bound the final document too.
  limit(Buffer.byteLength(html) > settings.maxUnpackedBytes, '素材を展開したHTMLのサイズが上限を超えています。');
  // Keep the already validated tree for the worker's trusted head insertion.
  // Parsing expanded, repeated image data URIs again has a much larger peak heap.
  return { document, html, width, height, slideCount: state.slideCount || 1, warnings: [], inputBytes: bytes.length, unpackedBytes, domNodes: state.domNodes };
}
