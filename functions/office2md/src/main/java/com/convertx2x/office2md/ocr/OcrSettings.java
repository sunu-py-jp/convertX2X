package com.convertx2x.office2md.ocr;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/** Server-controlled settings only; neither endpoint nor credential comes from conversion requests. */
public final class OcrSettings {
    public static final String ENDPOINT_SETTING = "DOCUMENT_INTELLIGENCE_ENDPOINT";
    public static final String KEY_SETTING = "DOCUMENT_INTELLIGENCE_KEY";
    public static final String TIMEOUT_SETTING = "DOCUMENT_INTELLIGENCE_TIMEOUT_SECONDS";
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;

    private OcrSettings(URI endpoint, String apiKey, int timeoutSeconds) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public static OcrSettings disabled() { return new OcrSettings(null, null, DEFAULT_TIMEOUT_SECONDS); }

    public static OcrSettings from(Map<String, String> settings) {
        String endpointText = value(settings, ENDPOINT_SETTING);
        String key = value(settings, KEY_SETTING);
        // Incomplete settings must not affect ordinary conversions that do not request OCR.
        if (endpointText.isBlank() || key.isBlank()) return disabled();
        try {
            URI endpoint = URI.create(endpointText);
            if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                    || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
                    || endpoint.getRawFragment() != null || endpoint.getPort() == 0 || endpoint.getPort() > 65535
                    || !(endpoint.getRawPath().isEmpty() || endpoint.getRawPath().equals("/"))
                    || key.codePoints().anyMatch(point -> point < 33 || point > 126)) {
                throw new IllegalArgumentException();
            }
            String timeoutText = value(settings, TIMEOUT_SETTING);
            int seconds = timeoutText.isBlank() ? DEFAULT_TIMEOUT_SECONDS : Integer.parseInt(timeoutText);
            if (seconds < 1 || seconds > 180) throw new IllegalArgumentException();
            URI origin = new URI("https", null, endpoint.getHost(), endpoint.getPort(), null, null, null);
            return new OcrSettings(origin, key, seconds);
        } catch (Exception failure) {
            throw new OcrException("OCR_INVALID_CONFIGURATION");
        }
    }

    public boolean configured() { return endpoint != null && apiKey != null; }
    public URI endpoint() { return endpoint; }
    public Duration timeout() { return timeout; }
    public int timeoutSeconds() { return Math.toIntExact(timeout.toSeconds()); }
    String apiKey() { return apiKey; }

    private static String value(Map<String, String> settings, String name) {
        String value = settings.get(name);
        return value == null ? "" : value.strip();
    }

    @Override public String toString() {
        return "OcrSettings[configured=" + configured() + ", timeoutSeconds=" + timeoutSeconds() + "]";
    }
}
