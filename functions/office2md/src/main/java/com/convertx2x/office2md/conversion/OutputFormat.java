package com.convertx2x.office2md.conversion;

/** Optional presentation of an Office-to-Markdown conversion result. */
public enum OutputFormat {
    MARKDOWN("markdown"), PDF("pdf");

    private final String wireValue;

    OutputFormat(String wireValue) { this.wireValue = wireValue; }

    public String wireValue() { return wireValue; }

    public static OutputFormat parse(String value) {
        if (value == null) return MARKDOWN;
        return switch (value) {
            case "markdown" -> MARKDOWN;
            case "pdf" -> PDF;
            default -> throw new ConversionException(400, "INVALID_OUTPUT_FORMAT",
                    "The output format must be markdown or pdf.");
        };
    }
}
