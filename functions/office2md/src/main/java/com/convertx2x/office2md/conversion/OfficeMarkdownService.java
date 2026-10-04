package com.convertx2x.office2md.conversion;

import com.convertx2x.office2md.presentation.PowerPointMarkdownConverter;
import com.convertx2x.office2md.word.WordMarkdownConverter;
import com.convertx2x.office2md.ocr.OcrClient;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.poifs.filesystem.FileMagic;

/** One format-neutral entry point for HTTP and Queue. Input files never supply a network destination. */
public final class OfficeMarkdownService {
    private static final Semaphore SLOT = new Semaphore(1, true);
    private final ConversionLimits limits;
    private final OcrClient ocrClient;
    private final ExcelMarkdownService excel;
    private final WordMarkdownConverter word;
    private final PowerPointMarkdownConverter presentation;

    public OfficeMarkdownService(ConversionLimits limits) {
        this(limits, OcrClient.disabled());
    }

    public OfficeMarkdownService(ConversionLimits limits, OcrClient ocrClient) {
        this.limits = Objects.requireNonNull(limits);
        this.ocrClient = Objects.requireNonNull(ocrClient);
        excel = new ExcelMarkdownService(limits);
        word = new WordMarkdownConverter(limits);
        presentation = new PowerPointMarkdownConverter(limits);
    }

    public void validate(byte[] input, String filename) {
        if (input == null || input.length == 0)
            throw new ConversionException(400, "EMPTY_INPUT", "Officeファイルを指定してください。");
        if (input.length > limits.maxInputBytes())
            throw ConversionWorkspace.limit("INPUT_BYTES_LIMIT", "入力ファイルのサイズが上限を超えました。");
        String extension = extension(filename);
        if (!java.util.Set.of("xlsx", "xls", "docx", "pptx").contains(extension))
            throw new ConversionException(415, "UNSUPPORTED_FORMAT", ".xlsx / .xls / .docx / .pptx に対応しています。");
        FileMagic magic;
        try { magic = FileMagic.valueOf(input); }
        catch (IllegalArgumentException failure) {
            throw new ConversionException(415, "UNSUPPORTED_FORMAT", "Officeファイルの形式を確認できません。", failure);
        }
        if (magic != FileMagic.OOXML && magic != FileMagic.OLE2)
            throw new ConversionException(415, "UNSUPPORTED_FORMAT", "Officeファイルの形式を確認できません。");
        // OLE2 also wraps encrypted OOXML; format-specific readers report that explicitly.
        // Each converter verifies its actual main-part type, including macro/template rejection.
    }

    public ConversionResult convert(byte[] input, String filename) {
        return convert(input, filename, ImageMode.IGNORE);
    }

    public void validateImageMode(ImageMode imageMode) {
        Objects.requireNonNull(imageMode);
        if (imageMode == ImageMode.OCR && !ocrClient.configured())
            throw new ConversionException(503, "OCR_NOT_CONFIGURED", "Document Intelligence の接続設定がないためOCRを実行できません。");
    }

    public ConversionResult convert(byte[] input, String filename, ImageMode imageMode) {
        validateImageMode(imageMode);
        validate(input, filename);
        try { SLOT.acquire(); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ConversionException(503, "CONVERSION_INTERRUPTED", "変換が中断されました。", failure);
        }
        try {
            return switch (extension(filename)) {
                case "xlsx", "xls" -> excel.convert(input, filename, imageMode, ocrClient);
                case "docx" -> word.convert(input, filename, imageMode, ocrClient);
                case "pptx" -> presentation.convert(input, filename, imageMode, ocrClient);
                default -> throw new IllegalStateException("Validated format is unavailable");
            };
        } catch (ConversionException failure) {
            throw failure;
        } catch (EncryptedDocumentException failure) {
            throw new ConversionException(415, "ENCRYPTED_DOCUMENT", "暗号化されたファイルには対応していません。", failure);
        } catch (RuntimeException failure) {
            throw new ConversionException(422, "INVALID_DOCUMENT", "Officeファイルを読み取れませんでした。形式と内容を確認してください。", failure);
        } finally { SLOT.release(); }
    }

    private static String extension(String filename) {
        String value = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        int dot = value.lastIndexOf('.');
        return dot < 0 ? "" : value.substring(dot + 1);
    }
}
