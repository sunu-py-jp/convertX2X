package com.convertx2x.excel2img.conversion;

import com.convertx2x.excel2img.drawing.ExcelDrawingLayer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/** Excel-only conversion entry shared by HTTP and Queue. One worker allocates one bitmap at a time. */
public final class ExcelImageService {
    private static final Semaphore SLOT = new Semaphore(1, true);
    private static final Set<String> FORMATS = Set.of("xlsx", "xls");
    private final ConversionLimits limits;
    private final RenderPolicy policy;
    public ExcelImageService(ConversionLimits limits) { this(limits, RenderPolicy.defaults()); }
    public ExcelImageService(ConversionLimits limits, RenderPolicy policy) {
        this.limits = Objects.requireNonNull(limits); this.policy = Objects.requireNonNull(policy);
    }
    public void validate(byte[] input, String filename) {
        if (input == null || input.length == 0) throw new ConversionException(400, "EMPTY_INPUT", "Excelファイルを指定してください。");
        if (input.length > limits.maxInputBytes()) throw ConversionWorkspace.limit("INPUT_BYTES_LIMIT", "入力ファイルのサイズが上限を超えました。");
        if (!FORMATS.contains(extension(filename)))
            throw new ConversionException(415, "UNSUPPORTED_FORMAT", ".xlsx / .xls に対応しています。");
        FileMagic magic;
        try { magic = FileMagic.valueOf(input); }
        catch (IllegalArgumentException failure) { throw new ConversionException(415, "UNSUPPORTED_FORMAT", "Excelファイルの形式を確認できません。", failure); }
        if (magic != FileMagic.OOXML && magic != FileMagic.OLE2)
            throw new ConversionException(415, "UNSUPPORTED_FORMAT", "Excelファイルの形式を確認できません。");
    }
    public ConversionResult convert(byte[] input, String filename) { return convert(input, filename, RenderOptions.defaults()); }
    public ConversionResult convert(byte[] input, String filename, RenderOptions options) {
        validate(input, filename);
        Objects.requireNonNull(options);
        try { SLOT.acquire(); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ConversionException(503, "CONVERSION_INTERRUPTED", "変換が中断されました。", failure);
        }
        ConversionWorkspace workspace = null;
        boolean completed = false;
        try {
            workspace = new ConversionWorkspace(limits);
            convertWorkbook(input, filename, options, workspace);
            completed = true;
            return new ConversionResult(workspace);
        } catch (ConversionException failure) { throw failure;
        } catch (EncryptedDocumentException failure) {
            throw new ConversionException(415, "ENCRYPTED_DOCUMENT", "暗号化されたファイルには対応していません。", failure);
        } catch (IOException | RuntimeException failure) {
            for (Throwable cause = failure.getCause(); cause != null && cause != cause.getCause(); cause = cause.getCause())
                if (cause instanceof ConversionException conversion) throw conversion;
            throw new ConversionException(422, "INVALID_DOCUMENT", "Excelファイルを描画できませんでした。形式と内容を確認してください。", failure);
        } finally {
            try { if (!completed && workspace != null) workspace.close(); }
            finally { SLOT.release(); }
        }
    }
    private void convertWorkbook(byte[] input, String filename, RenderOptions options, ConversionWorkspace workspace) throws IOException {
        List<Map<String, Object>> ranges = new ArrayList<>();
        List<Map<String, Object>> pages = new ArrayList<>();
        boolean partial = false;
        long plannedCells = 0;
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(input));
             SearchablePdfWriter pdf = options.format().equals("pdf")
                     ? new SearchablePdfWriter(limits, message -> workspace.warning("PDF_GLYPH_SUBSTITUTED", null, message)) : null) {
            if (options.sheet() != null && (workbook.getSheet(options.sheet()) == null ||
                    !workbook.getSheet(options.sheet()).getSheetName().equals(options.sheet())))
                throw new ConversionException(400, "SHEET_NOT_FOUND", "指定されたシートが見つかりません。");
            CellValues values = new CellValues(workspace);
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                if (options.sheet() != null && !sheet.getSheetName().equals(options.sheet())) continue;
                if (options.sheet() == null && (workbook.isSheetHidden(i) || workbook.isSheetVeryHidden(i))) {
                    ranges.add(skipped(sheet, null, "default", "HIDDEN_SHEET", "非表示のシートは既定では描画しません。"));
                    continue;
                }
                SheetLayout geometry = new SheetLayout(sheet);
                ExcelDrawingLayer drawings = ExcelDrawingLayer.prepare(sheet, geometry, policy.maxSourceImagePixels(),
                        warning -> workspace.warning(warning.code(), sheet.getSheetName(), warning.message()));
                partial |= drawings.hasUnsupported();
                if (sheet.getRepeatingRows() != null || sheet.getRepeatingColumns() != null)
                    workspace.warning("PRINT_TITLES_IGNORED", sheet.getSheetName(), "印刷タイトルの繰り返しは適用していません。シート座標に沿う連続範囲として描画します。");
                if (sheet.getSheetConditionalFormatting().getNumConditionalFormattings() > 0)
                    workspace.warning("CONDITIONAL_FORMATTING_IGNORED", sheet.getSheetName(), "条件付き書式は評価していません。保存された通常のセル書式を使用しました。");
                List<RangePlanner.Selection> selections;
                try {
                    selections = RangePlanner.selections(sheet, options, geometry, drawings, workspace, policy.maxCells());
                } catch (RangePlanner.ScanLimit over) {
                    ranges.add(skipped(sheet, null, "occupied", "CELL_LIMIT", "使用セルの走査数が上限を超えています。"));
                    partial = true;
                    continue;
                }
                if (selections.isEmpty()) {
                    ranges.add(skipped(sheet, null, "occupied", "EMPTY_RANGE", "描画できるセルまたはオブジェクトがありません。"));
                    continue;
                }
                boolean included = false;
                for (RangePlanner.Selection selection : selections) {
                    var selected = selection.cells();
                    long cells = ((long) selected.getLastRow() - selected.getFirstRow() + 1) *
                            ((long) selected.getLastColumn() - selected.getFirstColumn() + 1);
                    if (cells > policy.maxCells() - plannedCells) {
                        ranges.add(skipped(sheet, selected.formatAsString(), selection.source(), "CELL_LIMIT", "変換全体のセル数が描画上限を超えています。"));
                        partial = true; continue;
                    }
                    RangePlanner.Plan plan = RangePlanner.plan(sheet, selected, geometry, policy, limits, values, workspace);
                    if (plan.skipReason() != null) {
                        ranges.add(skipped(sheet, selected.formatAsString(), selection.source(), plan.skipReason(), plan.skipMessage()));
                        partial = true; continue;
                    }
                    plannedCells += cells;
                    if (ConversionLimits.exceeds((long) workspace.pageCount() + plan.pages().size(), limits.maxPages()))
                        throw ConversionWorkspace.limit("PAGE_LIMIT", "生成ページ数が上限を超えました。");
                    CellPainter painter = new CellPainter(sheet, geometry, values, workspace, selected, policy.maxCells());
                    if (plan.pages().size() > 1 && drawings.bounds() != null && !drawings.bounds().isEmpty())
                        workspace.warning("DRAWING_PAGINATION", sheet.getSheetName(),
                                "図形はシート座標のまま描画します。ページ境界にまたがる部分は次のページに続きます。");
                    Map<String, Object> rangeReport = new LinkedHashMap<>();
                    rangeReport.put("sheet", sheet.getSheetName()); rangeReport.put("range", selected.formatAsString());
                    rangeReport.put("selection", selection.source()); rangeReport.put("status", "rendered");
                    rangeReport.put("pixelsPerPoint", plan.scale());
                    rangeReport.put("sourceBoundsPoints", rectangle(plan.bounds()));
                    List<Integer> rangePages = new ArrayList<>();
                    for (RangePlanner.Page window : plan.pages()) {
                        int number = workspace.pageCount() + 1;
                        int width = RangePlanner.positiveCeil(window.width() * plan.scale());
                        int height = RangePlanner.positiveCeil(window.height() * plan.scale());
                        if ((long) width * height > limits.maxImagePixels())
                            throw ConversionWorkspace.limit("IMAGE_PIXELS_LIMIT", "生成画像の画素数が上限を超えました。");
                        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                        List<CellPainter.TextLine> captured;
                        try {
                            Graphics2D g = image.createGraphics();
                            try {
                                g.setColor(Color.WHITE); g.fillRect(0, 0, width, height);
                                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                                g.scale(plan.scale(), plan.scale());
                                g.translate(-window.x(), -window.y());
                                Rectangle2D pageBounds = new Rectangle2D.Double(window.x(), window.y(), window.width(), window.height());
                                g.clip(pageBounds);
                                captured = painter.draw(g, window, pageBounds);
                                drawings.draw(g);
                            } finally { g.dispose(); }
                            if (pdf != null) {
                                List<CellPainter.TextLine> visible = new ArrayList<>(captured.size());
                                for (CellPainter.TextLine line : captured) {
                                    Rectangle2D ink = new Rectangle2D.Double(line.x(),
                                            line.baseline() - line.fontSize() * 1.15,
                                            line.width(), line.fontSize() * 1.4);
                                    if (drawings.intersectsDrawing(ink))
                                        workspace.warning("PDF_TEXT_OVERLAPS_DRAWING", sheet.getSheetName(),
                                                "図形と重なるセル文字は、画像に隠された内容を検索文字層に含めないため、PDF検索対象から除外しました。");
                                    else visible.add(line);
                                }
                                pdf.page(image, visible, plan, window, policy.pixelsPerPoint());
                            }
                            else try (OutputStream output = workspace.output(String.format(Locale.ROOT, "pages/page-%03d.png", number))) {
                                if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder unavailable");
                            }
                        } finally { image.flush(); }
                        workspace.pagesGenerated(number); rangePages.add(number);
                        Map<String, Object> page = new LinkedHashMap<>();
                        page.put("page", number); page.put("sheet", sheet.getSheetName()); page.put("range", selected.formatAsString());
                        page.put("path", pdf == null ? String.format(Locale.ROOT, "pages/page-%03d.png", number) : "document.pdf");
                        page.put("widthPixels", width); page.put("heightPixels", height);
                        page.put("sourceBoundsPoints", rectangle(new Rectangle2D.Double(window.x(), window.y(), window.width(), window.height())));
                        page.put("firstRow", window.firstRow() + 1); page.put("lastRow", window.lastRow() + 1);
                        page.put("firstColumn", window.firstColumn() + 1); page.put("lastColumn", window.lastColumn() + 1);
                        pages.add(page);
                    }
                    rangeReport.put("pages", rangePages); ranges.add(rangeReport);
                    if (!included) { workspace.sectionIncluded(); included = true; }
                }
                partial |= drawings.hasUnsupported();
            }
            if (workspace.pageCount() == 0) {
                Map<String, Object> details = workspace.report(filename, ConversionWorkspace.sha256(input), extension(filename), options, ranges, pages, true);
                throw new ConversionException(422, "NO_RENDERABLE_RANGE", "描画対象となる範囲がありません。sheet / range または描画上限を見直してください。", details);
            }
            if (pdf != null) try (OutputStream output = workspace.output("document.pdf")) { pdf.finish(output); }
        }
        partial |= workspace.warnings().stream().anyMatch(w -> !Set.of("FONT_SUBSTITUTED",
                "FORMULA_CACHED_VALUE", "RANGE_SCALED", "DRAWING_PAGINATION", "HORIZONTAL_PAGINATION").contains(w.get("code")));
        workspace.finishReport(workspace.report(filename, ConversionWorkspace.sha256(input), extension(filename), options, ranges, pages, partial));
    }
    private static Map<String, Object> skipped(Sheet sheet, String range, String selection, String reason, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sheet", sheet.getSheetName()); result.put("range", range); result.put("selection", selection);
        result.put("status", "skipped"); result.put("reason", reason); result.put("message", message); result.put("pages", List.of());
        return result;
    }
    private static Map<String, Double> rectangle(Rectangle2D bounds) {
        return Map.of("x", bounds.getX(), "y", bounds.getY(), "width", bounds.getWidth(), "height", bounds.getHeight());
    }
    private static String extension(String filename) {
        String value = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        int dot = value.lastIndexOf('.'); return dot < 0 ? "" : value.substring(dot + 1);
    }
}
