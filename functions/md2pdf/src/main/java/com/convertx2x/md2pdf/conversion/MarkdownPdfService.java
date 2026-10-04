package com.convertx2x.md2pdf.conversion;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.Supplier;

/** Shared entry point for HTTP uploads, queued Blob inputs, and existing Office2MD files. */
public final class MarkdownPdfService {
    private static final Semaphore SLOT = new Semaphore(1, true);
    private static final Set<String> FORMATS = Set.of("md", "markdown", "zip");
    private final ConversionLimits limits;
    private record Prepared(String markdown, String sourceHash, String format,
                            Function<ConversionWorkspace, MarkdownImages> images) { }

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
        return convertPrepared(filename, () -> {
            MarkdownInput source = MarkdownInput.read(input, extension(filename), limits);
            return new Prepared(source.markdown(), ConversionWorkspace.sha256(input), extension(filename),
                    workspace -> new MarkdownImages(source, workspace));
        });
    }

    /** Render Office2MD's document.md and registered assets directly from their existing paths. */
    public ConversionResult convertFiles(Map<String, Path> files, String filename) {
        String format = extension(filename);
        if (!format.equals("md") && !format.equals("markdown"))
            throw new ConversionException(415, "UNSUPPORTED_FORMAT", ".md / .markdown に対応しています。");
        return convertPrepared(filename, () -> {
            MarkdownFiles source = MarkdownFiles.read(files, limits);
            return new Prepared(source.markdown(), source.sourceHash(), format,
                    workspace -> new MarkdownImages(source, workspace));
        });
    }

    private ConversionResult convertPrepared(String filename, Supplier<Prepared> loader) {
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
            Prepared source = loader.get();
            MarkdownImages images = source.images().apply(workspace);
            try (NormalizedPdfWriter pdf = new NormalizedPdfWriter(limits,
                    message -> current.warning("PDF_LAYOUT", null, message))) {
                new MarkdownPdfConverter().convert(source.markdown(), pdf, workspace, images::resolve);
                byte[] output = pdf.finish();
                workspace.pagesGenerated(pdf.pageCount());
                workspace.write("document.pdf", output);
                workspace.finishReport(filename, source.sourceHash(), source.format(), "document");
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
