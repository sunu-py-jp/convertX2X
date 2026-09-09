package com.slide2image.conversion;

import java.awt.image.BufferedImage;
import java.io.IOException;

/** Parses one input format and renders pages while its source resources remain open. */
interface InputConverter {
    /** Lightweight content detection only; full structure is checked by convert. */
    boolean supports(byte[] input);

    /** Calls the shared encoder synchronously before closing the source resources. */
    <T> T convert(byte[] input, ConversionOptions options, PageEncoder<T> encoder) throws Exception;

    @FunctionalInterface
    interface PageEncoder<T> {
        T encode(int totalPages, PageRenderer renderer) throws IOException;
    }

    @FunctionalInterface
    interface PageRenderer {
        BufferedImage render(int index) throws IOException;
    }
}
