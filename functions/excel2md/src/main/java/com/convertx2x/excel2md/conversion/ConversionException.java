package com.convertx2x.excel2md.conversion;

/** A stable, client-safe failure that adapters can map to an HTTP response or job status. */
public class ConversionException extends RuntimeException {
    private final int statusCode;
    private final String code;

    public ConversionException(int statusCode, String code, String message) {
        super(message);
        this.statusCode = statusCode;
        this.code = code;
    }

    public ConversionException(int statusCode, String code, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.code = code;
    }

    public int statusCode() {
        return statusCode;
    }

    public String code() {
        return code;
    }
}
