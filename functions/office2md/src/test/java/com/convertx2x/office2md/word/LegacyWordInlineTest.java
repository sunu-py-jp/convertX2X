package com.convertx2x.office2md.word;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.convertx2x.office2md.conversion.ConversionLimits;
import com.convertx2x.office2md.conversion.ConversionWorkspace;
import com.convertx2x.office2md.conversion.ImageMode;
import com.convertx2x.office2md.ocr.OcrClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LegacyWordInlineTest {
    private final ConversionWorkspace workspace = new ConversionWorkspace(ConversionLimits.defaults(), ImageMode.IGNORE, OcrClient.disabled());
    private final LegacyWordInline inline = new LegacyWordInline(new WordMarkdownWriter(workspace, "document"));

    @AfterEach void release() { workspace.close(); }

    @Test void boldRunsLineBreaksAndControlMarksBecomeMarkdown() {
        inline.beginParagraph("paragraph:1");
        inline.run("通常の", false, false, false);
        inline.run("太字", true, false, false);
        inline.run("一行目\u000b二行目\ttab\r", false, false, false);
        assertEquals("通常の**太字**一行目\n二行目    tab", inline.finishParagraph());

        inline.beginParagraph("paragraph:2");
        inline.run("セル*記号\u0007", false, false, false);
        assertEquals("セル\\*記号", inline.finishParagraph());
    }

    @Test void hyperlinkFieldBecomesALinkAndItsInstructionIsNeverShown() throws Exception {
        inline.beginParagraph("paragraph:1");
        inline.run("参照: \u0013 HYPERLINK \"https://example.com/spec\" \\o \"tip\" \u0014", false, false, false);
        inline.run("仕様書", true, false, false);
        inline.run("\u0015 を確認", false, false, false);
        assertEquals("参照: [**仕様書**](https://example.com/spec) を確認", inline.finishParagraph());
        assertTrue(codes("information").contains("FIELD_CACHED_RESULT"));
        assertFalse(codes("warnings").contains("UNSUPPORTED_LINK"));
    }

    @Test void internalAndUnsafeLinksKeepOnlyTheirLabel() throws Exception {
        inline.beginParagraph("paragraph:1");
        inline.run("\u0013 HYPERLINK \\l \"_Toc1\" \u0014目次の項目\u0015", false, false, false);
        inline.run("\u0013 HYPERLINK \"file:///C:/secret.doc\" \u0014社内ファイル\u0015", false, false, false);
        inline.run("\u0013 HYPERLINK \"javascript:alert(1)\" \u0014危険\u0015", false, false, false);
        assertEquals("目次の項目社内ファイル危険", inline.finishParagraph());
        assertTrue(codes("warnings").contains("UNSUPPORTED_LINK"));
    }

    @Test void otherFieldsKeepTheSavedResultAcrossRunsAndParagraphs() {
        inline.beginParagraph("paragraph:1");
        inline.run("ページ \u0013 PAGE ", false, false, false);
        assertFalse(inline.visible());
        inline.run("\\* MERGEFORMAT \u00143\u0015 / 全体", false, false, false);
        assertTrue(inline.visible());
        assertEquals("ページ 3 / 全体", inline.finishParagraph());

        // A table of contents is one field whose saved result spans several paragraphs.
        inline.beginParagraph("paragraph:2");
        inline.run("\u0013 TOC \\o \"1-3\" \u0014第1章", false, false, false);
        assertEquals("第1章", inline.finishParagraph());
        inline.beginParagraph("paragraph:3");
        inline.run("第2章\u0015本文", false, false, false);
        assertEquals("第2章本文", inline.finishParagraph());

        // A field without a saved result shows nothing.
        inline.beginParagraph("paragraph:4");
        inline.run("前\u0013 SEQ figure \u0015後", false, false, false);
        assertEquals("前後", inline.finishParagraph());
    }

    @Test void struckHiddenAndDeletedTextIsLeftOutWithTheSameNoticesAsDocx() throws Exception {
        inline.beginParagraph("paragraph:1");
        inline.run("残す", false, false, false);
        inline.run("取消線", false, true, false);
        inline.run("隠し文字", false, false, true);
        inline.deleted("変更履歴で削除");
        assertEquals("残す", inline.finishParagraph());
        assertEquals(List.of("STRIKETHROUGH_REMOVED", "HIDDEN_TEXT_REMOVED"), codes("information"));
    }

    @Test void pageBreaksAreMarkedForTheWriterExceptInDeletedText() {
        inline.beginParagraph("paragraph:1");
        inline.run("前\u000c後", false, false, false);
        inline.deleted("\u000c");
        assertEquals("前" + WordMarkdownWriter.PAGE_BREAK + "後", inline.finishParagraph());
    }

    private List<String> codes(String kind) throws Exception {
        workspace.finishReport("inline.doc", "0".repeat(64));
        List<String> codes = new ArrayList<>();
        for (var entry : new ObjectMapper().readTree(workspace.files().get("report.json").toFile()).path(kind))
            codes.add(entry.path("code").asText());
        return codes;
    }
}
