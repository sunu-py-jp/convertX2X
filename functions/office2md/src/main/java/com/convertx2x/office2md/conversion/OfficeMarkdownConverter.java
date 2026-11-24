package com.convertx2x.office2md.conversion;

import com.convertx2x.office2md.ocr.OcrClient;

/**
 * Converts one input format into Markdown and its assets.
 * {@link OfficeMarkdownService} registers one implementation per extension, so a new format is added
 * as a new implementation and never changes the converters of the formats already supported.
 */
public interface OfficeMarkdownConverter {
    ConversionResult convert(byte[] input, String filename, ImageMode imageMode, OcrClient ocrClient);
}
