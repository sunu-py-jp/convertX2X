package com.convertx2x.excel2md;

import com.convertx2x.excel2md.conversion.ConversionLimits;
import com.convertx2x.excel2md.jobs.BlobStorageProfiles;
import java.util.Map;

/** App settings are environment variables in Azure Functions. */
public record AppConfig(String storageConnectionString, ConversionLimits limits, BlobStorageProfiles blobStorageProfiles) {
    public static final String STORAGE_SETTING = "CONVERSION_STORAGE_CONNECTION_STRING";
    public static final String QUEUE_CONNECTION_SETTING = "CONVERSION_QUEUE_CONNECTION_STRING";

    public static AppConfig fromEnvironment() {
        return from(System.getenv());
    }

    public static AppConfig from(Map<String, String> settings) {
        ConversionLimits defaults = ConversionLimits.defaults();
        String connection = settings.getOrDefault(STORAGE_SETTING, "").trim();
        return new AppConfig(connection,
                new ConversionLimits(
                        positive(settings, "CONVERSION_MAX_INPUT_BYTES", defaults.maxInputBytes()),
                        Math.toIntExact(positive(settings, "CONVERSION_MAX_SHEETS", defaults.maxSheets())),
                        Math.toIntExact(positive(settings, "CONVERSION_MAX_READ_CELLS", defaults.maxReadCells())),
                        Math.toIntExact(positive(settings, "CONVERSION_MAX_TABLE_CELLS", defaults.maxTableCells())),
                        positive(settings, "CONVERSION_MAX_MARKDOWN_BYTES", defaults.maxMarkdownBytes()),
                        Math.toIntExact(positive(settings, "CONVERSION_MAX_IMAGES", defaults.maxImages())),
                        positive(settings, "CONVERSION_MAX_IMAGE_BYTES", defaults.maxImageBytes()),
                        positive(settings, "CONVERSION_MAX_OUTPUT_BYTES", defaults.maxOutputBytes()),
                        Math.toIntExact(positive(settings, "CONVERSION_MAX_SHAPES", defaults.maxShapes())),
                        Math.toIntExact(positive(settings, "CONVERSION_MAX_GROUP_DEPTH", defaults.maxGroupDepth())),
                        positive(settings, "CONVERSION_MAX_IMAGE_PIXELS", defaults.maxImagePixels())),
                BlobStorageProfiles.from(settings, connection));
    }

    public boolean asyncEnabled() {
        return storageConnectionString != null && !storageConnectionString.isBlank();
    }

    // Do not include secrets in generated record diagnostics.
    @Override
    public String toString() {
        return "AppConfig[asyncEnabled=" + asyncEnabled() + ", limits=" + limits + "]";
    }

    private static long positive(Map<String, String> settings, String name, long fallback) {
        String value = settings.get(name);
        if (value == null || value.isBlank()) return fallback;
        try {
            long number = Long.parseLong(value.trim());
            if (number > 0) return number;
        } catch (NumberFormatException ignored) {
            // Include only the setting name, never the supplied value.
        }
        throw new IllegalArgumentException(name + " must be a positive integer");
    }
}
