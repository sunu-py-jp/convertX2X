package com.convertx2x.excel2img.conversion;

import java.util.Map;

/** A stable client-safe conversion failure. */
public class ConversionException extends RuntimeException {
    private final int statusCode;
    private final String code;
    private final Map<String, Object> details;

    public ConversionException(int statusCode, String code, String message) {
        super(message);
        this.statusCode = statusCode;
        this.code = code;
        this.details = Map.of();
    }

    public ConversionException(int statusCode, String code, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.code = code;
        this.details = Map.of();
    }

    public ConversionException(int statusCode, String code, String message, Map<String, Object> details) {
        super(message);
        this.statusCode = statusCode;
        this.code = code;
        this.details = Map.copyOf(details);
    }

    public int statusCode() { return statusCode; }
    public String code() { return code; }
    public Map<String, Object> details() { return details; }
}
