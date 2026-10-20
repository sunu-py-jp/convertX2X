import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Creates a fictional, dense 120-column by 300-row workbook for two-axis pagination. */
public class CreateLargePaginationSample {
    private static final int COLUMNS = 120;
    private static final int ROWS = 300;
    private static final int GROUP_WIDTH = 10;
    private static final int COLUMN_WIDTH = 14 * 256;
    private static final float ROW_HEIGHT = 26;
    private static final String SHEET_NAME = "120列300行の分割検証";
    private static final String FILE_NAME = "large-120-columns-300-rows.xlsx";
    private static final Color INK = new Color(27, 47, 65);
    private static final Color RULE = new Color(188, 203, 212);
    private static final Color[] GROUP_COLORS = {
        new Color(39, 101, 150), new Color(30, 121, 111), new Color(117, 81, 143),
        new Color(164, 102, 38), new Color(48, 115, 137), new Color(148, 75, 100)
    };
    private static final String[] HEADERS = {
        "行ID", "拠点", "担当", "受付", "完了", "保留", "達成率", "予定日", "状態", "行帯の目印"
    };

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass the input output directory");
        Path directory = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path output = directory.resolve(FILE_NAME);
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet(SHEET_NAME);
            sheet.setDisplayGridlines(false);
            sheet.createFreezePane(0, 4);
            for (int column = 0; column < COLUMNS; column++) sheet.setColumnWidth(column, COLUMN_WIDTH);
            for (int row = 0; row < ROWS; row++) {
                var target = sheet.createRow(row);
                target.setHeightInPoints(ROW_HEIGHT);
                for (int column = 0; column < COLUMNS; column++) target.createCell(column);
            }

            var bodyFont = font(book, 11, false, INK);
            var boldFont = font(book, 11, true, INK);
            var whiteFont = font(book, 11, true, Color.WHITE);
            var titleFont = font(book, 16, true, Color.WHITE);
            var groupFont = font(book, 12, true, INK);
            short percentFormat = book.createDataFormat().getFormat("0.0%");

