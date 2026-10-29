import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import JSZip from 'jszip';
import { DOMParser } from '@xmldom/xmldom';
import { convertHtml } from '../src/service.js';

const executablePath = process.env.HTML2PPTX_CHROMIUM_PATH || process.env.HTML2PPTX_TEST_BROWSER;
const canRun = Boolean(executablePath || process.platform === 'linux' || existsSync('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'));
const options = { skip: !canRun, timeout: 60000 };
const source = await readFile(new URL('../examples/product-dashboard.png', import.meta.url));
const imageUrl = `data:image/png;base64,${source.toString('base64')}`;
const all = (node, name) => [...node.getElementsByTagName('*')].filter(child => child.localName === name);
const picture = (alt, style = '') => `<img alt="${alt}" src="${imageUrl}" style="${style}">`;
const fixture = content => `<style>
  body{margin:0}
  section{position:relative;width:1280px;height:720px;background:white}
  .overlap{position:absolute;left:100px;top:100px;width:280px;height:160px}
  img{position:absolute;left:0;top:0;width:280px;height:160px;display:block}
  .text{position:absolute;left:20px;top:20px;width:240px;height:80px;font-size:24px;margin:0;color:#111111}
</style><section data-slide>${content}</section>`;

async function assertPaintOrder(content, markers, expected) {
  const result = await convertHtml(Buffer.from(fixture(content)), { executablePath });
  const zip = await JSZip.loadAsync(result.pptx);
  const xml = await zip.file('ppt/slides/slide1.xml').async('string');
  const doc = new DOMParser().parseFromString(xml, 'application/xml');
  const tree = all(doc, 'spTree')[0];
  assert.ok(tree, 'slide must have a drawing tree');
  const order = [];
  // DrawingML paints these direct children back-to-front. Native CSS geometry
  // can be inside a group, but must stay beside its original neighboring items.
  for (const unit of [...tree.childNodes].filter(node => node.nodeType === 1)) {
    const matches = markers.filter(marker => {
      if (marker.picture) return all(unit, 'cNvPr').some(node => node.getAttribute('descr') === marker.picture);
      if (marker.text) return all(unit, 't').some(node => node.textContent === marker.text);
      return all(unit, 'solidFill').some(fill => all(fill, 'srgbClr').some(color => color.getAttribute('val').toUpperCase() === marker.fill));
    });
    assert.ok(matches.length <= 1, `independent overlapping items were flattened into one drawing unit: ${matches.map(marker => marker.id).join(', ')}`);
    for (const marker of matches) {
      if (marker.picture) assert.equal(unit.localName, 'pic', 'original image must remain an editable picture');
      if (marker.fill) assert.equal(all(unit, 'pic').length, 0, 'colored geometry must remain editable, not a screenshot');
      order.push(marker.id);
    }
  }
  assert.deepEqual(order, expected, 'PPTX back-to-front order must match the HTML stacking order, with every item exactly once');
  assert.equal(all(doc, 'pic').length, markers.filter(marker => marker.picture).length);
}

test('overlapping pictures, native geometry and text retain their DOM paint order', options, async () => {
  await assertPaintOrder(`<div class="overlap">
    ${picture('DOM back image')}
    <div style="position:absolute;inset:0;background:#AD3D47"></div>
    ${picture('DOM front image')}
    <p class="text">DOM text</p>
  </div>`, [
    { id: 'back-image', picture: 'DOM back image' },
    { id: 'shape', fill: 'AD3D47' },
    { id: 'front-image', picture: 'DOM front image' },
    { id: 'text', text: 'DOM text' },
  ], ['back-image', 'shape', 'front-image', 'text']);
});

test('explicit z-index overrides reverse DOM order across pictures, shapes and text', options, async () => {
  await assertPaintOrder(`<div class="overlap">
    <p class="text" style="z-index:30">Z-order text</p>
    ${picture('Z-index front image', 'z-index:20')}
    <div style="position:absolute;inset:0;background:#2B846F;z-index:10"></div>
    ${picture('Z-index back image', 'z-index:0')}
  </div>`, [
    { id: 'text', text: 'Z-order text' },
    { id: 'front-image', picture: 'Z-index front image' },
    { id: 'shape', fill: '2B846F' },
    { id: 'back-image', picture: 'Z-index back image' },
  ], ['back-image', 'shape', 'front-image', 'text']);
});

test('a child at z-index 999 cannot escape a parent stacking context below another context', options, async () => {
  await assertPaintOrder(`<div class="overlap" style="z-index:2;background:#B85E32">
    <p class="text" style="z-index:1">Context B</p>
  </div>
  <div class="overlap" style="z-index:1;background:#31587A">
    ${picture('Lower context child at 999', 'z-index:999')}
  </div>`, [
    { id: 'higher-background', fill: 'B85E32' },
    { id: 'higher-text', text: 'Context B' },
    { id: 'lower-background', fill: '31587A' },
    { id: 'lower-image', picture: 'Lower context child at 999' },
  ], ['lower-background', 'lower-image', 'higher-background', 'higher-text']);
});

test('negative z-index children paint above their own stacking-context background', options, async () => {
  await assertPaintOrder(`<div class="overlap" style="z-index:0;background:#6D4FA3">
    <p class="text">Front text</p>
    ${picture('Negative child image', 'z-index:-1')}
  </div>`, [
    { id: 'background', fill: '6D4FA3' },
    { id: 'text', text: 'Front text' },
    { id: 'negative-image', picture: 'Negative child image' },
  ], ['background', 'negative-image', 'text']);
});
