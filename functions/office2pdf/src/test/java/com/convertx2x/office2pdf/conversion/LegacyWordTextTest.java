package com.convertx2x.office2pdf.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LegacyWordTextTest {
    @Test void fieldInstructionsAreDroppedAndSavedResultsAreKept() {
        LegacyWordText text = new LegacyWordText();
        assertEquals("前仕様書後", text.visible("前\u0013 HYPERLINK \"https://example.com/spec\" \u0014仕様書\u0015後"));
        // A field without a saved result contributes nothing.
        assertEquals("AB", text.visible("A\u0013 TOC \\o \u0015B"));
    }

    @Test void fieldStateContinuesAcrossRunsAndNestedFields() {
        LegacyWordText text = new LegacyWordText();
        assertEquals("開始", text.visible("開始\u0013 IF "));
        assertTrue(text.insideInstruction());
        assertEquals("", text.visible("\u0013 PAGE \u00141\u0015 = 1 "));
        assertEquals("結果", text.visible("\u0014結果"));
        assertFalse(text.insideInstruction());
        assertEquals("終了", text.visible("\u0015終了"));
    }

    @Test void controlMarksBecomeReadableTextOrDisappear() {
        LegacyWordText text = new LegacyWordText();
        assertEquals("一行目\n二行目", text.visible("一行目\u000b二行目\r"));
        assertEquals("セル", text.visible("セル\u0007"));
        assertEquals("a\tb", text.visible("a\tb"));
        assertEquals("non‑breaking", text.visible("non\u001ebreaking"));
        assertEquals("optional", text.visible("opt\u001fional"));
        assertEquals("図の位置", text.visible("図\u0001の\u0008位置\u0002"));
        // Page and section breaks stay visible to the caller, which starts a new page there.
        assertEquals("前" + LegacyWordText.PAGE_BREAK + "後", text.visible("前\u000c後"));
    }
}
