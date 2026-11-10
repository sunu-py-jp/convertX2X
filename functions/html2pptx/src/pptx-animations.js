// Native PresentationML behaviors. The browser supplies sampled, computed CSS
// values; this module does not infer animation semantics from class names.
// References: ECMA-376 Part 1, 19.5 (Animation), and Microsoft Learn's
// presentation/working-with-animation. No Office installation is required.
const P = 'http://schemas.openxmlformats.org/presentationml/2006/main';
const A = 'http://schemas.openxmlformats.org/drawingml/2006/main';
const EMU = 9525;
const CHANNELS = { opacity: 1, x: 0, y: 0, scaleX: 1, scaleY: 1, rotation: 0 };
const SHAPES = new Set(['sp', 'pic', 'graphicFrame', 'grpSp', 'cxnSp']);
const list = (node, name) => Array.from(node.getElementsByTagNameNS(P, name));
const children = node => Array.from(node.childNodes).filter(child => child.nodeType === 1);
const near = (a, b) => Math.abs(a - b) < 1e-5;
const number = value => value.toFixed(9).replace(/(\.\d*?[1-9])0+$|\.0+$/, '$1').replace(/^-0$/, '0');
const changed = (frames, channel) => frames.some(frame => Math.abs((frame[channel] ?? CHANNELS[channel]) - CHANNELS[channel]) > (['x', 'y', 'rotation'].includes(channel) ? 0.001 : 1e-5));

function nodeFactory(doc) {
  return function element(name, attrs = {}, descendants = []) {
    const el = doc.createElementNS(name.startsWith('a:') ? A : P, name);
    for (const [key, value] of Object.entries(attrs)) el.setAttribute(key, String(value));
    for (const child of descendants) el.appendChild(typeof child === 'string' ? doc.createTextNode(child) : child);
    return el;
  };
}

function validate(effect) {
  if (!effect || typeof effect.id !== 'string' || !effect.id) return 'invalid_effect';
  if (!Number.isFinite(effect.durationMs) || effect.durationMs < 1 || effect.durationMs > 3600000 ||
      !Number.isFinite(effect.delayMs ?? 0) || (effect.delayMs ?? 0) < 0 || (effect.delayMs ?? 0) > 3600000) return 'invalid_timing';
  const frames = effect.keyframes;
  if (!Array.isArray(frames) || frames.length < 2 || frames.length > 257 ||
      frames.some(frame => !frame || typeof frame !== 'object') ||
      !near(frames[0].offset, 0) || !near(frames.at(-1).offset, 1)) return 'invalid_keyframes';
  for (let i = 0; i < frames.length; i++) {
    if (!Number.isFinite(frames[i].offset) || frames[i].offset < 0 || frames[i].offset > 1 ||
        (i && frames[i].offset <= frames[i - 1].offset)) return 'invalid_keyframes';
    if (Object.keys(frames[i]).some(key => key !== 'offset' && !(key in CHANNELS))) return 'unsupported_channel';
  }
  for (const channel of Object.keys(CHANNELS)) {
    if (frames.some(frame => channel in frame) && frames.some(frame => !Number.isFinite(frame[channel]))) return 'invalid_keyframes';
  }
  const bounds = effect.bounds;
  if (!bounds || !['x', 'y', 'width', 'height'].every(key => Number.isFinite(bounds[key])) || bounds.width <= 0 || bounds.height <= 0) return 'invalid_bounds';
  if (effect.origin && (!Number.isFinite(effect.origin.x) || !Number.isFinite(effect.origin.y) ||
      Math.abs(effect.origin.x - bounds.x - bounds.width / 2) > 1 || Math.abs(effect.origin.y - bounds.y - bounds.height / 2) > 1)) return 'unsupported_pivot';
  for (const [channel, base] of Object.entries(CHANNELS)) {
    if (!near(frames.at(-1)[channel] ?? base, base)) return 'non_final_base';
  }
  const opacity = frames.map(frame => frame.opacity ?? 1);
  if (!opacity.every(value => near(value, 1)) && (!near(opacity[0], 0) || !near(opacity.at(-1), 1) ||
      opacity.some((value, i) => value < 0 || value > 1 || (i && value < opacity[i - 1] - 1e-5)))) return 'unsupported_opacity';
  if (frames.some(frame => ['scaleX', 'scaleY'].some(key => (frame[key] ?? 1) < 0 || (frame[key] ?? 1) > 1000))) return 'unsupported_scale';
  if (frames.some(frame => Math.abs(frame.rotation ?? 0) > 30000 || Math.abs(frame.x ?? 0) > 1000000 || Math.abs(frame.y ?? 0) > 1000000)) return 'invalid_keyframes';
  if (Object.keys(CHANNELS).every(key => !changed(frames, key))) return 'no_visible_change';
  if (['x', 'y', 'scaleX', 'scaleY'].some(channel => changed(frames, channel))) {
    const delay = Math.round(effect.delayMs ?? 0), duration = Math.round(effect.durationMs), total = delay + duration;
    const ticks = frames.map(frame => Math.round((delay + frame.offset * duration) / total * 100000));
    if (delay) ticks.unshift(0);
    // Collapsed tav ticks could turn a long hold into motion over the whole
    // delay. Keep the final static state rather than silently changing timing.
    if (new Set(ticks).size !== ticks.length) return 'timing_resolution';
  }
  return null;
}

