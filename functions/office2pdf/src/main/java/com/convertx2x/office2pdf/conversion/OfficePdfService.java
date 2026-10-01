package com.convertx2x.office2pdf.conversion;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.poifs.filesystem.FileMagic;

/** Format-neutral conversion entry shared by HTTP and Queue transports. */
public final class OfficePdfService {
    private static final Semaphore SLOT = new Semaphore(1, true);
    private static final Set<String> FORMATS = Set.of("xlsx", "xls", "docx", "pptx", "ppt");
    private final ConversionLimits limits;
    private final ExcelPdfConverter excel = new ExcelPdfConverter();
    private final WordPdfConverter word = new WordPdfConverter();
    private final PowerPointPdfConverter presentation = new PowerPointPdfConverter();

    public OfficePdfService(ConversionLimits limits) { this.limits = Objects.requireNonNull(limits); }

    public void validate(byte[] input, String filename) {
        if (input == null || input.length == 0) throw new ConversionException(400, "EMPTY_INPUT", "Officeファイルを指定してください。");
        if (input.length > limits.maxInputBytes()) throw ConversionWorkspace.limit("INPUT_BYTES_LIMIT", "入力ファイルのサイズが上限を超えました。");
        if (!FORMATS.contains(extension(filename)))
            throw new ConversionException(415, "UNSUPPORTED_FORMAT", ".xlsx / .xls / .docx / .pptx / .ppt に対応しています。");
        FileMagic magic;
        try { magic = FileMagic.valueOf(input); }
        catch (IllegalArgumentException failure) { throw new ConversionException(415, "UNSUPPORTED_FORMAT", "Officeファイルの形式を確認できません。", failure); }
        if (magic != FileMagic.OOXML && magic != FileMagic.OLE2)
            throw new ConversionException(415, "UNSUPPORTED_FORMAT", "Officeファイルの形式を確認できません。");
    }

    public ConversionResult convert(byte[] input, String filename) {
        validate(input, filename);
        try { SLOT.acquire(); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ConversionException(503, "CONVERSION_INTERRUPTED", "変換が中断されました。", failure);
        }
        ConversionWorkspace workspace = new ConversionWorkspace(limits);
        boolean completed = false;
        String format = extension(filename);
        try (NormalizedPdfWriter pdf = new NormalizedPdfWriter(limits,
                message -> workspace.warning("PDF_LAYOUT_TRUNCATED", null, message))) {
            switch (format) {
                case "xlsx", "xls" -> excel.convert(input, pdf, workspace);
                case "docx" -> word.convert(input, pdf, workspace);
                case "pptx", "ppt" -> presentation.convert(input, pdf, workspace);
                default -> throw new IllegalStateException("Validated format is unavailable");
            }
            byte[] output = pdf.finish();
            workspace.pagesGenerated(pdf.pageCount());
            workspace.write("document.pdf", output);
            workspace.finishReport(filename, ConversionWorkspace.sha256(input), format, sectionKind(format));
            completed = true;
            return new ConversionResult(workspace);
        } catch (ConversionException failure) {
            throw failure;
        } catch (EncryptedDocumentException failure) {
            throw new ConversionException(415, "ENCRYPTED_DOCUMENT", "暗号化されたファイルには対応していません。", failure);
        } catch (IOException failure) {
            throw new ConversionException(422, "INVALID_DOCUMENT", "OfficeファイルをPDFへ変換できませんでした。形式と内容を確認してください。", failure);
        } catch (RuntimeException failure) {
            if (failure instanceof ConversionException conversion) throw conversion;
            throw new ConversionException(422, "INVALID_DOCUMENT", "OfficeファイルをPDFへ変換できませんでした。形式と内容を確認してください。", failure);
        } finally {
            if (!completed) workspace.close();
            SLOT.release();
        }
    }

    private static String extension(String filename) {
        String value = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        int dot = value.lastIndexOf('.');
        return dot < 0 ? "" : value.substring(dot + 1);
    }

    private static String sectionKind(String format) {
        return switch (format) { case "docx" -> "document"; case "ppt", "pptx" -> "slide"; default -> "sheet"; };
    }
}
