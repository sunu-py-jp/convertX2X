package com.convertx2x.office2md.conversion;

import java.util.Locale;

/** Whether to supplement original embedded image references with OCR text. */
public enum ImageMode {
    IGNORE("ignore"), OCR("ocr");

    private final String wireValue;
    ImageMode(String wireValue) { this.wireValue = wireValue; }
    public String wireValue() { return wireValue; }

    public static ImageMode parse(String value) {
        if (value == null || value.isBlank()) return IGNORE;
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "ignore" -> IGNORE;
            case "ocr" -> OCR;
            default -> throw new ConversionException(400, "INVALID_IMAGE_MODE", "imageMode は ignore または ocr を指定してください。");
        };
    }
}
