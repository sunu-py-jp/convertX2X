package com.convertx2x.office2pdf.conversion;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.imageio.ImageIO;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.PictureData;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/** Converts populated cells into compact searchable tables without recalculating formulas. */
final class ExcelPdfConverter {
    void convert(byte[] input, NormalizedPdfWriter pdf, ConversionWorkspace workspace) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(input))) {
            DataFormatter formatter = new DataFormatter(java.util.Locale.JAPAN);
            formatter.setUseCachedValuesForFormulaCells(true);
            int visible = 0;
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                if (workbook.isSheetHidden(index) || workbook.isSheetVeryHidden(index)) continue;
                Sheet sheet = workbook.getSheetAt(index);
                List<List<String>> rows = normalizedRows(sheet, formatter);
                workspace.sectionIncluded();
                visible++;
                if (rows.isEmpty()) {
                    pdf.heading("[" + sheet.getSheetName() + "] シート", 1);
                    pdf.paragraph("（値の入ったセルはありません）", false);
                } else pdf.table("[" + sheet.getSheetName() + "] シート", rows);
            }
            if (visible == 0) throw new ConversionException(422, "EMPTY_DOCUMENT", "表示対象のシートがありません。");
            appendPictures(workbook.getAllPictures(), pdf, workspace);
        }
    }

    private static List<List<String>> normalizedRows(Sheet sheet, DataFormatter formatter) {
        TreeSet<Integer> columns = new TreeSet<>();
        List<Map<Integer, String>> sparse = new ArrayList<>();
        for (Row row : sheet) {
            Map<Integer, String> values = new TreeMap<>();
            for (Cell cell : row) {
                String value = formatter.formatCellValue(cell).trim();
                if (!value.isEmpty()) {
                    columns.add(cell.getColumnIndex());
                    values.put(cell.getColumnIndex(), value);
                }
            }
            if (!values.isEmpty()) sparse.add(values);
        }
        if (columns.isEmpty()) return List.of();
        List<Integer> selected = List.copyOf(columns);
        List<List<String>> rows = new ArrayList<>(sparse.size());
        for (Map<Integer, String> row : sparse) {
            List<String> values = new ArrayList<>(selected.size());
            for (int column : selected) values.add(row.getOrDefault(column, ""));
            rows.add(values);
        }
        return rows;
    }

    private static void appendPictures(List<? extends PictureData> pictures, NormalizedPdfWriter pdf,
                                       ConversionWorkspace workspace) throws IOException {
        if (pictures.isEmpty()) return;
        pdf.heading("埋め込み画像", 1);
        int number = 0;
        for (PictureData picture : pictures) {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(picture.getData()));
            if (image == null) {
                workspace.warning("IMAGE_FORMAT_UNSUPPORTED", null,
                        "Excel内の画像形式をPDFへ配置できませんでした: " + picture.suggestFileExtension());
            } else pdf.image(image, "画像 " + (++number));
        }
    }
}
