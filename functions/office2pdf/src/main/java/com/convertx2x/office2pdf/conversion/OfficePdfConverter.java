package com.convertx2x.office2pdf.conversion;

import java.io.IOException;

/**
 * Reads one input format and places its content into the normalized PDF.
 * {@link OfficePdfService} registers one implementation per extension, so a new format is added
 * as a new implementation and never changes the readers of the formats already supported.
 */
interface OfficePdfConverter {
    void convert(byte[] input, NormalizedPdfWriter pdf, ConversionWorkspace workspace) throws IOException;
}
