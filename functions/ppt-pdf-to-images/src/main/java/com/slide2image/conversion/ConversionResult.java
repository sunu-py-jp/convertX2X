package com.slide2image.conversion;

/** pageCount is the number of images in this result (one for a selected page). */
public record ConversionResult(byte[] bytes, String contentType, String filename, int pageCount) {
}
