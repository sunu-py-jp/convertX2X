/** Trusted browser code. Resolve CSS animation timelines without running input JavaScript. */
export async function prepareAnimations({maxAnimations = 1000, maxSamples = 33} = {}) {
  const fail = message => { throw new Error(`UNSUPPORTED_ANIMATION: ${message}`); };
  if (!Number.isInteger(maxAnimations) || maxAnimations < 1 || maxAnimations > 1000 ||
      !Number.isInteger(maxSamples) || maxSamples < 2 || maxSamples > 65) fail('アニメーション処理の上限設定が不正です。');
  const slides = [...document.querySelectorAll('[data-slide]')];
  const animations = document.getAnimations();
  if (animations.length > maxAnimations) fail('アニメーションの数が上限を超えています。');
  const effects = [], staticEffects = [], warnings = [], targets = new WeakMap();
  globalThis.__convertX2XAnimationTargets = targets;
  const cssName = property => property.startsWith('--') ? property : property.replace(/[A-Z]/g, letter => `-${letter.toLowerCase()}`);
  const metadata = new Set(['offset', 'computedOffset', 'easing', 'composite']);
  const supported = new Set(['opacity', 'transform', 'translate', 'scale', 'rotate']);
  // Chromium can omit unregistered custom properties from getKeyframes(), even
  // though they change the rendered style. Retain their declared names too.
  const customPropertiesByName = new Map();
  const collectCustomProperties = rules => {
    for (const rule of rules) {
      if (rule.type === CSSRule.KEYFRAMES_RULE) {
        const properties = customPropertiesByName.get(rule.name) || new Set();
        for (const frame of rule.cssRules) for (const property of frame.style) if (property.startsWith('--')) properties.add(property);
        customPropertiesByName.set(rule.name, properties);
      } else if (rule.cssRules) collectCustomProperties(rule.cssRules);
    }
  };
  for (const sheet of document.styleSheets) collectCustomProperties(sheet.cssRules);
  const number = value => Number.parseFloat(value) || 0;
  const info = element => ({tag: element.tagName.toLowerCase(), id: (element.id || '').slice(0, 100)});
  const slideOf = element => slides.indexOf(element.closest('[data-slide]')) + 1;
  const descriptions = {
    'infinite-animation': '無限アニメーションは、再生方向を考慮した1回分の終端の見た目で固定しました。',
    'scroll-driven-animation': 'スクロール連動アニメーションは、アニメーションを適用しない静止状態にしました。',
    'unsupported-properties': 'PPTXのアニメーションに変換できないCSSプロパティを含むため、終端の見た目で固定しました。',
    'discontinuous-composition': '複数アニメーションの切り替わりに不連続な変化があるため、要素全体を合成した終端の見た目で固定しました。',
    'nested-animations': '親子要素のアニメーションが重なるため、終端の見た目で固定しました。',
    'unsupported-timing': '繰り返し・交互再生・負の遅延などを含むため、終端の見た目で固定しました。',
    'unsupported-easing': '段階的な変化を含むタイミング関数のため、終端の見た目で固定しました。',
    'pseudo-element': '疑似要素のアニメーションは、終端の見た目で固定しました。',
    'non-centered-transform-origin': '中央以外を基点とした変形のため、終端の見た目で固定しました。',
    'transformed-ancestor': '変形した親要素を含むため、終端の見た目で固定しました。',
    'unsupported-transform': '3D・せん断・複雑な回転と拡縮などを含む変形のため、終端の見た目で固定しました。',
    'unsupported-opacity': '途中の透明度や点滅をPPTXで正確に再現できないため、終端の見た目で固定しました。',
    'exit-animation': '消えるアニメーションは、終端の非表示状態で固定しました。',
    'unsupported-element': 'この要素のアニメーションは、終端の見た目で固定しました。',
    'outside-slide': 'スライド外のアニメーションは、終端の見た目で固定しました。',
    'no-visible-change': '見た目の変化がないため、静止状態で出力しました。',
    'too-many-keyframes': 'キーフレームを安全にサンプリングできる数を超えるため、終端の見た目で固定しました。',
    'transition': 'CSSトランジションは、変化後の静止状態で固定しました。'
  };
  const warn = (record, reason, combination) => {
    const detail = {id: record.id, reason, slide: slideOf(record.element) || undefined,
      element: info(record.element), animationName: record.name, ...(combination ? {
        ...combination, durationMs: record.duration, delayMs: record.delay
      } : {})};
    staticEffects.push(detail);
    warnings.push({code: 'ANIMATION_STATIC_FALLBACK', ...detail, message: descriptions[reason]});
  };
  const records = animations.map((animation, index) => {
    const effect = animation.effect, element = effect?.target;
    if (!(element instanceof Element)) fail('アニメーション対象を特定できませんでした。');
    const timing = effect.getTiming(), computed = effect.getComputedTiming(), frames = effect.getKeyframes();
    const duration = Number(computed.duration), delay = Number(timing.delay), iterations = Number(timing.iterations);
    const scroll = animation.timeline && animation.timeline !== document.timeline;
    if (!scroll && (!Number.isFinite(duration) || duration < 0 || duration > 3600000 ||
        !Number.isFinite(delay) || Math.abs(delay) > 3600000 || !Number.isFinite(Number(timing.endDelay)) ||
        Math.abs(Number(timing.endDelay)) > 3600000 || (!Number.isFinite(iterations) && iterations !== Infinity) ||
        iterations < 0 || (Number.isFinite(iterations) && duration * iterations > 3600000))) {
      fail('アニメーションの時間指定が上限（1時間）を超えているか、不正です。');
    }
    if (frames.length > 1000) fail('アニメーションのキーフレーム数が上限を超えています。');
    const properties = [...new Set([...frames.flatMap(frame => Object.keys(frame).filter(key => !metadata.has(key))).map(cssName),
      ...(customPropertiesByName.get(animation.animationName) || [])])];
    return {animation, effect, element, timing, duration, delay, iterations, frames, properties, scroll,
      pseudo: effect.pseudoElement || '', id: `animation-${index + 1}`,
      name: String(animation.animationName || animation.transitionProperty || 'animation').slice(0, 200),
      transition: typeof CSSTransition !== 'undefined' && animation instanceof CSSTransition};
  });

  // Pause every effect before sampling any one of them. This also makes composed
  // terminal states independent of wall-clock timing and document size.
  const setTerminal = record => {
    const {animation, effect, scroll, delay, duration, iterations} = record;
    if (scroll) { animation.cancel(); return; }
    if (animation.effect !== effect) animation.effect = effect;
    animation.pause();
    // Cap an infinite effect before seeking its exact endpoint. Seeking to one
    // duration on an infinite timeline would instead select the next iteration.
    const resolvedIterations = iterations === Infinity ? 1 : iterations;
    effect.updateTiming({fill: 'both', iterations: resolvedIterations});
    // With a large negative delay the effect has already finished at t=0.
    // A negative currentTime can fall into the before phase in Chromium.
    animation.currentTime = Math.max(0, delay + duration * resolvedIterations);
  };
  for (const record of records) setTerminal(record);
  const read = record => getComputedStyle(record.element, record.pseudo || null);
  const borderBox = element => {
    const style = getComputedStyle(element);
    const axis = name => {
      const horizontal = name === 'width', raw = Number.parseFloat(style[name]);
      if (!Number.isFinite(raw)) return horizontal ? element.offsetWidth : element.offsetHeight;
      return raw + (style.boxSizing === 'border-box' ? 0 : horizontal
        ? number(style.paddingLeft) + number(style.paddingRight) + number(style.borderLeftWidth) + number(style.borderRightWidth)
        : number(style.paddingTop) + number(style.paddingBottom) + number(style.borderTopWidth) + number(style.borderBottomWidth));
    };
    return {width: axis('width'), height: axis('height')};
  };
  const matrix = style => {
    let value = new DOMMatrix();
    if (style.translate && style.translate !== 'none') {
      const parts = style.translate.split(/\s+/);
      if (parts.length > 2 && number(parts[2]) !== 0) return null;
    }
    if (style.rotate && style.rotate !== 'none') {
      if (!/^-?[\d.]+(?:deg|rad|grad|turn)$/.test(style.rotate)) return null;
      let angle = number(style.rotate);
      if (style.rotate.endsWith('grad')) angle *= .9;
      else if (style.rotate.endsWith('rad')) angle *= 180 / Math.PI;
      else if (style.rotate.endsWith('turn')) angle *= 360;
      value = value.rotate(angle);
    }
    if (style.scale && style.scale !== 'none') {
      const parts = style.scale.split(/\s+/).map(Number);
      if (parts.length > 2 && parts[2] !== 1) return null;
      value = value.scale(parts[0], parts[1] ?? parts[0]);
    }
    if (style.transform !== 'none') value = value.multiply(new DOMMatrix(style.transform));
    const sx = Math.hypot(value.a, value.b), sy = Math.hypot(value.c, value.d);
    if (!value.is2D || sx < .000001 || sy < .000001 || value.a * value.d - value.b * value.c <= 0 ||
        Math.abs(value.a * value.c + value.b * value.d) > .00001 * sx * sy) return null;
    return {scaleX: sx, scaleY: sy, rotation: Math.atan2(value.b, value.a) * 180 / Math.PI};
  };
  const sample = record => {
    const style = read(record), transform = matrix(style), rect = record.element.getBoundingClientRect();
    if (!transform) return null;
    return {x: rect.x + rect.width / 2, y: rect.y + rect.height / 2,
      opacity: Number(style.opacity), ...transform};
  };
  const nativeReason = record => {
    const {element, properties, timing, iterations, duration, delay, frames} = record;
    if (record.scroll) return 'scroll-driven-animation';
    if (iterations === Infinity) return 'infinite-animation';
    if (record.transition) return 'transition';
    if (record.pseudo) return 'pseudo-element';
    if (!slideOf(element)) return 'outside-slide';
    if (records.some(other => other.element !== element && (other.element.contains(element) || element.contains(other.element)))) return 'nested-animations';
    if (iterations !== 1 || duration <= 0 || delay < 0 || number(timing.iterationStart) !== 0 || number(timing.endDelay) !== 0 ||
        !['normal', 'reverse'].includes(timing.direction)) return 'unsupported-timing';
    if ([timing.easing, ...frames.map(frame => frame.easing)].some(easing => /steps\(|step-start|step-end/.test(easing || ''))) return 'unsupported-easing';
    if (properties.some(property => !supported.has(property)) ||
        !['replace', 'add', 'accumulate'].includes(record.effect.composite || 'replace') ||
        frames.some(frame => frame.composite && !['auto', 'replace', 'add', 'accumulate'].includes(frame.composite))) return 'unsupported-properties';
    // Matrix decomposition loses whole turns. Bound authored rotation so adjacent
    // samples cannot silently select the opposite rotation direction.
    const angleBudget = Math.min(1440, 90 * (maxSamples - 1));
    if (frames.some(frame => [frame.transform, frame.rotate].filter(Boolean).reduce((sum, value) => sum +
      [...String(value).matchAll(/(-?(?:\d+(?:\.\d*)?|\.\d+))(deg|rad|grad|turn)/g)].reduce((total, match) => total +
        Math.abs(Number(match[1]) * ({deg: 1, rad: 180 / Math.PI, grad: .9, turn: 360}[match[2]])), 0), 0) > angleBudget)) return 'unsupported-transform';
    const rotations = frames.filter(frame => frame.transform !== undefined || frame.rotate !== undefined).map(frame => ({
      offset: frame.computedOffset,
      angle: [frame.transform, frame.rotate].filter(Boolean).reduce((sum, value) => sum +
        [...String(value).matchAll(/(-?(?:\d+(?:\.\d*)?|\.\d+))(deg|rad|grad|turn)/g)].reduce((total, match) => total +
          Number(match[1]) * ({deg: 1, rad: 180 / Math.PI, grad: .9, turn: 360}[match[2]]), 0), 0)
    }));
    // A full turn within a very short authored segment can look identical at
    // both sampled boundaries. Do not infer a shorter rotation from its matrix.
    if (rotations.some((frame, index) => index > 0 &&
      frame.offset - rotations[index - 1].offset < 1 / (maxSamples - 1) &&
      Math.abs(frame.angle - rotations[index - 1].angle) > 90)) return 'unsupported-transform';
    const style = read(record), box = borderBox(element), root = element.closest('[data-slide]');
    if (element === root || element.namespaceURI === 'http://www.w3.org/2000/svg' && element.localName !== 'svg' ||
        style.display === 'contents' || element.getClientRects().length !== 1 || !box.width || !box.height) return 'unsupported-element';
    if (properties.some(property => property !== 'opacity')) {
      const origin = style.transformOrigin.split(/\s+/).map(number);
      if (Math.abs(origin[0] - box.width / 2) > .25 || Math.abs(origin[1] - box.height / 2) > .25 || (origin[2] || 0) !== 0) return 'non-centered-transform-origin';
    }
    for (let parent = element.parentElement; parent; parent = parent.parentElement) {
      const parentStyle = getComputedStyle(parent);
      if (['transform', 'translate', 'rotate', 'scale', 'perspective'].some(property => parentStyle[property] && parentStyle[property] !== 'none')) return 'transformed-ancestor';
    }
    return null;
  };
  const offsetsFor = record => [...new Set([
    ...Array.from({length: maxSamples}, (_, index) => index / (maxSamples - 1)),
    ...record.frames.map(frame => record.timing.direction === 'reverse' ? 1 - frame.computedOffset : frame.computedOffset)
  ])].sort((a, b) => a - b);
  const appendSample = (samples, record, offset) => {
    const value = sample(record);
    if (!value) return false;
    if (samples.length) {
      const previous = samples[samples.length - 1].rotation;
      while (value.rotation - previous > 180) value.rotation -= 360;
      while (value.rotation - previous < -180) value.rotation += 360;
    }
    samples.push({offset, ...value});
    return true;
  };
  const trajectoryReason = (samples, allowConstant = false) => {
    const first = samples[0], last = samples[samples.length - 1];
    const finalAngle = ((last.rotation % 360) + 360) % 360;
    if (Math.min(finalAngle, 360 - finalAngle) > .001 && samples.some(value =>
      Math.abs(value.scaleX / last.scaleX - value.scaleY / last.scaleY) > .0001)) return 'unsupported-transform';
    if (last.opacity <= .0001) return 'exit-animation';
    if (!samples.every(value => Math.abs(value.opacity - 1) < .0001) &&
        !(first.opacity <= .0001 && Math.abs(last.opacity - 1) < .0001 &&
          samples.every((value, i) => i === 0 || value.opacity >= samples[i - 1].opacity - .0001))) return 'unsupported-opacity';
    const changes = ['x', 'y', 'scaleX', 'scaleY', 'rotation', 'opacity'].some(property =>
      samples.some(value => Math.abs(value[property] - last[property]) > .0001));
    return !allowConstant && !changes ? 'no-visible-change' : null;
  };
  const sourceInfo = record => ({id: record.id, animationName: record.name, durationMs: record.duration, delayMs: record.delay});
  const groups = new Map();
  for (const record of records) {
    const group = groups.get(record.element) || [];
    group.push(record); groups.set(record.element, group);
  }
  for (const group of groups.values()) {
    const record = group[0], combined = group.length > 1, isolated = new Map();
    let reason, cause, samples, duration = record.duration, delay = record.delay;
    // Validate each source independently. A later replacement must not hide an
    // unsupported exit, 3D transform, color change, etc. from this decision.
    try {
      if (combined) for (const member of group) member.animation.effect = null;
      for (const member of group) {
        setTerminal(member);
        reason = nativeReason(member);
        const offsets = offsetsFor(member), values = [];
        if (!reason && offsets.length > 65) reason = 'too-many-keyframes';
        for (const offset of reason ? [] : offsets) {
          member.animation.currentTime = member.delay + member.duration * offset;
          if (!appendSample(values, member, offset)) { reason = 'unsupported-transform'; break; }
        }
        if (!reason) reason = trajectoryReason(values, combined);
        isolated.set(member, values);
        if (combined) member.animation.effect = null;
        if (reason) { cause = member; break; }
      }
    } finally {
      for (const member of group) setTerminal(member);
    }
    if (!reason && !combined) samples = isolated.get(record);
    if (!reason && combined) {
      delay = Math.min(...group.map(member => member.delay));
      const end = Math.max(...group.map(member => member.delay + member.duration));
      duration = end - delay;
      const times = [...new Set(group.flatMap(member => isolated.get(member).map(value =>
        member.delay + member.duration * value.offset)))].sort((a, b) => a - b)
        .filter((value, i, all) => !i || value - all[i - 1] > 1e-7);
      if (duration > 3600000) reason = 'unsupported-timing';
      else if (times.length > 257) reason = 'too-many-keyframes';
      // Use every source's regular samples, not just one global grid: a short
      // rotation must not disappear inside a much longer companion animation.
      if (!reason) for (let i = 1; i < times.length; i++) {
        let angularTravel = 0;
        for (const member of group) {
          const values = isolated.get(member);
          for (let j = 1; j < values.length; j++) {
            const a = member.delay + member.duration * values[j - 1].offset;
            const b = member.delay + member.duration * values[j].offset;
            const overlap = Math.max(0, Math.min(b, times[i]) - Math.max(a, times[i - 1]));
            angularTravel += Math.abs(values[j].rotation - values[j - 1].rotation) * overlap / (b - a);
          }
        }
        if (angularTravel > 90 + .001) { reason = 'unsupported-transform'; break; }
      }
      const seek = time => {
        for (const member of group) member.animation.currentTime = time;
        return sample(record);
      };
      const continuous = (a, b) => a && b &&
        Math.abs(a.x - b.x) < .05 && Math.abs(a.y - b.y) < .05 &&
        Math.abs(a.scaleX - b.scaleX) < .0001 && Math.abs(a.scaleY - b.scaleY) < .0001 &&
        Math.abs(a.opacity - b.opacity) < .0001 &&
        Math.abs(((a.rotation - b.rotation + 540) % 360) - 180) < .05;
      const boundaryLimit = (near, far) => near && far && ({
        ...Object.fromEntries(['x', 'y', 'scaleX', 'scaleY', 'opacity'].map(key => [key, 2 * near[key] - far[key]])),
        rotation: near.rotation + ((near.rotation - far.rotation + 540) % 360) - 180
      });
      try {
        if (!reason) {
          const terminal = sample(record);
          // Sampling honors original fill and CSS replacement/addition order.
          // Forcing backwards/forwards fill throughout would change ownership.
          for (const member of group) member.effect.updateTiming({fill: member.timing.fill});
          const boundaries = [...new Set(group.flatMap(member => [member.delay, member.delay + member.duration]))]
            .filter(time => time > 0 && time >= delay && time <= end).sort((a, b) => a - b)
            .filter((value, i, all) => !i || value - all[i - 1] > 1e-7);
          for (let i = 0; i < boundaries.length; i++) {
            const time = boundaries[i], before = i ? boundaries[i - 1] : 0;
            const after = boundaries[i + 1] ?? end;
            // Chromium may treat sub-microsecond offsets as the boundary itself.
            // Probe clearly inside each phase, then extrapolate its limit to
            // distinguish a real jump from continuous fast motion.
            const epsilon = Math.min(.01, (time - before) / 4, time < end ? (after - time) / 4 : Infinity);
            if (epsilon < .001) { reason = 'discontinuous-composition'; break; }
            const left = boundaryLimit(seek(time - epsilon), seek(time - 2 * epsilon));
            const at = time === end ? terminal : seek(time);
            const right = time === end ? terminal : boundaryLimit(seek(time + epsilon), seek(time + 2 * epsilon));
            if (!continuous(left, at) || !continuous(at, right)) { reason = 'discontinuous-composition'; break; }
          }
          samples = [];
          for (const time of reason ? [] : times) {
            if (time === end) for (const member of group) setTerminal(member);
            else seek(time);
            if (!appendSample(samples, record, (time - delay) / duration)) { reason = 'unsupported-transform'; break; }
          }
          if (!reason) reason = trajectoryReason(samples);
        }
      } finally {
        for (const member of group) setTerminal(member);
      }
    }
    if (reason) {
      for (const member of group) warn(member, reason, combined ? {groupId: record.id, causeAnimationId: cause?.id ?? null} : undefined);
      continue;
    }
    const last = samples.at(-1), rootRect = record.element.closest('[data-slide]').getBoundingClientRect(), box = borderBox(record.element);
    const width = box.width * last.scaleX, height = box.height * last.scaleY;
    effects.push({id: record.id, slide: slideOf(record.element), element: info(record.element), animationName: record.name,
      ...(combined ? {sourceAnimations: group.map(sourceInfo)} : {}),
      durationMs: duration, delayMs: delay, base: 'final',
      sampling: {method: 'computed-css-timing', sampleCount: samples.length, easingApproximation: true},
      bounds: {x: last.x - rootRect.x - width / 2, y: last.y - rootRect.y - height / 2, width, height},
      origin: {x: last.x - rootRect.x, y: last.y - rootRect.y},
      keyframes: samples.map(value => ({offset: value.offset, opacity: value.opacity,
        x: value.x - last.x, y: value.y - last.y, scaleX: value.scaleX / last.scaleX,
        scaleY: value.scaleY / last.scaleY, rotation: value.rotation - last.rotation}))});
    targets.set(record.element, [record.id]);
  }

  // Capture all selected states before cancelling one effect: siblings may share
  // custom properties and animation composition must not depend on cancel order.
  const snapshots = records.filter(record => !record.scroll).map(record => ({record,
    values: record.properties.map(property => [property, read(record).getPropertyValue(property)])}));
  let pseudoLayer;
  for (const {record, values} of snapshots) {
    let style = record.element.style;
    if (record.pseudo) {
      if (!pseudoLayer) {
        const sheet = document.createElement('style'); document.head.prepend(sheet);
        sheet.sheet.insertRule('@layer convertX2XAnimationSnapshot {}');
        pseudoLayer = sheet.sheet.cssRules[0];
      }
      // Attribute names and selector values are generated, never input text.
      const attribute = `data-convertx2x-animation-${record.id}`;
      record.element.setAttribute(attribute, '');
      const index = pseudoLayer.cssRules.length;
      pseudoLayer.insertRule(`[${attribute}]${record.pseudo} {}`, index);
      style = pseudoLayer.cssRules[index].style;
    }
    for (const [property, value] of values) style.setProperty(property, value, 'important');
    style.setProperty('animation', 'none', 'important');
    style.setProperty('transition', 'none', 'important');
  }
  for (const record of records) record.animation.cancel();
  // Transitions require an external state change. A declaration alone does not
  // tell us which hover/class/interaction state the author intends to export.
  const transitionTargets = [...document.querySelectorAll('[data-slide], [data-slide] *')].filter(element =>
    getComputedStyle(element).transitionDuration.split(',').some(value => number(value) > 0));
  for (const element of transitionTargets.slice(0, maxAnimations)) warnings.push({code: 'TRANSITION_STATIC_FALLBACK',
    reason: 'transition-not-triggered', slide: slideOf(element), element: info(element),
    message: 'CSSトランジションの変化先は自動推測せず、現在の静止状態で出力しました。'});
  if (transitionTargets.length > maxAnimations) fail('CSSトランジションの数が上限を超えています。');
  return {effects, staticEffects, warnings, animationCount: records.length, nativeCount: effects.length,
    staticCount: staticEffects.length, transitionCount: transitionTargets.length};
}

/** Run after pseudo-element materialization so no copied effect can restart. */
export function freezeAnimations() {
  const style = document.createElement('style');
  style.textContent = '@layer convertX2XAnimationStop { *,*::before,*::after {animation:none!important;transition:none!important} }';
  document.head.prepend(style);
  for (const animation of document.getAnimations()) animation.cancel();
}
