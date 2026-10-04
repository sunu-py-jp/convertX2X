package com.convertx2x.office2md.ocr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentIntelligenceOcrClientTest {
    private static final String ENDPOINT = "https://di.example.test";
    private static final String KEY = "test-only-credential";
    private static final String RESULT_PATH = "/documentintelligence/documentModels/prebuilt-read/analyzeResults/12345678-1234-1234-1234-123456789abc";
    private static final String RESULT = ENDPOINT + RESULT_PATH + "?api-version=2024-11-30";
    private static final byte[] IMAGE = "test-only-image-payload".getBytes(StandardCharsets.UTF_8);

    @Test void submitsOnlyBinaryBytesAndPollsForPlainTextWithOneSharedDeadline() throws Exception {
        FakeTime time = new FakeTime();
        FakeTransport transport = new FakeTransport();
        transport.add(202, Map.of("Operation-Location", RESULT, "Retry-After", "1"), "");
        transport.add(200, Map.of("Retry-After", "3"), "{\"status\":\"running\"}");
        transport.add(200, Map.of(), "{\"status\":\"succeeded\",\"analyzeResult\":{\"content\":\"日本語の文字\\n次の行\"}}");
        OcrResult result = client(60, transport, time).recognize(IMAGE, "image/png");
        assertEquals("日本語の文字\n次の行", result.text());
        assertEquals(3, transport.requests.size());
        HttpRequest post = transport.requests.getFirst();
        assertEquals("POST", post.method());
        assertEquals(ENDPOINT + "/documentintelligence/documentModels/prebuilt-read:analyze?api-version=2024-11-30", post.uri().toString());
        assertArrayEquals(IMAGE, body(post));
        assertEquals("image/png", post.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(3)), time.sleeps);
        assertEquals(List.of(60L, 59L, 56L), transport.requests.stream().map(request -> request.timeout().orElseThrow().toSeconds()).toList());
        for (HttpRequest request : transport.requests) {
            assertEquals("di.example.test", request.uri().getHost());
            assertEquals(KEY, request.headers().firstValue("Ocp-Apim-Subscription-Key").orElseThrow());
        }
        for (HttpRequest get : transport.requests.subList(1, 3)) {
            assertEquals("GET", get.method());
            assertEquals(RESULT, get.uri().toString());
            assertTrue(get.bodyPublisher().isEmpty());
        }
    }

    @Test void retriesOnlyBoundedThrottleOrUnavailableResponsesAndClampsRetryAfter() {
        FakeTime time = new FakeTime();
        FakeTransport transport = new FakeTransport();
        transport.add(429, Map.of("Retry-After", "999999"), "secret response");
        transport.add(503, Map.of("Retry-After", "invalid"), "secret response");
        transport.add(202, Map.of("Operation-Location", RESULT, "Retry-After", "0"), "");
        transport.add(503, Map.of("Retry-After", "-2"), "secret response");
        transport.add(200, Map.of(), "{\"status\":\"succeeded\",\"analyzeResult\":{\"content\":\"\"}}");
        assertEquals("", client(60, transport, time).recognize(IMAGE, "image/jpeg").text());
        assertEquals(5, transport.requests.size());
        assertEquals(List.of(Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(1)), time.sleeps);
        for (int status : new int[]{429, 503}) {
            FakeTransport repeated = new FakeTransport();
            for (int attempt = 0; attempt < 3; attempt++) repeated.add(status, Map.of(), KEY);
            OcrException failure = assertThrows(OcrException.class,
                    () -> client(60, repeated, new FakeTime()).recognize(IMAGE, "image/png"));
            assertEquals(status == 429 ? "OCR_RATE_LIMITED" : "OCR_SERVICE_UNAVAILABLE", failure.code());
            assertEquals(3, repeated.requests.size());
            assertSafe(failure);
        }
    }

    @Test void rejectsRedirectsAndUnsafePollLocationsBeforeAnyCredentialCanReachAnotherOrigin() {
        for (String location : new String[]{"https://other.example.test" + RESULT_PATH + "?api-version=2024-11-30",
                "http://di.example.test" + RESULT_PATH + "?api-version=2024-11-30",
                "https://di.example.test:8443" + RESULT_PATH + "?api-version=2024-11-30",
                "https://user@di.example.test" + RESULT_PATH + "?api-version=2024-11-30",
                RESULT + "#secret", RESULT + "&redirect=https://other.example.test", RESULT.replace("prebuilt-read", "prebuilt-layout"),
                RESULT.replace("/analyzeResults/", "/analyzeResults/%2e%2e/"), RESULT_PATH + "?api-version=2024-11-30", ""}) {
            FakeTransport transport = new FakeTransport();
            transport.add(202, Map.of("Operation-Location", location), "");
            OcrException failure = assertThrows(OcrException.class,
                    () -> client(60, transport, new FakeTime()).recognize(IMAGE, "image/png"));
            assertEquals("OCR_UNSAFE_RESPONSE", failure.code());
            assertEquals(1, transport.requests.size(), location);
            assertEquals("di.example.test", transport.requests.getFirst().uri().getHost());
            assertSafe(failure);
        }
        for (boolean pollRedirect : new boolean[]{false, true}) {
            FakeTransport transport = new FakeTransport();
            if (pollRedirect) transport.add(202, Map.of("Operation-Location", RESULT), "");
            transport.add(307, Map.of("Location", "https://other.example.test/collect"), KEY);
            assertEquals("OCR_UNSAFE_RESPONSE", assertThrows(OcrException.class,
                    () -> client(60, transport, new FakeTime()).recognize(IMAGE, "image/png")).code());
            assertEquals(pollRedirect ? 2 : 1, transport.requests.size());
            assertTrue(transport.requests.stream().allMatch(request -> request.uri().getHost().equals("di.example.test")));
        }
    }

    @Test void sameOriginAllowsExplicitDefaultHttpsPort() {
        FakeTransport transport = new FakeTransport();
        transport.add(202, Map.of("Operation-Location", RESULT.replace(ENDPOINT, ENDPOINT + ":443")), "");
        transport.add(200, Map.of(), "{\"status\":\"succeeded\",\"analyzeResult\":{\"content\":\"text\"}}");
        assertEquals("text", client(60, transport, new FakeTime()).recognize(IMAGE, "image/png").text());
    }

    @Test void respectsTheImageDeadlineIncludingHttpAndPollingInsteadOfRestartingEachRequest() {
        FakeTime time = new FakeTime();
        FakeTransport transport = new FakeTransport();
        transport.add(202, Map.of("Operation-Location", RESULT, "Retry-After", "1"), "");
        transport.add(200, Map.of("Retry-After", "2"), "{\"status\":\"running\"}");
        assertEquals("OCR_TIMEOUT", assertThrows(OcrException.class,
                () -> client(3, transport, time).recognize(IMAGE, "image/png")).code());
        assertEquals(2, transport.requests.size());
        assertEquals(List.of(3L, 2L), transport.requests.stream().map(request -> request.timeout().orElseThrow().toSeconds()).toList());
        FakeTransport slow = new FakeTransport();
        FakeTime slowTime = new FakeTime();
        slow.responses.add(request -> {
            slowTime.now += Duration.ofSeconds(4).toNanos();
            return response(202, Map.of("Operation-Location", RESULT), "");
        });
        assertEquals("OCR_TIMEOUT", assertThrows(OcrException.class,
                () -> client(3, slow, slowTime).recognize(IMAGE, "image/png")).code());
        assertEquals(1, slow.requests.size());
    }

    @Test void fixedFailuresDoNotExposeServerErrorsCredentialsImagesOrNestedCauses() {
        for (int status : new int[]{400, 401, 403, 415, 500}) {
            FakeTransport transport = new FakeTransport();
            transport.add(status, Map.of(), KEY + new String(IMAGE, StandardCharsets.UTF_8));
            OcrException failure = assertThrows(OcrException.class,
                    () -> client(60, transport, new FakeTime()).recognize(IMAGE, "image/png"));
            assertSafe(failure);
            assertEquals(1, transport.requests.size());
        }
        for (String body : new String[]{"{\"status\":\"failed\",\"error\":{\"message\":\"" + KEY + "\"}}",
                "not-json " + KEY, "{\"status\":\"succeeded\"}", "{\"status\":\"unknown\"}", "null"}) {
            FakeTransport transport = new FakeTransport();
            transport.add(202, Map.of("Operation-Location", RESULT), "");
            transport.add(200, Map.of(), body);
            OcrException failure = assertThrows(OcrException.class,
                    () -> client(60, transport, new FakeTime()).recognize(IMAGE, "image/png"));
            assertEquals(body.contains("\"failed\"") ? "OCR_ANALYSIS_FAILED" : "OCR_INVALID_RESPONSE", failure.code());
            assertSafe(failure);
        }
        FakeTransport unavailable = new FakeTransport();
        unavailable.responses.add(request -> { throw new IOException(KEY + RESULT); });
        OcrException failure = assertThrows(OcrException.class,
                () -> client(60, unavailable, new FakeTime()).recognize(IMAGE, "image/png"));
        assertEquals("OCR_SERVICE_UNAVAILABLE", failure.code());
        assertSafe(failure);
        FakeTransport timeout = new FakeTransport();
        timeout.responses.add(request -> { throw new HttpTimeoutException(KEY); });
        assertEquals("OCR_TIMEOUT", assertThrows(OcrException.class,
                () -> client(60, timeout, new FakeTime()).recognize(IMAGE, "image/png")).code());
    }

    @Test void disabledOrUnsupportedInputNeverMakesARequest() {
        FakeTransport transport = new FakeTransport();
        var disabled = new DocumentIntelligenceOcrClient(OcrSettings.disabled(), transport, new FakeTime());
        assertFalse(disabled.configured());
        assertEquals("OCR_NOT_CONFIGURED", assertThrows(OcrException.class,
                () -> disabled.recognize(IMAGE, "image/png")).code());
        var configured = client(60, transport, new FakeTime());
        for (String type : new String[]{"image/gif", "image/emf", "application/pdf", "https://other.example.test", "image/png\r\nInjected: header"})
            assertEquals("OCR_UNSUPPORTED_IMAGE", assertThrows(OcrException.class,
                    () -> configured.recognize(IMAGE, type)).code());
        assertEquals("OCR_IMAGE_REJECTED", assertThrows(OcrException.class,
                () -> configured.recognize(new byte[0], "image/png")).code());
        assertTrue(transport.requests.isEmpty());
    }

    @Test void interruptionPreservesThreadFlagAndReturnsOnlySafeFailure() {
        FakeTransport transport = new FakeTransport();
        transport.responses.add(request -> { throw new InterruptedException(KEY); });
        try {
            OcrException failure = assertThrows(OcrException.class,
                    () -> client(60, transport, new FakeTime()).recognize(IMAGE, "image/png"));
            assertEquals("OCR_INTERRUPTED", failure.code());
            assertTrue(Thread.currentThread().isInterrupted());
            assertSafe(failure);
        } finally { Thread.interrupted(); }
    }

    @Test void rejectsOversizedRepliesAndCancelsChunkedBodiesAtTheBound() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.responses.add(request -> new DocumentIntelligenceOcrClient.Response(202,
                headers(Map.of("Operation-Location", RESULT)), new byte[DocumentIntelligenceOcrClient.MAX_RESPONSE_BYTES + 1]));
        assertEquals("OCR_RESPONSE_LIMIT", assertThrows(OcrException.class,
                () -> client(60, transport, new FakeTime()).recognize(IMAGE, "image/png")).code());
        var subscriber = new DocumentIntelligenceOcrClient.BoundedBodySubscriber(5, false);
        TestSubscription subscription = new TestSubscription();
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2, 3})));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{4, 5, 6})));
        assertTrue(subscription.canceled);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> subscriber.getBody().toCompletableFuture().get());
        assertEquals("OCR_RESPONSE_LIMIT", ((OcrException) failure.getCause()).code());
        var declared = new DocumentIntelligenceOcrClient.BoundedBodySubscriber(5, true);
        TestSubscription declaredSubscription = new TestSubscription();
        declared.onSubscribe(declaredSubscription);
        assertTrue(declaredSubscription.canceled);
        var exact = new DocumentIntelligenceOcrClient.BoundedBodySubscriber(5, false);
        exact.onSubscribe(new TestSubscription());
        exact.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2}), ByteBuffer.wrap(new byte[]{3, 4, 5})));
        exact.onComplete();
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, exact.getBody().toCompletableFuture().get());
    }

    private static DocumentIntelligenceOcrClient client(int timeoutSeconds, FakeTransport transport, FakeTime time) {
        return new DocumentIntelligenceOcrClient(OcrSettings.from(Map.of(OcrSettings.ENDPOINT_SETTING, ENDPOINT,
                OcrSettings.KEY_SETTING, KEY, OcrSettings.TIMEOUT_SETTING, Integer.toString(timeoutSeconds))), transport, time);
    }

    private static void assertSafe(OcrException failure) {
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains(KEY));
        assertFalse(failure.toString().contains(RESULT));
        assertFalse(failure.toString().contains(new String(IMAGE, StandardCharsets.UTF_8)));
    }

    private static HttpHeaders headers(Map<String, String> headers) {
        return HttpHeaders.of(headers.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                entry -> List.of(entry.getValue()))), (name, value) -> true);
    }

    private static DocumentIntelligenceOcrClient.Response response(int status, Map<String, String> headers, String body) {
        return new DocumentIntelligenceOcrClient.Response(status, headers(headers), body.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] body(HttpRequest request) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buffer) {
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            @Override public void onError(Throwable error) { result.completeExceptionally(error); }
            @Override public void onComplete() { result.complete(bytes.toByteArray()); }
        });
        return result.get();
    }

    private static final class FakeTime implements DocumentIntelligenceOcrClient.TimeSource {
        long now;
        final List<Duration> sleeps = new ArrayList<>();
        @Override public long nanoTime() { return now; }
        @Override public void sleep(Duration duration) { sleeps.add(duration); now += duration.toNanos(); }
    }

    @FunctionalInterface private interface Reply {
        DocumentIntelligenceOcrClient.Response respond(HttpRequest request) throws IOException, InterruptedException;
    }

    private static final class FakeTransport implements DocumentIntelligenceOcrClient.Transport {
        final Deque<Reply> responses = new ArrayDeque<>();
        final List<HttpRequest> requests = new ArrayList<>();
        void add(int status, Map<String, String> headers, String body) { responses.add(request -> response(status, headers, body)); }
        @Override public DocumentIntelligenceOcrClient.Response send(HttpRequest request, int maximumBytes) throws IOException, InterruptedException {
            assertEquals(DocumentIntelligenceOcrClient.MAX_RESPONSE_BYTES, maximumBytes);
            requests.add(request);
            return responses.removeFirst().respond(request);
        }
    }

    private static final class TestSubscription implements Flow.Subscription {
        boolean canceled;
        @Override public void request(long amount) { }
        @Override public void cancel() { canceled = true; }
    }
}
