package com.convertx2x.office2pdf.conversion;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.OldFileFormatException;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.model.PicturesTable;
import org.apache.poi.hwpf.model.StyleDescription;
import org.apache.poi.hwpf.usermodel.CharacterRun;
import org.apache.poi.hwpf.usermodel.HeaderStories;
import org.apache.poi.hwpf.usermodel.OfficeDrawing;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableCell;
import org.apache.poi.hwpf.usermodel.TableRow;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/**
 * Produces the same searchable flow-layout PDF as {@link WordPdfConverter} from a Word 97-2003 (.doc) body.
 * Layout decisions are shared through {@link WordFlow}; this class only reads the binary document.
 */
final class LegacyWordPdfConverter implements OfficePdfConverter {
    private static final String NOT_WORD_BINARY = ".doc はWord 97-2003形式のファイルを指定してください。";

    @Override
    public void convert(byte[] input, NormalizedPdfWriter pdf, ConversionWorkspace workspace) throws IOException {
        if (FileMagic.valueOf(input) != FileMagic.OLE2)
            throw new ConversionException(415, "UNSUPPORTED_FORMAT", NOT_WORD_BINARY);
        try (POIFSFileSystem filesystem = new POIFSFileSystem(new ByteArrayInputStream(input))) {
            // An OLE2 container also wraps password-protected OOXML and other Office formats.
            if (filesystem.getRoot().hasEntry("EncryptedPackage"))
                throw new EncryptedDocumentException("Encrypted package");
            if (!filesystem.getRoot().hasEntry("WordDocument"))
                throw new ConversionException(415, "UNSUPPORTED_FORMAT", NOT_WORD_BINARY);
            try (HWPFDocument document = new HWPFDocument(filesystem)) {
                workspace.sectionIncluded();
                new Reader(document, new WordFlow(pdf, workspace)).read();
            }
        } catch (OldFileFormatException failure) {
            throw new ConversionException(415, "UNSUPPORTED_FORMAT",
                    "Word 95以前の形式には対応していません。Word 97-2003形式（.doc）または .docx で保存し直してください。", failure);
        }
    }

    /** Heading level 1-3 from the style name, or from the outline level (0-8; 9 is body text) Word stores for headings. */
    static int headingLevel(String styleName, int outlineLevel) {
        int named = WordFlow.headingLevel(styleName);
        if (named > 0) return named;
        return outlineLevel >= 0 && outlineLevel <= 8 ? Math.min(3, outlineLevel + 1) : 0;
    }

    private static final class Reader {
        private final HWPFDocument document;
        private final WordFlow flow;
        private final PicturesTable pictures;
        private final LegacyWordText text = new LegacyWordText();

        private record FoundPicture(byte[] bytes, String filename, String description) { }

        Reader(HWPFDocument document, WordFlow flow) {
            this.document = document;
            this.flow = flow;
            this.pictures = document.getPicturesTable();
        }

        void read() throws IOException {
            Range body = document.getRange();
            for (int index = 0; index < body.numParagraphs(); index++) {
                Paragraph paragraph = body.getParagraph(index);
                Table table = paragraph.isInTable() ? tableStartingAt(body, paragraph) : null;
                if (table == null) { writeParagraph(paragraph); continue; }
                writeTable(table);
                // Continue after the last paragraph of the table, including its row-end marks.
                while (index + 1 < body.numParagraphs()
                        && body.getParagraph(index + 1).getStartOffset() < table.getEndOffset()) index++;
            }
            if (hasHeaderOrFooter()) flow.headerFooterOmitted();
        }

        private Table tableStartingAt(Range body, Paragraph paragraph) {
            try { return body.getTable(paragraph); }
            catch (IllegalArgumentException | IndexOutOfBoundsException unreadableTable) {
                // A paragraph that POI cannot place in a table is kept as ordinary text.
                return null;
            }
        }

