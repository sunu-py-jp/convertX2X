package com.convertx2x.office2md.ocr;

import java.util.Objects;

/** Plain OCR text; interpretation and placement belong to the Office converter. */
public record OcrResult(String text) {
    public OcrResult { text = Objects.requireNonNullElse(text, ""); }
}
