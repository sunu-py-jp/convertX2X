package com.convertx2x.excel2md;

import com.microsoft.azure.functions.*;
import com.convertx2x.excel2md.conversion.*;
import com.convertx2x.excel2md.jobs.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Logger;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;

class ConversionFunctionsTest {
    private final ExcelMarkdownService converter = new ExcelMarkdownService(ConversionLimits.defaults());

    @Test void synchronousHttpReturnsZipContainingMarkdownAndReportWithoutStorage() throws Exception {
        ConversionFunctions functions = new ConversionFunctions(AppConfig.from(Map.of()), converter,
                () -> { throw new AssertionError("Storage must not initialize"); });
        HttpResponseMessage response = functions.convert(request("/api/convert", workbook(),
                Map.of("filename", "日本語.xlsx"), Map.of()), context());
        assertEquals(200, response.getStatusCode());
        assertEquals("application/zip", response.getHeader("Content-Type"));
        assertEquals("1", response.getHeader("X-Sheet-Count"));
        Map<String, byte[]> files = unzip((byte[])response.getBody());
        assertTrue(new String(files.get("document.md"), StandardCharsets.UTF_8).contains("日本語"));
        assertNotNull(files.get("report.json"));
    }

    @Test void asynchronousStatusUsesRelativeLinksWithoutFunctionKeyAndReturnsSameArtifacts() throws Exception {
        MemoryJobs jobs = new MemoryJobs(converter);
        ConversionFunctions functions = new ConversionFunctions(AppConfig.from(Map.of(AppConfig.STORAGE_SETTING, "test")), converter, () -> jobs);
        byte[] input = workbook();
        HttpResponseMessage accepted = functions.submit(request("/custom/jobs?code=secret", input,
                Map.of("filename", "workbook.xlsx"), Map.of()), context());
        assertEquals(202, accepted.getStatusCode());
        assertEquals("/custom/jobs/" + jobs.id, accepted.getHeader("Location"));
        assertFalse(accepted.getBody().toString().contains("secret"));
        functions.process("message", context());
        HttpResponseMessage status = functions.status(request("/custom/jobs/" + jobs.id, "", Map.of(), Map.of()), jobs.id, context());
        for (String suffix : List.of("result", "report", "archive", "images/")) {
            assertTrue(status.getBody().toString().contains("/custom/jobs/" + jobs.id + "/" + suffix));
        }
        HttpResponseMessage markdown = functions.download(request("/custom/jobs/" + jobs.id + "/result", "", Map.of(), Map.of()), jobs.id, context());
        assertTrue(new String((byte[])markdown.getBody(), StandardCharsets.UTF_8).contains("日本語"));
        HttpResponseMessage archive = functions.archive(request("/custom/jobs/" + jobs.id + "/archive", "", Map.of(), Map.of()), jobs.id, context());
        assertArrayEquals(jobs.files.get("document.md"), unzip((byte[])archive.getBody()).get("document.md"));
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
        assertEquals(404, limited.asset(request("/api/jobs/x/images/y", "", Map.of(), Map.of()), "x", "../status.json", context()).getStatusCode());
    }

    @Test void capabilitiesAndErrorsNeverExposeStorageCredentials() {
        AppConfig config = AppConfig.from(Map.of(AppConfig.STORAGE_SETTING, "AccountKey=secret"));
        assertFalse(config.toString().contains("secret"));
        HttpResponseMessage capabilities = new PlaygroundFunctions(config).capabilities(request("/api/capabilities", "", Map.of(), Map.of()));
        assertTrue(capabilities.getBody().toString().contains("\"maxSheets\":50"));
        assertTrue(capabilities.getBody().toString().contains("\"maxImagePixels\":20000000"));
        assertFalse(capabilities.getBody().toString().contains("secret"));
        var functions = new ConversionFunctions(config, converter, () -> { throw new IllegalStateException("AccountKey=secret"); });
        HttpResponseMessage error = functions.status(request("/api/jobs/id", "", Map.of(), Map.of()), "id", context());
        assertEquals(500, error.getStatusCode());
        assertFalse(error.getBody().toString().contains("secret"));
        IllegalStateException queue = assertThrows(IllegalStateException.class, () -> functions.process("{}", context()));
        assertNull(queue.getCause());
        assertFalse(queue.getMessage().contains("secret"));
        assertThrows(IllegalArgumentException.class, () -> AppConfig.from(Map.of("CONVERSION_MAX_READ_CELLS", "0")));
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
        final ExcelMarkdownService converter;
        final String id = UUID.randomUUID().toString();
        final Map<String, byte[]> files = new HashMap<>();
        JobStatus job; byte[] input, archive;
        MemoryJobs(ExcelMarkdownService converter) { this.converter = converter; }
        public JobStatus submit(byte[] bytes, String filename) {
            input = bytes;
            return job = new JobStatus(id, "queued", filename, "now", "now", null, null, null, null);
        }
        public Optional<JobStatus> find(String id) { return Optional.ofNullable(job); }
        public JobDownload download(String id, String artifact) { return new JobDownload(files.get(artifact), "text/plain", artifact); }
        public JobDownload archive(String id) { return new JobDownload(archive, "application/zip", "document.zip"); }
        public void process(String message) {
            try (ConversionResult result = converter.convert(input, job.filename())) {
                for (var file : result.files().entrySet()) files.put(file.getKey(), Files.readAllBytes(file.getValue()));
                archive = result.zipBytes();
                job = new JobStatus(id, "succeeded", job.filename(), "now", "now", result.sheetCount(), result.warningCount(), null, null);
            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        }
        public void poison(String message) { }
    }
}
