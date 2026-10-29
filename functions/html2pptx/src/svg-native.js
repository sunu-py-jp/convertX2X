import svgpath from 'svgpath';
import polygonClipping from 'polygon-clipping';
import { FONT_FAMILY } from './fonts.js';

const NS_A = 'http://schemas.openxmlformats.org/drawingml/2006/main';
const NS_P = 'http://schemas.openxmlformats.org/presentationml/2006/main';
const escapeXml = (value) => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&apos;');
const integer = (value) => Math.round(value);
const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
function unsupported(message) {
  const error = new Error(message);
  error.code = 'UNSUPPORTED_SVG';
  return error;
}

/** Runs only as trusted code in the isolated browser; input scripts are never evaluated. */
export function extractSvgScene(svg, fallbackFont = 'Noto Sans JP') {
  const fail = (message) => { throw new Error(`UNSUPPORTED_SVG: ${message}`); };
  const ns = 'http://www.w3.org/2000/svg';
  if (/<!DOCTYPE|<!ENTITY/i.test(svg)) fail('SVG document type and entity declarations are unsupported.');
  const source = new DOMParser().parseFromString(svg, 'image/svg+xml');
  if (source.querySelector('parsererror') || source.documentElement.localName !== 'svg') fail('Invalid SVG XML.');
  const allowed = new Set(['svg', 'g', 'defs', 'rect', 'circle', 'ellipse', 'line', 'polyline', 'polygon', 'path', 'text', 'tspan', 'linearGradient', 'radialGradient', 'stop', 'marker', 'use', 'clipPath', 'title', 'desc', 'style']);
  const nodes = [...source.querySelectorAll('*')];
  if (nodes.length > 20000 || svg.length > 8 * 1024 * 1024) fail('SVG complexity limit exceeded.');
  for (const element of nodes) {
    if (element.namespaceURI !== ns) fail('Only SVG-namespace elements are allowed.');
    if (!allowed.has(element.localName)) fail(`Unsupported SVG element: ${element.localName}.`);
    for (const attribute of element.attributes) {
      if (/^on/i.test(attribute.name)) fail('SVG event handlers are not allowed.');
      if (attribute.localName === 'href' && !attribute.value.trim().startsWith('#')) fail('External SVG references are not allowed.');
      if (/\\/.test(attribute.value)) fail('Escaped SVG attributes are unsupported.');
      if (/url\s*\(/i.test(attribute.value) && [...attribute.value.matchAll(/url\s*\(([^)]*)\)/gi)].some((m) => !/^\s*['"]?#[^\s'"]+['"]?\s*$/.test(m[1]))) fail('External paint references are not allowed.');
    }
    const css = element.localName === 'style' ? element.textContent : element.getAttribute('style') || '';
    if (/@import|@font-face|\\|(?:image|image-set|-webkit-image-set|paint|src)\s*\(/i.test(css) || [...css.matchAll(/url\s*\(([^)]*)\)/gi)].some((m) => !/^\s*['"]?#[^\s'"]+['"]?\s*$/.test(m[1]))) fail('External SVG styles are not allowed.');
  }
  const host = document.createElement('div');
  host.style.cssText = 'position:fixed;left:0;top:0;opacity:0;pointer-events:none;z-index:-2147483647;';
  const shadow = host.attachShadow({ mode: 'closed' });
  const root = document.importNode(source.documentElement, true);
  shadow.append(root);
  document.body.append(host);
  try {
    for(const element of [root,...root.querySelectorAll('*')])element.style.setProperty('font-family',fallbackFont,'important');
    const vb = root.viewBox.baseVal;
    const number = (value, defaultValue = 0) => Number.isFinite(parseFloat(value)) ? parseFloat(value) : defaultValue;
    let viewWidth = number(root.getAttribute('width'), vb.width || 300);
    let viewHeight = number(root.getAttribute('height'), vb.height || 150);
    if (root.getAttribute('width')?.includes('%')) viewWidth = vb.width || 300;
    if (root.getAttribute('height')?.includes('%')) viewHeight = vb.height || 150;
    if (viewWidth <= 0 || viewHeight <= 0 || viewWidth > 20000 || viewHeight > 20000) fail('Invalid SVG dimensions.');
    root.setAttribute('width', String(viewWidth)); root.setAttribute('height', String(viewHeight));
    root.style.width = `${viewWidth}px`; root.style.height = `${viewHeight}px`;
    const findId = (id) => [...root.querySelectorAll('[id]')].find((el) => el.id === id);
    const reference = (value) => {
      if (!value || value === 'none') return null;
      const match = value.match(/url\(["']?(?:[^#)]*#)([^)'" ]+)["']?\)/);
      if (!match) fail('Unsupported SVG paint reference.');
      const node = findId(match[1]);
      if (!node) fail(`SVG reference not found: ${match[1]}.`);
      return node;
    };
    // Resolve local <use> before measuring. A strict expansion bound also rejects cycles.
    let expansion = 0, expandedNodes = nodes.length;
    for (let use = root.querySelector('use'); use; use = root.querySelector('use')) {
      if (++expansion > 2000) fail('Cyclic or excessive SVG use references.');
      const href = use.getAttribute('href') || use.getAttributeNS('http://www.w3.org/1999/xlink', 'href');
      const target = href?.startsWith('#') ? findId(href.slice(1)) : null;
      if (!target || ['defs', 'marker', 'clipPath', 'linearGradient', 'radialGradient'].includes(target.localName)) fail('Unsupported SVG use reference.');
      const cloneNodes = target.querySelectorAll('*').length + 1;
      if (expandedNodes + cloneNodes > 20000) fail('Expanded SVG use node limit exceeded.');
      expandedNodes += cloneNodes;
      // The measured matrix includes CSS transform/origin and individual rotate/
      // translate/scale properties. The use x/y translation is geometry and must
      // be appended separately before the referenced element's own transform.
      const screen = use.getScreenCTM(), parentScreen = use.parentElement.getScreenCTM?.();
      if (!screen || !parentScreen) fail('Unmeasurable SVG use transform.');
      const local = parentScreen.inverse().multiply(screen).translate(use.x.baseVal.value, use.y.baseVal.value);
      const group = document.createElementNS(ns, 'g');
      for (const attr of use.attributes) if (!['href', 'x', 'y', 'width', 'height', 'transform'].includes(attr.localName)) group.setAttribute(attr.name, attr.value);
      group.style.setProperty('transform', `matrix(${[local.a, local.b, local.c, local.d, local.e, local.f].join(',')})`, 'important');
      group.style.setProperty('transform-origin', '0 0', 'important');
      group.style.setProperty('transform-box', 'view-box', 'important');
      for (const property of ['translate', 'rotate', 'scale']) group.style.setProperty(property, 'none', 'important');
      const clone = target.cloneNode(true); clone.removeAttribute('id'); group.append(clone); use.replaceWith(group);
    }
    const origin = root.getBoundingClientRect();
    const matrix = (element) => {
      const m = element.getScreenCTM();
      if (!m) fail('Unmeasurable SVG transform.');
      return [m.a, m.b, m.c, m.d, m.e - origin.x, m.f - origin.y];
    };
    const point = (m, x, y) => ({ x: m[0] * x + m[2] * y + m[4], y: m[1] * x + m[3] * y + m[5] });
    const bounds = (element, m) => {
      const b = element.getBBox();
      const pts = [point(m, b.x, b.y), point(m, b.x + b.width, b.y), point(m, b.x + b.width, b.y + b.height), point(m, b.x, b.y + b.height)];
      const xs = pts.map((p) => p.x), ys = pts.map((p) => p.y);
      return { x: Math.min(...xs), y: Math.min(...ys), width: Math.max(...xs) - Math.min(...xs), height: Math.max(...ys) - Math.min(...ys) };
    };
    const canvas = document.createElement('canvas'); canvas.width = 1; canvas.height = 1;
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    const color = (input) => {
      ctx.clearRect(0, 0, 1, 1); ctx.fillStyle = '#000000'; ctx.fillStyle = input; ctx.fillRect(0, 0, 1, 1);
      const d = ctx.getImageData(0, 0, 1, 1).data;
      return { color: [...d.slice(0, 3)].map((v) => v.toString(16).padStart(2, '0')).join('').toUpperCase(), alpha: d[3] / 255 };
    };
    const effectiveOpacity = (element) => {
      let value = 1;
      for (let e = element; e && e !== host; e = e.parentElement) value *= number(getComputedStyle(e).opacity, 1);
      return value;
    };
    const paint = (input, opacity, element) => {
      if (input === 'none' || !input || opacity === 0) return null;
      if (!input.startsWith('url(')) { const c = color(input); return { ...c, alpha: c.alpha * opacity }; }
      const gradient = reference(input);
      if (gradient.localName !== 'linearGradient') fail('Only linear SVG gradients are supported.');
      if (gradient.hasAttribute('href') || gradient.hasAttribute('xlink:href')) fail('Inherited SVG gradients are not supported.');
      if ((gradient.getAttribute('spreadMethod') || 'pad') !== 'pad') fail('Repeating SVG gradients are not supported.');
      let previousOffset = 0;
      const stops = [...gradient.querySelectorAll('stop')].map((s) => {
        const cs = getComputedStyle(s), c = color(cs.stopColor);
        const raw = s.getAttribute('offset') || '0';
        previousOffset = Math.max(previousOffset, Math.max(0, Math.min(1, number(raw) / (raw.includes('%') ? 100 : 1))));
        return { offset: previousOffset, color: c.color, alpha: c.alpha * number(cs.stopOpacity, 1) * opacity };
      });
      if (!stops.length || stops.length > 256) fail('Invalid SVG gradient stops.');
      const bbox = element.getBBox(), object = gradient.getAttribute('gradientUnits') !== 'userSpaceOnUse';
      const coord = (name, fallback, axis) => {
        const raw = gradient.getAttribute(name) || fallback;
        const v = number(raw) / (raw.includes('%') ? 100 : 1);
        return object ? (axis === 'x' ? bbox.x + bbox.width * v : bbox.y + bbox.height * v) : (raw.includes('%') ? v * (axis === 'x' ? (vb.width || viewWidth) : (vb.height || viewHeight)) : v);
      };
      const m = element.getScreenCTM();
      let g = new DOMMatrix();
      const consolidated = gradient.gradientTransform.baseVal.consolidate();
      if (consolidated) g = new DOMMatrix([consolidated.matrix.a, consolidated.matrix.b, consolidated.matrix.c, consolidated.matrix.d, consolidated.matrix.e, consolidated.matrix.f]);
      const p1 = new DOMPoint(coord('x1', '0%', 'x'), coord('y1', '0%', 'y')).matrixTransform(g).matrixTransform(m);
      const p2 = new DOMPoint(coord('x2', '100%', 'x'), coord('y2', '0%', 'y')).matrixTransform(g).matrixTransform(m);
      return { type: 'linearGradient', stops, angle: Math.atan2(p2.y - p1.y, p2.x - p1.x) * 180 / Math.PI };
    };
    const warnings = [];
    const items = [];
    let totalTextChars = 0, totalPathChars = 0;
    const svgLength = (value, element, fallback = 0) => {
      if (!String(value).trim().endsWith('%')) return number(value, fallback);
      const viewport = element.ownerSVGElement || root, box = viewport.viewBox.baseVal;
      const w = box.width || viewport.width.baseVal.value, h = box.height || viewport.height.baseVal.value;
      return number(value, fallback) * Math.hypot(w, h) / Math.SQRT2 / 100;
    };
    function appearance(element, m) {
      const cs = getComputedStyle(element), opacity = effectiveOpacity(element);
      if (cs.filter !== 'none' || cs.maskImage !== 'none') fail('SVG filters and masks are unsupported.');
      const lineScale = cs.vectorEffect === 'non-scaling-stroke' ? 1 : Math.sqrt(Math.abs(m[0] * m[3] - m[1] * m[2]));
      const dashRaw = cs.strokeDasharray === 'none' ? [] : cs.strokeDasharray.split(/[ ,]+/).filter(Boolean).map((value) => svgLength(value, element));
      const dash = dashRaw.map((value) => value * lineScale);
      if (dash.some((v) => !Number.isFinite(v) || v < 0)) fail('Invalid SVG dash pattern.');
      return {
        fill: paint(cs.fill, opacity * number(cs.fillOpacity, 1), element),
        stroke: paint(cs.stroke, opacity * number(cs.strokeOpacity, 1), element),
        strokeWidth: svgLength(cs.strokeWidth, element, 1) * lineScale, strokeWidthRaw: svgLength(cs.strokeWidth, element, 1), miterLimit: number(cs.strokeMiterlimit, 4),
        dash, dashRaw,
        dashOffset: svgLength(cs.strokeDashoffset, element), nonScalingStroke: cs.vectorEffect === 'non-scaling-stroke',
        linecap: cs.strokeLinecap, linejoin: cs.strokeLinejoin,
        fillRule: cs.fillRule,
      };
    }
    const geometric = new Set(['rect', 'circle', 'ellipse', 'line', 'polyline', 'polygon', 'path']);
    function checkViewports(element, box = element.getBBox()) {
      const elementMatrix = element.getScreenCTM();
      if (!elementMatrix || (!box.width && !box.height)) return;
      const style = getComputedStyle(element);
      const strokeWidth = style.stroke !== 'none' && number(style.strokeOpacity, 1) > 0 ? svgLength(style.strokeWidth, element) : 0;
      for (let viewport = element.parentElement; viewport; viewport = viewport.parentElement) {
        if (viewport.localName !== 'svg') continue;
        const viewportStyle = getComputedStyle(viewport);
        // The picture viewport is always finite. Nested SVGs can explicitly allow overflow.
        const clipX = viewport === root || viewportStyle.overflowX !== 'visible';
        const clipY = viewport === root || viewportStyle.overflowY !== 'visible';
        if (!clipX && !clipY) continue;
        const width = viewport.width.baseVal.value, height = viewport.height.baseVal.value;
        if (!(width > 0 && height > 0)) fail('Invalid nested SVG viewport.');
        const viewBox = viewport.viewBox.baseVal, aspect = viewport.preserveAspectRatio.baseVal;
        let viewBoxMatrix = new DOMMatrix();
        if (viewBox.width > 0 && viewBox.height > 0) {
          let vx = width / viewBox.width, vy = height / viewBox.height, dx = 0, dy = 0;
          if (aspect.align !== 1) {
            vx = vy = aspect.meetOrSlice === 2 ? Math.max(vx, vy) : Math.min(vx, vy);
            const alignment = Math.max(0, aspect.align - 2);
            dx = (width - viewBox.width * vx) * (alignment % 3) / 2;
            dy = (height - viewBox.height * vy) * Math.floor(alignment / 3) / 2;
          }
          viewBoxMatrix = new DOMMatrix([vx, 0, 0, vy, dx - viewBox.x * vx, dy - viewBox.y * vy]);
        }
        // getScreenCTM includes viewBox fitting; remove it to compare with the viewport rectangle.
        const screenToViewport = viewBoxMatrix.multiply(viewport.getScreenCTM().inverse());
        const local = screenToViewport.multiply(elementMatrix);
        const points = [[box.x, box.y], [box.x + box.width, box.y], [box.x, box.y + box.height], [box.x + box.width, box.y + box.height]]
          .map(([x, y]) => new DOMPoint(x, y).matrixTransform(local));
        if (points.some((p) => !Number.isFinite(p.x) || !Number.isFinite(p.y))) fail('Invalid SVG viewport transform.');
        const strokeMatrix = style.vectorEffect === 'non-scaling-stroke' ? screenToViewport : local;
        const strokeX = strokeWidth * Math.hypot(strokeMatrix.a, strokeMatrix.c) / 2;
        const strokeY = strokeWidth * Math.hypot(strokeMatrix.b, strokeMatrix.d) / 2;
        // Thin strokes centred on an edge are common; tolerate at most 2 viewport pixels.
        const toleranceX = Math.max(0.75, Math.min(2, strokeX)), toleranceY = Math.max(0.75, Math.min(2, strokeY));
        if ((clipX && (Math.min(...points.map((p) => p.x)) - strokeX < -toleranceX || Math.max(...points.map((p) => p.x)) + strokeX > width + toleranceX))
          || (clipY && (Math.min(...points.map((p) => p.y)) - strokeY < -toleranceY || Math.max(...points.map((p) => p.y)) + strokeY > height + toleranceY))) {
          fail('SVG content extends beyond its viewport and would require clipping.');
        }
        if (viewport === root) break;
      }
    }
    function checkClips(element) {
      for (let e = element; e && e !== root.parentElement; e = e.parentElement) {
        const clip = getComputedStyle(e).clipPath;
        if (clip && clip !== 'none') {
          const node = reference(clip), children = [...node.children].filter((c) => !['title', 'desc'].includes(c.localName));
          if (node.localName !== 'clipPath' || children.length !== 1 || children[0].localName !== 'rect' || node.getAttribute('clipPathUnits') === 'objectBoundingBox') fail('Complex SVG clipping is unsupported.');
          const r = children[0];
          if (number(r.getAttribute('rx')) || number(r.getAttribute('ry')) || r.hasAttribute('transform') || node.hasAttribute('transform')) fail('Rounded or transformed SVG clipping is unsupported.');
          const cm = matrix(e), clipPoints = [point(cm, number(r.getAttribute('x')), number(r.getAttribute('y'))), point(cm, number(r.getAttribute('x')) + number(r.getAttribute('width')), number(r.getAttribute('y')) + number(r.getAttribute('height')))];
          const b = bounds(element, matrix(element));
          const cx = Math.min(...clipPoints.map((p) => p.x)), cy = Math.min(...clipPoints.map((p) => p.y));
          const cw = Math.abs(clipPoints[1].x - clipPoints[0].x), ch = Math.abs(clipPoints[1].y - clipPoints[0].y);
          if (b.x < cx - 0.1 || b.y < cy - 0.1 || b.x + b.width > cx + cw + 0.1 || b.y + b.height > cy + ch + 0.1) fail('SVG clipping would remove visible geometry.');
        }
        if (e === root) break;
      }
    }
    function textItems(element) {
      const firstItem = items.length;
      totalTextChars += element.textContent.length;
      if (totalTextChars > 100000) fail('SVG text complexity limit exceeded.');
      const texts = [], walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
      let textNode;
      while ((textNode = walker.nextNode())) {
        const cs = getComputedStyle(textNode.parentElement);
        for (const char of textNode.nodeValue) texts.push({ char, cs, sourceElement: textNode.parentElement });
      }
      const preserve = getComputedStyle(element).whiteSpace.startsWith('pre') || element.getAttribute('xml:space') === 'preserve';
      const chars = [];
      for (const c of texts) {
        if (!preserve && /\s/.test(c.char)) {
          if (chars.length && chars.at(-1).char !== ' ') chars.push({ ...c, char: ' ' });
        } else chars.push(c);
      }
      if (!preserve && chars.at(-1)?.char === ' ') chars.pop();
      const count = element.getNumberOfChars();
      if (chars.reduce((n, c) => n + c.char.length, 0) !== count) fail('SVG whitespace/text indexing is unsupported.');
      const m = matrix(element), sx = Math.hypot(m[0], m[1]), sy = Math.hypot(m[2], m[3]);
      if (!sx || !sy || Math.abs(m[0] * m[2] + m[1] * m[3]) > 1e-4 * sx * sy) fail('Skewed SVG text is unsupported.');
      if (m[0] * m[3] - m[1] * m[2] < 0) fail('Mirrored SVG text is unsupported.');
      if (Math.abs(sx - sy) > 0.01 * Math.max(sx, sy)) fail('Non-uniformly scaled SVG text is unsupported.');
      let current = null, index = 0;
      for (const { char, cs, sourceElement } of chars) {
        const start = element.getStartPositionOfChar(index), end = element.getEndPositionOfChar(index + char.length - 1), box = element.getExtentOfChar(index);
        const rot = element.getRotationOfChar(index);
        if (Math.abs(rot) > 0.001) fail('Per-character SVG rotation is unsupported.');
        const opacity = effectiveOpacity(sourceElement) * number(cs.fillOpacity, 1);
        if (cs.visibility === 'hidden' || cs.visibility === 'collapse' || opacity === 0 || cs.fill === 'none' && cs.stroke === 'none') {
          current = null; index += char.length; continue;
        }
        if (cs.filter !== 'none' || cs.maskImage !== 'none') fail('SVG text filters and masks are unsupported.');
        if (cs.fill.startsWith('url(') || cs.stroke !== 'none') fail('Gradient or outlined SVG text is unsupported.');
        const c = color(cs.fill), fontSize = number(cs.fontSize, 16);
        if (!c.alpha) { current = null; index += char.length; continue; }
        if (cs.writingMode !== 'horizontal-tb') fail('Vertical SVG text is unsupported.');
        const style = { fontFamily: cs.fontFamily.split(',')[0].trim().replace(/^["']|["']$/g, '') || fallbackFont, fontSize: fontSize * sy, bold: number(cs.fontWeight, 400) >= 600, italic: cs.fontStyle === 'italic', underline: cs.textDecorationLine.includes('underline'), strike: cs.textDecorationLine.includes('line-through'), color: c.color, alpha: c.alpha * opacity };
        const key = JSON.stringify(style);
        if (!current || current.key !== key || Math.abs(start.y - current.baseline) > 0.1 || Math.abs(start.x - current.end) > Math.max(0.3, fontSize * 0.025)) {
          current = { type: 'text', text: char, key, style, start: { x: start.x, y: start.y }, baseline: start.y, end: end.x, top: box.y, bottom: box.y + box.height, matrix: m };
          items.push(current);
        } else {
          current.text += char; current.end = end.x; current.top = Math.min(current.top, box.y); current.bottom = Math.max(current.bottom, box.y + box.height);
        }
        index += char.length;
      }
      for (const item of items.slice(firstItem)) checkViewports(element, { x: item.start.x, y: item.top, width: item.end - item.start.x, height: item.bottom - item.top });
    }
    function markers(element, depth) {
      if (!['line', 'polyline', 'polygon', 'path'].includes(element.localName)) return;
      const cs = getComputedStyle(element);
      if (cs.markerMid !== 'none') fail('SVG marker-mid is not supported.');
      const length = element.getTotalLength();
      if (!Number.isFinite(length) || length <= 0) return;
      for (const [side, value] of [['start', cs.markerStart], ['end', cs.markerEnd]]) {
        if (!value || value === 'none') continue;
        const marker = reference(value);
        if (marker.localName !== 'marker') fail('Invalid SVG marker.');
        if (depth > 4) fail('Nested SVG markers are too deep.');
        if (expandedNodes + marker.querySelectorAll('*').length + 1 > 20000) fail('Expanded SVG marker node limit exceeded.');
        const location = element.getPointAtLength(side === 'start' ? 0 : length);
        const near = element.getPointAtLength(side === 'start' ? Math.min(length, 0.01) : Math.max(0, length - 0.01));
        let angle = Math.atan2(side === 'start' ? near.y - location.y : location.y - near.y, side === 'start' ? near.x - location.x : location.x - near.x) * 180 / Math.PI;
        const orient = marker.getAttribute('orient') || '0';
        if (orient === 'auto-start-reverse' && side === 'start') angle += 180;
        else if (!['auto', 'auto-start-reverse'].includes(orient)) angle = marker.orientAngle.baseVal.value;
        const units = marker.getAttribute('markerUnits') === 'userSpaceOnUse' ? 1 : svgLength(cs.strokeWidth, element, 1);
        const mw = number(marker.getAttribute('markerWidth'), 3), mh = number(marker.getAttribute('markerHeight'), 3);
        const bv = marker.viewBox.baseVal, refX = number(marker.getAttribute('refX')), refY = number(marker.getAttribute('refY'));
        let vsx = 1, vsy = 1;
        if (bv.width && bv.height) {
          vsx = mw / bv.width; vsy = mh / bv.height;
          if ((marker.getAttribute('preserveAspectRatio') || '') !== 'none') vsx = vsy = Math.min(vsx, vsy);
        }
        const em = element.getScreenCTM(), rm = root.getScreenCTM().inverse().multiply(em);
        const group = document.createElementNS(ns, 'g');
        group.setAttribute('transform', `matrix(${rm.a} ${rm.b} ${rm.c} ${rm.d} ${rm.e} ${rm.f}) translate(${location.x} ${location.y}) rotate(${angle}) scale(${units}) scale(${vsx} ${vsy}) translate(${-refX} ${-refY})`);
        const markerStyle = getComputedStyle(marker);
        const contextPaint = (value) => value === 'context-stroke' ? cs.stroke : value === 'context-fill' ? cs.fill : value;
        group.style.fill = contextPaint(markerStyle.fill); group.style.stroke = contextPaint(markerStyle.stroke);
        group.style.strokeWidth = markerStyle.strokeWidth;
        group.style.opacity = String(effectiveOpacity(element) * number(markerStyle.opacity, 1) / Math.max(effectiveOpacity(root), 0.000001));
        for (const child of marker.children) {
          const clone = child.cloneNode(true);
          for (const e of [clone, ...clone.querySelectorAll('*')]) for (const attr of ['fill', 'stroke']) {
            if (e.getAttribute(attr) === 'context-stroke') e.setAttribute(attr, cs.stroke);
            if (e.getAttribute(attr) === 'context-fill') e.setAttribute(attr, cs.fill);
            if (e.style[attr]) e.style[attr] = contextPaint(e.style[attr]);
          }
          group.append(clone);
        }
        root.append(group);
        walk(group, depth + 1);
        group.remove();
      }
    }
    function walk(element, depth = 0) {
      const cs = getComputedStyle(element);
      if (cs.display === 'none' || cs.visibility === 'hidden' || effectiveOpacity(element) === 0) return;
      if (cs.filter !== 'none' || cs.maskImage !== 'none') fail('SVG filters and masks are unsupported.');
      if (['defs', 'marker', 'clipPath', 'linearGradient', 'radialGradient', 'title', 'desc', 'style'].includes(element.localName)) return;
      if (geometric.has(element.localName)) {
        const m = matrix(element), b = bounds(element, m), style = appearance(element, m);
        const visiblePaint = (paint) => paint && (paint.type === 'linearGradient' ? paint.stops.some((stop) => stop.alpha > 0) : paint.alpha > 0);
        if ((element.localName !== 'line' && visiblePaint(style.fill)) || (style.strokeWidth > 0 && visiblePaint(style.stroke))) checkViewports(element);
        checkClips(element);
        const attr = (name) => Number.isFinite(element[name]?.baseVal?.value) ? element[name].baseVal.value : number(element.getAttribute(name));
        const localBox = element.getBBox();
        const item = { type: element.localName, matrix: m, bounds: b, style };
        if (item.type === 'rect') {
          const radius = (name, axis) => {
            if (!cs[name] || cs[name] === 'auto') return attr(name);
            const viewport = element.ownerSVGElement || root, box = viewport.viewBox.baseVal;
            const dimension = axis === 'x' ? box.width || viewport.width.baseVal.value : box.height || viewport.height.baseVal.value;
            return cs[name].endsWith('%') ? number(cs[name]) * dimension / 100 : number(cs[name]);
          };
          const rx = radius('rx', 'x'), ry = radius('ry', 'y');
          Object.assign(item, { x: localBox.x, y: localBox.y, width: localBox.width, height: localBox.height, rx: rx || ry, ry: ry || rx });
        }
        else if (item.type === 'circle' || item.type === 'ellipse') Object.assign(item, { cx: localBox.x + localBox.width / 2, cy: localBox.y + localBox.height / 2, rx: localBox.width / 2, ry: localBox.height / 2 });
        else if (item.type === 'line') item.d = `M ${attr('x1')} ${attr('y1')} L ${attr('x2')} ${attr('y2')}`;
        else if (item.type === 'polyline' || item.type === 'polygon') {
          const p = [...element.points].map((v) => `${v.x} ${v.y}`); item.d = p.length ? `M ${p.join(' L ')}${item.type === 'polygon' ? ' Z' : ''}` : '';
        } else item.d = element.getAttribute('d') || '';
        totalPathChars += item.d?.length || 0;
        if ((item.d?.length || 0) > 200000 || totalPathChars > 1000000) fail('SVG path complexity limit exceeded.');
        if (item.type === 'line') item.style.fill = null;
        items.push(item);
        markers(element, depth);
      } else if (element.localName === 'text') { checkClips(element); textItems(element); }
      else if (element.localName === 'g' || element.localName === 'svg') for (const child of [...element.children]) walk(child, depth);
      else fail(`Unsupported SVG element: ${element.localName}.`);
      if (items.length > 20000) fail('SVG output shape limit exceeded.');
    }
    walk(root);
    return { width: viewWidth, height: viewHeight, items, warnings };
  } finally { host.remove(); }
}

function solidPaint(paint) {
  return `<a:srgbClr val="${escapeXml(paint.color)}"><a:alpha val="${integer(clamp(paint.alpha ?? 1, 0, 1) * 100000)}"/></a:srgbClr>`;
}
function fillXml(paint) {
  if (!paint) return '<a:noFill/>';
  if (paint.type === 'linearGradient') {
    const stops = paint.stops.map((s) => `<a:gs pos="${integer(s.offset * 100000)}">${solidPaint(s)}</a:gs>`).join('');
    return `<a:gradFill rotWithShape="1"><a:gsLst>${stops}</a:gsLst><a:lin ang="${integer(((paint.angle % 360 + 360) % 360) * 60000)}" scaled="1"/></a:gradFill>`;
  }
  return `<a:solidFill>${solidPaint(paint)}</a:solidFill>`;
}
// Common system dash patterns have consistent native support. Other SVG patterns (and
// offsets) are emitted as editable freeform subpaths, avoiding consumers that ignore custDash.
function dashPreset(style) {
  if (!style.dash?.length || style.dashOffset) return null;
  const pattern = style.dash.length % 2 ? [...style.dash, ...style.dash] : style.dash;
  for (const [name, values] of [['sysDot', [1, 1]], ['sysDash', [2, 2]]]) {
    if (pattern.length === values.length && values.every((v, i) => Math.abs(pattern[i] / Math.max(style.strokeWidth, 0.00001) - v) < 0.00001)) return name;
  }
  return null;
}
function bakedDash(style) { return Boolean(style.stroke && style.dash?.some((v) => v > 0) && !dashPreset(style)); }

function geometryPath(item) {
  if (item.type === 'rect') {
    const { x, y, width: w, height: h } = item, rx = Math.min(item.rx || 0, w / 2), ry = Math.min(item.ry || 0, h / 2);
    return rx && ry ? `M ${x + rx} ${y} H ${x + w - rx} A ${rx} ${ry} 0 0 1 ${x + w} ${y + ry} V ${y + h - ry} A ${rx} ${ry} 0 0 1 ${x + w - rx} ${y + h} H ${x + rx} A ${rx} ${ry} 0 0 1 ${x} ${y + h - ry} V ${y + ry} A ${rx} ${ry} 0 0 1 ${x + rx} ${y} Z` : `M ${x} ${y} H ${x + w} V ${y + h} H ${x} Z`;
  }
  if (['circle', 'ellipse'].includes(item.type)) return `M ${item.cx + item.rx} ${item.cy} A ${item.rx} ${item.ry} 0 1 1 ${item.cx - item.rx} ${item.cy} A ${item.rx} ${item.ry} 0 1 1 ${item.cx + item.rx} ${item.cy} Z`;
  return item.d || '';
}

// Adaptive curve flattening keeps dash geometry within 0.025 output CSS px. Each
// SVG subpath restarts its dash phase, while dashoffset and closed seams are retained.
function flattenPath(d, transform, tolerance) {
  const normalized = svgpath(d).unarc().unshort().abs().transform(`matrix(${transform.join(' ')})`).abs();
  if (normalized.err) throw unsupported('Invalid SVG stroke geometry.');
  const subpaths = []; let subpath, cursor = [0, 0], start = [0, 0], points = 0;
  const append = (p) => { if (++points > 200000) throw unsupported('Dashed SVG path complexity limit exceeded.'); subpath.points.push(p); };
  const mid = (a, b) => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
  const distance = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1]);
  const cubic = (p0, p1, p2, p3, depth = 0) => {
    const chord = distance(p0, p3), perimeter = distance(p0, p1) + distance(p1, p2) + distance(p2, p3);
    const deviation = chord ? Math.max(Math.abs((p3[0] - p0[0]) * (p0[1] - p1[1]) - (p0[0] - p1[0]) * (p3[1] - p0[1])), Math.abs((p3[0] - p0[0]) * (p0[1] - p2[1]) - (p0[0] - p2[0]) * (p3[1] - p0[1]))) / chord : perimeter;
    if (depth >= 18 || deviation <= tolerance && perimeter - chord <= tolerance) { append(p3); return; }
    const p01 = mid(p0, p1), p12 = mid(p1, p2), p23 = mid(p2, p3), p012 = mid(p01, p12), p123 = mid(p12, p23), p = mid(p012, p123);
    cubic(p0, p01, p012, p, depth + 1); cubic(p, p123, p23, p3, depth + 1);
  };
  normalized.iterate((s) => {
    const op = s[0];
    if (op === 'M') { cursor = [s[1], s[2]]; start = cursor; subpath = { points: [cursor], closed: false }; subpaths.push(subpath); }
    else if (op === 'L' || op === 'H' || op === 'V') { cursor = op === 'L' ? [s[1], s[2]] : op === 'H' ? [s[1], cursor[1]] : [cursor[0], s[1]]; append(cursor); }
    else if (op === 'C') { const end = [s[5], s[6]]; cubic(cursor, [s[1], s[2]], [s[3], s[4]], end); cursor = end; }
    else if (op === 'Q') { const q = [s[1], s[2]], end = [s[3], s[4]]; cubic(cursor, [cursor[0] + (q[0] - cursor[0]) * 2 / 3, cursor[1] + (q[1] - cursor[1]) * 2 / 3], [end[0] + (q[0] - end[0]) * 2 / 3, end[1] + (q[1] - end[1]) * 2 / 3], end); cursor = end; }
    else if (op === 'Z') { append(start); cursor = start; subpath.closed = true; }
    else throw unsupported(`Unsupported dashed SVG path command: ${op}.`);
  });
  return subpaths;
}
function dashedPath(item) {
  const m = item.matrix, scale = Math.max(Math.hypot(m[0], m[1], m[2], m[3]), 0.001);
  const tolerance = 0.025 / (item.style.nonScalingStroke ? 1 : scale);
  const subpaths = flattenPath(geometryPath(item), item.style.nonScalingStroke ? m : [1, 0, 0, 1, 0, 0], tolerance);
  const distance = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1]);
  let pattern = item.style.nonScalingStroke ? item.style.dash : item.style.dashRaw || item.style.dash.map((v) => v / Math.sqrt(Math.abs(m[0] * m[3] - m[1] * m[2])));
  if (pattern.length % 2) pattern = [...pattern, ...pattern];
  const period = pattern.reduce((sum, v) => sum + v, 0), paths = [];
  if (!period) return { d: geometryPath(item), matrix: m };
  let pieces = 0;
  for (const part of subpaths) {
    const lengths = [0];
    for (let i = 1; i < part.points.length; i++) lengths.push(lengths[i - 1] + distance(part.points[i - 1], part.points[i]));
    const length = lengths.at(-1);
    if (!length) continue;
    let index = 0, phase = ((item.style.dashOffset || 0) % period + period) % period;
    while (phase > 0 && phase >= pattern[index]) { phase -= pattern[index]; index = (index + 1) % pattern.length; }
    let position = 0, remaining = pattern[index] - phase;
    const ranges = [];
    while (position < length - 1e-9) {
      if (++pieces > 50000) throw unsupported('SVG dash count limit exceeded.');
      const end = Math.min(length, position + remaining);
      if (index % 2 === 0) ranges.push([position, end]);
      position = end; index = (index + 1) % pattern.length; remaining = pattern[index];
    }
    const indexAt = (at) => {
      let lo = 0, hi = lengths.length - 1;
      while (lo + 1 < hi) { const mid = Math.floor((lo + hi) / 2); if (lengths[mid] <= at) lo = mid; else hi = mid; }
      return lo;
    };
    const sample = (at) => {
      const lo = indexAt(at), hi = lo + 1;
      const t = (at - lengths[lo]) / Math.max(lengths[hi] - lengths[lo], 1e-12);
      return [part.points[lo][0] + (part.points[hi][0] - part.points[lo][0]) * t, part.points[lo][1] + (part.points[hi][1] - part.points[lo][1]) * t];
    };
    const segments = ranges.map(([from, to]) => {
      const segment = [sample(from)];
      for (let i = indexAt(from) + 1; i < lengths.length - 1 && lengths[i] < to; i++) if (lengths[i] > from) segment.push(part.points[i]);
      segment.push(sample(to)); return segment;
    });
    if (part.closed && segments.length > 1 && ranges[0][0] === 0 && Math.abs(ranges.at(-1)[1] - length) < 1e-8) {
      segments[0] = [...segments.pop(), ...segments[0].slice(1)];
    }
    for (const segment of segments) {
      if (segment.length === 2 && distance(segment[0], segment[1]) < 1e-12) {
        if (item.style.linecap === 'butt') continue;
        // Some PPTX consumers discard a zero-length line even with round caps. A
        // 0.001px segment retains the native round/square dot without a visible shift.
        segment[1] = [segment[0][0] + 0.001 / (item.style.nonScalingStroke ? 1 : scale), segment[0][1]];
      }
      const fmt = (p) => `${Number(p[0].toFixed(7))} ${Number(p[1].toFixed(7))}`;
      paths.push(`M ${segment.map(fmt).join(' L ')}`);
    }
  }
  return { d: paths.join(' '), matrix: item.style.nonScalingStroke ? [1, 0, 0, 1, 0, 0] : m };
}
function affineStroke(item, sx, sy) {
  if (!item.style.stroke || item.style.strokeWidth <= 0 || item.style.nonScalingStroke) return false;
  const [a, b, c, d] = item.matrix, xx = (a * sx) ** 2 + (b * sy) ** 2, yy = (c * sx) ** 2 + (d * sy) ** 2;
  return Math.abs(xx - yy) > 1e-5 * Math.max(xx, yy) || Math.abs(a * c * sx * sx + b * d * sy * sy) > 1e-5 * Math.max(xx, yy);
}

// Native line widths cannot express an anisotropically transformed stroke. Build its
// editable filled outline before applying the affine transform, for every path shape.
function strokeOutline(item) {
  const dashed = item.style.dash?.some((value) => value > 0) ? dashedPath(item) : { d: geometryPath(item), matrix: item.matrix };
  const m = dashed.matrix, scale = Math.max(Math.hypot(m[0], m[1], m[2], m[3]), 0.001), tolerance = 0.025 / scale;
  const paths = flattenPath(dashed.d, [1, 0, 0, 1, 0, 0], tolerance);
  const half = (item.style.nonScalingStroke ? item.style.strokeWidth : item.style.strokeWidthRaw || item.style.strokeWidth / Math.sqrt(Math.abs(m[0] * m[3] - m[1] * m[2]))) / 2;
  const polygons = []; let totalPoints = 0;
  const distance = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1]);
  const unit = (a, b) => { const length = distance(a, b); return [(b[0] - a[0]) / length, (b[1] - a[1]) / length]; };
  const offset = (p, n, amount) => [p[0] + n[0] * amount, p[1] + n[1] * amount];
  const polygon = (points) => {
    totalPoints += points.length; if (totalPoints > 300000) throw unsupported('SVG stroke outline complexity limit exceeded.');
    const area = points.reduce((sum, p, i) => { const q = points[(i + 1) % points.length]; return sum + p[0] * q[1] - q[0] * p[1]; }, 0);
    polygons.push(area < 0 ? [...points].reverse() : points);
  };
  const disc = (center) => {
    const count = Math.max(12, Math.min(512, Math.ceil(Math.PI / Math.acos(Math.max(-1, 1 - tolerance / Math.max(half, 0.00001))))));
    polygon(Array.from({ length: count }, (_, i) => [center[0] + half * Math.cos(i * Math.PI * 2 / count), center[1] + half * Math.sin(i * Math.PI * 2 / count)]));
  };
  for (const path of paths) {
    const pts = path.points.filter((p, i, all) => !i || distance(p, all[i - 1]) > 1e-10);
    if (path.closed && pts.length > 1 && distance(pts[0], pts.at(-1)) < 1e-9) pts.pop();
    if (pts.length < 2) continue;
    if (pts.length === 2 && distance(pts[0], pts[1]) < 0.002 / scale && item.style.linecap === 'round') { disc(pts[0]); continue; }
    const segmentCount = pts.length - (path.closed ? 0 : 1);
    for (let i = 0; i < segmentCount; i++) {
      let p = pts[i], q = pts[(i + 1) % pts.length]; const direction = unit(p, q), normal = [-direction[1], direction[0]];
      if (!path.closed && item.style.linecap === 'square') { if (i === 0) p = offset(p, direction, -half); if (i === segmentCount - 1) q = offset(q, direction, half); }
      polygon([offset(p, normal, half), offset(q, normal, half), offset(q, normal, -half), offset(p, normal, -half)]);
    }
    for (let i = path.closed ? 0 : 1; i < (path.closed ? pts.length : pts.length - 1); i++) {
      const p = pts[i], incoming = unit(pts[(i + pts.length - 1) % pts.length], p), outgoing = unit(p, pts[(i + 1) % pts.length]);
      const cross = incoming[0] * outgoing[1] - incoming[1] * outgoing[0];
      if (Math.abs(cross) < 1e-9) continue;
      if (item.style.linejoin === 'round') { disc(p); continue; }
      const side = -Math.sign(cross), a = offset(p, [-incoming[1], incoming[0]], half * side), b = offset(p, [-outgoing[1], outgoing[0]], half * side);
      const t = ((b[0] - a[0]) * outgoing[1] - (b[1] - a[1]) * outgoing[0]) / cross;
      const intersection = offset(a, incoming, t);
      if (item.style.linejoin === 'bevel' || distance(p, intersection) > half * (item.style.miterLimit || 4)) polygon([p, a, b]);
      else polygon([p, a, intersection, b]);
    }
    if (!path.closed && item.style.linecap === 'round') { disc(pts[0]); disc(pts.at(-1)); }
  }
  // DrawingML consumers differ in handling overlapping subpaths. A true union
  // gives the same visible region under both even-odd and nonzero fill rules.
  const unionAtPrecision = (precision) => {
    const multiplier = precision * scale;
    let unions = polygons.map((points) => [[points.map(([x, y]) => [Math.round(x * multiplier), Math.round(y * multiplier)])]]);
    while (unions.length > 1) {
      const next = [];
      for (let i = 0; i < unions.length; i += 128) next.push(polygonClipping.union(...unions.slice(i, i + 128)));
      unions = next;
    }
    return unions.length ? unions[0].flat().map((ring) => ring.map(([x, y]) => [x / multiplier, y / multiplier])) : [];
  };
  // Quantized coordinates avoid nearly coincident floating-point sweep edges. The
  // retry changes coordinates by at most 0.0005px, below the curve approximation.
  let outlines;
  try { outlines = unionAtPrecision(10000); }
  catch { try { outlines = unionAtPrecision(1000); } catch { throw unsupported('SVG stroke outline could not be resolved.'); } }
  if (outlines.reduce((sum, ring) => sum + ring.length, 0) > 300000) throw unsupported('SVG stroke outline complexity limit exceeded.');
  const transformed = outlines.flat().map(([x, y]) => [m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]]);
  if (!transformed.length) return null;
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const [x, y] of transformed) { minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); }
  return { type: 'path', d: outlines.map((points) => `M ${points.map((p) => `${p[0]} ${p[1]}`).join(' L ')} Z`).join(' '), matrix: m,
    bounds: { x: minX, y: minY, width: maxX - minX, height: maxY - minY }, style: { fill: item.style.stroke, stroke: null, strokeWidth: 0, dash: [] } };
}
function lineXml(style, scale, forceSolid = false) {
  if (!style.stroke || style.strokeWidth <= 0) return '<a:ln><a:noFill/></a:ln>';
  let dash = '<a:prstDash val="solid"/>';
  if (!forceSolid && dashPreset(style)) dash = `<a:prstDash val="${dashPreset(style)}"/>`;
  else if (!forceSolid && style.dash?.some((v) => v > 0)) {
    const parts = style.dash.length % 2 ? [...style.dash, ...style.dash] : style.dash;
    const pairs = [];
    for (let i = 0; i < parts.length; i += 2) pairs.push(`<a:ds d="${integer(parts[i] / Math.max(style.strokeWidth, 0.01) * 100000)}" sp="${integer(parts[i + 1] / Math.max(style.strokeWidth, 0.01) * 100000)}"/>`);
    dash = `<a:custDash>${pairs.join('')}</a:custDash>`;
  }
  const cap = { butt: 'flat', round: 'rnd', square: 'sq' }[style.linecap] || 'flat';
  const join = style.linejoin === 'round' ? '<a:round/>' : style.linejoin === 'bevel' ? '<a:bevel/>' : `<a:miter lim="${integer((style.miterLimit || 4) * 100000)}"/>`;
  return `<a:ln w="${Math.max(1, integer(style.strokeWidth * scale))}" cap="${cap}">${fillXml(style.stroke)}${dash}${join}</a:ln>`;
}
function xfrm(x, y, width, height, rotation = 0) {
  const rot = rotation ? ` rot="${integer(((rotation % 360 + 360) % 360) * 60000)}"` : '';
  return `<a:xfrm${rot}><a:off x="${integer(x)}" y="${integer(y)}"/><a:ext cx="${Math.max(1, integer(width))}" cy="${Math.max(1, integer(height))}"/></a:xfrm>`;
}
function pathGeometry(d, matrix, bounds, sx, sy, options = {}) {
  if (!d) { const empty = '<a:path w="1" h="1" fill="none" stroke="0"/>'; return options.pathOnly ? empty : customGeometry(empty); }
  const normalized = svgpath(d).unarc().unshort().abs().transform(`matrix(${matrix.join(' ')})`).abs();
  if (normalized.err) throw unsupported('Invalid SVG path geometry.');
  const pt = (x, y) => `<a:pt x="${integer((x - bounds.x) * sx)}" y="${integer((y - bounds.y) * sy)}"/>`;
  let x = 0, y = 0, startX = 0, startY = 0;
  const commands = [];
  normalized.iterate((segment) => {
    const op = segment[0];
    if (op === 'M') { x = startX = segment[1]; y = startY = segment[2]; commands.push(`<a:moveTo>${pt(x, y)}</a:moveTo>`); }
    else if (op === 'L') { x = segment[1]; y = segment[2]; commands.push(`<a:lnTo>${pt(x, y)}</a:lnTo>`); }
    else if (op === 'H') { x = segment[1]; commands.push(`<a:lnTo>${pt(x, y)}</a:lnTo>`); }
    else if (op === 'V') { y = segment[1]; commands.push(`<a:lnTo>${pt(x, y)}</a:lnTo>`); }
    else if (op === 'C') { x = segment[5]; y = segment[6]; commands.push(`<a:cubicBezTo>${pt(segment[1], segment[2])}${pt(segment[3], segment[4])}${pt(x, y)}</a:cubicBezTo>`); }
    else if (op === 'Q') {
      // An exact cubic equivalent also works with consumers shipping the lite OOXML schemas.
      const c1x = x + (segment[1] - x) * 2 / 3, c1y = y + (segment[2] - y) * 2 / 3;
      x = segment[3]; y = segment[4];
      const c2x = x + (segment[1] - x) * 2 / 3, c2y = y + (segment[2] - y) * 2 / 3;
      commands.push(`<a:cubicBezTo>${pt(c1x, c1y)}${pt(c2x, c2y)}${pt(x, y)}</a:cubicBezTo>`);
    }
    else if (op === 'Z') { x = startX; y = startY; commands.push('<a:close/>'); }
    else throw unsupported(`Unsupported SVG path command: ${op}.`);
  });
  const w = Math.max(1, integer(bounds.width * sx)), h = Math.max(1, integer(bounds.height * sy));
  const path = `<a:path w="${w}" h="${h}" fill="${options.fill || 'norm'}" stroke="${options.stroke === false ? 0 : 1}">${commands.join('')}</a:path>`;
  return options.pathOnly ? path : customGeometry(path);
}
function customGeometry(paths) { return `<a:custGeom><a:avLst/><a:gdLst/><a:ahLst/><a:cxnLst/><a:rect l="0" t="0" r="r" b="b"/><a:pathLst>${paths}</a:pathLst></a:custGeom>`; }
function geometryXml(item, sx, sy) {
  const m = item.matrix;
  if (bakedDash(item.style)) {
    const dashed = dashedPath(item);
    const fill = item.style.fill ? pathGeometry(geometryPath(item), m, item.bounds, sx, sy, { pathOnly: true, stroke: false }) : '';
    const outline = pathGeometry(dashed.d, dashed.matrix, item.bounds, sx, sy, { pathOnly: true, fill: 'none' });
    return customGeometry(fill + outline);
  }
  const simple = Math.abs(m[1]) < 1e-6 && Math.abs(m[2]) < 1e-6 && m[0] > 0 && m[3] > 0;
  if (simple && ['circle', 'ellipse'].includes(item.type)) return '<a:prstGeom prst="ellipse"><a:avLst/></a:prstGeom>';
  if (simple && item.type === 'rect') {
    if (!item.rx && !item.ry) return '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom>';
    const rx = Math.min(item.rx, item.width / 2) * m[0] * sx, ry = Math.min(item.ry, item.height / 2) * m[3] * sy;
    if (Math.abs(rx - ry) < Math.max(rx, ry) * 0.001) return `<a:prstGeom prst="roundRect"><a:avLst><a:gd name="adj" fmla="val ${integer(clamp(rx / Math.min(item.bounds.width * sx, item.bounds.height * sy) * 100000, 0, 50000))}"/></a:avLst></a:prstGeom>`;
  }
  return pathGeometry(geometryPath(item), m, item.bounds, sx, sy);
}