        private void writeParagraph(Paragraph paragraph) throws IOException {
            if (paragraph.pageBreakBefore()) flow.pageBreak();
            StyleDescription style = document.getStyleSheet().getStyleDescription(paragraph.getStyleIndex());
            int level = headingLevel(style == null ? null : style.getName(), paragraph.getLvl());
            StringBuilder visible = new StringBuilder();
            List<FoundPicture> found = new ArrayList<>();
            boolean anyText = false, allBold = true;
            for (int index = 0; index < paragraph.numCharacterRuns(); index++) {
                CharacterRun run = paragraph.getCharacterRun(index);
                // Deleted (tracked change) and hidden text is not part of the displayed document.
                if (run.isMarkedDeleted() || run.isVanished()) { text.visible(run.text()); continue; }
                boolean instruction = text.insideInstruction();
                String value = text.visible(run.text());
                if (!value.isBlank()) { anyText = true; allBold &= run.isBold(); }
                visible.append(value);
                if (!instruction) picture(run, found);
            }
            // A page break splits the paragraph: text before it stays on the current page.
            String[] pages = visible.toString().split(String.valueOf(LegacyWordText.PAGE_BREAK), -1);
            for (int page = 0; page < pages.length; page++) {
                if (page > 0) flow.pageBreak();
                if (pages.length == 1 || !pages[page].isBlank())
                    flow.paragraph(pages[page], level, paragraph.isInList(), level == 0 && anyText && allBold);
            }
            for (FoundPicture picture : found) flow.picture(picture.bytes(), picture.filename(), picture.description());
        }

        private void picture(CharacterRun run, List<FoundPicture> found) {
            if (pictures.hasPicture(run)) {
                Picture picture = pictures.extractPicture(run, true);
                if (picture != null && picture.getContent() != null)
                    found.add(new FoundPicture(picture.getContent(), picture.suggestFullFileName(), picture.getDescription()));
            } else if (pictures.hasEscherPicture(run)) {
                OfficeDrawing drawing = document.getOfficeDrawingsMain().getOfficeDrawingAt(run.getStartOffset());
                byte[] bytes = drawing == null ? null : drawing.getPictureData();
                if (bytes != null) found.add(new FoundPicture(bytes, "drawing-" + drawing.getShapeId(), null));
            }
        }

        private void writeTable(Table table) throws IOException {
            List<List<String>> rows = new ArrayList<>();
            for (int rowIndex = 0; rowIndex < table.numRows(); rowIndex++) {
                TableRow row = table.getRow(rowIndex);
                List<String> cells = new ArrayList<>();
                for (int cellIndex = 0; cellIndex < row.numCells(); cellIndex++) {
                    TableCell cell = row.getCell(cellIndex);
                    String value = cellText(cell);
                    // Like DOCX, a horizontally merged range is one cell; its covered cells are not repeated.
                    if (cell.isMerged() && !cell.isFirstMerged()) continue;
                    cells.add(value);
                }
                rows.add(cells);
            }
            flow.table(rows);
        }

        private String cellText(TableCell cell) {
            StringBuilder value = new StringBuilder();
            for (int index = 0; index < cell.numParagraphs(); index++) {
                Paragraph paragraph = cell.getParagraph(index);
                if (index > 0) value.append('\n');
                for (int runIndex = 0; runIndex < paragraph.numCharacterRuns(); runIndex++) {
                    CharacterRun run = paragraph.getCharacterRun(runIndex);
                    String visible = text.visible(run.text());
                    if (!run.isMarkedDeleted() && !run.isVanished())
                        value.append(visible.replace(LegacyWordText.PAGE_BREAK, '\n'));
                }
            }
            return value.toString().trim();
        }

        private boolean hasHeaderOrFooter() {
            HeaderStories stories = new HeaderStories(document);
            for (String story : new String[] {stories.getFirstHeader(), stories.getOddHeader(), stories.getEvenHeader(),
                    stories.getFirstFooter(), stories.getOddFooter(), stories.getEvenFooter()})
                if (story != null && !new LegacyWordText().visible(story).isBlank()) return true;
            return false;
        }
    }
}
