package com.convertx2x.office2pdf.conversion;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.poifs.filesystem.FileMagic;

/** Format-neutral conversion entry shared by HTTP and Queue transports. */
public final class OfficePdfService {
    private static final Semaphore SLOT = new Semaphore(1, true);
    /** Extension to reader, in the order shown to clients. A format is added by registering its converter here. */
    private static final Map<String, Format> FORMATS = formats();
    private static final String SUPPORTED = String.join(" / ", FORMATS.keySet().stream().map(extension -> "." + extension).toList())
            + " に対応しています。";
    private final ConversionLimits limits;

    private record Format(OfficePdfConverter converter, String sectionKind) { }

    private static Map<String, Format> formats() {
        OfficePdfConverter excel = new ExcelPdfConverter();
        OfficePdfConverter presentation = new PowerPointPdfConverter();
        Map<String, Format> formats = new LinkedHashMap<>();
        formats.put("xlsx", new Format(excel, "sheet"));
        formats.put("xls", new Format(excel, "sheet"));
        formats.put("docx", new Format(new WordPdfConverter(), "document"));
        formats.put("doc", new Format(new LegacyWordPdfConverter(), "document"));
        formats.put("pptx", new Format(presentation, "slide"));
        formats.put("ppt", new Format(presentation, "slide"));
        return Collections.unmodifiableMap(formats);
    }

    /** Extensions accepted by {@link #convert}, without the leading dot. */
    public static List<String> supportedFormats() { return List.copyOf(FORMATS.keySet()); }

    public OfficePdfService(ConversionLimits limits) { this.limits = Objects.requireNonNull(limits); }

    public void validate(byte[] input, String filename) {
        if (input == null || input.length == 0) throw new ConversionException(400, "EMPTY_INPUT", "Officeファイルを指定してください。");
        if (input.length > limits.maxInputBytes()) throw ConversionWorkspace.limit("INPUT_BYTES_LIMIT", "入力ファイルのサイズが上限を超えました。");
        if (!FORMATS.containsKey(extension(filename)))
            throw new ConversionException(415, "UNSUPPORTED_FORMAT", SUPPORTED);
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
        String extension = extension(filename);
        Format format = FORMATS.get(extension);
        try (NormalizedPdfWriter pdf = new NormalizedPdfWriter(limits,
                message -> workspace.warning("PDF_LAYOUT_TRUNCATED", null, message))) {
            format.converter().convert(input, pdf, workspace);
            byte[] output = pdf.finish();
            workspace.pagesGenerated(pdf.pageCount());
            workspace.write("document.pdf", output);
            workspace.finishReport(filename, ConversionWorkspace.sha256(input), extension, format.sectionKind());
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
}
