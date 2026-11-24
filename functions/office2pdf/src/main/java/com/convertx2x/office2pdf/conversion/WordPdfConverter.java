package com.convertx2x.office2pdf.conversion;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;

/** Produces a searchable flow-layout PDF from DOCX body content. */
final class WordPdfConverter implements OfficePdfConverter {
    @Override
    public void convert(byte[] input, NormalizedPdfWriter pdf, ConversionWorkspace workspace) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(input))) {
            WordFlow flow = new WordFlow(pdf, workspace);
            workspace.sectionIncluded();
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) writeParagraph(paragraph, flow);
                else if (element instanceof XWPFTable table) writeTable(table, flow);
            }
            if (!document.getHeaderList().isEmpty() || !document.getFooterList().isEmpty())
                flow.headerFooterOmitted();
        }
    }

    private void writeParagraph(XWPFParagraph paragraph, WordFlow flow) throws IOException {
        if (paragraph.isPageBreak()) flow.pageBreak();
        int level = WordFlow.headingLevel(paragraph.getStyle());
        flow.paragraph(paragraph.getText(), level, paragraph.getNumID() != null, level == 0 && allBold(paragraph));
        for (XWPFRun run : paragraph.getRuns()) {
            for (XWPFPicture picture : run.getEmbeddedPictures()) {
                flow.picture(picture.getPictureData().getData(), picture.getPictureData().getFileName(),
                        picture.getDescription());
            }
        }
    }

    private void writeTable(XWPFTable table, WordFlow flow) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        table.getRows().forEach(row -> rows.add(row.getTableCells().stream().map(cell -> cell.getText().trim()).toList()));
        flow.table(rows);
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
