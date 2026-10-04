package com.convertx2x.office2md.ocr;

public interface OcrClient {
    boolean configured();
    OcrResult recognize(byte[] image, String contentType);

    static OcrClient disabled() {
        return new OcrClient() {
            @Override public boolean configured() { return false; }
            @Override public OcrResult recognize(byte[] image, String contentType) {
                throw new OcrException("OCR_NOT_CONFIGURED");
            }
        };
    }
}
