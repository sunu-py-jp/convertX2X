package com.convertx2x.office2md.word;

import com.convertx2x.office2md.conversion.ConversionException;
import com.convertx2x.office2md.conversion.ConversionLimits;
import com.convertx2x.office2md.conversion.ConversionResult;
import com.convertx2x.office2md.conversion.ConversionWorkspace;
import com.convertx2x.office2md.conversion.ImageMode;
import com.convertx2x.office2md.conversion.OfficeMarkdownConverter;
import com.convertx2x.office2md.drawing.DiagramMetadata;
import com.convertx2x.office2md.drawing.NativeDrawingRenderer;
import com.convertx2x.office2md.images.EmbeddedImagePreprocessor;
import com.convertx2x.office2md.ocr.OcrClient;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.OldFileFormatException;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.model.PicturesTable;
import org.apache.poi.hwpf.model.StyleDescription;
import org.apache.poi.hwpf.usermodel.CharacterRun;
import org.apache.poi.hwpf.usermodel.HWPFList;
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
 * Word 97-2003 (.doc) semantic conversion. It reads the binary document with POI HWPF and writes through
 * the same {@link WordMarkdownWriter} and {@link WordListNumbers} as DOCX, so the Markdown structure,
 * report blocks and limits are identical. Does not evaluate fields, update links, or run macros.
 */
public final class LegacyWordMarkdownConverter implements OfficeMarkdownConverter {
    private static final String NOT_WORD_BINARY = "Word 97-2003形式（.doc）のファイルを指定してください。";
    private final ConversionLimits limits;

    public LegacyWordMarkdownConverter(ConversionLimits limits) { this.limits = Objects.requireNonNull(limits); }

    public ConversionResult convert(byte[] input, String filename) {
        return convert(input, filename, ImageMode.IGNORE, OcrClient.disabled());
    }