function quantizeRotation(samples, delay) {
  const byTime = new Map();
  for (const sample of samples) {
    const t = Math.round(sample.t);
    // Keep the exact initial angle at slide start and animation start. Within
    // other millisecond buckets, the latest sampled angle wins.
    if (!byTime.has(t) || (t !== 0 && t !== delay)) byTime.set(t, { t, value: sample.value });
  }
  const last = samples.at(-1);
  byTime.set(Math.round(last.t), { t: Math.round(last.t), value: last.value });
  return Array.from(byTime.values()).sort((a, b) => a.t - b.t);
}

function owningShape(node) {
  if (node?.namespaceURI === P && node.localName === 'cNvPr') node = node.parentNode?.parentNode;
  return node?.namespaceURI === P && SHAPES.has(node.localName) ? node : null;
}

function targetsFor(effect, targets, tree) {
  const input = targets?.get?.(effect.id) ?? [];
  const nodes = Array.from(new Set(Array.from(input).map(owningShape)));
  if (!nodes.length || nodes.some(node => !node || node.parentNode !== tree)) return { reason: 'unresolved_target' };
  const paint = children(tree).filter(node => SHAPES.has(node.localName));
  const order = nodes.map(node => paint.indexOf(node)).sort((a, b) => a - b);
  if (order.some((position, i) => i && position !== order[i - 1] + 1)) return { reason: 'interleaved_target' };
  return { nodes: order.map(position => paint[position]) };
}

function groupTargets(el, tree, nodes, effect, id) {
  const b = effect.bounds;
  const x = Math.round(b.x * EMU), y = Math.round(b.y * EMU);
  const cx = Math.max(1, Math.round(b.width * EMU)), cy = Math.max(1, Math.round(b.height * EMU));
  // Equal parent and child coordinate systems make this an identity transform.
  // The explicit CSS box also keeps text, borders and pictures on one pivot.
  const group = el('p:grpSp', {}, [
    el('p:nvGrpSpPr', {}, [el('p:cNvPr', { id, name: `CSS animation ${effect.id}` }), el('p:cNvGrpSpPr'), el('p:nvPr')]),
    el('p:grpSpPr', {}, [el('a:xfrm', {}, [el('a:off', { x, y }), el('a:ext', { cx, cy }), el('a:chOff', { x, y }), el('a:chExt', { cx, cy })])]),
  ]);
  tree.insertBefore(group, nodes[0]);
  for (const node of nodes) group.appendChild(node);
  return group;
}

/**
 * Add native animations to one finalized slide. `targets` maps effect IDs to
 * drawing objects, after SVG replacement and shape-ID reassignment. It must not
 * include unrelated paint items. The document is mutated only for safe effects.
 * Unsupported effects retain the static final-state objects and are reported.
 */