/** Serializes measured SVG elements as native DrawingML, without bitmap fallbacks. */
export function svgSceneToDrawingML(scene, { x = 0, y = 0, width, height, idStart = 1 } = {}) {
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0 || !scene.width || !scene.height) throw unsupported('Invalid SVG placement.');
  const sx = width / scene.width, sy = height / scene.height;
  let id = idStart;
  const groupId = id++, output = [], warnings = [...(scene.warnings || [])];
  const counts = { shapes: 0, text: 0, gradients: 0, images: 0 };
  for (const item of scene.items) {
    const shapeId = id++;
    if (item.type === 'text') {
      if (!item.text) continue;
      const m = item.matrix, style = item.style;
      const tx = m[0] * item.start.x + m[2] * item.top + m[4], ty = m[1] * item.start.x + m[3] * item.top + m[5];
      const localWidth = Math.max(0.1, item.end - item.start.x), localHeight = Math.max(0.1, item.bottom - item.top);
      const scaleX = Math.hypot(m[0], m[1]), scaleY = Math.hypot(m[2], m[3]);
      const rotation = Math.atan2(m[1], m[0]) * 180 / Math.PI;
      const w = (localWidth * scaleX + style.fontSize * 0.04) * sx, h = localHeight * scaleY * sy;
      // DrawingML rotates around the box centre, SVG around its local origin.
      const rad = rotation * Math.PI / 180, px = tx * sx + (Math.cos(rad) * w - Math.sin(rad) * h - w) / 2, py = ty * sy + (Math.sin(rad) * w + Math.cos(rad) * h - h) / 2;
      const font = Math.max(100, integer(style.fontSize * sy / 127));
      const props = ` lang="ja-JP" sz="${font}" b="${style.bold ? 1 : 0}" i="${style.italic ? 1 : 0}" u="${style.underline ? 'sng' : 'none'}" strike="${style.strike ? 'sngStrike' : 'noStrike'}" dirty="0"`;
      // The measured box follows the SVG font bounds. Centring distributes the
      // consumer's extra line leading equally instead of moving the baseline down.
      const text = `<p:txBody><a:bodyPr wrap="none" lIns="0" tIns="0" rIns="0" bIns="0" anchor="ctr"><a:noAutofit/></a:bodyPr><a:lstStyle/><a:p><a:pPr algn="l" marL="0" indent="0"><a:lnSpc><a:spcPct val="100000"/></a:lnSpc><a:spcBef><a:spcPts val="0"/></a:spcBef><a:spcAft><a:spcPts val="0"/></a:spcAft></a:pPr><a:r><a:rPr${props}>${fillXml(style)}<a:latin typeface="${escapeXml(style.fontFamily)}"/><a:ea typeface="${escapeXml(style.fontFamily)}"/><a:cs typeface="${escapeXml(style.fontFamily)}"/></a:rPr><a:t xml:space="preserve">${escapeXml(item.text)}</a:t></a:r><a:endParaRPr${props}/></a:p></p:txBody>`;
      output.push(`<p:sp><p:nvSpPr><p:cNvPr id="${shapeId}" name="SVG text ${shapeId}"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr>${xfrm(px, py, w, h, rotation)}<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></p:spPr>${text}</p:sp>`);
      counts.text++;
    } else {
      const b = item.bounds, style = item.style;
      if (!style.fill && !style.stroke) continue;
      if (style.fillRule === 'evenodd') warnings.push({ code: 'SVG_EVENODD_FILL', message: 'SVG evenodd fill is represented using DrawingML nonzero fill; overlapping subpaths may differ.' });
      if (style.fill?.type === 'linearGradient') counts.gradients++;
      const appendShape = (element, elementId) => {
        const box = element.bounds, paint = element.style;
        output.push(`<p:sp><p:nvSpPr><p:cNvPr id="${elementId}" name="SVG ${escapeXml(element.type)} ${elementId}"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>${xfrm(box.x * sx, box.y * sy, box.width * sx, box.height * sy)}${geometryXml(element, sx, sy)}${fillXml(paint.fill)}${lineXml(paint, Math.sqrt(sx * sy), bakedDash(paint))}</p:spPr></p:sp>`);
        counts.shapes++;
      };
      const zeroLengthDots = style.stroke && style.linecap !== 'butt' && style.dash?.some((value) => value === 0) && style.dash.some((value) => value > 0);
      if (affineStroke(item, sx, sy) || zeroLengthDots) {
        if (style.fill) appendShape({ ...item, style: { ...style, stroke: null, dash: [] } }, shapeId);
        const outline = strokeOutline(item);
        if (outline) appendShape(outline, style.fill ? id++ : shapeId);
      } else appendShape(item, shapeId);
    }
  }
  const xml = `<p:grpSp xmlns:p="${NS_P}" xmlns:a="${NS_A}"><p:nvGrpSpPr><p:cNvPr id="${groupId}" name="Editable SVG ${groupId}"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="${integer(x)}" y="${integer(y)}"/><a:ext cx="${integer(width)}" cy="${integer(height)}"/><a:chOff x="0" y="0"/><a:chExt cx="${integer(width)}" cy="${integer(height)}"/></a:xfrm></p:grpSpPr>${output.join('')}</p:grpSp>`;
  return { xml, nextId: id, counts, warnings };
}

export async function nativeSvgGroup({ page, svg, x = 0, y = 0, width, height, idStart = 1, fontFamily = FONT_FAMILY }) {
  let scene;
  try { scene = await page.evaluate(extractSvgScene, svg, fontFamily); }
  catch (error) {
    if (String(error.message).includes('UNSUPPORTED_SVG:')) throw unsupported(String(error.message).split('UNSUPPORTED_SVG:')[1].split('\n')[0].trim());
    throw error;
  }
  return svgSceneToDrawingML(scene, { x, y, width, height, idStart });
}
