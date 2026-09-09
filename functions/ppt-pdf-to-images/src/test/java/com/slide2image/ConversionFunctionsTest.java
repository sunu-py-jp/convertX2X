package com.slide2image;

import com.microsoft.azure.functions.*;
import com.slide2image.conversion.*;
import com.slide2image.jobs.*;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.Test;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class ConversionFunctionsTest {
    private final ConversionService converter = new ConversionService(ConversionLimits.defaults());

    @Test
    void synchronousHttpWorksWithoutStorageAndReturnsAnActualPng() throws Exception {
        ConversionFunctions functions = new ConversionFunctions(AppConfig.from(Map.of()), converter,
                () -> { throw new AssertionError("Storage must not be initialized"); });
        HttpResponseMessage result = functions.convert(request("/api/convert", pdf(),
                Map.of("page", "1", "width", "400"), Map.of()), context());
        assertEquals(200, result.getStatusCode());
        assertEquals("image/png", result.getHeader("Content-Type"));
        assertEquals("1", result.getHeader("X-Page-Count"));
        byte[] bytes = (byte[]) result.getBody();
        assertArrayEquals(new byte[] {(byte) 137, 80, 78, 71}, Arrays.copyOf(bytes, 4));
    }

    @Test
    void allAsyncHttpRoutesAreDisabledWhenConnectionIsBlank() {
        ConversionFunctions functions = new ConversionFunctions(AppConfig.from(Map.of(
                AppConfig.STORAGE_SETTING, "   ")), converter,
                () -> { throw new AssertionError("Disabled mode must not touch the SDK"); });
        assertDisabled(functions.submit(request("/api/jobs", new byte[] {1}, Map.of(), Map.of()), context()));
        assertDisabled(functions.status(request("/api/jobs/abc", "", Map.of(), Map.of()), "abc", context()));
        assertDisabled(functions.download(request("/api/jobs/abc/result", "", Map.of(), Map.of()), "abc", context()));
    }

    @Test
    void asyncUsesSameOptionsAndReturnsSameBytesAsSyncWithoutLeakingFunctionKey() throws Exception {
        MemoryJobs jobs = new MemoryJobs(converter);
        ConversionFunctions functions = new ConversionFunctions(AppConfig.from(Map.of(
                AppConfig.STORAGE_SETTING, "test-connection")), converter, () -> jobs);
        byte[] input = pdf();
        Map<String, String> options = Map.of("page", "1", "format", "jpeg", "filename", "slides.pdf");
        HttpResponseMessage sync = functions.convert(request("/custom/convert", input, options, Map.of()), context());
        HttpResponseMessage accepted = functions.submit(request("/custom/jobs?code=secret", input, options, Map.of()), context());
        assertEquals(202, accepted.getStatusCode());
        assertEquals("/custom/jobs/" + jobs.id, accepted.getHeader("Location"));
        assertFalse(accepted.getBody().toString().contains("secret"));
        assertEquals(new ConversionOptions(null, "jpeg", 1), jobs.job.options());
        functions.process(jobs.id, context());
        HttpResponseMessage status = functions.status(request("/custom/jobs/" + jobs.id, "", Map.of(), Map.of()), jobs.id, context());
        assertTrue(status.getBody().toString().contains("/custom/jobs/" + jobs.id + "/result"));
        HttpResponseMessage output = functions.download(request("/custom/jobs/" + jobs.id + "/result", "", Map.of(), Map.of()), jobs.id, context());
        assertArrayEquals((byte[]) sync.getBody(), (byte[]) output.getBody());
        assertEquals("image/jpeg", output.getHeader("Content-Type"));
    }

    @Test
    void malformedRequestsAreRejectedBeforeStorageIsUsed() {
        ConversionFunctions functions = new ConversionFunctions(AppConfig.from(Map.of(
                AppConfig.STORAGE_SETTING, "test", "CONVERSION_MAX_INPUT_BYTES", "10")), converter,
                () -> { throw new AssertionError("Invalid upload must not be submitted"); });
        assertEquals(413, functions.submit(request("/api/jobs", new byte[11], Map.of(), Map.of()), context()).getStatusCode());
        assertEquals(400, functions.submit(request("/api/jobs", new byte[1], Map.of("width", "NaN"), Map.of()), context()).getStatusCode());
        assertEquals(400, functions.submit(request("/api/jobs", new byte[1], Map.of("page", "0"), Map.of()), context()).getStatusCode());
        assertEquals(400, functions.submit(request("/api/jobs", new byte[1], Map.of("format", "gif"), Map.of()), context()).getStatusCode());
        assertEquals(415, functions.submit(request("/api/jobs", new byte[1], Map.of(), Map.of("content-type", "multipart/form-data; boundary=foo")), context()).getStatusCode());
        assertEquals(400, functions.convert(request("/api/convert", new byte[0], Map.of(), Map.of()), context()).getStatusCode());
    }

    @Test
    void internalErrorsDoNotExposeConnectionStrings() {
        ConversionFunctions functions = new ConversionFunctions(AppConfig.from(Map.of(AppConfig.STORAGE_SETTING, "secret")), converter,
                () -> { throw new IllegalStateException("AccountKey=secret"); });
        HttpResponseMessage response = functions.status(request("/api/jobs/abc", "", Map.of(), Map.of()), "abc", context());
        assertEquals(500, response.getStatusCode());
        assertFalse(response.getBody().toString().contains("secret"));
    }

    @Test
    void configRedactsSecretsAndRejectsInvalidLimits() {
        assertFalse(AppConfig.from(Map.of(AppConfig.STORAGE_SETTING, "AccountKey=secret")).toString().contains("secret"));
        assertThrows(IllegalArgumentException.class, () -> AppConfig.from(Map.of("CONVERSION_MAX_PAGES", "0")));
        assertThrows(IllegalArgumentException.class, () -> AppConfig.from(Map.of("CONVERSION_MAX_INPUT_BYTES", "invalid")));
    }

    private static void assertDisabled(HttpResponseMessage response) {
        assertEquals(503, response.getStatusCode());
        assertTrue(response.getBody().toString().contains("ASYNC_DISABLED"));
    }

    private static byte[] pdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.setNonStrokingColor(Color.RED);
                stream.addRect(50, 50, 200, 200);
                stream.fill();
            }
            document.save(out);
            return out.toByteArray();
        }
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

    private static class MemoryJobs implements JobService {
        private final ConversionService converter;
        private final String id = UUID.randomUUID().toString();
        private JobStatus job;
        private byte[] input;
        private ConversionResult result;

        MemoryJobs(ConversionService converter) { this.converter = converter; }

        public JobStatus submit(byte[] bytes, String filename, ConversionOptions options) {
            input = bytes;
            return job = new JobStatus(id, "queued", filename, options, "now", "now", null, null, null);
        }
        public Optional<JobStatus> find(String id) { return Optional.ofNullable(job); }
        public ConversionResult download(String id) { return result; }
        public void process(String message) {
            result = converter.convert(input, job.filename(), job.options());
            job = new JobStatus(id, "succeeded", job.filename(), job.options(), "now", "now", result.pageCount(), null, null);
        }
        public void poison(String message) { }
    }
}
