package com.convertx2x.office2md.word;

import static org.junit.jupiter.api.Assertions.*;
import com.convertx2x.office2md.conversion.ConversionException;
import com.convertx2x.office2md.conversion.ConversionLimits;
import com.convertx2x.office2md.conversion.ConversionResult;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import com.sun.net.httpserver.HttpServer;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.DocumentDocument;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.FootnotesDocument;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.EndnotesDocument;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.NumberingDocument;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.StylesDocument;

class WordMarkdownConverterTest {
    private static final String W = WordXml.W;
    private static final String R = WordXml.R;
    private record Converted(String markdown, String report) { }

    @Test void preservesExplicitHeadingsBodyOrderAndBorderlessTableWithoutGuessingHeader() throws Exception {
        byte[] bytes = doc("""
                <w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>節の見出し</w:t></w:r></w:p>
                <w:p><w:r><w:rPr><w:b/><w:sz w:val="72"/></w:rPr><w:t>大きい通常文</w:t></w:r></w:p>
                <w:tbl><w:tblGrid><w:gridCol/><w:gridCol/></w:tblGrid>
                <w:tr><w:tc><w:p><w:r><w:t>元の一行目</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>値</w:t></w:r></w:p></w:tc></w:tr>
                </w:tbl><w:p><w:r><w:t>表の後</w:t></w:r></w:p>
                """, document -> { });
        Converted result = convert(bytes);
        assertEquals("## 節の見出し\n\n**大きい通常文**\n\n|  |  |\n| --- | --- |\n| 元の一行目 | 値 |\n\n表の後\n\n", result.markdown());
        assertTrue(result.report().contains("\"sectionKind\" : \"document\""));
        assertTrue(result.report().contains("\"sectionCount\" : 1"));
    }

    @Test void resolvesInheritedOutlineAndRunStylesIncludingExplicitFalse() throws Exception {
        byte[] bytes = doc("""
                <w:p><w:pPr><w:pStyle w:val="CustomSubheading"/></w:pPr><w:r><w:t>継承見出し</w:t></w:r></w:p>
                <w:p><w:pPr><w:pStyle w:val="BoldStyle"/></w:pPr><w:r><w:t>強調</w:t></w:r>
                <w:r><w:rPr><w:b w:val="0"/></w:rPr><w:t>通常</w:t></w:r></w:p>
                <w:p><w:r><w:rPr><w:rStyle w:val="Removed"/></w:rPr><w:t>STYLE_SECRET</w:t></w:r></w:p>
                """, document -> setStyles(document, """
                <w:style w:type="paragraph" w:styleId="Base"><w:pPr><w:outlineLvl w:val="2"/></w:pPr></w:style>
                <w:style w:type="paragraph" w:styleId="CustomSubheading"><w:basedOn w:val="Base"/></w:style>
                <w:style w:type="paragraph" w:styleId="BoldStyle"><w:rPr><w:b/></w:rPr></w:style>
                <w:style w:type="character" w:styleId="Removed"><w:rPr><w:strike/></w:rPr></w:style>
                """));
        Converted result = convert(bytes);
        assertTrue(result.markdown().contains("### 継承見出し"));
        assertTrue(result.markdown().contains("**強調**通常"));
        assertFalse((result.markdown() + result.report()).contains("STYLE_SECRET"));
    }

