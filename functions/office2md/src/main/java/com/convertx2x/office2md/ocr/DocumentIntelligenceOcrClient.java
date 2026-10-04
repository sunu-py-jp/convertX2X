package com.convertx2x.office2md.ocr;

import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** Binary-only prebuilt-read client. It never fetches image URLs or follows redirects. */
public final class DocumentIntelligenceOcrClient implements OcrClient {
    static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_RETRIES = 2;
    private static final int MAX_POLL_REQUESTS = 180;
    private static final String API_VERSION = "api-version=2024-11-30";
    private static final String MODEL_PATH = "/documentintelligence/documentModels/prebuilt-read";
    private static final Pattern RESULT_PATH = Pattern.compile(Pattern.quote(MODEL_PATH)
            + "/analyzeResults/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/tiff", "image/bmp", "image/heif");
    private static final ObjectMapper JSON = new ObjectMapper(new JsonFactoryBuilder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(100).maxStringLength(MAX_RESPONSE_BYTES).build()).build());
    private final OcrSettings settings;
    private final Transport transport;
    private final TimeSource time;

    public DocumentIntelligenceOcrClient(OcrSettings settings) {
        this(settings, new JdkTransport(), new TimeSource() {
            @Override public long nanoTime() { return System.nanoTime(); }
            @Override public void sleep(Duration duration) throws InterruptedException { Thread.sleep(duration); }
        });
    }

    // Package-private injection keeps tests offline and lets the same deadline cover requests and polling.
    DocumentIntelligenceOcrClient(OcrSettings settings, Transport transport, TimeSource time) {
        this.settings = Objects.requireNonNull(settings);
        this.transport = Objects.requireNonNull(transport);
        this.time = Objects.requireNonNull(time);
    }

    @Override public boolean configured() { return settings.configured(); }

    @Override public OcrResult recognize(byte[] image, String contentType) {
        if (!configured()) throw new OcrException("OCR_NOT_CONFIGURED");
        String type = contentType == null ? "" : contentType.strip().toLowerCase(Locale.ROOT);
        if (!IMAGE_TYPES.contains(type)) throw new OcrException("OCR_UNSUPPORTED_IMAGE");
        if (image == null || image.length == 0) throw new OcrException("OCR_IMAGE_REJECTED");
        Budget budget = new Budget();
        try {
            URI submit = settings.endpoint().resolve(MODEL_PATH + ":analyze?" + API_VERSION);
            Response accepted = exchange(submit, image, type, budget);
            if (accepted.status() != 202) throw responseFailure(accepted.status());
            URI result = resultLocation(accepted.headers().firstValue("Operation-Location").orElse(""));
            pause(accepted, budget);
            for (int poll = 0; poll < MAX_POLL_REQUESTS; poll++) {
                Response response = exchange(result, null, null, budget);
                if (response.status() != 200) throw responseFailure(response.status());
                JsonNode body;
                try { body = JSON.readTree(response.body()); }
                catch (IOException | RuntimeException invalid) { throw new OcrException("OCR_INVALID_RESPONSE"); }
                budget.remaining();
                if (body == null || !body.isObject()) throw new OcrException("OCR_INVALID_RESPONSE");
                switch (body.path("status").asText()) {
                    case "succeeded" -> {
                        JsonNode content = body.path("analyzeResult").path("content");
                        if (!content.isTextual()) throw new OcrException("OCR_INVALID_RESPONSE");
                        return new OcrResult(content.textValue());
                    }
                    case "running", "notStarted" -> pause(response, budget);
                    case "failed", "canceled", "skipped" -> throw new OcrException("OCR_ANALYSIS_FAILED");
                    default -> throw new OcrException("OCR_INVALID_RESPONSE");
                }
            }
            throw new OcrException("OCR_TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new OcrException("OCR_INTERRUPTED");
        } catch (HttpTimeoutException timeout) {
            throw new OcrException("OCR_TIMEOUT");
        } catch (IOException unavailable) {
            throw new OcrException("OCR_SERVICE_UNAVAILABLE");
        } catch (OcrException known) {
            throw known;
        } catch (RuntimeException unexpected) {
            throw new OcrException("OCR_INVALID_RESPONSE");
        }
    }

    private Response exchange(URI uri, byte[] image, String contentType, Budget budget)
            throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(budget.remaining())
                    .header("Accept", "application/json")
                    .header("Ocp-Apim-Subscription-Key", settings.apiKey());
            if (image == null) request.GET();
            else request.header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(image));
            Response response = transport.send(request.build(), MAX_RESPONSE_BYTES);
            budget.remaining();
            if (response.body() == null || response.body().length > MAX_RESPONSE_BYTES)
                throw new OcrException("OCR_RESPONSE_LIMIT");
            if (response.status() >= 300 && response.status() < 400) throw new OcrException("OCR_UNSAFE_RESPONSE");
            if (response.status() != 429 && response.status() != 503) return response;
            if (attempt >= MAX_RETRIES) throw responseFailure(response.status());
            pause(response, budget);
        }
    }

    private URI resultLocation(String location) {
        try {
            URI result = URI.create(location);
            URI origin = settings.endpoint();
            if (!"https".equalsIgnoreCase(result.getScheme()) || result.getHost() == null
                    || !result.getHost().equalsIgnoreCase(origin.getHost()) || port(result) != port(origin)
                    || result.getRawUserInfo() != null || result.getRawFragment() != null
                    || !RESULT_PATH.matcher(result.getRawPath()).matches() || !API_VERSION.equals(result.getRawQuery())) {
                throw new IllegalArgumentException();
            }
            return result;
        } catch (RuntimeException invalid) {
            throw new OcrException("OCR_UNSAFE_RESPONSE");
        }
    }

    private void pause(Response response, Budget budget) throws InterruptedException {
        long seconds = 2;
        try { seconds = Long.parseLong(response.headers().firstValue("Retry-After").orElse("2").strip()); }
        catch (NumberFormatException ignored) { /* Invalid values use a bounded default, never a tight loop. */ }
        Duration delay = Duration.ofSeconds(Math.max(1, Math.min(10, seconds)));
        Duration remaining = budget.remaining();
        if (delay.compareTo(remaining) >= 0) throw new OcrException("OCR_TIMEOUT");
        time.sleep(delay);
        budget.remaining();
    }

    private static int port(URI uri) { return uri.getPort() == -1 ? 443 : uri.getPort(); }

    private static OcrException responseFailure(int status) {
        return new OcrException(switch (status) {
            case 401, 403 -> "OCR_AUTHENTICATION_FAILED";
            case 400, 413, 415, 422 -> "OCR_IMAGE_REJECTED";
            case 429 -> "OCR_RATE_LIMITED";
            case 500, 502, 503, 504 -> "OCR_SERVICE_UNAVAILABLE";
            default -> "OCR_INVALID_RESPONSE";
        });
    }

    private final class Budget {
        private final long started = time.nanoTime();
        Duration remaining() {
            long remaining = settings.timeout().toNanos() - (time.nanoTime() - started);
            if (remaining <= 0) throw new OcrException("OCR_TIMEOUT");
            return Duration.ofNanos(remaining);
        }
    }

    interface TimeSource {
        long nanoTime();
        void sleep(Duration duration) throws InterruptedException;
    }

    @FunctionalInterface interface Transport {
        Response send(HttpRequest request, int maximumBytes) throws IOException, InterruptedException;
    }

    record Response(int status, HttpHeaders headers, byte[] body) { }

    private static final class JdkTransport implements Transport {
        private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();

        @Override public Response send(HttpRequest request, int maximumBytes) throws IOException, InterruptedException {
            CompletableFuture<HttpResponse<byte[]>> pending = HTTP.sendAsync(request, info -> {
                long length;
                try { length = info.headers().firstValueAsLong("Content-Length").orElse(0); }
                catch (NumberFormatException invalid) { length = 0; }
                return new BoundedBodySubscriber(maximumBytes, length > maximumBytes);
            });
            try {
                // Unlike an InputStream body handler, this covers a slow body as well as response headers.
                HttpResponse<byte[]> response = pending.get(request.timeout().orElseThrow().toNanos(), TimeUnit.NANOSECONDS);
                return new Response(response.statusCode(), response.headers(), response.body());
            } catch (TimeoutException timeout) {
                pending.cancel(true);
                throw new HttpTimeoutException("OCR request timed out");
            } catch (InterruptedException interrupted) {
                pending.cancel(true);
                throw interrupted;
            } catch (ExecutionException failed) {
                Throwable failure = failed.getCause();
                // The JDK may wrap a failed body subscriber; keep only our safe, stable failures.
                for (int depth = 0; failure != null && depth < 12; depth++, failure = failure.getCause()) {
                    if (failure instanceof OcrException known) throw known;
                    if (failure instanceof HttpTimeoutException timeout) throw timeout;
                }
                throw new IOException("OCR request failed");
            }
        }
    }

    /** Cancels the HTTP body before buffering beyond the cap, including chunked responses. */
    static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;
        private final boolean declaredTooLarge;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        BoundedBodySubscriber(int maximum, boolean declaredTooLarge) {
            this.maximum = maximum;
            this.declaredTooLarge = declaredTooLarge;
        }

        @Override public CompletionStage<byte[]> getBody() { return body; }

        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (declaredTooLarge) reject();
            else subscription.request(1);
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                int size = buffer.remaining();
                if (size > maximum - bytes.size()) { reject(); return; }
                byte[] chunk = new byte[size];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }

        private void reject() {
            subscription.cancel();
            body.completeExceptionally(new OcrException("OCR_RESPONSE_LIMIT"));
        }
    }
}