    @Override
    public ConversionResult convert(byte[] input, String filename, ImageMode imageMode, OcrClient ocrClient) {
        if (input == null || input.length == 0) throw new ConversionException(400, "EMPTY_INPUT", "入力ファイルが空です。");
        if (input.length > limits.maxInputBytes()) throw ConversionWorkspace.limit("INPUT_BYTES_LIMIT", "入力ファイルのサイズが上限を超えています。");
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".doc"))
            throw new ConversionException(415, "UNSUPPORTED_FORMAT", NOT_WORD_BINARY);
        FileMagic magic;
        try { magic = FileMagic.valueOf(input); }
        catch (IllegalArgumentException e) { throw new ConversionException(415, "UNSUPPORTED_FORMAT", "Word の形式を確認できません。"); }
        if (magic != FileMagic.OLE2) throw new ConversionException(415, "UNSUPPORTED_FORMAT", NOT_WORD_BINARY);
        ConversionWorkspace workspace = new ConversionWorkspace(limits, imageMode, ocrClient);
        try (POIFSFileSystem filesystem = new POIFSFileSystem(new ByteArrayInputStream(input))) {
            // An OLE2 container also wraps password-protected OOXML and the other binary Office formats.
            if (filesystem.getRoot().hasEntry("EncryptedPackage"))
                throw new ConversionException(422, "ENCRYPTED_DOCUMENT", "暗号化された Word は変換できません。");
            if (!filesystem.getRoot().hasEntry("WordDocument"))
                throw new ConversionException(415, "UNSUPPORTED_FORMAT", NOT_WORD_BINARY);
            // The VBA project of a Word binary document is the "Macros" storage; it is never opened.
            if (filesystem.getRoot().hasEntry("Macros"))
                throw new ConversionException(415, "UNSUPPORTED_FORMAT", "マクロを含む Word には対応していません。");
            try (HWPFDocument document = new HWPFDocument(filesystem)) {
                workspace.sectionIncluded();
                String markdown = new Reader(document, workspace).convert();
                byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > limits.maxMarkdownBytes()) throw ConversionWorkspace.limit("MARKDOWN_BYTES_LIMIT", "Markdown のサイズが上限を超えています。");
                workspace.write("document.md", bytes);
                workspace.finishReport(filename, ConversionWorkspace.sha256(input));
            }
            return new ConversionResult(workspace);
        } catch (ConversionException e) {
            workspace.close(); throw e;
        } catch (EncryptedDocumentException e) {
            workspace.close(); throw new ConversionException(422, "ENCRYPTED_DOCUMENT", "暗号化された Word は変換できません。");
        } catch (OldFileFormatException e) {
            workspace.close(); throw new ConversionException(415, "UNSUPPORTED_FORMAT",
                    "Word 95以前の形式には対応していません。Word 97-2003形式（.doc）または .docx で保存し直してください。");
        } catch (Exception e) {
            workspace.close();
            // Parser exceptions can contain document text; keep them out of client/worker errors.
            throw new ConversionException(422, "INVALID_DOCUMENT", "Word ファイルを読み取れませんでした。");
        }
    }

    /** Heading level 1-6 from the style name, else from the outline level Word stores for headings (0-5). */
    static int headingLevel(String styleName, int outlineLevel) {
        int named = styleName == null ? 0 : WordStyles.headingName(styleName.strip());
        if (named > 0) return named;
        return outlineLevel >= 0 && outlineLevel < 6 ? outlineLevel + 1 : 0;
    }

    /** The WordprocessingML name of a binary number format code, as understood by {@link WordListNumbers}. */
    static String numberFormat(int code) {
        return switch (code) {
            case 0 -> "decimal";
            case 1 -> "upperRoman";
            case 2 -> "lowerRoman";
            case 3 -> "upperLetter";
            case 4 -> "lowerLetter";
            case 22 -> "decimalZero";
            case 23 -> "bullet";
            case 255 -> "none";
            default -> "format-" + code;
        };
    }

    /** Resolves a binary list into the shared definition; level texts use the characters 0-8 as placeholders. */
    static WordListNumbers.Definition definition(HWPFList list) {
        String[] formats = new String[9], texts = new String[9];
        int[] starts = new int[9], restarts = new int[9];
        Arrays.fill(formats, ""); Arrays.fill(texts, ""); Arrays.fill(starts, 1);
        for (int level = 0; level < 9; level++) {
            restarts[level] = level;
            try {
                String format = numberFormat(list.getNumberFormat((char) level));
                StringBuilder text = new StringBuilder();
                boolean numbered = false;
                for (char value : Objects.toString(list.getNumberText((char) level), "").toCharArray()) {
                    numbered |= value < 9;
                    text.append(value < 9 ? "%" + (value + 1) : String.valueOf(value));
                }
                // A level text without a number is a fixed marker such as "•", whatever its format code says.
                formats[level] = !numbered && !text.isEmpty() && !format.equals("none") ? "bullet" : format;
                texts[level] = text.toString();
                starts[level] = list.getStartAt((char) level);
            } catch (RuntimeException undefinedLevel) {
                // A simple list defines only its first level; the remaining levels keep the defaults.
            }
        }
        return new WordListNumbers.Definition(formats, texts, starts, restarts);
    }

    private static final class Reader {
        private final HWPFDocument document;
        private final ConversionWorkspace workspace;
        private final WordMarkdownWriter writer;
        private final WordListNumbers lists;
        private final LegacyWordInline inline;
        private final PicturesTable pictures;
        private final String section = "document";
        private int paragraphNumber, tableNumber, imageNumber;
        private long readItems;

        Reader(HWPFDocument document, ConversionWorkspace workspace) {
            this.document = document; this.workspace = workspace;
            writer = new WordMarkdownWriter(workspace, section);
            lists = new WordListNumbers(workspace);
            inline = new LegacyWordInline(writer);
            pictures = document.getPicturesTable();
        }

        String convert() throws IOException {
            StringBuilder output = writer.output();
            writer.begin();
            Range body = document.getRange();
            for (int index = 0; index < body.numParagraphs(); index++) {
                Paragraph paragraph = body.getParagraph(index);
                Table table = paragraph.isInTable() ? tableStartingAt(body, paragraph) : null;
                if (table == null) {
                    String range = "paragraph:" + (++paragraphNumber);
                    String text = paragraph(paragraph, range, false);
                    if (!text.isBlank()) writer.emit(output, text, "paragraph", range, false);
                    continue;
                }
                String range = "table:" + (++tableNumber);
                String text = table(table, range);
                if (!text.isBlank()) writer.emit(output, text, "table", range, false);
                // Continue after the last paragraph of the table, including its row-end marks.
                while (index + 1 < body.numParagraphs()
                        && body.getParagraph(index + 1).getStartOffset() < table.getEndOffset()) index++;
            }
            reportOmittedStories();
            return output.toString();
        }

        private Table tableStartingAt(Range body, Paragraph paragraph) {
            try { return body.getTable(paragraph); }
            catch (IllegalArgumentException | IndexOutOfBoundsException unreadableTable) {
                // A paragraph that POI cannot place in a table is kept as ordinary text.
                return null;
            }
        }

        private void count() {
            if (ConversionLimits.exceeds(++readItems, workspace.limits().maxReadItems()))
                throw ConversionWorkspace.limit("READ_ITEMS_LIMIT", "読み取り要素数が上限を超えています。");
        }

        private String paragraph(Paragraph paragraph, String range, boolean cell) throws IOException {
            count();
            String prefix = listPrefix(paragraph, range);
            inline.beginParagraph(range);
            for (int index = 0; index < paragraph.numCharacterRuns(); index++) {
                CharacterRun run = paragraph.getCharacterRun(index);
                count();
                if (run.isMarkedDeleted()) { inline.deleted(run.text()); continue; }
                boolean shown = inline.visible() && !run.isVanished() && !run.isStrikeThrough();
                inline.run(run.text(), run.isBold(), run.isStrikeThrough(), run.isVanished());
                if (shown) picture(run, range);
            }
            String result = inline.finishParagraph();
            boolean breakBefore = !cell && paragraph.pageBreakBefore();
            if (cell) result = result.replace(WordMarkdownWriter.PAGE_BREAK, "");
            if (result.isBlank()) return breakBefore ? WordMarkdownWriter.PAGE_BREAK : "";
            StyleDescription style = document.getStyleSheet().getStyleDescription(paragraph.getStyleIndex());
            int heading = headingLevel(style == null ? null : style.getName(), paragraph.getLvl());
            if (!cell && heading > 0) result = "#".repeat(heading) + " " + result;
            return (breakBefore ? WordMarkdownWriter.PAGE_BREAK : "") + prefix + result;
        }

        private String listPrefix(Paragraph paragraph, String range) {
            if (!paragraph.isInList()) return "";
            int level = Math.clamp(paragraph.getIlvl(), 0, 8);
            try {
                HWPFList list = paragraph.getList();
                if (list == null) throw new IllegalArgumentException();
                return lists.prefix(Integer.toString(paragraph.getIlfo()), level, definition(list), section, range);
            } catch (RuntimeException unresolvedList) {
                workspace.warning("NUMBERING_APPROXIMATED", section, range, "番号定義を解決できないため段落の箇条書きとして保持しました。");
                return "  ".repeat(level) + "- ";
            }
        }

        private void picture(CharacterRun run, String range) throws IOException {
            if (pictures.hasPicture(run)) {
                Picture picture = pictures.extractPicture(run, true);
                if (picture == null || picture.getContent() == null) return;
                // Goal sizes are twips of the unscaled picture; scaling factors are per mille.
                double width = picture.getDxaGoal() / 20d * scale(picture.getHorizontalScalingFactor());
                double height = picture.getDyaGoal() / 20d * scale(picture.getVerticalScalingFactor());
                EmbeddedImagePreprocessor.Crop crop = crop(picture);
                image(picture.getContent(), picture.getMimeType(), width, height, crop, picture.getDescription(), range);
            } else if (pictures.hasEscherPicture(run)) {
                OfficeDrawing drawing = document.getOfficeDrawingsMain().getOfficeDrawingAt(run.getStartOffset());
                byte[] bytes = drawing == null ? null : drawing.getPictureData();
                if (bytes == null) return;
                double width = (drawing.getRectangleRight() - drawing.getRectangleLeft()) / 20d;
                double height = (drawing.getRectangleBottom() - drawing.getRectangleTop()) / 20d;
                image(bytes, "", width, height, EmbeddedImagePreprocessor.Crop.none(), "", range);
            }
        }

        private static double scale(int perMille) { return perMille > 0 ? perMille / 1000d : 1d; }

        private static EmbeddedImagePreprocessor.Crop crop(Picture picture) {
            // Crop distances are twips of the unscaled picture.
            double width = picture.getDxaGoal(), height = picture.getDyaGoal();
            if (width <= 0 || height <= 0) return EmbeddedImagePreprocessor.Crop.none();
            double left = picture.getDxaCropLeft() / width, right = picture.getDxaCropRight() / width;
            double top = picture.getDyaCropTop() / height, bottom = picture.getDyaCropBottom() / height;
            boolean present = left != 0 || right != 0 || top != 0 || bottom != 0;
            return new EmbeddedImagePreprocessor.Crop(left, top, right, bottom, present);
        }

        private void image(byte[] bytes, String declaredType, double width, double height,
                           EmbeddedImagePreprocessor.Crop crop, String description, String range) throws IOException {
            workspace.imagePlacement();
            if (bytes.length > workspace.limits().maxImageBytes())
                throw ConversionWorkspace.limit("IMAGE_BYTES_LIMIT", "画像1件のサイズが上限を超えました。");
            String imageRange = range + "/image:" + (++imageNumber);
            byte[] original = workspace.retainImageBytes(bytes);
            String[] format = WordDrawings.imageFormat(original, Objects.toString(declaredType, ""));
            EmbeddedImagePreprocessor.Prepared prepared = EmbeddedImagePreprocessor.prepare(original, format[0], format[1],
                    crop, new AffineTransform(), width, height, workspace.limits());
            prepared = prepared.withBytes(workspace.retainImageBytes(prepared.bytes()));
            if (List.of("png", "jpg").contains(prepared.extension())) NativeDrawingRenderer.verifyPicture(prepared.bytes(), workspace);
            else workspace.warning("IMAGE_FORMAT_ATTACHMENT", section, imageRange, "この画像形式は元ファイルの添付として保持します。");
            if (prepared.skipReason() != null)
                workspace.warning("IMAGE_VISIBLE_AREA_UNAVAILABLE", section, imageRange, prepared.skipReason() + "元画像を保存しました。");
            Map<String, Object> metadata = DiagramMetadata.of("画像", Objects.toString(description, ""),
                    width > 0 && height > 0 ? new Rectangle2D.Double(0, 0, width, height) : null);
            inline.block(workspace.embeddedImage(prepared.bytes(), prepared.extension(), prepared.contentType(),
                    metadata, section, imageRange, prepared.ocrEligible()));
        }

        private String table(Table table, String range) throws IOException {
            List<List<String>> values = new ArrayList<>();
            int width = 0;
            boolean header = false, nested = false;
            for (int rowIndex = 0; rowIndex < table.numRows(); rowIndex++) {
                TableRow row = table.getRow(rowIndex);
                if (values.isEmpty()) header = row.isTableHeader();
                List<String> cells = new ArrayList<>();
                for (int cellIndex = 0; cellIndex < row.numCells(); cellIndex++) {
                    TableCell cell = row.getCell(cellIndex);
                    writer.checkTableWidth(Math.max(width, cells.size() + 1L), values.size() + 1, header);
                    String cellRange = range + "/row:" + (values.size() + 1) + "/column:" + (cells.size() + 1);
                    StringBuilder out = new StringBuilder();
                    for (int index = 0; index < cell.numParagraphs(); index++) {
                        Paragraph paragraph = cell.getParagraph(index);
                        nested |= paragraph.getTableLevel() > table.getTableLevel();
                        String value = paragraph(paragraph, cellRange, true);
                        if (!value.isBlank()) { if (!out.isEmpty()) writer.append(out, "<br>"); writer.append(out, value.replace("\n", "<br>")); }
                    }
                    // Only the first cell of a merged range keeps its content, as in DOCX.
                    boolean continuation = cell.isMerged() && !cell.isFirstMerged()
                            || cell.isVerticallyMerged() && !cell.isFirstVerticallyMerged();
                    cells.add(continuation ? "" : out.toString());
                }
                values.add(cells);
                width = Math.max(width, cells.size());
            }
            if (nested) writer.notice("NESTED_TABLE_UNSUPPORTED", range, "入れ子の表は文字だけを保持しました。", false);
            if (width == 0 || values.isEmpty()) return "";
            return writer.table(values, width, header);
        }

        /** Stories outside the main text are not converted from this format; say so when they hold content. */
        private void reportOmittedStories() {
            List<String> omitted = new ArrayList<>();
            if (document.getFootnotes().getNotesCount() > 0 || document.getEndnotes().getNotesCount() > 0) omitted.add("脚注・文末脚注");
            if (hasText(document.getMainTextboxRange())) omitted.add("テキストボックス");
            if (hasText(document.getCommentsRange())) omitted.add("コメント");
            if (!omitted.isEmpty()) writer.notice("LEGACY_WORD_CONTENT_OMITTED", "document",
                    ".doc の" + String.join("・", omitted) + "は本文へ出力していません。必要な場合は .docx で保存し直してください。", false);
        }

        private static boolean hasText(Range range) {
            if (range == null) return false;
            for (char value : range.text().toCharArray()) if (value > 0x20 && value != 0x7f) return true;
            return false;
        }
    }
}