export function applyNativeAnimations({ document: doc, effects = [], targets, slideWidthPx, slideHeightPx, warnings = [] }) {
  const result = { nativeEffects: [], fallbackEffects: [], groupsCreated: 0, warnings };
  const tree = list(doc, 'spTree')[0];
  const el = nodeFactory(doc);
  const fallback = (effect, reason) => {
    const entry = { id: effect?.id ?? null, slide: effect?.slide, animationName: effect?.animationName,
      element: effect?.element, sampling: effect?.sampling,
      ...(effect?.sourceAnimations ? {sourceAnimations: effect.sourceAnimations} : {}), status: 'static', reason };
    result.fallbackEffects.push(entry);
    warnings.push({ code: 'ANIMATION_STATIC_FALLBACK', slide: effect?.slide, animationId: effect?.id,
      reason, message: 'このアニメーションは静止した最終状態で出力しました。' });
  };
  const globalReason = !tree || !Number.isFinite(slideWidthPx) || slideWidthPx <= 0 || !Number.isFinite(slideHeightPx) || slideHeightPx <= 0
    ? 'invalid_slide' : list(doc, 'timing').length ? 'existing_timing' : null;
  const plans = effects.map(effect => ({ effect, reason: globalReason || validate(effect) }));
  for (const plan of plans) if (!plan.reason) Object.assign(plan, targetsFor(plan.effect, targets, tree));
  // Reject all competing owners, rather than silently letting iteration order
  // decide which CSS element moves an exported object.
  const owners = new Map();
  for (const plan of plans.filter(plan => !plan.reason)) for (const node of plan.nodes) {
    const previous = owners.get(node);
    if (previous) { previous.reason = 'shared_target'; plan.reason = 'shared_target'; }
    else owners.set(node, plan);
  }
  const usedIds = list(doc, 'cNvPr').map(node => Number(node.getAttribute('id'))).filter(Number.isFinite);
  let shapeId = Math.max(0, ...usedIds) + 1, timeId = 1;
  const id = () => timeId++;
  const conditions = delay => el('p:stCondLst', {}, [el('p:cond', { delay: Math.max(0, Math.round(delay)) })]);
  const time = (duration, delay = 0, extra = {}) => el('p:cTn', { id: id(), dur: Math.max(1, Math.round(duration)), fill: 'hold', ...extra }, [conditions(delay)]);
  const behavior = (targetId, duration, delay, property, extra = {}) => el('p:cBhvr', { additive: 'base' }, [
    time(duration, delay, extra),
    el('p:tgtEl', {}, [el('p:spTgt', { spid: targetId })]),
    ...(property ? [el('p:attrNameLst', {}, [el('p:attrName', {}, [property])])] : []),
  ]);
  const setVisibility = (targetId, value, delay) => el('p:set', {}, [behavior(targetId, 1, delay, 'style.visibility'), el('p:to', {}, [el('p:strVal', { val: value })])]);
  const automaticEffects = [];
  const buildTargets = [];

  for (const plan of plans) {
    const { effect } = plan;
    if (plan.reason) { fallback(effect, plan.reason); continue; }
    const targetId = shapeId++;
    groupTargets(el, tree, plan.nodes, effect, targetId);
    result.groupsCreated++;
    const frames = effect.keyframes, duration = Math.round(effect.durationMs), delay = Math.round(effect.delayMs ?? 0), total = duration + delay;
    const channels = Object.keys(CHANNELS).filter(key => changed(frames, key));
    const timeline = [];
    let timingQuantized = false;
    const sampled = channel => {
      const entries = frames.map(frame => ({ t: delay + frame.offset * duration, value: frame[channel] ?? CHANNELS[channel] }));
      if (delay) entries.unshift({ t: 0, value: entries[0].value });
      return entries;
    };
    for (const [channel, property, factor] of [['x', 'ppt_x', slideWidthPx], ['y', 'ppt_y', slideHeightPx], ['scaleX', 'ppt_w', null], ['scaleY', 'ppt_h', null]]) {
      if (!channels.includes(channel)) continue;
      const values = sampled(channel).map(sample => el('p:tav', { tm: Math.round(sample.t / total * 100000) }, [
        el('p:val', {}, [el('p:strVal', { val: factor
          ? `#${property}${sample.value < 0 ? '-' : '+'}${number(Math.abs(sample.value) / factor)}`
          : `#${property}*${number(sample.value)}` })]),
      ]));
      timeline.push(el('p:anim', { calcmode: 'lin', valueType: 'num' }, [behavior(targetId, total, 0, property), el('p:tavLst', {}, values)]));
    }
    if (channels.includes('rotation')) {
      // Rotation uses the dedicated native behavior, in 1/60000 degree units.
      // Sampling its individual linear segments preserves the CSS timing curve.
      const sourceValues = sampled('rotation'), values = quantizeRotation(sourceValues, delay);
      timingQuantized = values.length !== sourceValues.length;
      for (let i = 1; i < values.length; i++) {
        const start = Math.round(values[i - 1].t), end = Math.round(values[i].t);
        if (end <= start) continue;
        timeline.push(el('p:animRot', { from: Math.round(values[i - 1].value * 60000), to: Math.round(values[i].value * 60000) }, [behavior(targetId, end - start, start, 'r')]));
      }
    }
    if (channels.includes('opacity')) {
      // Arbitrary style.opacity p:anim curves are not reliably honored by
      // PowerPoint. A full-range native fade plus a time filter is well-defined.
      if (delay) timeline.push(setVisibility(targetId, 'hidden', 0));
      timeline.push(setVisibility(targetId, 'visible', delay));
      const filter = frames.map(frame => `${number(frame.offset)},${number(frame.opacity)}`).join(';');
      timeline.push(el('p:animEffect', { transition: 'in', filter: 'fade' }, [behavior(targetId, duration, delay, null, { tmFilter: filter })]));
    }
    const effectTime = time(total, 0, { nodeType: 'withEffect', presetClass: channels.includes('opacity') ? 'entr' : 'emph', presetID: channels.includes('opacity') ? 10 : 0, grpId: 0 });
    effectTime.appendChild(el('p:childTnLst', {}, timeline));
    automaticEffects.push(el('p:par', {}, [effectTime]));
    buildTargets.push(targetId);
    result.nativeEffects.push({ id: effect.id, slide: effect.slide, animationName: effect.animationName,
      element: effect.element, sampling: effect.sampling,
      ...(effect.sourceAnimations ? {sourceAnimations: effect.sourceAnimations} : {}), status: 'native', targetShapeId: String(targetId), channels,
      durationMs: duration, delayMs: delay, keyframeCount: frames.length, ...(timingQuantized ? { timingQuantized: true, timingResolutionMs: 1 } : {}) });
    if (timingQuantized) warnings.push({ code: 'ANIMATION_TIMING_QUANTIZED', slide: effect.slide, animationId: effect.id,
      reason: 'sub_millisecond_keyframes', message: '回転の中間状態を1ミリ秒単位にまとめました。開始角度と最終角度は保持しています。' });
  }
  if (!automaticEffects.length) return result;

  // One automatic parallel group gives every effect a common slide-start clock.
  // Delays are absolute, so independent CSS effects do not become a click chain.
  const parallel = time(Math.max(...result.nativeEffects.map(effect => effect.delayMs + effect.durationMs)), 0);
  parallel.appendChild(el('p:childTnLst', {}, automaticEffects));
  const sequenceTime = el('p:cTn', { id: id(), dur: 'indefinite', nodeType: 'mainSeq' }, [el('p:childTnLst', {}, [el('p:par', {}, [parallel])])]);
  const sequence = el('p:seq', { concurrent: 1, nextAc: 'seek' }, [sequenceTime]);
  for (const [tag, event] of [['prevCondLst', 'onPrev'], ['nextCondLst', 'onNext']]) sequence.appendChild(el(`p:${tag}`, {}, [el('p:cond', { evt: event, delay: 0 }, [el('p:tgtEl', {}, [el('p:sldTgt')])])]));
  const root = el('p:cTn', { id: id(), dur: 'indefinite', restart: 'never', nodeType: 'tmRoot' }, [el('p:childTnLst', {}, [sequence])]);
  const timing = el('p:timing', {}, [el('p:tnLst', {}, [el('p:par', {}, [root])]), el('p:bldLst', {}, buildTargets.map(spid => el('p:bldP', { spid, grpId: 0, build: 'allAtOnce', animBg: 1 })))]);
  // No time-node references are emitted. Assign root-first IDs after building
  // the tree, following the ordering used by PowerPoint's own serialization.
  list(timing, 'cTn').forEach((node, index) => node.setAttribute('id', String(index + 1)));
  const slide = doc.documentElement;
  slide.insertBefore(timing, children(slide).find(node => node.localName === 'extLst') || null);
  return result;
}
