export const FONT_FAMILY = 'Noto Sans JP';

/** Trusted browser code: normalize before measuring, including inline/SVG overrides. */
export async function normalizeDocumentFonts(family) {
  const normalizeRules = rules => {
    for (const rule of rules) {
      // Include pseudo-elements and nested @layer/@media rules. Inline styles
      // cannot override a pseudo-element's own font or its ch/ex dimensions.
      if (rule.style && rule.selectorText) rule.style.setProperty('font-family', family, 'important');
      if (rule.cssRules) normalizeRules(rule.cssRules);
    }
  };
  for (const sheet of document.styleSheets) normalizeRules(sheet.cssRules);
  for (const element of document.querySelectorAll('*')) {
    if (element.style) element.style.setProperty('font-family', family, 'important');
  }
  for (const weight of [400, 700]) {
    const faces = await document.fonts.load(`${weight} 16px "${family}"`, '日本語 ABC 123');
    if (!faces.length || faces.some(face => face.status !== 'loaded')) {
      throw new Error('Bundled conversion font could not be loaded');
    }
  }
  await document.fonts.ready;
}
