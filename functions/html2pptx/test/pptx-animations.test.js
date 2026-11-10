import test from 'node:test';
import assert from 'node:assert/strict';
import { DOMParser, XMLSerializer } from '@xmldom/xmldom';
import { applyNativeAnimations } from '../src/pptx-animations.js';

const P = 'http://schemas.openxmlformats.org/presentationml/2006/main';
const A = 'http://schemas.openxmlformats.org/drawingml/2006/main';
const all = (node, name) => [...node.getElementsByTagName('*')].filter(el => el.localName === name);
const xml = node => new XMLSerializer().serializeToString(node);
const shape = (id, text = `Item ${id}`) => `<p:sp><p:nvSpPr><p:cNvPr id="${id}" name="${text}"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="952500" y="952500"/><a:ext cx="1905000" cy="952500"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>${text}</a:t></a:r></a:p></p:txBody></p:sp>`;
const fixture = (items = shape(10) + shape(11) + shape(20)) => new DOMParser().parseFromString(`<p:sld xmlns:p="${P}" xmlns:a="${A}"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>${items}</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr><p:extLst/></p:sld>`, 'application/xml');
const shapes = doc => all(doc, 'sp');
const effect = (id, extra = {}) => ({ id, slide: 1, animationName: 'custom-name', durationMs: 1000, delayMs: 400,
  bounds: { x: 100, y: 100, width: 200, height: 100 }, origin: { x: 200, y: 150 },
  keyframes: [{ offset: 0, opacity: 0, x: -100 }, { offset: 0.5, opacity: 0.8, x: -10 }, { offset: 1, opacity: 1, x: 0 }], ...extra });
const run = (doc, effects, targets) => applyNativeAnimations({ document: doc, effects, targets, slideWidthPx: 1000, slideHeightPx: 500 });

test('groups complete editable paint fragments without moving them and targets only finalized IDs', () => {
  const doc = fixture(), [back, label, other] = shapes(doc);
  const before = [xml(back), xml(label), xml(other)];
  const result = run(doc, [effect('card'), effect('another', { delayMs: 1200 })], new Map([['card', [back, label]], ['another', [other]]]));
  assert.equal(result.nativeEffects.length, 2);
  assert.equal(result.fallbackEffects.length, 0);
  assert.equal(result.groupsCreated, 2);
  assert.deepEqual([xml(back), xml(label), xml(other)], before, 'text, geometry and native object properties must remain unchanged');
  const groups = all(doc, 'grpSp');
  assert.equal(back.parentNode, groups[0]);
  assert.equal(label.parentNode, groups[0]);
  assert.equal(other.parentNode, groups[1]);
  assert.deepEqual(all(doc, 't').map(node => node.textContent), ['Item 10', 'Item 11', 'Item 20']);
  assert.equal(all(doc, 'pic').length, 0);
  const ids = all(doc, 'cNvPr').map(node => node.getAttribute('id'));
  assert.equal(new Set(ids).size, ids.length);
  assert.deepEqual(result.nativeEffects.map(item => item.targetShapeId), ['21', '22']);
  assert.ok(all(doc, 'spTgt').every(node => ['21', '22'].includes(node.getAttribute('spid'))));
  const groupTransform = all(groups[0], 'xfrm')[0];
  assert.equal(all(groupTransform, 'off')[0].getAttribute('x'), all(groupTransform, 'chOff')[0].getAttribute('x'));
  assert.equal(all(groupTransform, 'ext')[0].getAttribute('cx'), all(groupTransform, 'chExt')[0].getAttribute('cx'));
  assert.equal(all(doc, 'timing')[0].nextSibling.localName, 'extLst');
  const timeIds = all(doc, 'cTn').map(node => node.getAttribute('id'));
  assert.equal(new Set(timeIds).size, timeIds.length);
  assert.deepEqual(timeIds, timeIds.map((_, index) => String(index + 1)), 'time IDs must follow root-first document order');
  assert.equal(all(doc, 'seq').length, 1);
  assert.equal(all(doc, 'cTn').filter(node => node.getAttribute('nodeType') === 'withEffect').length, 2);
  for (const fade of all(doc, 'animEffect')) assert.equal(fade.getAttribute('filter'), 'fade');
  assert.deepEqual(all(doc, 'animEffect').map(fade => all(fade, 'cond')[0].getAttribute('delay')), ['400', '1200']);
  assert.ok(!all(doc, 'cond').some(node => node.getAttribute('delay') === 'indefinite'), 'no input click should be required');
  assert.ok(!xml(doc).includes('style.opacity'), 'opacity must use a real fade, not a silently ignored opacity tween');
});

