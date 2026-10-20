package com.convertx2x.excel2img;

import com.microsoft.azure.functions.*;
import com.convertx2x.excel2img.conversion.*;
import com.convertx2x.excel2img.jobs.*;
import org.apache.pdfbox.Loader;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.imageio.ImageIO;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Logger;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;

class ConversionFunctionsTest {
    private final ExcelImageService converter = new ExcelImageService(ConversionLimits.defaults());

    @Test void synchronousHttpReturnsPngAndPdfZipWithReportWithoutStorage() throws Exception {
        ConversionFunctions functions = new ConversionFunctions(AppConfig.from(Map.of()), converter,
                () -> { throw new AssertionError("Storage must not initialize"); });
        for (String format : List.of("png", "pdf")) {
            HttpResponseMessage response = functions.convert(request("/api/convert", workbook(),
                    Map.of("filename", "日本語.xlsx", "format", format, "sheet", "売上", "range", "A1:B2"), Map.of()), context());
            assertEquals(200, response.getStatusCode(), response.getBody().toString());
            assertEquals("application/zip", response.getHeader("Content-Type"));
            assertEquals("1", response.getHeader("X-Section-Count"));
            assertEquals("1", response.getHeader("X-Page-Count"));
            var files = unzip((byte[]) response.getBody());
            assertTrue(files.containsKey("report.json"));
            var report = new ObjectMapper().readTree(files.get("report.json"));
            assertEquals(format, report.path("format").asText());
            assertEquals("A1:B2", report.path("options").path("range").asText());
            if (format.equals("png")) {
                assertNotNull(ImageIO.read(new ByteArrayInputStream(files.get("pages/page-001.png"))));
                assertFalse(files.containsKey("document.pdf"));
            } else {
                try (var pdf = Loader.loadPDF(files.get("document.pdf"))) { assertEquals(1, pdf.getNumberOfPages()); }
            }
        }
    }

    @Test void asynchronousStatusUsesRelativeLinksWithoutFunctionKeyAndReturnsSameArtifacts() throws Exception {
        MemoryJobs jobs = new MemoryJobs(converter);
        ConversionFunctions functions = new ConversionFunctions(AppConfig.from(Map.of(AppConfig.STORAGE_SETTING, "test")), converter, () -> jobs);
        HttpResponseMessage accepted = functions.submit(request("/custom/jobs?code=secret", workbook(),
                Map.of("filename", "workbook.xlsx", "format", "png", "sheet", "売上", "range", "A1:B2"), Map.of()), context());
        assertEquals(202, accepted.getStatusCode());
        assertEquals(new RenderOptions("png", "売上", "A1:B2"), jobs.options);
        assertEquals("/custom/jobs/" + jobs.id, accepted.getHeader("Location"));
        assertFalse(accepted.getBody().toString().contains("secret"));
        functions.process("message", context());
        HttpResponseMessage status = functions.status(request("/custom/jobs/" + jobs.id, "", Map.of(), Map.of()), jobs.id, context());
        for (String suffix : List.of("result", "report", "archive"))
            assertTrue(status.getBody().toString().contains("/custom/jobs/" + jobs.id + "/" + suffix));
        HttpResponseMessage archive = functions.download(request("/custom/jobs/" + jobs.id + "/result", "", Map.of(), Map.of()), jobs.id, context());
        assertEquals("application/zip", archive.getHeader("Content-Type"));
        assertArrayEquals(jobs.files.get("pages/page-001.png"), unzip((byte[]) archive.getBody()).get("pages/page-001.png"));
        HttpResponseMessage page = functions.file(request("/custom/jobs/" + jobs.id + "/files/pages/page-001.png", "", Map.of(), Map.of()), jobs.id, "pages/page-001.png", context());
        assertEquals("image/png", page.getHeader("Content-Type")); assertArrayEquals(jobs.files.get("pages/page-001.png"), (byte[]) page.getBody());
        assertEquals(404, functions.file(request("/custom/jobs/" + jobs.id + "/files/input", "", Map.of(), Map.of()), jobs.id, "input", context()).getStatusCode());
    }

