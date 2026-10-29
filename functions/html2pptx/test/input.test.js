import test from 'node:test';
import assert from 'node:assert/strict';
import JSZip from 'jszip';
import * as parse5 from 'parse5';
import { prepareInput } from '../src/input.js';

const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2nTQAAAAASUVORK5CYII=', 'base64');
const PNG_URI = `data:image/png;base64,${PNG.toString('base64')}`;
const html = value => Buffer.from(value);
async function zip(files, options = {}) {
  const z = new JSZip();
  for (const [name, content] of Object.entries(files)) z.file(name, content);
  return z.generateAsync({ type: 'nodebuffer', compression: 'DEFLATE', ...options });
}
const rejects = (promise, code) => assert.rejects(promise, error => error.code === code);
function elements(node, tag) {
  return [...(node.tagName === tag ? [node] : []), ...(node.childNodes ?? []).flatMap(n => elements(n, tag))];
}

test('plain HTML becomes a single slide, preserving editable SVG and text', async () => {
  const result = await prepareInput(html('<h1>経営方針</h1><svg width="300" height="100"><rect width="120" height="60" rx="10" fill="#036"/><text x="10" y="30">代表取締役</text></svg>'));
  assert.equal(result.slideCount, 1);
  assert.equal(result.width, 1280);
  assert.equal(result.height, 720);
  assert.match(result.html, /<body data-slide="">/u);
  assert.match(result.html, /<text x="10" y="30">代表取締役<\/text>/u);
});

test('explicit slides and common CSS layouts remain supported', async () => {
  const result = await prepareInput(html('<meta name="pptx-slide-width" content="1600"><meta name="pptx-slide-height" content="900"><style>:root { --brand: #124; }.grid {display:grid;grid-template-columns:repeat(3,1fr);gap:16px;background:linear-gradient(90deg,#eee,#fff)}.card{border-radius:12px;color:var(--brand)}</style><section data-slide class="grid"><div class="card">売上</div></section><section data-slide>利益</section>'));
  assert.equal(result.slideCount, 2);
  assert.equal(result.width, 1600);
  assert.equal(result.height, 900);
  assert.match(result.html, /grid-template-columns:repeat\(3,1fr\)/u);
});