test('sampled paths, scale and rotation preserve timing relative to exported final geometry', () => {
  const doc = fixture(), target = shapes(doc)[0];
  const keyframes = [
    { offset: 0, x: -100, y: 50, scaleX: 0.5, scaleY: 0.25, rotation: -90 },
    { offset: 0.5, x: 20, y: -10, scaleX: 1.2, scaleY: 1.1, rotation: 15 },
    { offset: 1, x: 0, y: 0, scaleX: 1, scaleY: 1, rotation: 0 },
  ];
  const result = run(doc, [effect('path', { keyframes, delayMs: 1000 })], new Map([['path', [target]]]));
  assert.equal(result.nativeEffects.length, 1);
  const animations = all(doc, 'anim');
  assert.equal(animations.length, 4);
  assert.ok(animations.every(node => node.getAttribute('calcmode') === 'lin' && !node.hasAttribute('calcMode')), 'PresentationML uses the lowercase calcmode attribute');
  const values = property => all(animations.find(node => all(node, 'attrName')[0].textContent === property), 'strVal').map(node => node.getAttribute('val'));
  assert.deepEqual(values('ppt_x'), ['#ppt_x-0.1', '#ppt_x-0.1', '#ppt_x+0.02', '#ppt_x+0']);
  assert.deepEqual(values('ppt_y'), ['#ppt_y+0.1', '#ppt_y+0.1', '#ppt_y-0.02', '#ppt_y+0']);
  assert.deepEqual(values('ppt_w'), ['#ppt_w*0.5', '#ppt_w*0.5', '#ppt_w*1.2', '#ppt_w*1']);
  assert.deepEqual(all(animations[0], 'tav').map(node => node.getAttribute('tm')), ['0', '50000', '75000', '100000']);
  const rotations = all(doc, 'animRot');
  assert.deepEqual(rotations.map(node => [node.getAttribute('from'), node.getAttribute('to')]), [['-5400000', '-5400000'], ['-5400000', '900000'], ['900000', '0']]);
  assert.deepEqual(rotations.map(node => all(node, 'cond')[0].getAttribute('delay')), ['0', '1000', '1500']);
  assert.equal(all(doc, 'animEffect').length, 0);
});

test('interleaved fragments never reorder unrelated shapes to make an animation group', () => {
  const doc = fixture(), [a, middle, b] = shapes(doc), before = xml(doc);
  const sourceAnimations = [{id:'fade',animationName:'fade',durationMs:500,delayMs:0},{id:'move',animationName:'move',durationMs:1000,delayMs:0}];
  const result = run(doc, [effect('unsafe', {sourceAnimations})], new Map([['unsafe', [a, b]]]));
  assert.equal(result.nativeEffects.length, 0);
  assert.equal(result.fallbackEffects[0].reason, 'interleaved_target');
  assert.equal(xml(doc), before);
  assert.equal(middle.parentNode.localName, 'spTree');
  assert.equal(result.warnings[0].code, 'ANIMATION_STATIC_FALLBACK');
  assert.deepEqual(result.fallbackEffects[0].sourceAnimations, sourceAnimations, 'all combined sources must remain traceable after writer-level fallback');
});

test('shared targets fall back for every owner and cannot depend on iteration order', () => {
  const doc = fixture(), [a, b] = shapes(doc), before = xml(doc);
  const result = run(doc, [effect('parent'), effect('child')], new Map([['parent', [a, b]], ['child', [b]]]));
  assert.deepEqual(result.fallbackEffects.map(item => item.reason), ['shared_target', 'shared_target']);
  assert.equal(xml(doc), before);
});

