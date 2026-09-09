package com.convertx2x.excel2md.conversion;

import com.convertx2x.excel2md.drawing.DrawingBlock;
import com.convertx2x.excel2md.drawing.DrawingExtractor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;

/** Shared synchronous conversion core; at most one workbook is rendered per Java process. */
public class ExcelMarkdownService {
    private static final Semaphore CONVERSION_SLOT = new Semaphore(1, true);
    private final ConversionLimits limits;
    public ExcelMarkdownService(ConversionLimits limits) { this.limits = Objects.requireNonNull(limits); }

    public void validate(byte[] input, String filename) {
        if (input == null || input.length == 0) throw new ConversionException(400, "EMPTY_INPUT", "Excelファイルを指定してください。");
        if (input.length > limits.maxInputBytes()) throw ConversionWorkspace.limit("INPUT_BYTES_LIMIT", "入力ファイルのサイズが上限を超えました。");
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls"))
            throw new ConversionException(415, "UNSUPPORTED_FORMAT", ".xlsx / .xls に対応しています。");
        try {
            FileMagic magic = FileMagic.valueOf(input);
            if (magic != FileMagic.OLE2 && magic != FileMagic.OOXML)
                throw new ConversionException(415, "UNSUPPORTED_FORMAT", "Excelファイルの形式を確認できません。");
        } catch (IllegalArgumentException e) {
            throw new ConversionException(415, "UNSUPPORTED_FORMAT", "Excelファイルの形式を確認できません。", e);
        }
    }

