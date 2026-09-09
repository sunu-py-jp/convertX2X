package com.convertx2x.excel2md.conversion;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.*;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ExcelMarkdownServiceTest {
    private static final ConversionLimits DEFAULTS = ConversionLimits.defaults();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static Workbook book(boolean xlsx) { return xlsx ? new XSSFWorkbook() : new HSSFWorkbook(); }
    private static byte[] bytes(Workbook book) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); book.write(out); return out.toByteArray();
    }
    private static ConversionResult convert(Workbook book) throws IOException { return new ExcelMarkdownService(DEFAULTS).convert(bytes(book), book instanceof XSSFWorkbook ? "資料.xlsx" : "資料.xls"); }
    private static String markdown(ConversionResult result) throws IOException { return Files.readString(result.files().get("document.md")); }
    private static JsonNode report(ConversionResult result) throws IOException { return JSON.readTree(result.files().get("report.json").toFile()); }
    static Cell cell(Sheet sheet, int row, int column, String value) {
        Row r = sheet.getRow(row); if (r == null) r = sheet.createRow(row);
        Cell c = r.getCell(column); if (c == null) c = r.createCell(column);
        c.setCellValue(value); return c;
    }
    static CellStyle grid(Workbook book) {
        CellStyle s = book.createCellStyle(); s.setBorderTop(BorderStyle.THIN); s.setBorderBottom(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN); s.setBorderRight(BorderStyle.THIN); return s;
    }
    static void table(Sheet sheet, int r1, int r2, int c1, int c2) {
        CellStyle style = grid(sheet.getWorkbook());
        for (int r = r1; r <= r2; r++) for (int c = c1; c <= c2; c++) {
            Row row = sheet.getRow(r); if (row == null) row = sheet.createRow(r);
            Cell cell = row.getCell(c); if (cell == null) cell = row.createCell(c); cell.setCellStyle(style);
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void sheetHeadingsAndPlainRowsHaveNoInventedStructure(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("売上"); cell(sheet,0,0,"大きなタイトル"); cell(sheet,0,4,"同じ行");
            cell(sheet,1,0,"2行目\nセル内改行"); cell(sheet,4,0,"次の段落");
            book.createSheet("空白"); Sheet second = book.createSheet("補足"); cell(second,0,0,"内容");
            try (ConversionResult result = convert(book)) {
                assertEquals("# 売上\n\n大きなタイトル　同じ行  \n2行目  \nセル内改行\n\n次の段落\n\n# 補足\n\n内容\n", markdown(result));
                assertEquals(2, result.sheetCount()); assertTrue(report(result).toString().contains("EMPTY_SHEET_OMITTED"));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void bordersKeepEveryOriginalRowAndBlankCells(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("一覧"); table(sheet,1,4,1,3);
            cell(sheet,1,1,"商品"); cell(sheet,1,3,"価格"); cell(sheet,2,1,"りんご"); cell(sheet,4,3,"末尾");
            try (ConversionResult result = convert(book)) {
                assertTrue(markdown(result).contains("|  |  |  |\n| --- | --- | --- |\n| 商品 |  | 価格 |\n| りんご |  |  |\n|  |  |  |\n|  |  | 末尾 |"));
                JsonNode block = report(result).path("blocks").get(0); assertEquals("empty-generated", block.path("header").asText());
                assertEquals(List.of(2,3,4,5), JSON.convertValue(block.path("sourceRows"), List.class));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void outerBoxAndPartialGridFallbackToTextWithoutLosingValues(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("枠"); cell(sheet,0,0,"タイトル").setCellStyle(grid(book));
            table(sheet,3,4,0,1); cell(sheet,3,0,"A"); cell(sheet,3,1,"B"); cell(sheet,4,0,"C"); cell(sheet,4,1,"D");
            CellStyle incomplete = book.createCellStyle(); incomplete.cloneStyleFrom(sheet.getRow(4).getCell(1).getCellStyle());
            incomplete.setBorderRight(BorderStyle.NONE); sheet.getRow(4).getCell(1).setCellStyle(incomplete);
            try (ConversionResult result = convert(book)) {
                String md = markdown(result); assertFalse(md.contains("| ---"));
                assertTrue(md.contains("タイトル")); assertTrue(md.contains("A　B")); assertTrue(md.contains("C　D"));
                assertTrue(report(result).toString().contains("BORDER_NOT_TABLE"));
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void neighborBordersAndSeparateGridsAreDetected(boolean xlsx) throws Exception {
        try (Workbook book = book(xlsx)) {
            Sheet sheet = book.createSheet("表");
            for (int r=0;r<2;r++) for(int c=0;c<2;c++) {
                Cell cell = cell(sheet,r,c,"値"+r+c); CellStyle style = book.createCellStyle();
                style.setBorderBottom(BorderStyle.DOUBLE); style.setBorderRight(BorderStyle.DASHED);
                if(r==0) style.setBorderTop(BorderStyle.THIN); if(c==0) style.setBorderLeft(BorderStyle.THICK); cell.setCellStyle(style);
            }
            table(sheet,4,5,3,4); cell(sheet,4,3,"別表");
            try(ConversionResult result=convert(book)) { assertEquals(2, report(result).path("blocks").size()); assertEquals(2, markdown(result).split("\\| --- \\| --- \\|",-1).length-1); }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void strikeWinsOverBoldLinksAndFormulaFallbacks(boolean xlsx) throws Exception {
        try(Workbook book=book(xlsx)) {
            Sheet sheet=book.createSheet("文字"); Font strike=book.createFont();strike.setBold(true);strike.setStrikeout(true);
            CellStyle style=book.createCellStyle();style.setFont(strike);
            Cell a=cell(sheet,0,0,"削除秘密");a.setCellStyle(style);
            Hyperlink link=book.getCreationHelper().createHyperlink(HyperlinkType.URL);link.setAddress("https://secret.example/hidden");a.setHyperlink(link);
            Cell b=cell(sheet,1,0,"");b.setCellFormula("HYPERLINK(\"https://secret.example/formula\",\"秘密\")");b.setCellStyle(style);
            Cell c=cell(sheet,2,0,"");RichTextString rich=book.getCreationHelper().createRichTextString("旧価格新価格"); rich.applyFont(0,3,strike);c.setCellValue(rich);
            try(ConversionResult result=convert(book)) {
                String md=markdown(result);assertEquals("# 文字\n\n新価格\n",md);
                String report=report(result).toString(); assertTrue(report.contains("STRIKETHROUGH_REMOVED"));
                for(String forbidden:List.of("削除秘密","旧価格","secret.example","HYPERLINK")) {assertFalse(md.contains(forbidden));assertFalse(report.contains(forbidden));}
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void richTextBoldAndExplicitNormalOverrideCellBold(boolean xlsx) throws Exception {
        try(Workbook book=book(xlsx)) {
            Sheet sheet=book.createSheet("書式"); Font bold=book.createFont();bold.setBold(true);Font normal=book.createFont();normal.setBold(false);normal.setStrikeout(false);
            CellStyle style=book.createCellStyle();style.setFont(bold);
            Cell cell=cell(sheet,0,0,"");cell.setCellStyle(style);
            RichTextString rich=book.getCreationHelper().createRichTextString("太字通常太字");rich.applyFont(2,4,normal);cell.setCellValue(rich);
            try(ConversionResult result=convert(book)){assertEquals("# 書式\n\n**太字**通常**太字**\n",markdown(result));}
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void safeLinksEscapeMarkdownAndUnsafeHtmlRemainsText(boolean xlsx) throws Exception {
        try(Workbook book=book(xlsx)) {
            Sheet sheet=book.createSheet("リンク"); Cell a=cell(sheet,0,0,"[詳細]|<script>alert(1)</script>");
            Hyperlink link=book.getCreationHelper().createHyperlink(HyperlinkType.URL);link.setAddress("https://example.com/a(b)?q=x%20y");a.setHyperlink(link);
            Cell b=cell(sheet,2,0,"危険リンク");Hyperlink unsafe=book.getCreationHelper().createHyperlink(HyperlinkType.URL);unsafe.setAddress("javascript:alert(1)");b.setHyperlink(unsafe);
            Cell empty=cell(sheet,4,0,"");Hyperlink email=book.getCreationHelper().createHyperlink(HyperlinkType.EMAIL);email.setAddress("mailto:test@example.com");empty.setHyperlink(email);
            try(ConversionResult result=convert(book)){String md=markdown(result);assertTrue(md.contains("\\[詳細\\]\\|&lt;script&gt;"));assertTrue(md.contains("https://example.com/a%28b%29?q=x%20y"));assertFalse(md.contains("javascript:"));assertTrue(md.contains("[mailto:test@example.com](mailto:test@example.com)"));}
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void numericFormatsUseSavedValuesAndNeverEvaluate(boolean xlsx) throws Exception {
        try(Workbook book=book(xlsx)) {
            Sheet sheet=book.createSheet("数値");Cell number=cell(sheet,0,0,"");number.setCellValue(12);
            CellStyle leading=book.createCellStyle();leading.setDataFormat(book.createDataFormat().getFormat("00000"));number.setCellStyle(leading);
            Cell formula=cell(sheet,1,0,"");formula.setCellFormula("A1*2");formula.setCellValue(999);
            Cell percentage=cell(sheet,2,0,"");percentage.setCellValue(.125);CellStyle percent=book.createCellStyle();percent.setDataFormat(book.createDataFormat().getFormat("0.0%"));percentage.setCellStyle(percent);
            try(ConversionResult result=convert(book)){String md=markdown(result);assertTrue(md.contains("00012"));assertTrue(md.contains("999"));assertFalse(md.contains("24"));assertTrue(md.contains("12.5%"));}
        }
    }
    @Test void missingFormulaCacheAndConstantHyperlinkHaveExplicitFallback() throws Exception {
        try(XSSFWorkbook book=new XSSFWorkbook()) {
            XSSFSheet sheet=book.createSheet("数式");XSSFCell formula=(XSSFCell)cell(sheet,0,0,"");formula.setCellFormula("1+2");formula.getCTCell().unsetV();
            XSSFCell link=(XSSFCell)cell(sheet,2,0,"");link.setCellFormula("HYPERLINK(\"https://example.com\",\"表示\")");link.getCTCell().unsetV();
            try(ConversionResult result=convert(book)){String md=markdown(result);assertTrue(md.contains("=1\\+2"));assertTrue(md.contains("[表示](https://example.com)"));assertTrue(report(result).toString().contains("FORMULA_CACHE_MISSING"));}
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void hiddenCellsAndMergedAnchorAreNeverCopied(boolean xlsx) throws Exception {
        try(Workbook book=book(xlsx)) {
            Sheet sheet=book.createSheet("表示");table(sheet,0,3,0,2);cell(sheet,0,0,"表示値");cell(sheet,1,0,"非表示行の秘密");sheet.getRow(1).setZeroHeight(true);
            cell(sheet,2,1,"非表示列の秘密");sheet.setColumnHidden(1,true);
            Sheet hidden=book.createSheet("隠しシート");cell(hidden,0,0,"シート秘密");book.setSheetHidden(1,true);
            Sheet merge=book.createSheet("結合");cell(merge,0,0,"アンカー秘密");cell(merge,0,1,"結合内部秘密");merge.addMergedRegion(new CellRangeAddress(0,0,0,1));merge.setColumnHidden(0,true);cell(merge,2,0,"これも非表示");cell(merge,2,1,"表示本文");
            try(ConversionResult result=convert(book)){String md=markdown(result);assertFalse(md.contains("秘密"));assertTrue(md.contains("表示値"));assertTrue(md.contains("表示本文"));JsonNode b=report(result).path("blocks").get(0);assertEquals(List.of(1,3,4),JSON.convertValue(b.path("sourceRows"),List.class));assertEquals(List.of(1,3),JSON.convertValue(b.path("sourceColumns"),List.class));}
        }
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void mergedHeaderInsideGridKeepsAnchorAndEmptyContinuation(boolean xlsx) throws Exception {
        try(Workbook book=book(xlsx)) {
            Sheet sheet=book.createSheet("結合表");table(sheet,0,2,0,1);cell(sheet,0,0,"結合見出し");cell(sheet,1,0,"値");
            sheet.addMergedRegion(new CellRangeAddress(0,0,0,1));
            CellStyle left=book.createCellStyle();left.cloneStyleFrom(grid(book));left.setBorderRight(BorderStyle.NONE);sheet.getRow(0).getCell(0).setCellStyle(left);
            CellStyle right=book.createCellStyle();right.cloneStyleFrom(grid(book));right.setBorderLeft(BorderStyle.NONE);sheet.getRow(0).getCell(1).setCellStyle(right);
            try(ConversionResult result=convert(book)){assertTrue(markdown(result).contains("| 結合見出し |  |"));assertTrue(report(result).toString().contains("TABLE_MERGE_FLATTENED"));}
        }
    }
    @Test void nativeTableHeaderIsUsedOnlyWhenBordersMatch() throws Exception {
        try(XSSFWorkbook book=new XSSFWorkbook()) {
            XSSFSheet sheet=book.createSheet("実テーブル");cell(sheet,0,0,"項目");cell(sheet,0,1,"値");cell(sheet,1,0,"A");cell(sheet,1,1,"B");table(sheet,0,1,0,1);
            sheet.createTable(new AreaReference("A1:B2",book.getSpreadsheetVersion()));
            XSSFSheet second=book.createSheet("スタイルのみ");cell(second,0,0,"項目");cell(second,0,1,"値");cell(second,1,0,"C");cell(second,1,1,"D");second.createTable(new AreaReference("A1:B2",book.getSpreadsheetVersion()));
            try(ConversionResult result=convert(book)){String md=markdown(result);assertTrue(md.contains("| 項目 | 値 |\n| --- | --- |\n| A | B |"));assertTrue(md.contains("# スタイルのみ\n\n項目　値  \nC　D"));assertEquals("excel-table",report(result).path("blocks").get(0).path("header").asText());}
        }
    }
    @Test void outputZipContainsOnlyArtifactsAndTemporaryFilesAreDeleted() throws Exception {
        Path directory;
        try(Workbook book=new XSSFWorkbook()){cell(book.createSheet("表"),0,0,"内容");try(ConversionResult result=convert(book)){
            directory=result.directory();Set<String> entries=new HashSet<>();
            try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(result.zipBytes()))){for(ZipEntry e;(e=zip.getNextEntry())!=null;)entries.add(e.getName());}
            assertEquals(Set.of("document.md","report.json"),entries);assertFalse(Files.exists(directory.resolve("source.workbook")));
        }}assertFalse(Files.exists(directory));
    }
    @Test void inputAndCellAndOutputLimitsFailClearly() throws Exception {
        assertEquals("EMPTY_INPUT",assertThrows(ConversionException.class,()->new ExcelMarkdownService(DEFAULTS).convert(new byte[0],"x.xlsx")).code());
        assertEquals(415,assertThrows(ConversionException.class,()->new ExcelMarkdownService(DEFAULTS).convert(new byte[]{1,2,3},"x.xlsx")).statusCode());
        try(Workbook book=new XSSFWorkbook()) {
            cell(book.createSheet("表"),0,0,"本文");cell(book.getSheetAt(0),1,0,"次");byte[] bytes=bytes(book);
            ConversionLimits small=new ConversionLimits(DEFAULTS.maxInputBytes(),50,1,100,100,10,1000,1000,10,10,1000);
            assertEquals("READ_CELLS_LIMIT",assertThrows(ConversionException.class,()->new ExcelMarkdownService(small).convert(bytes,"x.xlsx")).code());
            ConversionLimits tiny=new ConversionLimits(DEFAULTS.maxInputBytes(),50,100,100,2,10,1000,1000,10,10,1000);
            assertEquals("MARKDOWN_BYTES_LIMIT",assertThrows(ConversionException.class,()->new ExcelMarkdownService(tiny).convert(bytes,"x.xlsx")).code());
        }
    }
    @Test void macroContentIsRejectedEvenWithXlsxFilename() throws Exception {
        try(XSSFWorkbook book=new XSSFWorkbook(XSSFWorkbookType.XLSM)){cell(book.createSheet("表"),0,0,"内容");byte[] bytes=bytes(book);assertEquals("UNSUPPORTED_FORMAT",assertThrows(ConversionException.class,()->new ExcelMarkdownService(DEFAULTS).convert(bytes,"pretend.xlsx")).code());}
    }
    @Test void imageFormulaIsNotFetchedOrExposedAsRemoteMarkdownImage() throws Exception {
        try(XSSFWorkbook book=new XSSFWorkbook()){Cell cell=cell(book.createSheet("画像"),0,0,"");cell.setCellFormula("_xlfn.IMAGE(\"https://private.example/pic.png\")");try(ConversionResult result=convert(book)){String md=markdown(result);assertFalse(md.contains("private.example"));assertTrue(md.contains("未対応"));assertTrue(report(result).toString().contains("CELL_IMAGE_UNSUPPORTED"));}}
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void pictureOnlySheetIsKeptAndOverlappingPictureFollowsWholeTable(boolean xlsx) throws Exception {
        try(Workbook book=book(xlsx)) {
            java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(4,4,java.awt.image.BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream png=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",png);image.flush();
            int picture=book.addPicture(png.toByteArray(),Workbook.PICTURE_TYPE_PNG);
            Sheet sheet=book.createSheet("配置");table(sheet,0,9,0,1);cell(sheet,0,0,"表の先頭");cell(sheet,9,1,"表の末尾");cell(sheet,1,4,"表外本文");
            ClientAnchor anchor=book.getCreationHelper().createClientAnchor();anchor.setRow1(4);anchor.setRow2(6);anchor.setCol1(0);anchor.setCol2(1);
            sheet.createDrawingPatriarch().createPicture(anchor,picture);
            Sheet imageOnly=book.createSheet("画像のみ");ClientAnchor other=book.getCreationHelper().createClientAnchor();other.setRow1(1);other.setRow2(3);other.setCol1(0);other.setCol2(2);imageOnly.createDrawingPatriarch().createPicture(other,picture);
            try(ConversionResult result=convert(book)) {
                String md=markdown(result);assertEquals(2,result.sheetCount());assertTrue(md.indexOf("表の末尾")<md.indexOf("!["));assertTrue(md.indexOf("![")<md.indexOf("表外本文"));
                assertTrue(md.contains("# 画像のみ"));assertEquals(1,report(result).path("assets").size());
            }
        }
    }
    @Test void hyperlinkWithDynamicLabelKeepsLiteralDestinationAndCachedText() throws Exception {
        try(XSSFWorkbook book=new XSSFWorkbook()) {
            Cell link=cell(book.createSheet("リンク"),0,0,"");link.setCellFormula("HYPERLINK(\"https://example.com/a\",B1)");link.setCellValue("保存済み表示");
            try(ConversionResult result=convert(book)){assertTrue(markdown(result).contains("[保存済み表示](https://example.com/a)"));}
        }
    }
}
