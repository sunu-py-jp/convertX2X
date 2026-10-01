package com.convertx2x.office2pdf.conversion;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;

/** Produces a searchable flow-layout PDF from DOCX body content. */
final class WordPdfConverter {
    void convert(byte[] input, NormalizedPdfWriter pdf, ConversionWorkspace workspace) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(input))) {
            workspace.sectionIncluded();
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) writeParagraph(paragraph, pdf, workspace);
                else if (element instanceof XWPFTable table) writeTable(table, pdf);
            }
            if (!document.getHeaderList().isEmpty() || !document.getFooterList().isEmpty())
                workspace.warning("HEADER_FOOTER_OMITTED", null, "ヘッダーとフッターは正規化PDFへ含めていません。");
        }
    }

    private void writeParagraph(XWPFParagraph paragraph, NormalizedPdfWriter pdf,
                                ConversionWorkspace workspace) throws IOException {
        if (paragraph.isPageBreak()) pdf.pageBreak();
        String text = paragraph.getText();
        String style = paragraph.getStyle();
        int level = headingLevel(style);
        if (paragraph.getNumID() != null && !text.isBlank()) text = "• " + text;
        if (level > 0) pdf.heading(text, level);
        else pdf.paragraph(text, allBold(paragraph));
        for (XWPFRun run : paragraph.getRuns()) {
            for (XWPFPicture picture : run.getEmbeddedPictures()) {
                byte[] bytes = picture.getPictureData().getData();
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                if (image == null) {
                    workspace.warning("IMAGE_FORMAT_UNSUPPORTED", null,
                            "Word内の画像形式をPDFへ配置できませんでした: " + picture.getPictureData().getFileName());
                } else pdf.image(image, picture.getDescription());
            }
        }
    }

    private void writeTable(XWPFTable table, NormalizedPdfWriter pdf) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        table.getRows().forEach(row -> rows.add(row.getTableCells().stream().map(cell -> cell.getText().trim()).toList()));
        pdf.table("Word 表", rows);
    }

    private static int headingLevel(String style) {
        if (style == null) return 0;
        String normalized = style.toLowerCase(java.util.Locale.ROOT);
        if (!normalized.startsWith("heading") && !normalized.startsWith("見出し")) return 0;
        String digits = normalized.replaceAll("\\D", "");
        if (digits.isEmpty()) return 1;
        try { return Math.max(1, Math.min(3, Integer.parseInt(digits))); }
        catch (NumberFormatException ignored) { return 1; }
    }

    private static boolean allBold(XWPFParagraph paragraph) {
        boolean text = false;
        for (XWPFRun run : paragraph.getRuns()) {
            if (run.text().isBlank()) continue;
            text = true;
            if (!run.isBold()) return false;
        }
        return text;
    }
}
