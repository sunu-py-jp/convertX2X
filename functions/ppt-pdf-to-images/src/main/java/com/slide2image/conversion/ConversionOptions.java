package com.slide2image.conversion;

/** Null width uses source dimensions at 96 dpi; explicit width is pixels. Page is one-based, or null for ZIP. */
public record ConversionOptions(Integer width, String format, Integer page) {
}
