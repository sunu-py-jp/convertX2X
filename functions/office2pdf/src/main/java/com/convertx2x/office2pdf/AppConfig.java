package com.convertx2x.office2pdf;

import com.convertx2x.office2pdf.conversion.ConversionLimits;
import com.convertx2x.office2pdf.jobs.BlobStorageProfiles;
import com.convertx2x.office2pdf.jobs.IntegrationSettings;
import java.util.Map;

/** App settings are environment variables in Azure Functions. */
public record AppConfig(String storageConnectionString, ConversionLimits limits,
                        BlobStorageProfiles blobStorageProfiles, IntegrationSettings integration) {
    public static final String STORAGE_SETTING = "CONVERSION_STORAGE_CONNECTION_STRING";
    public static final String QUEUE_CONNECTION_SETTING = "CONVERSION_QUEUE_CONNECTION_STRING";

    public static AppConfig fromEnvironment() { return from(System.getenv()); }
    public static AppConfig from(Map<String, String> settings) {
        ConversionLimits defaults = ConversionLimits.defaults();
        String connection = settings.getOrDefault(STORAGE_SETTING, "").trim();
        return new AppConfig(connection, new ConversionLimits(
                positive(settings, "CONVERSION_MAX_INPUT_BYTES", defaults.maxInputBytes()),
                positive(settings, "CONVERSION_MAX_OUTPUT_BYTES", defaults.maxOutputBytes()),
                Math.toIntExact(nonNegative(settings, "CONVERSION_MAX_PAGES", defaults.maxPages())),
                positive(settings, "CONVERSION_MAX_IMAGE_PIXELS", defaults.maxImagePixels())),
                BlobStorageProfiles.from(settings, connection), IntegrationSettings.from(settings));
    }

    public boolean asyncEnabled() { return integration.storage().configured(); }
    @Override public String toString() { return "AppConfig[asyncEnabled=" + asyncEnabled() + ", limits=" + limits + "]"; }

    private static long positive(Map<String, String> settings, String name, long fallback) { return number(settings, name, fallback, 1); }
    private static long nonNegative(Map<String, String> settings, String name, long fallback) { return number(settings, name, fallback, 0); }
    private static long number(Map<String, String> settings, String name, long fallback, int minimum) {
        String value = settings.get(name);
        if (value == null || value.isBlank()) return fallback;
        try {
            long number = Long.parseLong(value.trim());
            if (number >= minimum) return number;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(name + (minimum == 0 ? " must be a non-negative integer" : " must be a positive integer"));
    }
}
