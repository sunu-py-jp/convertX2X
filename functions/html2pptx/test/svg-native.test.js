import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { DOMParser } from '@xmldom/xmldom';
import puppeteer from 'puppeteer-core';
import { extractSvgScene, nativeSvgGroup, svgSceneToDrawingML } from '../src/svg-native.js';

const style = { fill: { color: 'DDEEFF', alpha: 1 }, stroke: { color: '123456', alpha: 1 }, strokeWidth: 2, dash: [], linecap: 'round', linejoin: 'round' };
const matrix = [1, 0, 0, 1, 0, 0];
const xmlDocument = (xml) => new DOMParser().parseFromString(xml, 'application/xml');
const byLocal = (doc, name) => [...doc.getElementsByTagName('*')].filter((node) => node.localName === name);

test('SVG basic shapes remain native presets and paths, with unique IDs', () => {
  const scene = { width: 400, height: 200, items: [
    { type: 'rect', matrix, bounds: { x: 10, y: 20, width: 100, height: 60 }, x: 10, y: 20, width: 100, height: 60, rx: 8, ry: 8, style },
    { type: 'ellipse', matrix, bounds: { x: 150, y: 20, width: 100, height: 60 }, cx: 200, cy: 50, rx: 50, ry: 30, style },
    { type: 'path', matrix, bounds: { x: 10, y: 100, width: 200, height: 80 }, d: 'M10 100 C40 180 160 20 210 180 Q250 180 260 120 A20 20 0 0 1 300 120', style: { ...style, fill: null } },
  ] };
  const result = svgSceneToDrawingML(scene, { x: 9525, y: 19050, width: 3810000, height: 1905000, idStart: 30 });
  const doc = xmlDocument(result.xml);
  assert.equal(byLocal(doc, 'grpSp').length, 1);
  assert.equal(byLocal(doc, 'sp').length, 3);
  assert.deepEqual(byLocal(doc, 'prstGeom').map((n) => n.getAttribute('prst')), ['roundRect', 'ellipse']);
  assert.ok(byLocal(doc, 'cubicBezTo').length >= 2);
  assert.equal(byLocal(doc, 'quadBezTo').length, 0); // Exact cubic conversion avoids lite-schema consumers failing.
  assert.equal(byLocal(doc, 'pic').length, 0);
  const ids = byLocal(doc, 'cNvPr').map((n) => n.getAttribute('id'));
  assert.equal(new Set(ids).size, ids.length);
  assert.equal(result.nextId, 34);
});

test('SVG text is escaped and remains a text body with actual font and coordinates', () => {
  const result = svgSceneToDrawingML({ width: 100, height: 100, items: [{ type: 'text', text: '設計 & API <v2>', matrix, start: { x: 10, y: 30 }, end: 90, top: 12, bottom: 35, style: { fontFamily: 'Noto Sans JP', fontSize: 20, color: '223344', alpha: 1, bold: true, italic: false, underline: false, strike: false } }] }, { width: 952500, height: 952500 });
  const doc = xmlDocument(result.xml);
  assert.equal(byLocal(doc, 't')[0].textContent, '設計 & API <v2>');
  assert.equal(byLocal(doc, 'rPr')[0].getAttribute('sz'), '1500');
  assert.equal(byLocal(doc, 'latin')[0].getAttribute('typeface'), 'Noto Sans JP');
  assert.equal(byLocal(doc, 'pic').length, 0);
});

test('linear gradient is native DrawingML fill', () => {
  const item = { type: 'rect', matrix, bounds: { x: 0, y: 0, width: 100, height: 100 }, x: 0, y: 0, width: 100, height: 100, rx: 0, ry: 0, style: { ...style, fill: { type: 'linearGradient', angle: 90, stops: [{ offset: 0, color: '000000', alpha: 1 }, { offset: 1, color: 'FFFFFF', alpha: 0.5 }] } } };
  const result = svgSceneToDrawingML({ width: 100, height: 100, items: [item] }, { width: 952500, height: 952500 });
  assert.equal(byLocal(xmlDocument(result.xml), 'gradFill').length, 1);
  assert.equal(result.counts.gradients, 1);
});

