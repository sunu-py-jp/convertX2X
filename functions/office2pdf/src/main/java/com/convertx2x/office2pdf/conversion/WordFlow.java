package com.convertx2x.office2pdf.conversion;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Format-neutral placement of Word body content into the normalized PDF.
 * Each Word reader translates its own document model into these calls, so headings, list markers,
 * tables, pictures and their warnings are decided in one place for every Word format.
 */
final class WordFlow {
    private final NormalizedPdfWriter pdf;
    private final ConversionWorkspace workspace;

    WordFlow(NormalizedPdfWriter pdf, ConversionWorkspace workspace) {
        this.pdf = pdf;
        this.workspace = workspace;
    }

    void pageBreak() throws IOException { pdf.pageBreak(); }

    /** @param headingLevel 0 for body text; otherwise the heading level from {@link #headingLevel(String)} */
    void paragraph(String text, int headingLevel, boolean listed, boolean strong) throws IOException {
        if (listed && !text.isBlank()) text = "• " + text;
        if (headingLevel > 0) pdf.heading(text, headingLevel);
        else pdf.paragraph(text, strong);
    }

    void picture(byte[] bytes, String filename, String description) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            workspace.warning("IMAGE_FORMAT_UNSUPPORTED", null,
                    "Word内の画像形式をPDFへ配置できませんでした: " + filename);
        } else pdf.image(image, description);
    }

    void table(List<List<String>> rows) throws IOException { pdf.table("Word 表", rows); }

    void headerFooterOmitted() {
        workspace.warning("HEADER_FOOTER_OMITTED", null, "ヘッダーとフッターは正規化PDFへ含めていません。");
    }

    /** Heading level 1-3 from a Word style name or id such as "Heading1", "heading 2" or "見出し 3"; 0 otherwise. */
    static int headingLevel(String style) {
        if (style == null) return 0;
        String normalized = style.toLowerCase(java.util.Locale.ROOT);
        if (!normalized.startsWith("heading") && !normalized.startsWith("見出し")) return 0;
        String digits = normalized.replaceAll("\\D", "");
        if (digits.isEmpty()) return 1;
        try { return Math.max(1, Math.min(3, Integer.parseInt(digits))); }
        catch (NumberFormatException ignored) { return 1; }
    }
}
