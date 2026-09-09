package com.convertx2x.excel2md.conversion;

import java.io.*;
import java.nio.file.*;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.hssf.usermodel.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.convertx2x.excel2md.conversion.ExcelMarkdownServiceTest.*;

/** A reproducible, original sample for API/browser verification; no third-party workbook. */
class PlaygroundFixtureTest {
    @Test void writeJapaneseExamplesAndVerifyTheirArtifacts() throws Exception {
        Path directory = Path.of("target", "fixtures"); Files.createDirectories(directory);
        for (boolean xlsx : new boolean[]{true, false}) {
            String filename = "sample." + (xlsx ? "xlsx" : "xls");
            try (Workbook book = xlsx ? new XSSFWorkbook() : new HSSFWorkbook()) {
                Sheet sheet = book.createSheet("月次レポート");
                Font bold = book.createFont(); bold.setBold(true);
                CellStyle heading = book.createCellStyle(); heading.setFont(bold);
                cell(sheet, 0, 0, "2026年9月　営業レポート").setCellStyle(heading);
                cell(sheet, 2, 0, "日本語・太字・リンク・取消線・罫線表・図形の変換例です。");
                Cell changed = cell(sheet, 3, 0, "");
                Font deleted = book.createFont(); deleted.setStrikeout(true);
                RichTextString rich = book.getCreationHelper().createRichTextString("旧担当：削除される文字担当：山田 花子");
                rich.applyFont(0, 11, deleted); changed.setCellValue(rich);
                Cell link = cell(sheet, 4, 0, "Apache POI の公式サイト");
                Hyperlink hyperlink = book.getCreationHelper().createHyperlink(HyperlinkType.URL);
                hyperlink.setAddress("https://poi.apache.org/"); link.setHyperlink(hyperlink);
                table(sheet, 6, 9, 0, 2);
                cell(sheet, 6, 0, "商品"); cell(sheet, 6, 1, "売上"); cell(sheet, 6, 2, "備考");
                cell(sheet, 7, 0, "りんご"); Cell price = cell(sheet, 7, 1, ""); price.setCellValue(12800);
                CellStyle money = book.createCellStyle(); money.cloneStyleFrom(grid(book));
                money.setDataFormat(book.createDataFormat().getFormat("#,##0\" 円\"")); price.setCellStyle(money);
                cell(sheet, 7, 2, "増加\n継続販売"); cell(sheet, 9, 0, "みかん"); cell(sheet, 9, 1, "8,600 円"); cell(sheet, 9, 2, "好調");
                if (sheet instanceof XSSFSheet x) {
                    XSSFDrawing drawing = x.createDrawingPatriarch();
                    XSSFSimpleShape shape = drawing.createSimpleShape(new XSSFClientAnchor(0,0,0,0,0,12,5,19));
                    shape.setShapeType(ShapeTypes.ROUND_RECT); shape.setFillColor(232,244,255); shape.setLineStyleColor(35,91,138);
                    shape.clearText(); XSSFTextRun run = shape.addNewTextParagraph().addNewTextRun();
                    run.setText("申請 → 確認 → 完了\n日本語の図形をPNG化"); run.setFontSize(18); run.setBold(true);
                    run.setFontFamily("Noto Sans CJK JP",(byte)0,(byte)0,false);
                } else if (sheet instanceof HSSFSheet h) {
                    HSSFSimpleShape shape = h.createDrawingPatriarch().createSimpleShape(new HSSFClientAnchor(0,0,0,0,(short)0,12,(short)5,19));
                    shape.setShapeType(HSSFShapeTypes.RoundRectangle); shape.setFillColor(232,244,255); shape.setLineStyleColor(35,91,138);
                    Font font = book.createFont(); font.setFontName("Noto Sans CJK JP"); font.setBold(true); font.setFontHeightInPoints((short)18);
                    HSSFRichTextString text = new HSSFRichTextString("申請 → 確認 → 完了\n日本語の図形をPNG化"); text.applyFont(font); shape.setString(text);
                }
                Sheet hidden = book.createSheet("非表示"); cell(hidden,0,0,"このシートは出力されません"); book.setSheetHidden(1,true);
                ByteArrayOutputStream input = new ByteArrayOutputStream(); book.write(input);
                Files.write(directory.resolve(filename), input.toByteArray());
                try (ConversionResult result = new ExcelMarkdownService(ConversionLimits.defaults()).convert(input.toByteArray(), filename)) {
                    Path output = directory.resolve(xlsx ? "xlsx-output" : "xls-output"); Files.createDirectories(output);
                    for (var artifact : result.files().entrySet()) {
                        Path path = output.resolve(artifact.getKey()); Files.createDirectories(path.getParent()); Files.copy(artifact.getValue(),path,StandardCopyOption.REPLACE_EXISTING);
                    }
                    String markdown = Files.readString(result.files().get("document.md"));
                    assertTrue(markdown.contains("月次レポート")); assertTrue(markdown.contains("担当：山田 花子")); assertFalse(markdown.contains("削除される文字"));
                    assertTrue(markdown.contains("images/diagram-0001.png"));
                    Files.write(directory.resolve(xlsx ? "xlsx-output.zip" : "xls-output.zip"), result.zipBytes());
                }
            }
        }
    }
}