const executablePath = process.env.HTML2PPTX_TEST_BROWSER || (existsSync('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome') ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' : undefined);
test('browser extraction preserves complex chart text, two arrowheads, curves and rounded rectangles', { skip: !executablePath }, async () => {
  const browser = await puppeteer.launch({ executablePath, headless: true });
  try {
    const page = await browser.newPage(); await page.setContent('<html><body></body></html>');
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="800" height="480" viewBox="0 0 800 480"><style>text{font:20px sans-serif;fill:#234} .node{fill:#def;stroke:#234;stroke-width:2}</style><defs><marker id="arrow" viewBox="0 0 10 10" markerWidth="4" markerHeight="4" refX="9" refY="5" orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" fill="#234"/></marker><linearGradient id="g"><stop offset="0" stop-color="#fff"/><stop offset="1" stop-color="#cdf"/></linearGradient></defs><rect class="node" x="300" y="20" width="200" height="80" rx="12"/><text x="400" y="55" text-anchor="middle">最高責任者<tspan x="400" dy="26">意思決定</tspan></text><g transform="translate(20 150)"><circle class="node" cx="90" cy="40" r="35"/><ellipse class="node" cx="280" cy="40" rx="70" ry="35"/><rect x="400" y="0" width="180" height="80" fill="url(#g)"/><text x="90" y="45" text-anchor="middle">企画</text><text x="280" y="45" text-anchor="middle">開発</text></g><path d="M110 250 C200 420 500 100 600 300" fill="none" stroke="#234" stroke-width="3" marker-start="url(#arrow)" marker-end="url(#arrow)"/><polyline points="400,100 400,130 110,130 110,150" fill="none" stroke="#234" stroke-width="2"/></svg>`;
    const scene = await page.evaluate(extractSvgScene, svg);
    const result = svgSceneToDrawingML(scene, { width: 7620000, height: 4572000, idStart: 90 });
    const doc = xmlDocument(result.xml);
    assert.equal(byLocal(doc, 'pic').length, 0);
    assert.deepEqual(byLocal(doc, 't').map((t) => t.textContent), ['最高責任者', '意思決定', '企画', '開発']);
    assert.equal(result.counts.text, 4);
    assert.equal(result.counts.shapes, 8); // 4 nodes + curve + polyline + 2 arrowhead paths
    assert.equal(result.counts.gradients, 1);
    assert.ok(byLocal(doc, 'cubicBezTo').length > 0);
    assert.ok(byLocal(doc, 'prstGeom').some((n) => n.getAttribute('prst') === 'roundRect'));
    const native = await nativeSvgGroup({ page, svg, width: 7620000, height: 4572000 });
    assert.equal(native.counts.text, 4);
    await assert.rejects(nativeSvgGroup({ page, svg: '<svg xmlns="http://www.w3.org/2000/svg"><image href="https://example.com/a.png"/></svg>', width: 1000, height: 1000 }), (error) => error.code === 'UNSUPPORTED_SVG');
  } finally { await browser.close(); }
});

test('SVG external references, invalid clips and unsupported effects fail explicitly', { skip: !executablePath }, async () => {
  const browser = await puppeteer.launch({ executablePath, headless: true });
  try {
    const page = await browser.newPage(); await page.setContent('<html><body></body></html>');
    for (const fragment of ['<rect width="100" height="100" fill="url(https://example.com/paint.svg#x)"/>', '<script>alert(1)</script>', '<foreignObject/>', '<defs><clipPath id="c"><circle r="10"/></clipPath></defs><rect width="50" height="50" clip-path="url(#c)"/>']) {
      await assert.rejects(nativeSvgGroup({ page, svg: `<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">${fragment}</svg>`, width: 952500, height: 952500 }), (error) => error.code === 'UNSUPPORTED_SVG');
    }
    const fullClip = `<svg xmlns="http://www.w3.org/2000/svg" width="100" height="50"><defs><clipPath id="c"><rect width="100" height="50"/></clipPath></defs><g clip-path="url(#c)"><rect x="0" y="47" width="100" height="3" fill="#123"/></g></svg>`;
    const result = await nativeSvgGroup({ page, svg: fullClip, width: 952500, height: 476250 });
    assert.equal(result.counts.shapes, 1);
  } finally { await browser.close(); }
});

test('computed px dash patterns, nested viewBox, and rotated text preserve drawing coordinates', { skip: !executablePath }, async () => {
  const browser = await puppeteer.launch({ executablePath, headless: true });
  try {
    const page = await browser.newPage(); await page.setContent('<html><body></body></html>');
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="400" height="200"><svg x="20" y="30" width="200" height="100" viewBox="0 0 100 50"><rect x="10" y="5" width="60" height="30" rx="4" fill="#def" stroke="#234" stroke-width="2" stroke-dasharray="6 4"/><g transform="translate(80 10) rotate(90)"><text x="0" y="0" font-family="sans-serif" font-size="8">計画</text></g></svg></svg>`;
    const scene = await page.evaluate(extractSvgScene, svg);
    const rect = scene.items.find((item) => item.type === 'rect');
    assert.deepEqual(rect.bounds, { x: 40, y: 40, width: 120, height: 60 });
    assert.deepEqual(rect.style.dash, [12, 8]);
    assert.equal(rect.style.strokeWidth, 4);
    const result = svgSceneToDrawingML(scene, { width: 3810000, height: 1905000 });
    const doc = xmlDocument(result.xml);
    assert.equal(byLocal(doc, 'custDash').length, 0);
    assert.ok(byLocal(doc, 'moveTo').length > 10); // Arbitrary dash pattern remains editable geometry across renderers.
    assert.ok(byLocal(doc, 'path').some((node) => node.getAttribute('stroke') === '0')); // Separate background fill in the same shape.
    assert.ok(byLocal(doc, 'xfrm').some((x) => x.getAttribute('rot') === '5400000'));
    assert.equal(byLocal(doc, 't')[0].textContent, '計画');
  } finally { await browser.close(); }
});

test('curve markers follow endpoint tangents and auto-start-reverse supports both arrow ends', { skip: !executablePath }, async () => {
  const browser = await puppeteer.launch({ executablePath, headless: true });
  try {
    const page = await browser.newPage(); await page.setContent('<html><body></body></html>');
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="300" height="220"><defs><marker id="m" markerWidth="10" markerHeight="10" refX="10" refY="5" markerUnits="userSpaceOnUse" orient="auto-start-reverse" fill="#d34"><path d="M0 0L10 5L0 10Z"/></marker></defs><path d="M50 100C50 20 250 20 250 100" fill="none" stroke="#123" stroke-width="2" marker-start="url(#m)" marker-end="url(#m)"/></svg>`;
    const scene = await page.evaluate(extractSvgScene, svg);
    assert.equal(scene.items.length, 3);
    for (const arrow of scene.items.slice(1)) {
      // Both endpoints point down, and the marker tip meets the actual path endpoint.
      assert.ok(arrow.matrix[1] > 0.99);
      assert.ok(Math.abs(arrow.bounds.y + arrow.bounds.height - 100) < 0.02);
      assert.equal(arrow.style.fill.color, 'DD3344');
    }
    const start = scene.items[1], end = scene.items[2];
    assert.ok(Math.abs(start.bounds.x + start.bounds.width / 2 - 50) < 0.02);
    assert.ok(Math.abs(end.bounds.x + end.bounds.width / 2 - 250) < 0.02);
    await assert.rejects(nativeSvgGroup({ page, svg: svg.replace('marker-end="url(#m)"', 'marker-mid="url(#m)"'), width: 2857500, height: 2095500 }), (e) => e.code === 'UNSUPPORTED_SVG');
  } finally { await browser.close(); }
});

test('unsupported text mirroring and non-identity clipping fail instead of silently losing meaning', { skip: !executablePath }, async () => {
  const browser = await puppeteer.launch({ executablePath, headless: true });
  try {
    const page = await browser.newPage(); await page.setContent('<html><body></body></html>');
    for (const content of [
      '<g transform="translate(200 0) scale(-1 1)"><text x="20" y="40">計画</text></g>',
      '<g style="filter:blur(2px)"><rect width="40" height="40"/></g>',
      '<defs><clipPath id="c"><rect width="20" height="20"/></clipPath></defs><rect width="100" height="100" clip-path="url(#c)"/>',
    ]) await assert.rejects(nativeSvgGroup({ page, svg: `<svg xmlns="http://www.w3.org/2000/svg" width="300" height="200">${content}</svg>`, width: 2857500, height: 1905000 }), (e) => e.code === 'UNSUPPORTED_SVG');
  } finally { await browser.close(); }
});

test('SVG isolated extractor rejects mixed namespaces, escaped references and expanding use graphs', { skip: !executablePath }, async () => {
  const browser = await puppeteer.launch({ executablePath, headless: true });
  try {
    const page = await browser.newPage(); await page.setContent('<html><body></body></html>');
    const unsafe = [
      '<style xmlns="http://www.w3.org/1999/xhtml">body {background:red}</style>',
      '<style>svg { background-image:image-set("https://example.com/a.png" 1x) }</style>',
      '<rect width="20" height="20" fill="u\\72l(https://example.com/a.svg)"/>',
      '<defs><g id="cycle"><use href="#cycle"/><use href="#cycle"/></g></defs><use href="#cycle"/>',
    ];
    for (const content of unsafe) await assert.rejects(nativeSvgGroup({ page, svg: `<svg xmlns="http://www.w3.org/2000/svg" width="300" height="200">${content}</svg>`, width: 2857500, height: 1905000 }), (e) => e.code === 'UNSUPPORTED_SVG');
    await assert.rejects(nativeSvgGroup({ page, svg: '<!DOCTYPE svg [<!ENTITY x "test">]><svg xmlns="http://www.w3.org/2000/svg"><text>&x;</text></svg>', width: 2857500, height: 1905000 }), (e) => e.code === 'UNSUPPORTED_SVG');
  } finally { await browser.close(); }
});

test('SVG viewport clipping is explicit, including nested viewBox and overflowing text', { skip: !executablePath }, async () => {
  const browser = await puppeteer.launch({ executablePath, headless: true });
  try {
    const page = await browser.newPage(); await page.setContent('<html><body></body></html>');
    const clipped = [
      '<rect x="90" y="10" width="50" height="30"/>',
      '<text x="95" y="30" font-size="20">outside</text>',
      '<svg x="10" y="10" width="50" height="50" viewBox="0 0 100 100"><rect x="90" y="10" width="50" height="30"/></svg>',
      '<svg x="10" y="10" width="60" height="30" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid slice"><rect width="100" height="100"/></svg>',
    ];
    for (const content of clipped) await assert.rejects(nativeSvgGroup({ page, svg: `<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">${content}</svg>`, width: 952500, height: 952500 }), (e) => e.code === 'UNSUPPORTED_SVG' && /viewport/.test(e.message));
    const valid = [
      '<rect width="100" height="100" fill="none" stroke="#123" stroke-width="2"/>',
      '<svg x="10" y="10" width="60" height="30" viewBox="0 0 100 100"><rect width="100" height="100"/></svg>',
      '<svg x="10" y="10" width="50" height="50" viewBox="0 0 100 100" overflow="visible"><rect x="90" y="10" width="50" height="30"/></svg>',
      '<svg x="10" y="10" width="80" height="80" viewBox="50 50 100 100"><rect x="50" y="50" width="100" height="100" fill="none" stroke="#123" stroke-width="2"/></svg>',
    ];
    for (const content of valid) {
      const result = await nativeSvgGroup({ page, svg: `<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">${content}</svg>`, width: 952500, height: 952500 });
      assert.equal(result.counts.shapes, 1);
    }
  } finally { await browser.close(); }
});

test('invisible SVG text and tspans are not turned into visible black text', { skip: !executablePath }, async () => {
  const browser = await puppeteer.launch({ executablePath, headless: true });
  try {
    const page = await browser.newPage(); await page.setContent('<html><body></body></html>');
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="300" height="100"><text x="10" y="40" font-size="12"><tspan>A</tspan><tspan visibility="hidden">B</tspan><tspan opacity="0">C</tspan><tspan fill="none">D</tspan><tspan fill="transparent">E</tspan><tspan>F</tspan></text><text x="390" y="80" fill="none">invisible</text></svg>`;
    const result = await nativeSvgGroup({ page, svg, width: 2857500, height: 952500 });
    assert.deepEqual(byLocal(xmlDocument(result.xml), 't').map((node) => node.textContent), ['A', 'F']);
  } finally { await browser.close(); }
});

test('arbitrary dash offset becomes editable path pieces without a raster or extra shapes', () => {
  const line = { type: 'path', matrix, bounds: { x: 10, y: 10, width: 80, height: 0 }, d: 'M10 10H90', style: { ...style, fill: null, strokeWidth: 2, dash: [6, 4], dashRaw: [6, 4], dashOffset: 5, linecap: 'butt' } };
  const result = svgSceneToDrawingML({ width: 100, height: 100, items: [line] }, { width: 952500, height: 952500 });
  const doc = xmlDocument(result.xml), path = byLocal(doc, 'path')[0];
  const commands = [...path.childNodes].filter((node) => node.nodeType === 1);
  const xAt = (i) => Number(byLocal(commands[i], 'pt')[0].getAttribute('x'));
  assert.equal(xAt(0), 0);
  assert.equal(xAt(1), 9525); // A positive 5px offset leaves 1px of the first 6px dash.
  assert.equal(xAt(2), 47625); // Then the 4px gap ends at x=15.
  assert.equal(byLocal(doc, 'sp').length, 1);
  assert.equal(byLocal(doc, 'pic').length, 0);
  assert.equal(byLocal(doc, 'custDash').length, 0);
  assert.equal(result.warnings.some((w) => w.code === 'SVG_DASH_OFFSET'), false);
});

test('matching standard dash patterns remain native line properties', () => {
  const line = { type: 'line', matrix, bounds: { x: 10, y: 10, width: 80, height: 0 }, d: 'M10 10H90', style: { ...style, fill: null, strokeWidth: 2, dash: [4, 4], dashRaw: [4, 4] } };
  const result = svgSceneToDrawingML({ width: 100, height: 100, items: [line] }, { width: 952500, height: 952500 });
  assert.equal(byLocal(xmlDocument(result.xml), 'prstDash')[0].getAttribute('val'), 'sysDash');
});

test('generic polygons, concave shapes, affine transforms and percentage geometry remain editable', { skip: !executablePath }, async () => {
  const browser = await puppeteer.launch({ executablePath, headless: true });
  try {
    const page = await browser.newPage(); await page.setContent('<html><body></body></html>');
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="800" height="300"><g fill="#cde" stroke="#345" stroke-width="2"><polygon points="80,30 120,120 30,120" transform="rotate(12 80 80)"/><polygon points="230,20 245,60 290,65 255,95 265,140 230,115 190,140 200,95 170,65 215,60"/><path d="M350 30H450V60H390V140H350Z" transform="skewX(12)"/><rect x="65%" y="15%" width="20%" height="30%" rx="2%" transform="rotate(-10 600 100)"/><path d="M40 220 Q100 150 160 220 T280 220 C330 280 390 150 450 220" fill="none" stroke-dasharray="7 3 2 3" stroke-dashoffset="-4"/></g></svg>`;
    const scene = await page.evaluate(extractSvgScene, svg);
    const rect = scene.items.find((item) => item.type === 'rect');
    assert.equal(rect.x, 520); assert.equal(rect.y, 45); assert.equal(rect.width, 160); assert.equal(rect.height, 90); assert.equal(rect.rx, 16);
    const result = svgSceneToDrawingML(scene, { width: 7620000, height: 2857500 });
    const doc = xmlDocument(result.xml);
    assert.ok(result.counts.shapes >= 5); // Skewed stroke is a separate native filled outline.
    assert.equal(byLocal(doc, 'pic').length, 0);
    assert.ok(byLocal(doc, 'custGeom').length >= 5);
    assert.ok(byLocal(doc, 'moveTo').length > 20);
    assert.equal(byLocal(doc, 'quadBezTo').length, 0);
  } finally { await browser.close(); }
});

test('zero-length round dots are native filled outlines instead of discarded line caps', () => {
  const line = { type: 'line', matrix, bounds: { x: 10, y: 10, width: 80, height: 0 }, d: 'M10 10H90', style: { ...style, fill: null, strokeWidth: 3, strokeWidthRaw: 3, dash: [0, 6], dashRaw: [0, 6], linecap: 'round' } };
  const result = svgSceneToDrawingML({ width: 100, height: 100, items: [line] }, { width: 952500, height: 952500 });
  const doc = xmlDocument(result.xml);
  assert.equal(byLocal(doc, 'sp').length, 1);
  assert.equal(byLocal(doc, 'pic').length, 0);
  assert.ok(byLocal(doc, 'close').length >= 14);
  const spPr = byLocal(doc, 'spPr')[0];
  assert.ok([...spPr.childNodes].some((node) => node.localName === 'solidFill'));
  assert.ok(byLocal(doc, 'ln').every((node) => byLocal(node, 'noFill').length === 1));
});

test('nonuniform and skew stroke widths become affine filled outlines, not average widths', () => {
  const line = { type: 'line', matrix: [2, 0, 0, 1, 0, 0], bounds: { x: 20, y: 10, width: 0, height: 10 }, d: 'M10 10V20', style: { ...style, fill: null, strokeWidth: 4 * Math.SQRT2, strokeWidthRaw: 4, linecap: 'butt' } };
  const result = svgSceneToDrawingML({ width: 100, height: 100, items: [line] }, { width: 952500, height: 952500 });
  const doc = xmlDocument(result.xml), transform = byLocal(doc, 'spPr')[0].getElementsByTagName('a:xfrm')[0];
  const off = byLocal(transform, 'off')[0], ext = byLocal(transform, 'ext')[0];
  assert.equal(Number(off.getAttribute('x')), 16 * 9525);
  assert.equal(Number(ext.getAttribute('cx')), 8 * 9525);
  assert.equal(Number(off.getAttribute('y')), 10 * 9525);
  assert.equal(Number(ext.getAttribute('cy')), 10 * 9525);
  assert.equal(byLocal(doc, 'pic').length, 0);
});

test('CSS-transformed local use preserves its origin, x/y and referenced transform', { skip: !executablePath }, async () => {
  const browser = await puppeteer.launch({ executablePath, headless: true });
  try {
    const page = await browser.newPage(); await page.setContent('<html><body></body></html>');
    const wrap = (content) => `<svg xmlns="http://www.w3.org/2000/svg" width="300" height="200">${content}</svg>`;
    const referenced = '<rect width="10" height="20" transform="translate(4 3)"/>';
    const svg = wrap(`<defs><g id="tile">${referenced}</g></defs><g transform="translate(20 10)"><use href="#tile" x="30" y="40" style="transform:rotate(10deg);transform-origin:50px 50px"/></g>`);
    const expected = wrap(`<g transform="translate(20 10)"><g style="transform:rotate(10deg);transform-origin:50px 50px"><g transform="translate(30 40)">${referenced}</g></g></g>`);
    const actualScene = await page.evaluate(extractSvgScene, svg), expectedScene = await page.evaluate(extractSvgScene, expected);
    assert.equal(actualScene.items.length, 1);
    for (let i = 0; i < 6; i++) assert.ok(Math.abs(actualScene.items[0].matrix[i] - expectedScene.items[0].matrix[i]) < 1e-7);
    const identity = await page.evaluate(extractSvgScene, wrap(`<defs><rect id="r" width="10" height="20"/></defs><use href="#r" x="30" y="40" style="transform:none!important"/>`));
    assert.equal(identity.items[0].bounds.x, 30); assert.equal(identity.items[0].bounds.y, 40);
  } finally { await browser.close(); }
});

test('thick affine curves and dotted ellipses resolve overlapping stroke polygons', { skip: !executablePath }, async () => {
  const browser = await puppeteer.launch({ executablePath, headless: true });
  try {
    const page = await browser.newPage(); await page.setContent('<html><body></body></html>');
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="800" height="350"><g transform="translate(30 50) scale(1.8 .7)" fill="none" stroke="#8154aa" stroke-width="12" stroke-linecap="round" stroke-linejoin="round" stroke-dasharray="9 9"><path d="M10 180C10 10 180 10 180 180"/></g><g transform="translate(450 50) scale(1.8 .6)" fill="none" stroke="#8154aa" stroke-width="10" stroke-linecap="round" stroke-dasharray="0 14"><ellipse cx="80" cy="100" rx="70" ry="85"/></g></svg>`;
    const result = await nativeSvgGroup({ page, svg, width: 7620000, height: 3333750 });
    const doc = xmlDocument(result.xml);
    assert.equal(result.counts.shapes, 2); assert.equal(result.counts.images, 0);
    assert.ok(byLocal(doc, 'close').length > 25);
    assert.ok(byLocal(doc, 'ln').every((node) => byLocal(node, 'noFill').length === 1));
    assert.ok(!/NaN|Infinity/.test(result.xml));
  } finally { await browser.close(); }
});
