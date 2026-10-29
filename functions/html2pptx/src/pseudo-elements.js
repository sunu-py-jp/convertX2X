/** Trusted browser hook. Materialize empty CSS decorations without guessing their shape. */
export function materializePseudoElements({maxStyleBytes = 32 * 1024 * 1024} = {}) {
  const fail = message => { throw new Error(`UNSUPPORTED_LAYOUT: ${message}`); };
  const elements = [...document.body.querySelectorAll('*'), document.body];
  const decorations = [];
  const stylePool = new Map();
  let styleBytes = 0;
  const snapshotStyle = style => {
    const declarations = [];
    for (let i = 0; i < style.length; i++) {
      const property = style.item(i);
      // A computed custom property can still contain var(), URLs or other unevaluated tokens.
      // All ordinary properties are captured as computed values instead.
      if (!property.startsWith('--')) declarations.push(`${property}:${style.getPropertyValue(property)}!important;`);
    }
    const cssText = declarations.join('');
    // Count every eventual inline style, even when the snapshot strings deduplicate.
    // This bounds both JS snapshots and the CSSOM expansion after materialization.
    styleBytes += cssText.length * 2;
    if (styleBytes > maxStyleBytes) fail('疑似要素の配置を保持するためのスタイル量が上限を超えています。');
    const shared = stylePool.get(cssText);
    if (shared !== undefined) return shared;
    stylePool.set(cssText, cssText);
    return cssText;
  };
  const visible = element => {
    for (let current = element; current; current = current.parentElement) {
      const style = getComputedStyle(current);
      if (style.display === 'none' || Number(style.opacity) === 0) return false;
    }
    return getComputedStyle(element).visibility === 'visible';
  };
  for (const element of elements) {
    if (element.namespaceURI !== 'http://www.w3.org/1999/xhtml' || !visible(element)) continue;
    for (const pseudo of ['::before', '::after']) {
      const style = getComputedStyle(element, pseudo);
      if (['none', 'normal'].includes(style.content) || style.display === 'none' || style.visibility !== 'visible' || Number(style.opacity) === 0) continue;
      if (style.content !== '""' && style.content !== "''") fail('文字やカウンターを含む疑似要素には対応していません。');
      if (['IMG', 'INPUT', 'TEXTAREA', 'SELECT', 'BR', 'HR'].includes(element.tagName)) fail('置換要素の疑似要素には対応していません。');
      if (style.display === 'contents' || style.display.includes('table') || style.position === 'sticky') fail('この疑似要素の配置には対応していません。');
      decorations.push({element, pseudo, style: snapshotStyle(style)});
    }
  }
  if (!decorations.length) return {materializedCount: 0};
  if (decorations.length > 2000) fail('疑似要素の数が上限を超えています。');

  const rectValues = rect => [rect.left, rect.top, rect.width, rect.height];
  const originals = elements.map(element => ({element, style: snapshotStyle(getComputedStyle(element)), rect: rectValues(element.getBoundingClientRect())}));
  const texts = [];
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  for (let text = walker.nextNode(); text; text = walker.nextNode()) {
    if (!text.textContent.trim() || text.parentElement?.closest('style,title,script') || !visible(text.parentElement)) continue;
    const range = document.createRange();
    range.selectNodeContents(text);
    texts.push({range, rects: [...range.getClientRects()].map(rectValues)});
  }
  const apply = (element, cssText) => { element.style.cssText = cssText; };
  // Inserting children can change :first-child, :last-child and :nth-child rules.
  // Capture every existing element first, then freeze the computed presentation.
  for (const original of originals) apply(original.element, original.style);

  // An earliest named layer wins against other author !important rules, including
  // unlayered rules. Verify below rather than assume selector specificity wins.
  const suppress = document.createElement('style');
  suppress.textContent = '@layer convertX2XPseudoSuppression { *::before,*::after { content:none!important;display:none!important } }';
  document.head.prepend(suppress);
  for (const {element, pseudo, style} of decorations) {
    const replacement = document.createElement('span');
    apply(replacement, style);
    replacement.style.setProperty('content', 'normal', 'important');
    if (pseudo === '::before') element.prepend(replacement);
    else element.append(replacement);
  }
  for (const {element, pseudo} of decorations) {
    const style = getComputedStyle(element, pseudo);
    if (!['none', 'normal'].includes(style.content) && style.display !== 'none') fail('元の疑似要素を安全に置き換えられませんでした。');
  }
  const same = (a, b) => a.length === b.length && a.every((value, i) => Math.abs(value - b[i]) <= 0.25);
  for (const original of originals) if (!same(original.rect, rectValues(original.element.getBoundingClientRect()))) fail('疑似要素の置き換えで元の要素の配置が変わるため変換できません。');
  for (const {range, rects} of texts) {
    const after = [...range.getClientRects()].map(rectValues);
    if (rects.length !== after.length || rects.some((rect, i) => !same(rect, after[i]))) fail('疑似要素の置き換えで文字の配置が変わるため変換できません。');
  }
  return {materializedCount: decorations.length};
}