    @Test void optionsAreValidatedBeforeStorageAndUnsupportedDocumentsAreRejected() throws Exception {
        var functions = new ConversionFunctions(AppConfig.from(Map.of(AppConfig.STORAGE_SETTING, "test")), converter,
                () -> { throw new AssertionError("Invalid options must not initialize Storage"); });
        for (var options : List.of(Map.of("filename", "book.xlsx", "format", "jpeg"),
                Map.of("filename", "book.xlsx", "range", "A1:B2"), Map.of("filename", "book.xlsx", "unexpected", "x"))) {
            assertEquals(400, functions.submit(request("/api/jobs", workbook(), options, Map.of()), context()).getStatusCode());
            assertEquals(400, functions.convert(request("/api/convert", workbook(), options, Map.of()), context()).getStatusCode());
        }
        assertEquals(415, functions.convert(request("/api/convert", workbook(), Map.of("filename", "book.docx"), Map.of()), context()).getStatusCode());
    }

    @Test void allSkippedHttpErrorIncludesRangeReasons() throws Exception {
        var tiny = new RenderPolicy(100, 100, 1000, 2, 200000, 20000000);
        var functions = new ConversionFunctions(AppConfig.from(Map.of()), new ExcelImageService(ConversionLimits.defaults(), tiny),
                () -> { throw new AssertionError("Storage must not initialize"); });
        var response = functions.convert(request("/api/convert", workbook(), Map.of("filename", "book.xlsx"), Map.of()), context());
        assertEquals(422, response.getStatusCode());
        var error = new ObjectMapper().readTree(response.getBody().toString()).path("error");
        assertEquals("NO_RENDERABLE_RANGE", error.path("code").asText());
        assertTrue(error.path("details").isObject());
        assertFalse(error.path("details").isEmpty());
    }

    @Test void disabledAndInvalidRequestsNeverInitializeStorage() {
        var disabled = new ConversionFunctions(AppConfig.from(Map.of()), converter,
                () -> { throw new AssertionError("Storage unavailable"); });
        assertEquals(503, disabled.submit(request("/api/jobs", new byte[]{1}, Map.of(), Map.of()), context()).getStatusCode());
        assertEquals(503, disabled.status(request("/api/jobs/x", "", Map.of(), Map.of()), "x", context()).getStatusCode());
        assertEquals(503, disabled.archive(request("/api/jobs/x/archive", "", Map.of(), Map.of()), "x", context()).getStatusCode());
        var limited = new ConversionFunctions(AppConfig.from(Map.of(AppConfig.STORAGE_SETTING, "test", "CONVERSION_MAX_INPUT_BYTES", "10")), converter,
                () -> { throw new AssertionError("Invalid input must not initialize Storage"); });
        assertEquals(413, limited.submit(request("/api/jobs", new byte[11], Map.of(), Map.of()), context()).getStatusCode());
        assertEquals(415, limited.convert(request("/api/convert", new byte[]{1}, Map.of(), Map.of("Content-Type", "multipart/form-data")), context()).getStatusCode());
        assertEquals(400, limited.convert(request("/api/convert", new byte[0], Map.of(), Map.of()), context()).getStatusCode());
        assertEquals(400, limited.convert(request("/api/convert", new byte[]{1}, Map.of(), Map.of()), context()).getStatusCode());
    }

    @Test void capabilitiesAndErrorsNeverExposeStorageCredentials() {
        AppConfig config = AppConfig.from(Map.of(AppConfig.STORAGE_SETTING, "AccountKey=secret"));
        assertFalse(config.toString().contains("secret"));
        HttpResponseMessage capabilities = new PlaygroundFunctions(config).capabilities(request("/api/capabilities", "", Map.of(), Map.of()));
        assertTrue(capabilities.getBody().toString().contains("\"maxPages\":" + ConversionLimits.defaults().maxPages()));
        assertTrue(capabilities.getBody().toString().contains("\"supportedFormats\":[\"xlsx\",\"xls\"]"));
        assertTrue(capabilities.getBody().toString().contains("\"outputFormats\":[\"png\",\"pdf\"]"));
        assertTrue(capabilities.getBody().toString().contains("\"maxWidthPixels\":2048"));
        assertTrue(capabilities.getBody().toString().contains("\"maxImagePixels\":20000000"));
        assertFalse(capabilities.getBody().toString().contains("secret"));
        var functions = new ConversionFunctions(config, converter, () -> { throw new IllegalStateException("AccountKey=secret"); });
        HttpResponseMessage error = functions.status(request("/api/jobs/id", "", Map.of(), Map.of()), "id", context());
        assertEquals(500, error.getStatusCode());
        assertFalse(error.getBody().toString().contains("secret"));
        IllegalStateException queue = assertThrows(IllegalStateException.class, () -> functions.process("{}", context()));
        assertNull(queue.getCause());
        assertFalse(queue.getMessage().contains("secret"));
        assertThrows(IllegalArgumentException.class, () -> AppConfig.from(Map.of("CONVERSION_MAX_PAGES", "-1")));
    }

