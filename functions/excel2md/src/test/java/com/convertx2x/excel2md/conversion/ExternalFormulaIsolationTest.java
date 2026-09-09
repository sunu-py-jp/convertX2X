package com.convertx2x.excel2md.conversion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STCalcMode;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.STCellType;

import static org.junit.jupiter.api.Assertions.*;

/** Uses real workbook conversion and a reachable local origin, without contacting external services. */
@Timeout(30)
class ExternalFormulaIsolationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REMOTE_VALUE = "REMOTE_RESPONSE_MUST_NOT_REPLACE_SAVED_VALUE";

    @Test
    void importsAndWebserviceUseSavedValuesOrFormulaTextWithoutFetching() throws Exception {
        try (LoopbackOrigin origin = new LoopbackOrigin(); XSSFWorkbook book = new XSSFWorkbook()) {
            List<String> expressions = List.of(
                    "IMPORTRANGE(\"" + origin.url("range") + "\",\"Sheet1!A1:B2\")",
                    "IMPORTXML(\"" + origin.url("xml") + "\",\"//title\")",
                    "IMPORTHTML(\"" + origin.url("html") + "\",\"table\",1)",
                    "IMPORTDATA(\"" + origin.url("data.csv") + "\")",
                    "IMPORTFEED(\"" + origin.url("feed.xml") + "\")",
                    "_xlfn.WEBSERVICE(\"" + origin.url("webservice") + "\")",
                    "NEW_REMOTE_FETCH(\"" + origin.url("unknown-function") + "\")",
                    "IFERROR(IMPORTXML(\"" + origin.url("nested-xml") + "\",\"//title\"),\"fallback\")");
            XSSFSheet cached = book.createSheet("保存済み結果");
            XSSFSheet missing = book.createSheet("キャッシュなし");
            for (int i = 0; i < expressions.size(); i++) {
                formula(cached, i, expressions.get(i), "保存済み結果" + (i + 1));
                formula(missing, i, expressions.get(i), null);
            }
            XSSFCell number = formula(cached, expressions.size(),
                    "VALUE(_xlfn.WEBSERVICE(\"" + origin.url("numeric") + "\"))", "1234.5");
            number.getCTCell().setT(STCellType.N);
            var style = book.createCellStyle();
            style.setDataFormat(book.createDataFormat().getFormat("0.00"));
            number.setCellStyle(style);

            try (ConversionResult result = convert(book)) {
                String markdown = markdown(result);
                JsonNode report = report(result);
                assertEquals(2, result.sheetCount());
                for (int i = 0; i < expressions.size(); i++) {
                    assertTrue(markdown.contains("保存済み結果" + (i + 1)), "Saved value was lost for " + expressions.get(i));
                    assertTrue(markdown.contains(Markdown.escape("=" + expressions.get(i))),
                            "A missing cache must leave the formula unevaluated");
                    assertTrue(hasWarning(report, "FORMULA_CACHE_MISSING", "キャッシュなし", "A" + (i + 1)));
                }
                assertTrue(markdown.contains("1234.50"), "The saved numeric result must retain its display format");
                assertFalse(markdown.contains(REMOTE_VALUE));
                assertOnlyTextArtifacts(result, report);
            }
            origin.assertNoConversionRequests();
        }
    }

    @Test
    void directAndNestedImageFormulasNeverFetchOrCreateRemoteMarkdownImages() throws Exception {
        try (LoopbackOrigin origin = new LoopbackOrigin(); XSSFWorkbook book = new XSSFWorkbook()) {
            List<String> expressions = List.of(
                    "IMAGE(\"" + origin.url("direct.png") + "\",\"外部画像\")",
                    "_xlfn.IMAGE(\"" + origin.url("prefixed.png") + "\")",
                    "IF(TRUE,_xlfn.IMAGE(\"" + origin.url("nested.png") + "\"),\"fallback\")",
                    "IFERROR(IMAGE(\"" + origin.url("nested-error.png") + "\"),\"fallback\")",
                    "IF(TRUE,IMAGE(IMPORTXML(\"" + origin.url("image-location.xml") + "\",\"//url\")),\"fallback\")");
            XSSFSheet cached = book.createSheet("画像キャッシュあり");
            XSSFSheet missing = book.createSheet("画像キャッシュなし");
            for (int i = 0; i < expressions.size(); i++) {
                formula(cached, i, expressions.get(i), "保存された画像式の表示" + (i + 1));
                formula(missing, i, expressions.get(i), null);
            }
            try (ConversionResult result = convert(book)) {
                String markdown = markdown(result);
                JsonNode report = report(result);
                assertEquals(2, result.sheetCount());
                assertTrue(markdown.contains("セル内画像: 未対応"));
                assertFalse(markdown.contains("!["), "A formula must not become a browser-fetched Markdown image");
                assertFalse(markdown.contains(REMOTE_VALUE));
                assertOnlyTextArtifacts(result, report);
                for (int i = 0; i < expressions.size(); i++)
                    assertTrue(hasWarning(report, "FORMULA_CACHE_MISSING", "画像キャッシュなし", "A" + (i + 1)));
            }
            origin.assertNoConversionRequests();
        }
    }

    @Test
    void hyperlinksRemainUserOperatedLinksWithoutResolvingDynamicFunctions() throws Exception {
        try (LoopbackOrigin origin = new LoopbackOrigin(); XSSFWorkbook book = new XSSFWorkbook()) {
            XSSFSheet sheet = book.createSheet("外部リンク");
            String literal = origin.url("user-click-only");
            String literalMissing = origin.url("user-click-without-cache");
            formula(sheet, 0, "HYPERLINK(\"" + literal + "\",\"式内の表示\")", "保存されたリンク表示");
            formula(sheet, 1, "_xlfn.HYPERLINK(\"" + literalMissing + "\",\"クリック用の表示\")", null);
            String dynamic = "HYPERLINK(_xlfn.WEBSERVICE(\"" + origin.url("dynamic-destination") + "\"),\"式内の表示\")";
            formula(sheet, 2, dynamic, "保存された動的リンク表示");
            formula(sheet, 3, dynamic, null);
            String dynamicLabel = origin.url("literal-with-dynamic-label");
            formula(sheet, 4, "HYPERLINK(\"" + dynamicLabel + "\",IMPORTXML(\""
                    + origin.url("dynamic-label.xml") + "\",\"//title\"))", "保存されたラベル");

            try (ConversionResult result = convert(book)) {
                String markdown = markdown(result);
                JsonNode report = report(result);
                assertTrue(markdown.contains("[保存されたリンク表示](" + literal + ")"));
                assertTrue(markdown.contains("[クリック用の表示](" + literalMissing + ")"));
                assertTrue(markdown.contains("[保存されたラベル](" + dynamicLabel + ")"));
                assertTrue(markdown.contains("保存された動的リンク表示"));
                assertFalse(markdown.contains("[保存された動的リンク表示]("));
                assertTrue(markdown.contains(Markdown.escape("=" + dynamic)));
                assertTrue(hasWarning(report, "DYNAMIC_HYPERLINK_UNSUPPORTED", "外部リンク", "A3"));
                assertTrue(hasWarning(report, "DYNAMIC_HYPERLINK_UNSUPPORTED", "外部リンク", "A4"));
                assertFalse(markdown.contains(REMOTE_VALUE));
                assertOnlyTextArtifacts(result, report);
            }
            origin.assertNoConversionRequests();
        }
    }

    private static XSSFCell formula(XSSFSheet sheet, int row, String expression, String cached) {
        XSSFCell cell = sheet.createRow(row).createCell(0);
        // Store foreign functions without asking POI's formula parser or evaluator to interpret them.
        var xml = cell.getCTCell();
        xml.addNewF().setStringValue(expression);
        xml.setT(cached == null ? STCellType.N : STCellType.STR);
        if (cached == null) { if (xml.isSetV()) xml.unsetV(); }
        else xml.setV(cached);
        return cell;
    }

    private static ConversionResult convert(XSSFWorkbook book) throws IOException {
        var calculation = book.getCTWorkbook().isSetCalcPr()
                ? book.getCTWorkbook().getCalcPr() : book.getCTWorkbook().addNewCalcPr();
        calculation.setCalcMode(STCalcMode.AUTO);
        calculation.setFullCalcOnLoad(true);
        calculation.setForceFullCalc(true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        book.write(bytes);
        return new ExcelMarkdownService(ConversionLimits.defaults()).convert(bytes.toByteArray(), "external-formulas.xlsx");
    }

    private static String markdown(ConversionResult result) throws IOException {
        return Files.readString(result.files().get("document.md"));
    }

    private static JsonNode report(ConversionResult result) throws IOException {
        return JSON.readTree(result.files().get("report.json").toFile());
    }

    private static boolean hasWarning(JsonNode report, String code, String sheet, String range) {
        for (JsonNode warning : report.path("warnings"))
            if (code.equals(warning.path("code").asText()) && sheet.equals(warning.path("sheet").asText())
                    && range.equals(warning.path("range").asText())) return true;
        return false;
    }

    private static void assertOnlyTextArtifacts(ConversionResult result, JsonNode report) {
        assertEquals(Set.of("document.md", "report.json"), result.files().keySet());
        assertEquals(0, report.path("assets").size());
    }

    private static final class LoopbackOrigin implements AutoCloseable {
        private final ConcurrentLinkedQueue<String> requests = new ConcurrentLinkedQueue<>();
        private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "excel2md-formula-origin");
            thread.setDaemon(true);
            return thread;
        });
        private final HttpServer server;
        private final String base;

        LoopbackOrigin() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            base = "http://127.0.0.1:" + server.getAddress().getPort();
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                if (!exchange.getRequestURI().getPath().equals("/test-health"))
                    requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                byte[] body = REMOTE_VALUE.getBytes(StandardCharsets.UTF_8);
                try (exchange) {
                    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
            try {
                // A live-origin probe prevents a false pass caused by an unreachable test server.
                HttpURLConnection probe = (HttpURLConnection) URI.create(base + "/test-health")
                        .toURL().openConnection(Proxy.NO_PROXY);
                probe.setConnectTimeout(2000);
                probe.setReadTimeout(2000);
                try (var response = probe.getInputStream()) {
                    assertEquals(REMOTE_VALUE, new String(response.readAllBytes(), StandardCharsets.UTF_8));
                } finally { probe.disconnect(); }
            } catch (IOException | RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        String url(String path) { return base + "/formula/" + path; }

        void assertNoConversionRequests() {
            assertEquals(List.of(), new ArrayList<>(requests), "Conversion must not issue requests to formula destinations");
        }

        @Override public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
