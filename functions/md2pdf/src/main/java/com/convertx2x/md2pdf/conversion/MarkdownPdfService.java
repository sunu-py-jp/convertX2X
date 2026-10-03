package com.convertx2x.md2pdf.conversion;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;

/** Shared entry point for HTTP uploads and queued Blob inputs. */
public final class MarkdownPdfService {
    private static final Semaphore SLOT = new Semaphore(1, true);
    private static final Set<String> FORMATS = Set.of("md", "markdown", "zip");
    private final ConversionLimits limits;

    public MarkdownPdfService(ConversionLimits limits) { this.limits = Objects.requireNonNull(limits); }

    public void validate(byte[] input, String filename) {
        validateEnvelope(input, filename);
        MarkdownInput.read(input, extension(filename), limits);
    }

    private void validateEnvelope(byte[] input, String filename) {
        if (input == null || input.length == 0)
            throw new ConversionException(400, "EMPTY_INPUT", "Markdownファイルを指定してください。");
        if (input.length > limits.maxInputBytes())
            throw ConversionWorkspace.limit("INPUT_BYTES_LIMIT", "入力ファイルのサイズが上限を超えました。");
        if (!FORMATS.contains(extension(filename)))
            throw new ConversionException(415, "UNSUPPORTED_FORMAT", ".md / .markdown / .zip に対応しています。");
    }

    public ConversionResult convert(byte[] input, String filename) {
        validateEnvelope(input, filename);
        try { SLOT.acquire(); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ConversionException(503, "CONVERSION_INTERRUPTED", "変換が中断されました。", failure);
        }
        ConversionWorkspace workspace = null;
        boolean completed = false;
        try {
            workspace = new ConversionWorkspace(limits);
            ConversionWorkspace current = workspace;
            MarkdownInput source = MarkdownInput.read(input, extension(filename), limits);
            MarkdownImages images = new MarkdownImages(source, workspace);
            try (NormalizedPdfWriter pdf = new NormalizedPdfWriter(limits,
                    message -> current.warning("PDF_LAYOUT", null, message))) {
                new MarkdownPdfConverter().convert(source.markdown(), pdf, workspace, images::resolve);
                byte[] output = pdf.finish();
                workspace.pagesGenerated(pdf.pageCount());
                workspace.write("document.pdf", output);
                workspace.finishReport(filename, ConversionWorkspace.sha256(input), extension(filename), "document");
            }
            completed = true;
            return new ConversionResult(workspace);
        } catch (ConversionException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new ConversionException(422, "INVALID_DOCUMENT", "MarkdownをPDFへ変換できませんでした。内容を確認してください。", failure);
        } finally {
            try { if (!completed && workspace != null) workspace.close(); }
            finally { SLOT.release(); }
        }
    }

    private static String extension(String filename) {
        String value = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        int dot = value.lastIndexOf('.');
        return dot < 0 ? "" : value.substring(dot + 1);
    }
}