    private static byte[] workbook() throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("売上");
            sheet.createRow(0).createCell(0).setCellValue("日本語");
            sheet.getRow(0).createCell(1).setCellValue("金額");
            sheet.createRow(1).createCell(0).setCellValue("東京");
            sheet.getRow(1).createCell(1).setCellValue(12345);
            book.write(bytes);
            return bytes.toByteArray();
        }
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) files.put(entry.getName(), zip.readAllBytes());
        }
        return files;
    }

    private static ExecutionContext context() {
        return proxy(ExecutionContext.class, (p, m, a) -> switch (m.getName()) {
            case "getLogger" -> Logger.getLogger("test");
            case "getInvocationId" -> "test-invocation";
            case "getFunctionName" -> "test";
            default -> null;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> HttpRequestMessage<Optional<T>> request(String path, T body,
            Map<String, String> query, Map<String, String> headers) {
        return proxy(HttpRequestMessage.class, (p, m, a) -> switch (m.getName()) {
            case "getBody" -> Optional.ofNullable(body);
            case "getUri" -> URI.create("https://example.test" + path);
            case "getQueryParameters" -> query;
            case "getHeaders" -> headers;
            case "getHttpMethod" -> HttpMethod.POST;
            case "createResponseBuilder" -> responseBuilder((HttpStatusType) a[0]);
            default -> null;
        });
    }

    private static HttpResponseMessage.Builder responseBuilder(HttpStatusType initialStatus) {
        Map<String, String> headers = new HashMap<>();
        Object[] body = {null};
        HttpStatusType[] status = {initialStatus};
        return proxy(HttpResponseMessage.Builder.class, (p, m, a) -> {
            switch (m.getName()) {
                case "header" -> headers.put((String) a[0], (String) a[1]);
                case "body" -> body[0] = a[0];
                case "status" -> status[0] = (HttpStatusType) a[0];
                case "build" -> {
                    return proxy(HttpResponseMessage.class, (rp, rm, ra) -> switch (rm.getName()) {
                        case "getStatusCode" -> status[0].value();
                        case "getStatus" -> status[0];
                        case "getHeader" -> headers.get((String) ra[0]);
                        case "getBody" -> body[0];
                        default -> null;
                    });
                }
                default -> { }
            }
            return p;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }


    private static final class MemoryJobs implements JobService {
        final ExcelImageService converter;
        final String id = UUID.randomUUID().toString();
        final Map<String, byte[]> files = new HashMap<>();
        JobStatus job; byte[] input, archive; RenderOptions options;
        MemoryJobs(ExcelImageService converter) { this.converter = converter; }
        public JobStatus submit(byte[] bytes, String filename, RenderOptions options) {
            input = bytes; this.options = options;
            return job = new JobStatus(id, "queued", filename, "now", "now", null, null, null, null);
        }
        public Optional<JobStatus> find(String id) { return Optional.ofNullable(job); }
        public JobDownload download(String id, String artifact) {
            if (!files.containsKey(artifact)) throw new ConversionException(404, "ARTIFACT_NOT_FOUND", "Artifact not found");
            String type = artifact.endsWith(".png") ? "image/png" : artifact.equals("document.pdf") ? "application/pdf" : "application/json; charset=utf-8";
            return new JobDownload(files.get(artifact), type, artifact);
        }
        public JobDownload archive(String id) { return new JobDownload(archive, "application/zip", "document.zip"); }
        public void process(String message) {
            try (ConversionResult result = converter.convert(input, job.filename(), options)) {
                for (var file : result.files().entrySet()) files.put(file.getKey(), Files.readAllBytes(file.getValue()));
                archive = result.zipBytes();
                job = new JobStatus(id, "succeeded", job.filename(), "now", "now", result.sectionCount(), result.warningCount(), null, null);
            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        }
        public void poison(String message) { }
    }
}
