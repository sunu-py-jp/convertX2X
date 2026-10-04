package com.convertx2x.office2md.ocr;

/** A stable, safe failure. Service responses, credentials, URLs and nested causes are never retained. */
public final class OcrException extends RuntimeException {
    private final String code;

    public OcrException(String code) {
        super(message(code));
        this.code = code;
    }

    public String code() { return code; }

    private static String message(String code) {
        return switch (code) {
            case "OCR_NOT_CONFIGURED" -> "画像OCRの接続設定がありません。";
            case "OCR_INVALID_CONFIGURATION" -> "画像OCRの接続設定が不正です。";
            case "OCR_UNSUPPORTED_IMAGE" -> "この画像形式はOCRに対応していません。";
            case "OCR_IMAGE_REJECTED" -> "OCRサービスが画像を受け付けませんでした。";
            case "OCR_AUTHENTICATION_FAILED" -> "OCRサービスの認証に失敗しました。";
            case "OCR_RATE_LIMITED" -> "OCRサービスの呼び出し上限に達しました。";
            case "OCR_SERVICE_UNAVAILABLE" -> "OCRサービスに接続できませんでした。";
            case "OCR_ANALYSIS_FAILED" -> "画像のOCR処理に失敗しました。";
            case "OCR_INVALID_RESPONSE" -> "OCRサービスから有効な結果を取得できませんでした。";
            case "OCR_UNSAFE_RESPONSE" -> "OCRサービスの応答先を安全に確認できませんでした。";
            case "OCR_RESPONSE_LIMIT" -> "OCRサービスの応答サイズが上限を超えました。";
            case "OCR_TIMEOUT" -> "画像のOCR処理が制限時間を超えました。";
            case "OCR_INTERRUPTED" -> "画像のOCR処理が中断されました。";
            default -> "画像のOCR処理に失敗しました。";
        };
    }
}
