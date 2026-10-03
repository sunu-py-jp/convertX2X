package com.convertx2x.md2pdf;

import com.microsoft.azure.functions.*;
import com.convertx2x.md2pdf.conversion.*;
import com.convertx2x.md2pdf.jobs.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Logger;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;

class ConversionFunctionsTest {
    private final MarkdownPdfService converter = new MarkdownPdfService(ConversionLimits.defaults());

    @Test void synchronousHttpReturnsSearchablePdfWithoutStorage() throws Exception {
        ConversionFunctions functions = new ConversionFunctions(AppConfig.from(Map.of()), converter,
                () -> { throw new AssertionError("Storage must not initialize"); });
        HttpResponseMessage response = functions.convert(request("/api/convert", markdown(),
                Map.of("filename", "日本語.md"), Map.of()), context());
        assertEquals(200, response.getStatusCode());
        assertEquals("application/pdf", response.getHeader("Content-Type"));
        assertEquals("1", response.getHeader("X-Section-Count"));
        try (var pdf = Loader.loadPDF((byte[]) response.getBody())) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("日本語"));
        }
    }

    @Test void asynchronousStatusUsesRelativeLinksWithoutFunctionKeyAndReturnsSameArtifacts() throws Exception {
        MemoryJobs jobs = new MemoryJobs(converter);
        ConversionFunctions functions = new ConversionFunctions(AppConfig.from(Map.of(AppConfig.STORAGE_SETTING, "test")), converter, () -> jobs);
        byte[] input = markdown();
        HttpResponseMessage accepted = functions.submit(request("/custom/jobs?code=secret", input,
                Map.of("filename", "document.md"), Map.of()), context());
        assertEquals(202, accepted.getStatusCode());
        assertEquals("/custom/jobs/" + jobs.id, accepted.getHeader("Location"));
        assertFalse(accepted.getBody().toString().contains("secret"));
        functions.process("message", context());
        HttpResponseMessage status = functions.status(request("/custom/jobs/" + jobs.id, "", Map.of(), Map.of()), jobs.id, context());
        for (String suffix : List.of("result", "report", "archive")) {
            assertTrue(status.getBody().toString().contains("/custom/jobs/" + jobs.id + "/" + suffix));
        }
        HttpResponseMessage pdfResponse = functions.download(request("/custom/jobs/" + jobs.id + "/result", "", Map.of(), Map.of()), jobs.id, context());
        assertEquals("application/pdf", pdfResponse.getHeader("Content-Type"));
        try (var pdf = Loader.loadPDF((byte[]) pdfResponse.getBody())) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("日本語"));
        }
        HttpResponseMessage archive = functions.archive(request("/custom/jobs/" + jobs.id + "/archive", "", Map.of(), Map.of()), jobs.id, context());
        assertArrayEquals(jobs.files.get("document.pdf"), unzip((byte[])archive.getBody()).get("document.pdf"));
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
        assertTrue(capabilities.getBody().toString().contains("\"maxPages\":0"));
        assertTrue(capabilities.getBody().toString().contains("\"supportedFormats\":[\"md\",\"markdown\",\"zip\"]"));
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

    private static byte[] markdown() {
        return "# 売上\n\n| 地域 | 金額 |\n| --- | --- |\n| 日本語・東京 | 12345 |\n".getBytes(StandardCharsets.UTF_8);
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
        final MarkdownPdfService converter;
        final String id = UUID.randomUUID().toString();
        final Map<String, byte[]> files = new HashMap<>();
        JobStatus job; byte[] input, archive;
        MemoryJobs(MarkdownPdfService converter) { this.converter = converter; }
        public JobStatus submit(byte[] bytes, String filename) {
            input = bytes;
            return job = new JobStatus(id, "queued", filename, "now", "now", null, null, null, null);
        }
        public Optional<JobStatus> find(String id) { return Optional.ofNullable(job); }
        public JobDownload download(String id, String artifact) {
            String type = artifact.equals("document.pdf") ? "application/pdf" : "application/json; charset=utf-8";
            return new JobDownload(files.get(artifact), type, artifact);
        }
        public JobDownload archive(String id) { return new JobDownload(archive, "application/zip", "document.zip"); }
        public void process(String message) {
            try (ConversionResult result = converter.convert(input, job.filename())) {
                for (var file : result.files().entrySet()) files.put(file.getKey(), Files.readAllBytes(file.getValue()));
                archive = result.zipBytes();
                job = new JobStatus(id, "succeeded", job.filename(), "now", "now", result.sectionCount(), result.warningCount(), null, null);
            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        }
        public void poison(String message) { }
    }
}