            for (int group = 0; group < COLUMNS / GROUP_WIDTH; group++) {
                int firstColumn = group * GROUP_WIDTH;
                int lastColumn = firstColumn + GROUP_WIDTH - 1;
                Color color = GROUP_COLORS[group % GROUP_COLORS.length];
                CellStyle title = style(book, titleFont, color, HorizontalAlignment.LEFT);
                CellStyle groupNote = style(book, groupFont, tint(color, 0.84), HorizontalAlignment.LEFT);
                CellStyle columnNumber = style(book, whiteFont, color, HorizontalAlignment.CENTER);
                CellStyle columnLabel = style(book, boldFont, tint(color, 0.73), HorizontalAlignment.CENTER);
                String letters = CellReference.convertNumToColString(firstColumn) + ":"
                        + CellReference.convertNumToColString(lastColumn);
                mergeRow(sheet, 0, firstColumn, lastColumn,
                        "架空業務一覧 / 120列 × 300行 / 横・縦ページ分割の確認", title);
                mergeRow(sheet, 1, firstColumn, lastColumn,
                        String.format(Locale.ROOT, "グループ%02d | %s列 | 列%03d〜%03d | 明細はExcelの5〜300行",
                                group + 1, letters, firstColumn + 1, lastColumn + 1), groupNote);
                for (int column = firstColumn; column <= lastColumn; column++) {
                    text(sheet, 2, column, String.format(Locale.ROOT, "%03d / %s",
                            column + 1, CellReference.convertNumToColString(column)), columnNumber);
                    text(sheet, 3, column, HEADERS[column - firstColumn], columnLabel);
                }

                // Alternate every 50 source rows. The first column of every group repeats the source row ID.
                CellStyle[][][] body = new CellStyle[2][2][4];
                for (int band = 0; band < 2; band++) {
                    for (int boundary = 0; boundary < 2; boundary++) {
                        Color background = tint(color, band == 0 ? 0.95 : 0.84);
                        body[band][boundary][0] = style(book, bodyFont, background, HorizontalAlignment.LEFT);
                        body[band][boundary][1] = style(book, bodyFont, background, HorizontalAlignment.RIGHT);
                        body[band][boundary][2] = style(book, boldFont, tint(color, band == 0 ? 0.85 : 0.69),
                                HorizontalAlignment.LEFT);
                        body[band][boundary][3] = style(book, bodyFont, background, HorizontalAlignment.RIGHT);
                        body[band][boundary][3].setDataFormat(percentFormat);
                        body[band][boundary][2].setBorderLeft(BorderStyle.MEDIUM);
                        if (boundary == 1) {
                            for (CellStyle cellStyle : body[band][boundary]) cellStyle.setBorderTop(BorderStyle.MEDIUM);
                        }
                    }
                }

                for (int row = 4; row < ROWS; row++) {
                    int sourceRow = row + 1;
                    int band = (row / 50) % 2;
                    int boundary = row % 50 == 0 ? 1 : 0;
                    CellStyle[] cellStyles = body[band][boundary];
                    int received = 120 + (sourceRow * 7 + group * 19) % 780;
                    int pending = 1 + (sourceRow + group * 3) % 19;
                    int completed = received - pending;
                    text(sheet, row, firstColumn,
                            String.format(Locale.ROOT, "G%02d-R%03d", group + 1, sourceRow), cellStyles[2]);
                    text(sheet, row, firstColumn + 1,
                            String.format(Locale.ROOT, "拠点%02d", 1 + (row + group) % 40), cellStyles[0]);
                    text(sheet, row, firstColumn + 2, "担当" + (char) ('A' + (row + group) % 12), cellStyles[0]);
                    number(sheet, row, firstColumn + 3, received, cellStyles[1]);
                    number(sheet, row, firstColumn + 4, completed, cellStyles[1]);
                    number(sheet, row, firstColumn + 5, pending, cellStyles[1]);
                    number(sheet, row, firstColumn + 6, (double) completed / received, cellStyles[3]);
                    text(sheet, row, firstColumn + 7,
                            String.format(Locale.ROOT, "10/%02d", 1 + (row + group) % 28), cellStyles[0]);
                    text(sheet, row, firstColumn + 8,
                            new String[]{"完了", "確認待", "処理中", "受付済"}[(row + group) % 4], cellStyles[0]);
                    text(sheet, row, firstColumn + 9,
                            String.format(Locale.ROOT, "R%03d / 帯%d", sourceRow, row / 50 + 1), cellStyles[0]);
                }
            }
            book.setPrintArea(0, 0, COLUMNS - 1, 0, ROWS - 1);
            try (var stream = Files.newOutputStream(output)) {
                book.write(stream);
            }
        }
        verifySaved(output);
    }

    private static XSSFFont font(XSSFWorkbook book, int points, boolean bold, Color color) {
        var font = book.createFont();
        font.setFontName("BIZ UDPゴシック");
        font.setFontHeightInPoints((short) points);
        font.setBold(bold);
        font.setColor(new XSSFColor(color, null));
        return font;
    }

    private static CellStyle style(XSSFWorkbook book, XSSFFont font, Color background,
                                   HorizontalAlignment alignment) {
        var style = book.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(new XSSFColor(background, null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(alignment);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(false);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        var borderColor = new XSSFColor(RULE, null);
        style.setTopBorderColor(borderColor);
        style.setBottomBorderColor(borderColor);
        style.setLeftBorderColor(borderColor);
        style.setRightBorderColor(borderColor);
        return style;
    }

    private static Color tint(Color color, double white) {
        return new Color((int) Math.round(color.getRed() * (1 - white) + 255 * white),
                (int) Math.round(color.getGreen() * (1 - white) + 255 * white),
                (int) Math.round(color.getBlue() * (1 - white) + 255 * white));
    }

    private static void mergeRow(XSSFSheet sheet, int row, int firstColumn, int lastColumn,
                                 String value, CellStyle style) {
        for (int column = firstColumn; column <= lastColumn; column++) {
            var cell = sheet.getRow(row).getCell(column);
            cell.setBlank();
            cell.setCellStyle(style);
        }
        sheet.getRow(row).getCell(firstColumn).setCellValue(value);
        sheet.addMergedRegion(new CellRangeAddress(row, row, firstColumn, lastColumn));
    }

    private static void text(XSSFSheet sheet, int row, int column, String value, CellStyle style) {
        var cell = sheet.getRow(row).getCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void number(XSSFSheet sheet, int row, int column, double value, CellStyle style) {
        var cell = sheet.getRow(row).getCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void verifySaved(Path output) throws Exception {
        try (var stream = Files.newInputStream(output); var book = new XSSFWorkbook(stream)) {
            if (book.getNumberOfSheets() != 1) throw new IllegalStateException("Expected exactly one sheet");
            var sheet = book.getSheetAt(0);
            if (sheet.getLastRowNum() != ROWS - 1 || sheet.getPhysicalNumberOfRows() != ROWS)
                throw new IllegalStateException("Unexpected saved row count");
            double widthPoints = 0;
            int populated = 0;
            for (int column = 0; column < COLUMNS; column++) {
                if (sheet.getColumnWidth(column) != COLUMN_WIDTH)
                    throw new IllegalStateException("Unexpected saved column width");
                widthPoints += sheet.getColumnWidthInPixels(column) * 0.75;
            }
            for (int row = 0; row < ROWS; row++) {
                var saved = sheet.getRow(row);
                if (saved.getLastCellNum() != COLUMNS || saved.getPhysicalNumberOfCells() != COLUMNS
                        || saved.getHeightInPoints() != ROW_HEIGHT)
                    throw new IllegalStateException("Unexpected saved row geometry at row " + (row + 1));
                for (var cell : saved) {
                    if (book.getFontAt(cell.getCellStyle().getFontIndex()).getFontHeightInPoints() < 11)
                        throw new IllegalStateException("Font smaller than 11pt");
                    if (cell.getCellType() != CellType.BLANK) populated++;
                    else if (row >= 4) throw new IllegalStateException("Blank detail cell");
                }
                if (row >= 4) {
                    for (int group = 0; group < COLUMNS / GROUP_WIDTH; group++) {
                        String id = String.format(Locale.ROOT, "G%02d-R%03d", group + 1, row + 1);
                        if (!saved.getCell(group * GROUP_WIDTH).getStringCellValue().equals(id))
                            throw new IllegalStateException("Source row ID is missing");
                    }
                }
            }
            if (!book.getPrintArea(0).endsWith("$A$1:$DP$300"))
                throw new IllegalStateException("Unexpected saved print area");
            for (var merged : sheet.getMergedRegions()) {
                if (merged.getLastColumn() - merged.getFirstColumn() + 1 > GROUP_WIDTH)
                    throw new IllegalStateException("Merge extends beyond one group");
            }
            if (!book.getAllPictures().isEmpty()) throw new IllegalStateException("Unexpected picture");
            System.out.printf(Locale.ROOT,
                    "%s%nbytes=%d sheets=1 rows=%d columns=%d physicalCells=%d populatedCells=%d%n"
                            + "printArea=%s columnWidth=14 rowHeight=26pt minFont=11pt merges=%d%n"
                            + "sourceWidth=%.3fpt sourceHeight=%.3fpt%n",
                    output, Files.size(output), ROWS, COLUMNS, ROWS * COLUMNS, populated,
                    book.getPrintArea(0), sheet.getNumMergedRegions(), widthPoints, ROWS * ROW_HEIGHT);
        }
    }
}