    public ConversionResult convert(byte[] input, String filename) {
        validate(input, filename);
        try { CONVERSION_SLOT.acquire(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ConversionException(503, "CONVERSION_INTERRUPTED", "変換が中断されました。", e); }
        ConversionWorkspace workspace = null;
        try {
            workspace = new ConversionWorkspace(limits);
            Path source = workspace.directory().resolve("source.workbook");
            try { Files.write(source, input); }
            catch (IOException e) { throw ConversionWorkspace.io(e); }
            rejectOleMacrosOrEncryption(source, input);
            try (Workbook workbook = WorkbookFactory.create(source.toFile(), null, true)) {
                if (workbook instanceof XSSFWorkbook x && (x.isMacroEnabled() || !x.getPackagePart().getContentType()
                        .equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml")))
                    throw new ConversionException(415, "UNSUPPORTED_FORMAT", "マクロ有効ブック・テンプレートには対応していません。");
                if (workbook.getNumberOfSheets() > limits.maxSheets())
                    throw ConversionWorkspace.limit("SHEET_LIMIT", "シート数が上限を超えました。");
                long readCells = 0;
                for (Sheet sheet : workbook) for (Row row : sheet) {
                    readCells += row.getPhysicalNumberOfCells();
                    if (readCells > limits.maxReadCells()) throw ConversionWorkspace.limit("READ_CELLS_LIMIT", "読み取りセル数が上限を超えました。");
                }
                StringBuilder markdown = new StringBuilder();
                CellMarkdown cells = new CellMarkdown(workbook, workspace);
                long tableCells = 0;
                int line = 1;
                for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                    Sheet sheet = workbook.getSheetAt(index);
                    if (workbook.isSheetHidden(index) || workbook.isSheetVeryHidden(index)) {
                        workspace.info("HIDDEN_SHEET_OMITTED", sheet.getSheetName(), null, "非表示シートを除外しました。"); continue;
                    }
                    MergedRanges merges = new MergedRanges(sheet.getMergedRegions());
                    if (merges.all().size() > limits.maxReadCells()) throw ConversionWorkspace.limit("MERGE_LIMIT", "結合範囲の数が上限を超えました。");
                    if (sheet.getSheetConditionalFormatting().getNumConditionalFormattings() > 0)
                        workspace.warning("CONDITIONAL_FORMATTING_UNEVALUATED", sheet.getSheetName(), null,
                                "条件付き書式の罫線・太字・取消線は評価していません。");
                    if (sheet instanceof XSSFSheet x && !x.getTables().isEmpty())
                        workspace.info("TABLE_STYLE_UNEVALUATED", sheet.getSheetName(), null,
                                "Excelテーブルのスタイルは評価せず、直接セル罫線から表を検出しました。");
                    List<CellRangeAddress> tables = new BorderTables(sheet, merges, workspace).detect();
                    for (CellRangeAddress table : tables) {
                        tableCells += BorderTables.area(table);
                        if (tableCells > limits.maxTableCells()) throw ConversionWorkspace.limit("TABLE_CELLS_LIMIT", "ブック内の罫線表の展開セル数が上限を超えました。");
                    }
                    Map<Long, String> values = readCells(sheet, merges, cells, workspace);
                    List<Block> blocks = blocks(sheet, tables, merges, values, workspace);
                    List<DrawingBlock> drawings = new DrawingExtractor().extract(sheet, workspace);
                    for (DrawingBlock drawing : drawings) {
                        int row = drawing.firstRow(), column = drawing.firstColumn();
                        for (CellRangeAddress table : tables) if (overlaps(table, drawing)) {
                            // Tables are sorted. Pin an overlapping image immediately after
                            // the last such table, regardless of its original anchor row.
                            row = table.getFirstRow(); column = table.getFirstColumn();
                        }
                        CellRangeAddress drawingRange = drawing.firstRow() < 0 || drawing.firstColumn() < 0 || drawing.firstRow() == Integer.MAX_VALUE ? null
                                : new CellRangeAddress(drawing.firstRow(), drawing.lastRow(), drawing.firstColumn(), drawing.lastColumn());
                        blocks.add(new Block("drawing", drawingRange, drawing.markdown(), row < 0 ? Integer.MAX_VALUE : row,
                                column < 0 ? Integer.MAX_VALUE : column, 2, Map.of()));
                    }
                    blocks.sort(Comparator.comparingInt(Block::row).thenComparingInt(Block::column).thenComparingInt(Block::order));
                    if (blocks.isEmpty()) {
                        workspace.info("EMPTY_SHEET_OMITTED", sheet.getSheetName(), null, "出力する内容のないシートを除外しました。"); continue;
                    }
                    workspace.sheetIncluded();
                    if (!markdown.isEmpty()) { markdown.append('\n'); line++; }
                    String heading = "# " + Markdown.escape(sheet.getSheetName()) + "\n\n";
                    markdown.append(heading); line += 2;
                    Block previous = null;
                    for (Block block : blocks) {
                        if (previous != null) {
                            boolean adjacentText = block.type().equals("text") && previous.type().equals("text") && block.row() == previous.row() + 1;
                            if (adjacentText) { markdown.append("  \n"); line++; }
                            else { markdown.append("\n\n"); line += 2; }
                        }
                        String content = block.type().equals("text") ? block.markdown().replace("\n", "  \n") : block.markdown();
                        int start = line;
                        markdown.append(content); line += (int) content.chars().filter(c -> c == '\n').count();
                        Map<String, Object> metadata = new LinkedHashMap<>(block.metadata());
                        metadata.put("type", block.type()); metadata.put("sheet", sheet.getSheetName());
                        metadata.put("range", block.range() == null ? null : block.range().formatAsString());
                        metadata.put("markdownStartLine", start); metadata.put("markdownEndLine", line);
                        workspace.block(metadata);
                        if (markdown.length() > limits.maxMarkdownBytes()) throw ConversionWorkspace.limit("MARKDOWN_BYTES_LIMIT", "Markdownのサイズが上限を超えました。");
                        previous = block;
                    }
                    markdown.append('\n'); line++;
                }
                byte[] content = markdown.toString().getBytes(StandardCharsets.UTF_8);
                if (content.length > limits.maxMarkdownBytes()) throw ConversionWorkspace.limit("MARKDOWN_BYTES_LIMIT", "Markdownのサイズが上限を超えました。");
                workspace.write("document.md", content);
                workspace.finishReport(safeFilename(filename), ConversionWorkspace.sha256(input));
            }
            try { Files.delete(source); }
            catch (IOException e) { throw ConversionWorkspace.io(e); }
            return new ConversionResult(workspace);
        } catch (ConversionException e) {
            cleanup(workspace, e); throw e;
        } catch (EncryptedDocumentException e) {
            cleanup(workspace, e); throw new ConversionException(415, "ENCRYPTED_WORKBOOK", "暗号化されたExcelファイルには対応していません。", e);
        } catch (IOException | RuntimeException e) {
            cleanup(workspace, e); throw new ConversionException(422, "INVALID_WORKBOOK", "Excelファイルを読み取れませんでした。形式と内容を確認してください。", e);
        } finally { CONVERSION_SLOT.release(); }
    }

    private static Map<Long, String> readCells(Sheet sheet, MergedRanges merges, CellMarkdown formatter, ConversionWorkspace workspace) {
        Map<Long, String> values = new TreeMap<>();
        Set<Integer> hiddenColumns = new TreeSet<>();
        long characters = 0;
        for (Row row : sheet) {
            if (row.getZeroHeight()) {
                workspace.info("HIDDEN_ROW_OMITTED", sheet.getSheetName(), (row.getRowNum() + 1) + ":" + (row.getRowNum() + 1), "非表示行を除外しました。"); continue;
            }
            for (Cell cell : row) {
                if (sheet.isColumnHidden(cell.getColumnIndex())) { hiddenColumns.add(cell.getColumnIndex()); continue; }
                if (merges.covered(row.getRowNum(), cell.getColumnIndex())) continue;
                String value = formatter.format(cell);
                characters += value.length();
                if (characters > workspace.limits().maxMarkdownBytes()) throw ConversionWorkspace.limit("MARKDOWN_BYTES_LIMIT", "Markdownのサイズが上限を超えました。");
                if (!value.isEmpty()) values.put(BorderTables.key(row.getRowNum(), cell.getColumnIndex()), value);
            }
        }
        for (int column : hiddenColumns) workspace.info("HIDDEN_COLUMN_OMITTED", sheet.getSheetName(),
                org.apache.poi.ss.util.CellReference.convertNumToColString(column), "非表示列を除外しました。");
        for (CellRangeAddress merge : merges.all()) workspace.info("MERGED_CELLS", sheet.getSheetName(), merge.formatAsString(), "結合セルは表示中の左上セルの値だけを出力します。");
        return values;
    }

    private static List<Block> blocks(Sheet sheet, List<CellRangeAddress> tables, MergedRanges merges,
            Map<Long, String> values, ConversionWorkspace workspace) {
        List<Block> blocks = new ArrayList<>();
        Set<Long> tablePositions = new HashSet<>();
        for (CellRangeAddress table : tables) {
            List<Integer> rows = new ArrayList<>(), columns = new ArrayList<>();
            for (int row = table.getFirstRow(); row <= table.getLastRow(); row++) {
                Row physical = sheet.getRow(row);
                if (physical == null || !physical.getZeroHeight()) rows.add(row);
            }
            for (int column = table.getFirstColumn(); column <= table.getLastColumn(); column++) if (!sheet.isColumnHidden(column)) columns.add(column);
            boolean content = false;
            for (int row : rows) for (int column : columns) {
                long key = BorderTables.key(row, column); tablePositions.add(key);
                if (!values.getOrDefault(key, "").isBlank()) content = true;
            }
            if (!content || rows.isEmpty() || columns.isEmpty()) {
                workspace.info("EMPTY_TABLE_OMITTED", sheet.getSheetName(), table.formatAsString(), "出力内容のない表を除外しました。"); continue;
            }
            boolean nativeHeader = nativeHeader(sheet, table) && rows.getFirst() == table.getFirstRow();
            StringBuilder markdown = new StringBuilder();
            if (nativeHeader) appendTableRow(markdown, rows.getFirst(), columns, values);
            else appendTableRow(markdown, -1, columns, values);
            markdown.append('\n').append('|');
            for (int ignored : columns) markdown.append(" --- |");
            for (int row : rows) {
                if (nativeHeader && row == rows.getFirst()) continue;
                markdown.append('\n'); appendTableRow(markdown, row, columns, values);
                if (markdown.length() > workspace.limits().maxMarkdownBytes()) throw ConversionWorkspace.limit("MARKDOWN_BYTES_LIMIT", "Markdownのサイズが上限を超えました。");
            }
            List<String> merged = merges.all().stream().filter(table::intersects).map(CellRangeAddress::formatAsString).toList();
            if (!merged.isEmpty()) workspace.warning("TABLE_MERGE_FLATTENED", sheet.getSheetName(), table.formatAsString(), "Markdown表はセル結合を再現できないため、結合の続きは空欄です。");
            blocks.add(new Block("table", table, markdown.toString(), table.getFirstRow(), table.getFirstColumn(), 1,
                    Map.of("sourceRows", rows.stream().map(r -> r + 1).toList(), "sourceColumns", columns.stream().map(c -> c + 1).toList(),
                            "header", nativeHeader ? "excel-table" : "empty-generated", "mergedRanges", merged)));
        }
        Map<Integer, List<Map.Entry<Long, String>>> textRows = new TreeMap<>();
        for (var entry : values.entrySet()) if (!tablePositions.contains(entry.getKey()))
            textRows.computeIfAbsent(BorderTables.row(entry.getKey()), ignored -> new ArrayList<>()).add(entry);
        for (var row : textRows.entrySet()) {
            String joined = String.join("　", row.getValue().stream().map(Map.Entry::getValue).toList());
            if (joined.isBlank()) continue;
            int firstColumn = BorderTables.col(row.getValue().getFirst().getKey()), lastColumn = BorderTables.col(row.getValue().getLast().getKey());
            blocks.add(new Block("text", new CellRangeAddress(row.getKey(), row.getKey(), firstColumn, lastColumn), joined, row.getKey(), firstColumn, 0, Map.of()));
        }
        return blocks;
    }
    private static void appendTableRow(StringBuilder output, int row, List<Integer> columns, Map<Long, String> values) {
        output.append('|');
        for (int column : columns) output.append(' ').append(row < 0 ? "" : values.getOrDefault(BorderTables.key(row, column), "").replace("\n", "<br>")).append(" |");
    }
    private static boolean nativeHeader(Sheet sheet, CellRangeAddress range) {
        if (sheet instanceof XSSFSheet x) for (XSSFTable table : x.getTables())
            if (table.getHeaderRowCount() > 0 && table.getStartRowIndex() == range.getFirstRow() && table.getEndRowIndex() == range.getLastRow()
                    && table.getStartColIndex() == range.getFirstColumn() && table.getEndColIndex() == range.getLastColumn()) return true;
        return false;
    }
    private static boolean overlaps(CellRangeAddress range, DrawingBlock drawing) {
        return drawing.firstRow() <= range.getLastRow() && drawing.lastRow() >= range.getFirstRow()
                && drawing.firstColumn() <= range.getLastColumn() && drawing.lastColumn() >= range.getFirstColumn();
    }
    private static void rejectOleMacrosOrEncryption(Path file, byte[] input) throws IOException {
        if (FileMagic.valueOf(input) != FileMagic.OLE2) return;
        try (POIFSFileSystem fs = new POIFSFileSystem(file.toFile(), true)) {
            if (fs.getRoot().hasEntry("EncryptedPackage")) throw new EncryptedDocumentException("Encrypted input");
            if (fs.getRoot().hasEntry("_VBA_PROJECT_CUR") || fs.getRoot().hasEntry("VBA"))
                throw new ConversionException(415, "UNSUPPORTED_FORMAT", "マクロを含むブックには対応していません。");
        }
    }
    private static String safeFilename(String filename) {
        String basename = filename.replace('\\', '/'); basename = basename.substring(basename.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        return basename.length() > 255 ? basename.substring(0, 255) : basename;
    }
    private static void cleanup(ConversionWorkspace workspace, Throwable failure) {
        if (workspace != null) try { workspace.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
    }
    private record Block(String type, CellRangeAddress range, String markdown, int row, int column, int order, Map<String, Object> metadata) { }
}
