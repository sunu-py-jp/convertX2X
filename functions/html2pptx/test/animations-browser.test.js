import test from 'node:test';
import assert from 'node:assert/strict';
import {existsSync} from 'node:fs';
import {mkdtemp, rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {launchBrowser, closeBrowser, securedPage} from '../src/browser.js';
import {prepareAnimations, freezeAnimations} from '../src/animations.js';
import {materializePseudoElements} from '../src/pseudo-elements.js';

const executablePath = process.env.HTML2PPTX_CHROMIUM_PATH || process.env.HTML2PPTX_TEST_BROWSER;
const skip = !(executablePath || process.platform === 'linux' || existsSync('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'));
const base = `<style>@layer hold{*,*::before,*::after{animation-play-state:paused!important;transition-property:none!important}}
body{margin:0}section{position:relative;width:900px;height:600px}.box{position:absolute;left:100px;top:100px;width:120px;height:80px;background:red}</style>`;
async function pageFor(t, css, body = '<div id="target" class="box">Text</div>') {
  const scratch = await mkdtemp(path.join(os.tmpdir(), 'html2pptx-animation-test-'));
  const browser = await launchBrowser(scratch, executablePath);
  t.after(async () => { await closeBrowser(browser); await rm(scratch, {recursive: true, force: true}); });
  const page = await securedPage(browser, 900, 600);
  await page.setContent(`${base}<style>${css}</style><section data-slide>${body}</section>`);
  return page;
}
const state = page => page.$eval('#target', element => {
  const style = getComputedStyle(element), rect = element.getBoundingClientRect();
  return {opacity: Number(style.opacity), transform: style.transform, background: style.backgroundColor,
    x: rect.x, y: rect.y, width: rect.width, height: rect.height, animations: document.getAnimations().length};
});

test('finite fade and translation seek immediately to terminal frame even with fill none', {skip, timeout: 15000}, async t => {
  const page = await pageFor(t, '@keyframes enter{from{opacity:0;transform:translate(80px,40px)}to{opacity:1;transform:none}}#target{animation:enter 10s 20s linear}');
  const result = await page.evaluate(prepareAnimations);
  assert.equal(result.nativeCount, 1); assert.equal(result.warnings.length, 0);
  const effect = result.effects[0];
  assert.equal(effect.durationMs, 10000); assert.equal(effect.delayMs, 20000);
  assert.equal(effect.keyframes[0].opacity, 0); assert.equal(effect.keyframes.at(-1).opacity, 1);
  assert.equal(effect.keyframes[0].x, 80); assert.equal(effect.keyframes[0].y, 40);
  assert.equal(effect.keyframes.at(-1).x, 0); assert.equal(effect.bounds.x, 100);
  assert.deepEqual(await page.$eval('#target', element => globalThis.__convertX2XAnimationTargets.get(element)), [effect.id]);
  assert.deepEqual(await state(page), {opacity: 1, transform: 'matrix(1, 0, 0, 1, 0, 0)', background: 'rgb(255, 0, 0)', x: 100, y: 100, width: 120, height: 80, animations: 0});
});
test('reverse and repeated alternate use browser timeline direction at the endpoint', {skip, timeout: 15000}, async t => {
  const page = await pageFor(t, '@keyframes move{from{transform:translateX(0)}to{transform:translateX(90px)}}#target{animation:move 1s linear reverse}');
  const reverse = await page.evaluate(prepareAnimations);
  assert.equal(reverse.nativeCount, 1); assert.equal(reverse.effects[0].keyframes[0].x, 90);
  assert.equal((await state(page)).x, 100);
  await page.setContent(`${base}<style>@keyframes move{from{transform:translateX(0)}to{transform:translateX(90px)}}#target{animation:move 1s linear 2 alternate}</style><section data-slide><div id="target" class="box"></div></section>`);
  const alternate = await page.evaluate(prepareAnimations);
  assert.equal(alternate.warnings[0].reason, 'unsupported-timing');
  assert.equal((await state(page)).x, 100);
});
test('infinite animations freeze the first iteration endpoint with direction and delay preserved', {skip, timeout: 15000}, async t => {
  const page = await pageFor(t, '');
  for (const [direction, delay, duration, easing, y] of [
    ['normal', '2s', '1s', 'linear', 160],
    ['reverse', '2s', '1s', 'linear', 112],
    ['alternate', '-.5s', '1s', 'linear', 160],
    ['alternate-reverse', '-.5s', '1s', 'linear', 112],
    ['normal', '-2s', '1s', 'linear', 160],
    ['reverse', '-2s', '1s', 'linear', 112],
    ['normal', '0s', '1s', 'steps(2,end)', 160]
  ]) {
    await page.setContent(`${base}<style>@keyframes float{from{transform:translateY(12px)}to{transform:translateY(60px)}}#target{animation:float ${duration} ${delay} ${easing} infinite ${direction}}</style><section data-slide><div id="target" class="box"></div></section>`);
    const result = await page.evaluate(prepareAnimations);
    assert.equal(result.nativeCount, 0);
    assert.equal(result.warnings[0]?.reason, 'infinite-animation', `${direction} / ${delay} / ${duration} / ${easing}`);
    const frozen = await state(page);
    assert.equal(frozen.y, y, `${direction} / ${delay} / ${duration} / ${easing}`);
    assert.equal(frozen.animations, 0);
  }
});
test('infinite cyclic and disappearing effects keep their actual endpoint rather than inventing a visible state', {skip, timeout: 15000}, async t => {
  const page = await pageFor(t, '@keyframes loop{0%,100%{transform:none}50%{transform:translateY(60px)}}#target{animation:loop 1s infinite}', '<div id="target" class="box"></div>');
  await page.evaluate(prepareAnimations);
  assert.equal((await state(page)).y, 100);
  await page.setContent(`${base}<style>@keyframes vanish{from{opacity:1}to{opacity:0}}#target{animation:vanish 1s infinite}</style><section data-slide><div id="target" class="box"></div></section>`);
  const result = await page.evaluate(prepareAnimations);
  assert.equal(result.warnings[0].reason, 'infinite-animation');
  assert.equal((await state(page)).opacity, 0);
});
test('finite animations already completed by a negative delay retain their terminal state', {skip, timeout: 15000}, async t => {
  const page = await pageFor(t, '');
  for (const [direction, x] of [['normal', 190], ['reverse', 100]]) {
    await page.setContent(`${base}<style>@keyframes move{from{transform:translateX(0)}to{transform:translateX(90px)}}#target{animation:move 1s -2s linear ${direction} both}</style><section data-slide><div id="target" class="box"></div></section>`);
    const result = await page.evaluate(prepareAnimations);
    assert.equal(result.warnings[0]?.reason, 'unsupported-timing');
    assert.equal((await state(page)).x, x, direction);
  }
});
test('unsupported style properties still retain the final computed appearance', {skip, timeout: 15000}, async t => {
  const page = await pageFor(t, '@keyframes recolor{from{background-color:red;border-radius:0}to{background-color:blue;border-radius:20px}}#target{animation:recolor 30s}');
  const result = await page.evaluate(prepareAnimations);
  assert.equal(result.warnings[0].reason, 'unsupported-properties');
  assert.equal((await state(page)).background, 'rgb(0, 0, 255)');
  assert.equal(await page.$eval('#target', element => getComputedStyle(element).borderRadius), '20px');
});
test('rotation and scale trajectories are relative to the exported final geometry', {skip, timeout: 15000}, async t => {
  const page = await pageFor(t, '@keyframes spin{from{transform:rotate(-30deg) scale(.5)}to{transform:rotate(30deg) scale(1.5)}}#target{animation:spin 2s linear}');
  const {effects} = await page.evaluate(prepareAnimations);
  assert.equal(effects.length, 1);
  assert.ok(Math.abs(effects[0].keyframes[0].rotation + 60) < .001);
  assert.ok(Math.abs(effects[0].keyframes[0].scaleX - 1 / 3) < .001);
  assert.ok(Math.abs(effects[0].bounds.width - 180) < .001);
  assert.ok(Math.abs(effects[0].origin.x - 160) < .001);
});
test('nested, concurrent, noncenter, exit and stepped effects are explicitly static', {skip, timeout: 15000}, async t => {
  const page = await pageFor(t, `@keyframes move{from{transform:translateX(10px)}to{transform:none}}@keyframes fade{from{opacity:1}to{opacity:0}}
    #target{animation:move 2s}#nested{animation:move 1s}#multi{animation:move 1s,fade 1s}#exit{animation:fade 1s}
    #pivot{animation:move 1s;transform-origin:0 0}#step{animation:move 1s steps(3)}`,
    '<div id="target" class="box"><span id="nested">Nested</span></div><div id="multi" class="box"></div><div id="exit" class="box"></div><div id="pivot" class="box"></div><div id="step" class="box"></div>');
  const result = await page.evaluate(prepareAnimations), reasons = result.warnings.map(warning => warning.reason);
  assert.equal(result.effects.length, 0);
  for (const reason of ['nested-animations', 'multiple-animations', 'exit-animation', 'non-centered-transform-origin', 'unsupported-easing']) assert.ok(reasons.includes(reason), reason);
  assert.equal(await page.$eval('#exit', element => getComputedStyle(element).opacity), '0');
});
test('animated pseudo decorations keep the terminal style through materialization', {skip, timeout: 15000}, async t => {
  const page = await pageFor(t, '@keyframes move{from{transform:translateX(0);background:red}to{transform:translateX(50px);background:blue}}#target::before{content:"";position:absolute;left:0;top:0;width:20px;height:20px;background:red;animation:move 10s}');
  const result = await page.evaluate(prepareAnimations);
  assert.equal(result.warnings[0].reason, 'pseudo-element');
  assert.equal(await page.$eval('#target', element => getComputedStyle(element, '::before').backgroundColor), 'rgb(0, 0, 255)');
  assert.equal((await page.evaluate(materializePseudoElements)).materializedCount, 1);
  await page.evaluate(freezeAnimations);
  const decoration = await page.$eval('#target > span', element => ({x: element.getBoundingClientRect().x, color: getComputedStyle(element).backgroundColor, animations: document.getAnimations().length}));
  assert.deepEqual(decoration, {x: 150, color: 'rgb(0, 0, 255)', animations: 0});
});
test('untriggered transitions keep the supplied static state with an explicit warning', {skip, timeout: 15000}, async t => {
  const page = await pageFor(t, '#target{transition:transform 2s;transform:translateX(20px)}#target:hover{transform:translateX(90px)}');
  const result = await page.evaluate(prepareAnimations);
  assert.equal(result.effects.length, 0); assert.equal(result.transitionCount, 1);
  assert.equal(result.warnings[0].code, 'TRANSITION_STATIC_FALLBACK');
  await page.evaluate(freezeAnimations);
  assert.equal((await state(page)).x, 120);
});
test('animation count and unreasonably long timelines fail explicitly', {skip, timeout: 15000}, async t => {
  const page = await pageFor(t, '@keyframes move{to{transform:translateX(10px)}}.box{animation:move 1s}', '<div id="target" class="box"></div><div class="box"></div>');
  await assert.rejects(page.evaluate(prepareAnimations, {maxAnimations: 1}), /UNSUPPORTED_ANIMATION:.*数/);
  await page.setContent(`${base}<style>@keyframes move{to{transform:translateX(10px)}}#target{animation:move 3601s}</style><section data-slide><div id="target" class="box"></div></section>`);
  await assert.rejects(page.evaluate(prepareAnimations), /UNSUPPORTED_ANIMATION:.*時間/);
});
test('scroll-driven effects retain the underlying static style instead of inventing an endpoint', {skip, timeout: 15000}, async t => {
  const page = await pageFor(t, '@keyframes move{from{transform:translateX(100px)}to{transform:translateX(200px)}}#target{transform:translateX(20px);animation:move 1s linear both;animation-timeline:scroll()}');
  const result = await page.evaluate(prepareAnimations);
  assert.equal(result.warnings[0].reason, 'scroll-driven-animation');
  await page.evaluate(freezeAnimations);
  assert.equal((await state(page)).x, 120);
});
test('terminal custom property values survive cancellation and still apply to descendants', {skip, timeout: 15000}, async t => {
  const page = await pageFor(t, '@keyframes recolor{from{--ink:red}to{--ink:blue}}#target{animation:recolor 2s;background:var(--ink)}#child{color:var(--ink)}', '<div id="target" class="box"><span id="child">Child</span></div>');
  const result = await page.evaluate(prepareAnimations);
  assert.equal(result.warnings[0].reason, 'unsupported-properties');
  assert.equal((await state(page)).background, 'rgb(0, 0, 255)');
  assert.equal(await page.$eval('#child', element => getComputedStyle(element).color), 'rgb(0, 0, 255)');
});
test('large rotations cannot alias to a different direction between samples', {skip, timeout: 15000}, async t => {
  const page = await pageFor(t, '@keyframes spin{from{transform:rotate(10000deg)}to{transform:none}}#target{animation:spin 2s linear}');
  const result = await page.evaluate(prepareAnimations);
  assert.equal(result.effects.length, 0);
  assert.equal(result.staticEffects[0].reason, 'unsupported-transform');
  assert.equal((await state(page)).x, 100);
});
test('composed rotations share one angular budget and low sample counts tighten the budget', {skip, timeout: 15000}, async t => {
  const page = await pageFor(t, '@keyframes spin{from{transform:rotate(1440deg) rotate(1440deg) rotate(1440deg) rotate(1440deg)}to{transform:none}}#target{animation:spin 2s linear}');
  assert.equal((await page.evaluate(prepareAnimations)).staticEffects[0].reason, 'unsupported-transform');
  await page.setContent(`${base}<style>@keyframes spin{from{transform:rotate(360deg)}to{transform:none}}#target{animation:spin 2s linear}</style><section data-slide><div id="target" class="box"></div></section>`);
  assert.equal((await page.evaluate(prepareAnimations, {maxSamples: 2})).staticEffects[0].reason, 'unsupported-transform');
});
test('a full turn compressed into one percent is static while a full-timeline turn stays native', {skip, timeout: 15000}, async t => {
  const page = await pageFor(t, '@keyframes rapid{0%{transform:rotate(0deg)}1%,100%{transform:rotate(360deg)}}#target{animation:rapid 2s linear}');
  assert.equal((await page.evaluate(prepareAnimations)).staticEffects[0].reason, 'unsupported-transform');
  await page.setContent(`${base}<style>@keyframes spin{from{transform:rotate(0deg)}to{transform:rotate(360deg)}}#target{animation:spin 2s linear}</style><section data-slide><div id="target" class="box"></div></section>`);
  const result = await page.evaluate(prepareAnimations);
  assert.equal(result.effects.length, 1);
  assert.ok(Math.abs(result.effects[0].keyframes[0].rotation + 360) < .001);
  assert.equal(result.effects[0].keyframes.at(-1).rotation, 0);
});
test('narrow intermediate keyframes remain in the sampled trajectory, including reverse direction', {skip, timeout: 15000}, async t => {
  const page = await pageFor(t, '@keyframes flashMove{0%,50.09%,50.11%,100%{transform:none}50.1%{transform:translateX(90px)}}#target{animation:flashMove 1s linear reverse}');
  const result = await page.evaluate(prepareAnimations), effect = result.effects[0];
  assert.equal(result.effects.length, 1);
  assert.ok(effect.keyframes.some(frame => Math.abs(frame.offset - .499) < 1e-10 && Math.abs(frame.x - 90) < .001));
  assert.equal(effect.sampling.easingApproximation, true);
  assert.ok(effect.sampling.sampleCount > 33);
});
test('dense keyframes fall back instead of silently losing authored states', {skip, timeout: 15000}, async t => {
  const frames = Array.from({length: 80}, (_, i) => `${i * 100 / 79}%{transform:translateX(${i % 2}px)}`).join('');
  const page = await pageFor(t, `@keyframes dense{${frames}}#target{animation:dense 1s linear}`);
  const result = await page.evaluate(prepareAnimations);
  assert.equal(result.effects.length, 0);
  assert.equal(result.staticEffects[0].reason, 'too-many-keyframes');
});
test('anisotropic scale on a rotated final base falls back while uniform scale remains native', {skip, timeout: 15000}, async t => {
  const page = await pageFor(t, '@keyframes stretch{from{transform:rotate(10deg) scale(.5,.8)}to{transform:rotate(30deg) scale(1.5)}}#target{animation:stretch 2s linear}');
  const result = await page.evaluate(prepareAnimations);
  assert.equal(result.effects.length, 0);
  assert.equal(result.staticEffects[0].reason, 'unsupported-transform');
});
