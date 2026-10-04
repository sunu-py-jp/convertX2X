package com.convertx2x.office2md.conversion;

import com.convertx2x.office2md.drawing.DrawingBlock;
import com.convertx2x.office2md.drawing.DrawingExtractor;
import com.convertx2x.office2md.ocr.OcrClient;
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
import org.apache.poi.ss.util.CellReference;
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
        return convert(input, filename, ImageMode.IGNORE, OcrClient.disabled());
    }

    public ConversionResult convert(byte[] input, String filename, ImageMode imageMode, OcrClient ocrClient) {
        validate(input, filename);
        try { CONVERSION_SLOT.acquire(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ConversionException(503, "CONVERSION_INTERRUPTED", "変換が中断されました。", e); }
        ConversionWorkspace workspace = null;
        try {
            workspace = new ConversionWorkspace(limits, imageMode, ocrClient);
            Path source = workspace.directory().resolve("source.workbook");
            try { Files.write(source, input); }
            catch (IOException e) { throw ConversionWorkspace.io(e); }
            rejectOleMacrosOrEncryption(source, input);
            try (Workbook workbook = WorkbookFactory.create(source.toFile(), null, true)) {
                if (workbook instanceof XSSFWorkbook x && (x.isMacroEnabled() || !x.getPackagePart().getContentType()
                        .equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml")))
                    throw new ConversionException(415, "UNSUPPORTED_FORMAT", "マクロ有効ブック・テンプレートには対応していません。");
                if (ConversionLimits.exceeds(workbook.getNumberOfSheets(), limits.maxSections()))
                    throw ConversionWorkspace.limit("SHEET_LIMIT", "シート数が上限を超えました。");
                long readCells = 0;
                for (Sheet sheet : workbook) for (Row row : sheet) {
                    readCells += row.getPhysicalNumberOfCells();
                    if (ConversionLimits.exceeds(readCells, limits.maxReadItems())) throw ConversionWorkspace.limit("READ_CELLS_LIMIT", "読み取りセル数が上限を超えました。");
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
                    if (ConversionLimits.exceeds(merges.all().size(), limits.maxReadItems())) throw ConversionWorkspace.limit("MERGE_LIMIT", "結合範囲の数が上限を超えました。");
                    if (sheet.getSheetConditionalFormatting().getNumConditionalFormattings() > 0)
                        workspace.warning("CONDITIONAL_FORMATTING_UNEVALUATED", sheet.getSheetName(), null,
                                "条件付き書式の罫線・太字・取消線は評価していません。");
                    if (sheet instanceof XSSFSheet x && !x.getTables().isEmpty())
                        workspace.info("TABLE_STYLE_UNEVALUATED", sheet.getSheetName(), null,
                                "Excelテーブルのスタイルは評価せず、直接セル罫線から表を検出しました。");
                    BorderTables borders = new BorderTables(sheet, merges, workspace);
                    List<CellRangeAddress> detected = borders.detect();
                    NavigableMap<Long, String> values = readCells(sheet, merges, cells, workspace);
                    NavigableMap<Long, String> tableCandidates = new TreeMap<>(values);
                    for (long openCell : borders.openTopCells()) tableCandidates.remove(openCell);
                    List<TableExpansion.Table> tables = TableExpansion.expand(sheet, detected, borders, merges, tableCandidates);
                    List<NamedTableRanges.Part> tableParts = NamedTableRanges.apply(sheet, tables, merges, workspace);
                    for (NamedTableRanges.Part part : tableParts) {
                        TableExpansion.Table table = part.table();
                        tableCells += (table.range().getLastRow() - (long) table.range().getFirstRow() + 1) * table.columns().size();
                        limits.checkTableCells(tableCells);
                    }
                    List<Block> blocks = blocks(sheet, tableParts, merges, borders, values, workspace);
                    List<DrawingBlock> drawings = new DrawingExtractor().extract(sheet, workspace);
                    for (DrawingBlock drawing : drawings) {
                        int row = drawing.firstRow(), column = drawing.firstColumn();
                        for (NamedTableRanges.Part part : tableParts) if (overlaps(part.table().range(), drawing)) {
                            TableExpansion.Table table = part.table();
                            // Tables are sorted. Pin an overlapping image immediately after
                            // the last such table, regardless of its original anchor row.
                            row = table.range().getFirstRow(); column = table.range().getFirstColumn();
                        }
                        CellRangeAddress drawingRange = drawing.firstRow() < 0 || drawing.firstColumn() < 0 || drawing.firstRow() == Integer.MAX_VALUE ? null
                                : new CellRangeAddress(drawing.firstRow(), drawing.lastRow(), drawing.firstColumn(), drawing.lastColumn());
                        blocks.add(new Block("drawing", drawingRange, drawing.markdown(), row < 0 ? Integer.MAX_VALUE : row,
                                column < 0 ? Integer.MAX_VALUE : column, 2, drawing.metadata()));
                    }
                    blocks.sort(Comparator.comparingInt(Block::row).thenComparingInt(Block::column).thenComparingInt(Block::order));
                    if (blocks.isEmpty()) {
                        workspace.info("EMPTY_SHEET_OMITTED", sheet.getSheetName(), null, "出力する内容のないシートを除外しました。"); continue;
                    }
                    workspace.sectionIncluded();
                    if (!markdown.isEmpty()) { markdown.append('\n'); line++; }
                    String heading = "# [" + Markdown.escape(sheet.getSheetName()) + "] シート\n\n";
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
                        metadata.put("type", block.type()); metadata.put("section", sheet.getSheetName());
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

    private static NavigableMap<Long, String> readCells(Sheet sheet, MergedRanges merges, CellMarkdown formatter, ConversionWorkspace workspace) {
        NavigableMap<Long, String> values = new TreeMap<>();
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

    private static List<Block> blocks(Sheet sheet, List<NamedTableRanges.Part> tables, MergedRanges merges, BorderTables borders,
            Map<Long, String> values, ConversionWorkspace workspace) {
        List<Block> blocks = new ArrayList<>();
        Set<Long> tablePositions = new HashSet<>(), handledOpenNotes = new HashSet<>();
        Set<Long> openTopCells = borders.openTopCells();
        for (NamedTableRanges.Part part : tables) {
            TableExpansion.Table expanded = part.table();
            CellRangeAddress table = expanded.range();
            List<Integer> rows = new ArrayList<>(), columns = expanded.columns();
            for (int row = table.getFirstRow(); row <= table.getLastRow(); row++) {
                Row physical = sheet.getRow(row);
                if (physical == null || !physical.getZeroHeight()) rows.add(row);
            }
            if (expanded.values().values().stream().allMatch(String::isBlank) || rows.isEmpty() || columns.isEmpty()) {
                workspace.info("EMPTY_TABLE_OMITTED", sheet.getSheetName(), table.formatAsString(), "出力内容のない表を除外しました。"); continue;
            }
            tablePositions.addAll(expanded.values().keySet());
            Map<Long, String> tableValues = new HashMap<>(expanded.values());
            for (CellRangeAddress span : expanded.inferredMerges()) {
                List<String> pieces = new ArrayList<>();
                for (int column = span.getFirstColumn(); column <= span.getLastColumn(); column++) {
                    String value = tableValues.remove(BorderTables.key(span.getFirstRow(), column));
                    if (value != null && !value.isBlank()) pieces.add(value);
                }
                tableValues.put(BorderTables.key(span.getFirstRow(), span.getFirstColumn()), String.join("　", pieces));
            }
            List<Integer> headerRows = new ArrayList<>();
            for (int index = 0; index < rows.size(); index++) {
                int row = rows.get(index);
                if (index == 0 ? !coloredHeaderRow(sheet, row, part.headerSeeds(), merges)
                        : !fullyColoredHeaderRow(sheet, row, part.headerSeeds(), merges)) break;
                headerRows.add(row);
            }
            int matrixHeaderDepth = matrixGroupHeaderDepth(table, rows, columns, tableValues, merges, borders, openTopCells);
            boolean matrixHeader = matrixHeaderDepth > headerRows.size();
            if (matrixHeader) {
                headerRows.clear();
                headerRows.addAll(rows.subList(0, matrixHeaderDepth));
                workspace.info("MATRIX_HEADER_INFERRED", sheet.getSheetName(), table.formatAsString(),
                        "上段の罫線付き結合見出しと、結合で階層が確認できる下段を見出しとして扱いました。");
            }
            List<ColumnSpan> outputColumns = outputColumns(rows, columns, merges);
            StringBuilder markdown = new StringBuilder();
            appendTableHeader(markdown, headerRows, outputColumns, tableValues, merges, new MergedRanges(expanded.inferredMerges()));
            markdown.append('\n').append('|');
            for (ColumnSpan ignored : outputColumns) markdown.append(" --- |");
            if (markdown.length() > workspace.limits().maxMarkdownBytes()) throw ConversionWorkspace.limit("MARKDOWN_BYTES_LIMIT", "Markdownのサイズが上限を超えました。");
            for (int index = headerRows.size(); index < rows.size(); index++) {
                int row = rows.get(index);
                markdown.append('\n'); appendTableRow(markdown, row, outputColumns, tableValues);
                if (markdown.length() > workspace.limits().maxMarkdownBytes()) throw ConversionWorkspace.limit("MARKDOWN_BYTES_LIMIT", "Markdownのサイズが上限を超えました。");
            }
            List<CellRangeAddress> mergedRanges = merges.all().stream().filter(table::intersects).toList();
            List<String> merged = mergedRanges.stream().map(CellRangeAddress::formatAsString).toList();
            List<Map.Entry<Long, String>> openNotes = values.entrySet().stream()
                    .filter(entry -> openTopCells.contains(entry.getKey()) && table.isInRange(
                            BorderTables.row(entry.getKey()), BorderTables.col(entry.getKey())))
                    .sorted(Map.Entry.comparingByKey()).toList();
            if (!openNotes.isEmpty()) {
                int first = BorderTables.col(openNotes.getFirst().getKey());
                int last = BorderTables.col(openNotes.getLast().getKey());
                blocks.add(new Block("text", new CellRangeAddress(table.getFirstRow(), table.getFirstRow(), first, last),
                        String.join("　", openNotes.stream().map(Map.Entry::getValue).toList()),
                        table.getFirstRow(), table.getFirstColumn(), 0, Map.of("noteKind", "unbordered-top-note")));
                for (var entry : openNotes) handledOpenNotes.add(entry.getKey());
            }
            List<CellRangeAddress> bodyMerges = new ArrayList<>();
            int firstBodyRow = headerRows.size() < rows.size() ? rows.get(headerRows.size()) : Integer.MAX_VALUE;
            for (CellRangeAddress merge : mergedRanges) {
                if (collapsedMerge(merge, outputColumns) || merge.getFirstRow() < firstBodyRow
                        || merge.getFirstRow() < table.getFirstRow() || merge.getLastRow() > table.getLastRow()
                        || merge.getFirstColumn() < table.getFirstColumn() || merge.getLastColumn() > table.getLastColumn()) continue;
                String value = tableValues.getOrDefault(BorderTables.key(merge.getFirstRow(), merge.getFirstColumn()), "");
                if (!value.isBlank()) bodyMerges.add(merge);
            }
            if (!bodyMerges.isEmpty()) {
                List<String> notes = new ArrayList<>();
                for (CellRangeAddress merge : bodyMerges) {
                    String value = tableValues.get(BorderTables.key(merge.getFirstRow(), merge.getFirstColumn())).replace('\n', ' ');
                    String extent = merge.getFirstRow() == merge.getLastRow() ? "明細行の複数列" :
                            merge.getFirstColumn() == merge.getLastColumn() ? "複数の明細行" : "複数の明細行・列";
                    notes.add("結合セル " + merge.formatAsString() + "「" + value + "」は" + extent + "に共通です。");
                }
                blocks.add(new Block("text", table, String.join("\n", notes), table.getFirstRow(), table.getFirstColumn(), 0,
                        Map.of("noteKind", "body-merge", "mergedRanges", bodyMerges.stream().map(CellRangeAddress::formatAsString).toList())));
            }
            if (outputColumns.stream().anyMatch(span -> span.first() != span.last()))
                workspace.info("TABLE_MERGED_COLUMNS_COLLAPSED", sheet.getSheetName(), table.formatAsString(),
                        "全表示行で同じ幅に横結合された列を、Markdownの1列にまとめました。");
            if (mergedRanges.stream().anyMatch(merge -> !collapsedMerge(merge, outputColumns)))
                workspace.warning("TABLE_MERGE_FLATTENED", sheet.getSheetName(), table.formatAsString(),
                        "Markdown表はセル結合を直接表せないため、共有見出しは各列へ継承し、明細の結合範囲は表の直前に記して続きのセルは空欄にします。");
            if (!expanded.inferredMerges().isEmpty()) workspace.warning("TABLE_VISUAL_MERGE_FLATTENED", sheet.getSheetName(), table.formatAsString(),
                    "仕切り線のない横並びの区画を見た目上の1セルとして扱い、値を左側にまとめました。");
            if (!part.split() && (expanded.seeds().size() > 1 || !expanded.seeds().getFirst().formatAsString().equals(table.formatAsString())))
                workspace.info("TABLE_EXPANDED", sheet.getSheetName(), table.formatAsString(), "検出した表と同じ行の左右の値・表を取り込みました。");
            for (NamedTableRanges.Defined name : part.names())
                blocks.add(new Block("table-name", name.range(), "**" + Markdown.escape(name.name()) + "**",
                        table.getFirstRow(), table.getFirstColumn(), 1, Map.of("definedName", name.name())));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("sourceRows", rows.stream().map(r -> r + 1).toList());
            metadata.put("sourceColumns", columns.stream().map(c -> c + 1).toList());
            metadata.put("sourceColumnSpans", outputColumns.stream().map(span -> Map.of("first", span.first() + 1, "last", span.last() + 1)).toList());
            metadata.put("header", matrixHeader ? "matrix-grid" : headerRows.isEmpty() ? "empty-generated" : "fill-color");
            metadata.put("headerSourceRows", headerRows.stream().map(r -> r + 1).toList());
            metadata.put("mergedRanges", merged);
            metadata.put("detectedRanges", expanded.seeds().stream().map(CellRangeAddress::formatAsString).toList());
            metadata.put("inferredMergedRanges", expanded.inferredMerges().stream().map(CellRangeAddress::formatAsString).toList());
            if (!part.names().isEmpty()) metadata.put("definedNames", part.names().stream().map(NamedTableRanges.Defined::name).toList());
            if (part.split()) metadata.put("sourceTableRange", part.sourceRange().formatAsString());
            blocks.add(new Block("table", table, markdown.toString(), table.getFirstRow(), table.getFirstColumn(),
                    part.names().isEmpty() ? 1 : 2, metadata));
        }
        Map<Integer, List<Map.Entry<Long, String>>> textRows = new TreeMap<>();
        for (var entry : values.entrySet()) if (!tablePositions.contains(entry.getKey()) && !handledOpenNotes.contains(entry.getKey()))
            textRows.computeIfAbsent(BorderTables.row(entry.getKey()), ignored -> new ArrayList<>()).add(entry);
        for (var row : textRows.entrySet()) {
            String joined = String.join("　", row.getValue().stream().map(Map.Entry::getValue).toList());
            if (joined.isBlank()) continue;
            int firstColumn = BorderTables.col(row.getValue().getFirst().getKey()), lastColumn = BorderTables.col(row.getValue().getLast().getKey());
            blocks.add(new Block("text", new CellRangeAddress(row.getKey(), row.getKey(), firstColumn, lastColumn), joined, row.getKey(), firstColumn, 0, Map.of()));
        }
        return blocks;
    }
    private static List<ColumnSpan> outputColumns(List<Integer> rows, List<Integer> columns, MergedRanges merges) {
        List<ColumnSpan> result = new ArrayList<>();
        for (int index = 0; index < columns.size();) {
            int first = columns.get(index);
            CellRangeAddress initial = merges.at(rows.getFirst(), first);
            if (initial != null && initial.getFirstRow() == rows.getFirst() && initial.getLastRow() == rows.getFirst()
                    && initial.getFirstColumn() == first && initial.getLastColumn() > first) {
                int last = initial.getLastColumn(), width = last - first + 1;
                boolean complete = index + width <= columns.size();
                for (int offset = 0; complete && offset < width; offset++)
                    complete = columns.get(index + offset) == first + offset;
                for (int row : rows) {
                    if (!complete) break;
                    CellRangeAddress merge = merges.at(row, first);
                    complete = merge != null && merge.getFirstRow() == row && merge.getLastRow() == row
                            && merge.getFirstColumn() == first && merge.getLastColumn() == last;
                }
                if (complete) {
                    result.add(new ColumnSpan(first, last));
                    index += width;
                    continue;
                }
            }
            result.add(new ColumnSpan(first, first));
            index++;
        }
        return result;
    }
    private static boolean collapsedMerge(CellRangeAddress merge, List<ColumnSpan> columns) {
        if (merge.getFirstRow() != merge.getLastRow()) return false;
        return columns.stream().anyMatch(span -> span.first() == merge.getFirstColumn()
                && span.last() == merge.getLastColumn() && span.first() != span.last());
    }
    private static void appendTableRow(StringBuilder output, int row, List<ColumnSpan> columns, Map<Long, String> values) {
        output.append('|');
        for (ColumnSpan column : columns) output.append(' ').append(values.getOrDefault(BorderTables.key(row, column.first()), "").replace("\n", "<br>")).append(" |");
    }
    private static void appendTableHeader(StringBuilder output, List<Integer> headerRows, List<ColumnSpan> columns,
            Map<Long, String> values, MergedRanges merges, MergedRanges inferredMerges) {
        List<HeaderLabel> labels = new ArrayList<>();
        for (ColumnSpan column : columns) {
            List<String> parts = new ArrayList<>();
            boolean shared = false;
            for (int row : headerRows) {
                CellRangeAddress merge = headerMerge(row, column.first(), merges, inferredMerges);
                if (merge != null && merge.getFirstColumn() != merge.getLastColumn()) shared = true;
                String value = headerValue(row, column.first(), values, merges, inferredMerges).replace("\n", "<br>").strip();
                if (!value.isEmpty() && (parts.isEmpty() || !parts.getLast().equals(value))) parts.add(value);
            }
            labels.add(new HeaderLabel(String.join(" / ", parts), shared));
        }
        Map<String, Integer> counts = new HashMap<>();
        for (HeaderLabel label : labels) if (!label.text().isBlank()) counts.merge(label.text(), 1, Integer::sum);
        output.append('|');
        for (int index = 0; index < columns.size(); index++) {
            HeaderLabel label = labels.get(index);
            String text = label.text();
            if (label.shared() && counts.getOrDefault(text, 0) > 1) {
                ColumnSpan span = columns.get(index);
                String first = CellReference.convertNumToColString(span.first());
                String source = span.first() == span.last() ? first : first + ":" + CellReference.convertNumToColString(span.last());
                text += "（" + source + "列）";
            }
            output.append(' ').append(text).append(" |");
        }
    }
    private static String headerValue(int row, int column, Map<Long, String> values, MergedRanges merges, MergedRanges inferredMerges) {
        CellRangeAddress merge = headerMerge(row, column, merges, inferredMerges);
        if (merge != null) return values.getOrDefault(BorderTables.key(merge.getFirstRow(), merge.getFirstColumn()), "");
        return values.getOrDefault(BorderTables.key(row, column), "");
    }
    private static CellRangeAddress headerMerge(int row, int column, MergedRanges merges, MergedRanges inferredMerges) {
        CellRangeAddress merge = merges.at(row, column);
        return merge != null ? merge : inferredMerges.at(row, column);
    }
    /** Infer only header levels backed by the matrix grid and explicit group/row-span geometry. */
    private static int matrixGroupHeaderDepth(CellRangeAddress table, List<Integer> rows, List<Integer> columns,
                                              Map<Long, String> values, MergedRanges merges, BorderTables borders,
                                              Set<Long> openTopCells) {
        if (rows.size() < 2 || rows.getFirst() != table.getFirstRow() || rows.get(1) != table.getFirstRow() + 1)
            return 0;
        int top = table.getFirstRow(), next = rows.get(1);
        boolean hasOpenCorner = false;
        for (int column = table.getFirstColumn(); column <= table.getLastColumn(); column++)
            if (openTopCells.contains(BorderTables.key(top, column))) {
                hasOpenCorner = true;
                break;
            }
        if (!hasOpenCorner) return 0;
        boolean hasBorderedTopLabel = false;
        for (int column : columns) {
            if (openTopCells.contains(BorderTables.key(top, column))
                    || !borders.hasHorizontal(top, column) || !borders.hasHorizontal(top + 1, column)) continue;
            CellRangeAddress merge = merges.at(top, column);
            int anchorColumn = merge == null ? column : merge.getFirstColumn();
            if (!values.getOrDefault(BorderTables.key(top, anchorColumn), "").isBlank()) {
                hasBorderedTopLabel = true;
                break;
            }
        }
        if (!hasBorderedTopLabel) return 0;
        boolean hasMergedCaption = merges.all().stream().anyMatch(merge -> merge.getFirstRow() == top
                && merge.getLastRow() == top && merge.getLastColumn() > merge.getFirstColumn()
                && merge.getFirstColumn() >= table.getFirstColumn() && merge.getLastColumn() <= table.getLastColumn()
                && !values.getOrDefault(BorderTables.key(top, merge.getFirstColumn()), "").isBlank());
        if (!hasMergedCaption) return 1;
        for (int column : columns)
            if (!borders.hasHorizontal(next, column)) return 1;
        // When a matrix is attached after ordinary top-row columns, the next row
        // might already be data. Promote it only if a leading open corner or a
        // deeper row-span/group level establishes the header hierarchy.
        boolean leadingOpenCorner = openTopCells.contains(BorderTables.key(top, table.getFirstColumn()));
        boolean deeperGroup = rows.size() > 2 && rows.get(2) == next + 1
                && hasHeaderRowSpan(table, next, rows.get(2), values, merges)
                && hasHeaderGroupAt(table, rows.get(2), values, merges);
        if (!leadingOpenCorner && !deeperGroup) return 1;
        int depth = 2;
        while (depth < rows.size() - 1) {
            int candidate = rows.get(depth), previous = rows.get(depth - 1);
            if (candidate != previous + 1 || !hasHeaderRowSpan(table, next, candidate, values, merges)
                    || !hasHeaderGroupAt(table, candidate, values, merges)) break;
            depth++;
        }
        return depth;
    }
    private static boolean hasHeaderRowSpan(CellRangeAddress table, int firstHeaderRow, int candidate,
                                            Map<Long, String> values, MergedRanges merges) {
        return merges.all().stream().anyMatch(merge -> merge.getFirstRow() >= firstHeaderRow
                && merge.getFirstRow() < candidate && merge.getLastRow() >= candidate
                && merge.getFirstColumn() >= table.getFirstColumn() && merge.getLastColumn() <= table.getLastColumn()
                && !values.getOrDefault(BorderTables.key(merge.getFirstRow(), merge.getFirstColumn()), "").isBlank());
    }
    private static boolean hasHeaderGroupAt(CellRangeAddress table, int row,
                                            Map<Long, String> values, MergedRanges merges) {
        return merges.all().stream().anyMatch(merge -> merge.getFirstRow() == row && merge.getLastRow() == row
                && merge.getLastColumn() > merge.getFirstColumn()
                && merge.getFirstColumn() >= table.getFirstColumn() && merge.getLastColumn() <= table.getLastColumn()
                && !values.getOrDefault(BorderTables.key(row, merge.getFirstColumn()), "").isBlank());
    }
    /** A direct fill in any detected grid cell marks the first row; expanded side notes do not. */
    private static boolean coloredHeaderRow(Sheet sheet, int row, List<CellRangeAddress> seeds, MergedRanges merges) {
        Row physical = sheet.getRow(row);
        if (physical == null) return false;
        for (CellRangeAddress seed : seeds) {
            for (int column = seed.getFirstColumn(); column <= seed.getLastColumn(); column++) {
                if (sheet.isColumnHidden(column)) continue;
                Cell cell = physical.getCell(column);
                CellRangeAddress merge = merges.at(row, column);
                if (merge != null && merge.getFirstRow() == row && merge.getFirstColumn() != column)
                    cell = physical.getCell(merge.getFirstColumn());
                if (hasColoredFill(cell)) return true;
            }
        }
        return false;
    }
    /** Later header levels need a colored band, not just a colored category cell in a detail row. */
    private static boolean fullyColoredHeaderRow(Sheet sheet, int row, List<CellRangeAddress> seeds, MergedRanges merges) {
        Row physical = sheet.getRow(row);
        if (physical == null) return false;
        boolean hasOwnCell = false;
        for (CellRangeAddress seed : seeds) {
            if (row < seed.getFirstRow() || row > seed.getLastRow()) continue;
            for (int column = seed.getFirstColumn(); column <= seed.getLastColumn(); column++) {
                if (sheet.isColumnHidden(column)) continue;
                CellRangeAddress merge = merges.at(row, column);
                // A label merged down from an earlier header row adds no new level to this row.
                if (merge != null && merge.getFirstRow() < row) continue;
                int anchor = merge == null ? column : merge.getFirstColumn();
                Cell cell = physical.getCell(anchor);
                if (!hasColoredFill(cell)) return false;
                hasOwnCell = true;
            }
        }
        return hasOwnCell;
    }
    private static boolean hasColoredFill(Cell cell) {
        if (cell == null) return false;
        CellStyle style = cell.getCellStyle();
        if (style.getFillPattern() == FillPatternType.NO_FILL
                || style.getFillForegroundColor() == IndexedColors.WHITE.getIndex()
                || style.getFillForegroundColor() == IndexedColors.AUTOMATIC.getIndex()) return false;
        if (style.getFillForegroundColorColor() instanceof XSSFColor color) {
            byte[] rgb = color.getRGB();
            if (rgb != null && rgb.length == 3 && (rgb[0] & 0xff) == 255
                    && (rgb[1] & 0xff) == 255 && (rgb[2] & 0xff) == 255) return false;
        }
        return true;
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
    private record ColumnSpan(int first, int last) { }
    private record HeaderLabel(String text, boolean shared) { }
}