test('unsupported opacity, pivot, final base and malformed frames remain static with a reason', () => {
  for (const [extra, reason] of [
    [{ keyframes: [{ offset: 0, opacity: 0.2 }, { offset: 1, opacity: 1 }] }, 'unsupported_opacity'],
    [{ keyframes: [{ offset: 0, opacity: 0 }, { offset: 0.3, opacity: 0.8 }, { offset: 0.6, opacity: 0.5 }, { offset: 1, opacity: 1 }] }, 'unsupported_opacity'],
    [{ origin: { x: 100, y: 100 } }, 'unsupported_pivot'],
    [{ keyframes: [{ offset: 0, x: 0 }, { offset: 1, x: 50 }] }, 'non_final_base'],
    [{ keyframes: [{ offset: 0, x: -1 }, { offset: 0, x: 0 }, { offset: 1, x: 0 }] }, 'invalid_keyframes'],
    [{ keyframes: [{ offset: 0, x: NaN }, { offset: 1, x: 0 }] }, 'invalid_keyframes'],
    [{ keyframes: [{ offset: 0, filter: 'blur(5px)' }, { offset: 1, filter: 'none' }] }, 'unsupported_channel'],
  ]) {
    const doc = fixture(), before = xml(doc), target = shapes(doc)[0];
    const result = run(doc, [effect('unsupported', extra)], new Map([['unsupported', [target]]]));
    assert.equal(result.fallbackEffects[0].reason, reason);
    assert.equal(xml(doc), before);
  }
});

test('native SVG groups are kept editable and may be targeted as a complete unit', () => {
  const doc = fixture(`<p:grpSp><p:nvGrpSpPr><p:cNvPr id="40" name="SVG"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="1000" cy="1000"/><a:chOff x="0" y="0"/><a:chExt cx="1000" cy="1000"/></a:xfrm></p:grpSpPr>${shape(41, 'Vector label')}</p:grpSp>`);
  const svg = all(doc, 'grpSp')[0], svgBefore = xml(svg);
  const result = run(doc, [effect('vector')], new Map([['vector', [svg]]]));
  assert.equal(result.nativeEffects[0].targetShapeId, '42');
  assert.equal(xml(svg), svgBefore);
  assert.equal(all(doc, 'grpSp').length, 2);
  assert.equal(all(doc, 't')[0].textContent, 'Vector label');
});

test('names are escaped and existing timing is not overwritten', () => {
  const doc = fixture(), target = shapes(doc)[0], name = '<script>&"';
  const result = run(doc, [effect(name, { delayMs: 0 })], new Map([[name, [target]]]));
  assert.equal(result.nativeEffects.length, 1);
  assert.ok(xml(doc).includes('&lt;script&gt;&amp;&quot;'));
  assert.equal(all(doc, 'set').length, 1, 'zero-delay entrance must not race hidden and visible setters');
  const before = xml(doc);
  const again = run(doc, [effect('second')], new Map([['second', [target]]]));
  assert.equal(again.fallbackEffects[0].reason, 'existing_timing');
  assert.equal(xml(doc), before);
});

test('sub-millisecond rotation samples preserve exact first and final angles', () => {
  for (const durationMs of [1, 10]) for (const delayMs of [0, 400]) {
    const doc = fixture(), target = shapes(doc)[0];
    const keyframes = Array.from({ length: 33 }, (_, i) => ({ offset: i / 32, rotation: -60 + 60 * i / 32 }));
    const result = run(doc, [effect('fast', { durationMs, delayMs, keyframes })], new Map([['fast', [target]]]));
    assert.equal(result.nativeEffects.length, 1);
    const rotation = all(doc, 'animRot');
    assert.equal(rotation[0].getAttribute('from'), '-3600000');
    assert.equal(rotation.at(-1).getAttribute('to'), '0', 'last rounded segment must reach the exact final angle');
    assert.equal(Number(all(rotation.at(-1), 'cond')[0].getAttribute('delay')) + Number(all(rotation.at(-1), 'cTn')[0].getAttribute('dur')), durationMs + delayMs);
    assert.equal(result.nativeEffects[0].timingQuantized, true);
    assert.equal(result.warnings[0].code, 'ANIMATION_TIMING_QUANTIZED');
  }
});

test('keyframes below PresentationML value-time resolution fall back without changing the hold', () => {
  const doc = fixture(), target = shapes(doc)[0], before = xml(doc);
  const keyframes = Array.from({ length: 33 }, (_, i) => ({ offset: i / 32, x: -100 + 100 * i / 32 }));
  const result = run(doc, [effect('too-short', { durationMs: 1, delayMs: 60000, keyframes })], new Map([['too-short', [target]]]));
  assert.equal(result.fallbackEffects[0].reason, 'timing_resolution');
  assert.equal(xml(doc), before);
});