    @Test void finalRevisionViewRemovesDeletedMovedFromStruckHiddenAndTheirLinks() throws Exception {
        byte[] bytes = doc("""
                <w:p><w:r><w:t>保持</w:t></w:r>
                <w:ins><w:r><w:t>追加</w:t></w:r></w:ins>
                <w:moveTo><w:r><w:t>移動先</w:t></w:r></w:moveTo>
                <w:del><w:r><w:delText>DELETED_SECRET</w:delText></w:r></w:del>
                <w:moveFrom><w:r><w:t>MOVED_SECRET</w:t></w:r></w:moveFrom>
                <w:hyperlink r:id="rStrike"><w:r><w:rPr><w:strike/></w:rPr><w:t>STRIKE_SECRET</w:t></w:r></w:hyperlink>
                <w:r><w:rPr><w:vanish/></w:rPr><w:t>HIDDEN_SECRET</w:t></w:r>
                <w:r><w:rPr><w:dstrike/></w:rPr><w:t>DOUBLE_SECRET</w:t></w:r></w:p>
                <w:del><w:p><w:r><w:t>BLOCK_SECRET</w:t></w:r></w:p></w:del>
                <w:ins><w:p><w:r><w:t>追加段落</w:t></w:r></w:p></w:ins>
                """, d -> d.getPackagePart().addExternalRelationship("https://secret.invalid/STRIKE_URL_SECRET", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink", "rStrike"));
        Converted result = convert(bytes);
        assertEquals("保持追加移動先\n\n追加段落\n\n", result.markdown());
        assertFalse((result.markdown() + result.report()).contains("SECRET"));
        assertFalse(result.report().contains("UNSUPPORTED_LINK"));
        assertTrue(result.report().contains("STRIKETHROUGH_REMOVED"));
    }

    @Test void keepsSafeHyperlinksAndNeverTurnsInternalOrFileTargetsIntoMarkdownLinks() throws Exception {
        byte[] bytes = doc("""
                <w:p><w:hyperlink r:id="rWeb"><w:r><w:rPr><w:b/></w:rPr><w:t>公式</w:t></w:r></w:hyperlink>
                <w:hyperlink r:id="rFile"><w:r><w:t>ファイル</w:t></w:r></w:hyperlink>
                <w:hyperlink w:anchor="bookmark"><w:r><w:t>内部</w:t></w:r></w:hyperlink></w:p>
                """, d -> {
            d.getPackagePart().addExternalRelationship("https://example.com/a(b)", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink", "rWeb");
            d.getPackagePart().addExternalRelationship("file:///FILE_TARGET_SECRET", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink", "rFile");
        });
        Converted result = convert(bytes);
        assertEquals("[**公式**](https://example.com/a%28b%29)ファイル内部\n\n", result.markdown());
        assertFalse((result.markdown() + result.report()).contains("FILE_TARGET_SECRET"));
        assertTrue(result.report().contains("UNSUPPORTED_LINK"));
    }

    @Test void onlyReadsCachedFieldResultsIncludingNestedComplexFields() throws Exception {
        byte[] bytes = doc("""
                <w:p><w:fldSimple w:instr='INCLUDETEXT "http://127.0.0.1:1/FIELD_SECRET"'><w:r><w:t>保存済み表示</w:t></w:r></w:fldSimple></w:p>
                <w:p><w:r><w:fldChar w:fldCharType="begin"/></w:r>
                <w:r><w:instrText>DDE FIELD_SECRET</w:instrText><w:t>INSTRUCTION_SECRET</w:t></w:r>
                <w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>外側</w:t></w:r>
                <w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText>INCLUDEPICTURE SECRET</w:instrText></w:r>
                <w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>内側</w:t></w:r>
                <w:r><w:fldChar w:fldCharType="end"/></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>
                """, d -> { });
        Converted result = convert(bytes);
        assertEquals("保存済み表示\n\n外側内側\n\n", result.markdown());
        assertFalse((result.markdown() + result.report()).contains("SECRET"));
        assertTrue(result.report().contains("FIELD_CACHED_RESULT"));
    }

    @Test void mergesKeepAnchorOnlyAndExplicitHeaderAndNestedTableTextSurvives() throws Exception {
        byte[] bytes = doc("""
                <w:tbl><w:tblGrid><w:gridCol/><w:gridCol/><w:gridCol/></w:tblGrid>
                <w:tr><w:trPr><w:tblHeader/></w:trPr><w:tc><w:tcPr><w:gridSpan w:val="2"/><w:vMerge w:val="restart"/></w:tcPr><w:p><w:r><w:t>結合</w:t></w:r></w:p></w:tc>
                <w:tc><w:p><w:r><w:t>列3</w:t></w:r></w:p></w:tc></w:tr>
                <w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/><w:vMerge/></w:tcPr><w:p><w:r><w:t>CONTINUATION_SECRET</w:t></w:r></w:p></w:tc>
                <w:tc><w:tbl><w:tr><w:tc><w:p><w:r><w:t>入れ子本文</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:tc></w:tr>
                <w:tr><w:trPr><w:del/></w:trPr><w:tc><w:p><w:r><w:t>DELETED_ROW_SECRET</w:t></w:r></w:p></w:tc></w:tr>
                </w:tbl>
                """, d -> { });
        Converted result = convert(bytes);
        assertEquals("| 結合 |  | 列3 |\n| --- | --- | --- |\n|  |  | 入れ子本文 |\n\n", result.markdown());
        assertTrue(result.report().contains("NESTED_TABLE_UNSUPPORTED"));
        assertFalse((result.markdown() + result.report()).contains("SECRET"));
    }

    @Test void numberingRetainsStartOverrideAndDoesNotRenumberRemovedStrikeItems() throws Exception {
        byte[] bytes = doc(numbered("一", false) + numbered("NUMBER_SECRET", true) + numbered("三", false), d -> {
            try {
                d.createNumbering().setNumbering(NumberingDocument.Factory.parse("<w:numbering xmlns:w=\"" + W + "\">" + """
                        <w:abstractNum w:abstractNumId="1"><w:lvl w:ilvl="0"><w:start w:val="5"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl></w:abstractNum>
                        <w:num w:numId="7"><w:abstractNumId w:val="1"/><w:lvlOverride w:ilvl="0"><w:startOverride w:val="8"/></w:lvlOverride></w:num>
                        </w:numbering>
                        """).getNumbering());
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        Converted result = convert(bytes);
        assertEquals("8\\. 一\n\n10\\. 三\n\n", result.markdown());
        assertFalse((result.markdown() + result.report()).contains("NUMBER_SECRET"));
    }

    @Test void referencedFootnotesAndEndnotesAreAppendedOnceAndHeaderFooterAreExcluded() throws Exception {
        byte[] bytes = doc("""
                <w:p><w:r><w:t>本文</w:t><w:footnoteReference w:id="1"/><w:endnoteReference w:id="2"/><w:footnoteReference w:id="1"/></w:r></w:p>
                """, d -> {
            try {
                d.createFootnotes(); d.createEndnotes();
                d.addFootnote(FootnotesDocument.Factory.parse("<w:footnotes xmlns:w=\"" + W + "\"><w:footnote xmlns:w=\"" + W + "\" w:id=\"1\"><w:p><w:r><w:footnoteRef/><w:t>脚注本文</w:t></w:r></w:p></w:footnote></w:footnotes>").getFootnotes().getFootnoteArray(0));
                d.addFootnote(FootnotesDocument.Factory.parse("<w:footnotes xmlns:w=\"" + W + "\"><w:footnote xmlns:w=\"" + W + "\" w:id=\"9\"><w:p><w:r><w:t>UNREFERENCED_SECRET</w:t></w:r></w:p></w:footnote></w:footnotes>").getFootnotes().getFootnoteArray(0));
                d.addEndnote(EndnotesDocument.Factory.parse("<w:endnotes xmlns:w=\"" + W + "\"><w:endnote xmlns:w=\"" + W + "\" w:id=\"2\"><w:p><w:r><w:t>文末脚注</w:t></w:r></w:p></w:endnote></w:endnotes>").getEndnotes().getEndnoteArray(0));
                d.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("HEADER_SECRET");
                d.createFooter(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("FOOTER_SECRET");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        Converted result = convert(bytes);
        assertEquals("本文[^footnote-1][^endnote-2][^footnote-1]\n\n[^footnote-1]: 脚注本文\n\n[^endnote-2]: 文末脚注\n\n", result.markdown());
        assertFalse((result.markdown() + result.report()).contains("SECRET"));
    }

    @Test void markdownReadAndTableLimitsFailAndAValidConversionStillSucceeds() throws Exception {
        byte[] text = doc("<w:p><w:r><w:t>日本語の文字量</w:t></w:r></w:p>", d -> { });
        ConversionLimits defaults = ConversionLimits.defaults();
        ConversionLimits markdownLimit = limits(defaults.maxReadItems(), defaults.maxTableCells(), 10);
        assertEquals(413, assertThrows(ConversionException.class, () -> new WordMarkdownConverter(markdownLimit).convert(text, "test.docx")).statusCode());
        assertEquals("READ_ITEMS_LIMIT", assertThrows(ConversionException.class, () -> new WordMarkdownConverter(limits(2, 100, 1000)).convert(text, "test.docx")).code());
        byte[] table = doc("<w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val=\"1000\"/></w:tcPr><w:p/></w:tc></w:tr></w:tbl>", d -> { });
        assertEquals("TABLE_CELLS_LIMIT", assertThrows(ConversionException.class, () -> new WordMarkdownConverter(limits(100, 2, 1000)).convert(table, "test.docx")).code());
        assertTrue(convert(text).markdown().contains("日本語の文字量"));
    }

    @Test void rejectsWrongFormatAndClosesResultWorkspaceWithoutChangingSource() throws Exception {
        WordMarkdownConverter converter = new WordMarkdownConverter(ConversionLimits.defaults());
        assertEquals(415, assertThrows(ConversionException.class, () -> converter.convert("%PDF-1.7".getBytes(), "wrong.docx")).statusCode());
        byte[] bytes = doc("<w:p><w:r><w:t>原本</w:t></w:r></w:p>", d -> { });
        byte[] original = bytes.clone();
        Path directory;
        try (ConversionResult result = converter.convert(bytes, "test.docx")) {
            directory = result.directory();
            assertTrue(Files.exists(directory));
            assertEquals(2, result.files().size());
            assertTrue(result.zipBytes().length > 0);
        }
        assertFalse(Files.exists(directory));
        assertArrayEquals(original, bytes);
    }

    @Test void rejectsVbaPartEvenWhenMainDocumentClaimsOrdinaryDocx() throws Exception {
        byte[] bytes = doc("<w:p><w:r><w:t>本文</w:t></w:r></w:p>", d -> {
            try {
                var part = d.getPackage().createPart(PackagingURIHelper.createPartName("/word/vbaProject.bin"), "application/vnd.ms-office.vbaProject");
                try (var out = part.getOutputStream()) { out.write(new byte[]{1, 2, 3}); }
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        assertEquals(415, assertThrows(ConversionException.class,
                () -> new WordMarkdownConverter(ConversionLimits.defaults()).convert(bytes, "misleading.docx")).statusCode());
    }

    @Test void externalFieldsTemplatesAndAlternativeContentMakeZeroHttpRequests() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try {
            String address = "http://127.0.0.1:" + server.getAddress().getPort() + "/EXTERNAL_SECRET";
            byte[] bytes = doc("<w:p><w:fldSimple w:instr='INCLUDETEXT &quot;" + address + "&quot;'><w:r><w:t>保存された結果</w:t></w:r></w:fldSimple></w:p><w:altChunk r:id=\"rChunk\"/>", d -> {
                d.getPackagePart().addExternalRelationship(address, "http://schemas.openxmlformats.org/officeDocument/2006/relationships/aFChunk", "rChunk");
                d.getPackagePart().addExternalRelationship(address, "http://schemas.openxmlformats.org/officeDocument/2006/relationships/attachedTemplate", "rTemplate");
            });
            Converted result = convert(bytes);
            assertEquals(0, requests.get());
            assertTrue(result.markdown().contains("保存された結果"));
            assertTrue(result.report().contains("ALTERNATIVE_CONTENT_UNSUPPORTED"));
            assertFalse((result.markdown() + result.report()).contains("EXTERNAL_SECRET"));
        } finally { server.stop(0); }
    }

    static byte[] doc(String body, Consumer<XWPFDocument> configure) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.getDocument().setBody(DocumentDocument.Factory.parse("<w:document xmlns:w=\"" + W + "\" xmlns:r=\"" + R + "\"><w:body>" + body + "</w:body></w:document>").getDocument().getBody());
            configure.accept(document);
            document.write(output);
            return output.toByteArray();
        }
    }
    private static void setStyles(XWPFDocument document, String styles) {
        try { document.createStyles().setStyles(StylesDocument.Factory.parse("<w:styles xmlns:w=\"" + W + "\">" + styles + "</w:styles>").getStyles()); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private static String numbered(String text, boolean strike) {
        return "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"7\"/></w:numPr></w:pPr><w:r>"
                + (strike ? "<w:rPr><w:strike/></w:rPr>" : "") + "<w:t>" + text + "</w:t></w:r></w:p>";
    }
    private static Converted convert(byte[] bytes) throws Exception {
        try (ConversionResult result = new WordMarkdownConverter(ConversionLimits.defaults()).convert(bytes, "test.docx")) {
            return new Converted(Files.readString(result.files().get("document.md")), Files.readString(result.files().get("report.json")));
        }
    }
    private static ConversionLimits limits(int read, int table, long markdown) {
        ConversionLimits d = ConversionLimits.defaults();
        return new ConversionLimits(d.maxInputBytes(), d.maxSections(), read, table, markdown, d.maxImages(), d.maxImageBytes(), d.maxOutputBytes(), d.maxShapes(), d.maxGroupDepth(), d.maxImagePixels());
    }
}