test('ZIP assets and CSS paths resolve relative to each file and become data URIs', async () => {
  const input = await zip({
    'index.html': '<link rel="stylesheet" href="css/slides.css"><img src="images/logo.png"><p>内容</p>',
    'css/slides.css': '.hero{background-image:url(../images/logo.png)}',
    'images/logo.png': PNG,
  });
  const result = await prepareInput(input, { filename: 'slides.zip' });
  assert.match(result.html, /<style>/u);
  assert.match(result.html, /background-image:url\(data:image\/png;base64,/u);
  assert.match(result.html, /<img src="data:image\/png;base64,/u);
  assert.doesNotMatch(result.html, /href="css/u);
});

test('SVG image assets become editable inline SVG, respecting HTML display attributes', async () => {
  const input = await zip({ 'index.html': '<img src="diagram.svg" width="640" height="240" class="diagram">', 'diagram.svg': '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 120"><circle cx="60" cy="60" r="50"/><text x="40" y="60">東京</text></svg>' });
  const result = await prepareInput(input, { filename: 'diagram.zip' });
  assert.match(result.html, /<svg[^>]*width="640"[^>]*height="240"[^>]*class="diagram"/u);
  assert.doesNotMatch(result.html, /<img/u);
  assert.match(result.html, /<text x="40" y="60">東京<\/text>/u);
});

test('local SVG markers and use references are retained', async () => {
  const result = await prepareInput(html('<svg><defs><marker id="arrow"><path d="M0 0L10 5L0 10Z"/></marker><circle id="node" r="10"/></defs><use href="#node" x="20"/><line x1="0" y1="0" x2="100" y2="20" marker-start="url(#arrow)" marker-end="url(#arrow)"/></svg>'));
  assert.match(result.html, /href="#node"/u);
  assert.match(result.html, /marker-end="url\(#arrow\)"/u);
});

test('external hyperlinks are preserved without resolving them', async () => {
  const result = await prepareInput(html('<a href="https://example.com/docs">API仕様</a><a href="mailto:test@example.com">連絡先</a>'));
  assert.match(result.html, /https:\/\/example.com\/docs/u);
});

for (const [name, source, code] of [
  ['script', '<script>alert(1)</script>', 'UNSUPPORTED_HTML'],
  ['SVG script', '<svg><script>alert(1)</script></svg>', 'UNSUPPORTED_SVG'],
  ['event attribute', `<img src="${PNG_URI}" onerror="alert(1)">`, 'ACTIVE_CONTENT_FORBIDDEN'],
  ['iframe', '<iframe srcdoc="<h1>x</h1>"></iframe>', 'UNSUPPORTED_HTML'],
  ['base URL', '<base href="https://example.com">', 'UNSUPPORTED_HTML'],
  ['meta refresh', '<meta http-equiv="refresh" content="0;url=https://example.com">', 'ACTIVE_CONTENT_FORBIDDEN'],
  ['form', '<form><input></form>', 'UNSUPPORTED_HTML'],
  ['image network request', '<img src="https://example.com/pixel.png">', 'EXTERNAL_RESOURCE_FORBIDDEN'],
  ['loopback image request', '<img src="http://127.0.0.1:7071/api/secret">', 'EXTERNAL_RESOURCE_FORBIDDEN'],
  ['local file request', '<img src="file:///etc/passwd">', 'EXTERNAL_RESOURCE_FORBIDDEN'],
  ['CSS network request', '<style>.x{background:url(https://example.com/a.png)}</style>', 'EXTERNAL_RESOURCE_FORBIDDEN'],
  ['CSS import', '<style>@import "https://example.com/x.css";</style>', 'UNSUPPORTED_CSS'],
  ['CSS image-set string request', '<style>.x{background-image:image-set("https://example.com/a.png" 1x)}</style>', 'UNSUPPORTED_CSS'],
  ['escaped CSS URL function', '<style>.x{background:u\\72l("https://example.com/a.png")}</style>', 'UNSUPPORTED_CSS'],
  ['custom property network request', '<style>:root{--image:url(https://example.com/a.png)}.x{background:var(--image)}</style>', 'EXTERNAL_RESOURCE_FORBIDDEN'],
  ['external font', '<style>@font-face{font-family:x;src:url(font.ttf)}</style>', 'UNSUPPORTED_CSS'],
  ['animation', '<div style="animation:spin 1s">x</div>', 'UNSUPPORTED_CSS'],
  ['transition', '<div style="transition:color 1s">x</div>', 'UNSUPPORTED_CSS'],
  ['SVG external use', '<svg><use href="https://example.com/a.svg#x"/></svg>', 'EXTERNAL_RESOURCE_FORBIDDEN'],
  ['SVG foreignObject', '<svg><foreignObject><div>content</div></foreignObject></svg>', 'UNSUPPORTED_SVG'],
  ['SVG image reference', `<svg><image href="${PNG_URI}"/></svg>`, 'UNSUPPORTED_SVG'],
  ['unsafe hyperlink', '<a href="javascript:alert(1)">x</a>', 'UNSUPPORTED_LINK'],
  ['library image fallback control', '<div data-pptx-render="image">x</div>', 'UNSUPPORTED_HTML_ATTRIBUTE'],
  ['library raster override', '<div data-html2canvas-ignore="true">x</div>', 'UNSUPPORTED_HTML_ATTRIBUTE'],
]) {
  test(`rejects ${name}`, async () => rejects(prepareInput(html(source)), code));
}

test('CSS embedded from ZIP cannot break out of its style element', async () => {
  const input = await zip({ 'index.html': '<link rel="stylesheet" href="x.css"><div>安全な本文</div>', 'x.css': '.x:before{content:"</style><img src=https://example.com/pixel.png><script>alert(1)</script>"}' });
  const result = await prepareInput(input, { filename: 'input.zip' });
  const document = parse5.parse(result.html);
  assert.equal(elements(document, 'script').length, 0);
  assert.equal(elements(document, 'img').length, 0);
  assert.equal(elements(document, 'style').length, 1);
  assert.match(result.html, /\\3c \/style>/u);
});

test('SVG assets cannot introduce unvalidated active content', async () => {
  const input = await zip({ 'index.html': '<img src="diagram.svg">', 'diagram.svg': '<svg xmlns="http://www.w3.org/2000/svg" onload="fetch(\'https://example.com\')"><circle r="10"/></svg>' });
  await rejects(prepareInput(input, { filename: 'input.zip' }), 'ACTIVE_CONTENT_FORBIDDEN');
});

test('missing resources, entrypoint ambiguity and wrong types are explicit', async () => {
  await rejects(prepareInput(html('<img src="missing.png">')), 'ASSET_NOT_FOUND');
  await rejects(prepareInput(await zip({ 'a.html': 'x' }), { filename: 'input.zip' }), 'HTML_ENTRYPOINT_NOT_FOUND');
  await rejects(prepareInput(await zip({ 'index.html': 'x', 'index.htm': 'x' }), { filename: 'input.zip' }), 'AMBIGUOUS_ENTRYPOINT');
  await rejects(prepareInput(html('x'), { filename: 'input.txt' }), 'UNSUPPORTED_INPUT');
  await rejects(prepareInput(Buffer.from([0xff, 0xfe])), 'INVALID_UTF8');
});

test('dimensions must be complete, bounded and non-repeated', async () => {
  for (const source of [
    '<meta name="pptx-slide-width" content="1000">',
    '<meta name="pptx-slide-width" content="1000"><meta name="pptx-slide-height" content="999999">',
    '<meta name="pptx-slide-width" content="1000"><meta name="pptx-slide-width" content="1000"><meta name="pptx-slide-height" content="720">',
  ]) await rejects(prepareInput(html(source)), 'INVALID_SLIDE_SIZE');
});

test('nested slides and excessive slides are rejected', async () => {
  await rejects(prepareInput(html('<section data-slide><div data-slide>nested</div></section>')), 'NESTED_SLIDES');
  await rejects(prepareInput(html('<section data-slide>A</section><section data-slide>B</section>'), { limits: { maxSlides: 1 } }), 'INPUT_LIMIT_EXCEEDED');
});

test('input, DOM, depth, CSS and decoded image budgets are enforced', async () => {
  await rejects(prepareInput(html('<p>x</p>'), { limits: { maxInputBytes: 3 } }), 'INPUT_LIMIT_EXCEEDED');
  await rejects(prepareInput(html('<p>x</p>'), { limits: { maxDomNodes: 4 } }), 'INPUT_LIMIT_EXCEEDED');
  await rejects(prepareInput(html('<div><div><div><p>x</p></div></div></div>'), { limits: { maxDomDepth: 3 } }), 'INPUT_LIMIT_EXCEEDED');
  await rejects(prepareInput(html('<style>.x{color:red}</style>'), { limits: { maxCssBytes: 5 } }), 'INPUT_LIMIT_EXCEEDED');
  const huge = Buffer.from(PNG); huge.writeUInt32BE(100_000, 16); huge.writeUInt32BE(100_000, 20);
  await rejects(prepareInput(html(`<img src="data:image/png;base64,${huge.toString('base64')}">`)), 'INPUT_LIMIT_EXCEEDED');
});

test('ZIP actual expansion and entry counts are bounded', async () => {
  const input = await zip({ 'index.html': '<p>' + 'a'.repeat(10000) + '</p>' });
  await rejects(prepareInput(input, { filename: 'input.zip', limits: { maxUnpackedBytes: 500 } }), 'INPUT_LIMIT_EXCEEDED');
  await rejects(prepareInput(await zip({ 'index.html': '<p>x</p>', 'a.css': 'a{}' }), { filename: 'input.zip', limits: { maxFiles: 1 } }), 'INPUT_LIMIT_EXCEEDED');
});

test('repeated resource inlining cannot exceed the expanded document budget', async () => {
  const input = await zip({ 'index.html': '<img src="pixel.png">'.repeat(100), 'pixel.png': PNG });
  await rejects(prepareInput(input, { filename: 'input.zip', limits: { maxUnpackedBytes: 5000 } }), 'INPUT_LIMIT_EXCEEDED');
});

test('traversal, duplicate names, symlinks and nested archives are rejected', async () => {
  await assert.rejects(prepareInput(await zip({ 'index.html': 'x', '../escape.png': PNG }), { filename: 'input.zip' }), e => ['INVALID_ZIP', 'INVALID_ARCHIVE_PATH'].includes(e.code));
  const duplicate = await zip({ 'index.html': 'x', 'aa.css': 'a{}', 'bb.css': 'b{}' });
  for (let offset = duplicate.indexOf('bb.css'); offset !== -1; offset = duplicate.indexOf('bb.css')) duplicate.write('aa.css', offset);
  await rejects(prepareInput(duplicate, { filename: 'input.zip' }), 'DUPLICATE_ARCHIVE_PATH');
  const z = new JSZip(); z.file('index.html', 'x'); z.file('link.png', 'target', { unixPermissions: 0o120777 });
  await rejects(prepareInput(await z.generateAsync({ type: 'nodebuffer', platform: 'UNIX' }), { filename: 'input.zip' }), 'UNSUPPORTED_ZIP_ENTRY');
  await rejects(prepareInput(await zip({ 'index.html': 'x', 'inner.zip': 'x' }), { filename: 'input.zip' }), 'UNSUPPORTED_ASSET');
});

test('PNG MIME mismatches and non-image payloads are rejected', async () => {
  await rejects(prepareInput(html(`<img src="data:image/jpeg;base64,${PNG.toString('base64')}">`)), 'INVALID_IMAGE');
  await rejects(prepareInput(html('<img src="data:image/png;base64,YWJj">')), 'INVALID_IMAGE');
});

test('aggregate raster pixel budget counts repeated placements',async()=>{
  await rejects(prepareInput(html(`<img src="${PNG_URI}"><img src="${PNG_URI}">`),{limits:{maxTotalImagePixels:1}}),'INPUT_LIMIT_EXCEEDED');
});
