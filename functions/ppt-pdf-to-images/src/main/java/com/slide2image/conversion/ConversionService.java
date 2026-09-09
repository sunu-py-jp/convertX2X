package com.slide2image.conversion;

import com.slide2image.conversion.InputConverter.PageEncoder;
import com.slide2image.conversion.InputConverter.PageRenderer;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Shared conversion limits, input dispatch and image output for HTTP and Queue callers. */
public class ConversionService {
    private final ConversionLimits limits;
    private final List<InputConverter> inputConverters;
    private final Semaphore conversionSlot = new Semaphore(1);

    public ConversionService(ConversionLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.inputConverters = List.of(new PdfConverter(limits), new PptConverter(limits));
    }

    public ConversionResult convert(byte[] input, String filename, ConversionOptions options) {
        if (!conversionSlot.tryAcquire()) {
            throw new ConversionException(503, "CONVERSION_BUSY", "Another document is being converted. Retry later.");
        }
        try {
            return convertDocument(input, filename, options,
                    (totalPages, renderer) -> encode(totalPages, filename, options,
                            options.format().toLowerCase(Locale.ROOT), renderer));
        } finally {
            conversionSlot.release();
        }
    }

    /**
     * Render and deliver one encoded image at a time, without creating a ZIP or retaining previous pages.
     * The consumer runs synchronously and receives the original one-based page number.
     * Returns the number of images delivered; callback failures propagate unchanged.
     */
    public int convertPages(byte[] input, String filename, ConversionOptions options, PageConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer");
        if (!conversionSlot.tryAcquire()) {
            throw new ConversionException(503, "CONVERSION_BUSY", "Another document is being converted. Retry later.");
        }
        try {
            return convertDocument(input, filename, options,
                    (totalPages, renderer) -> emitPages(totalPages, options,
                            options.format().toLowerCase(Locale.ROOT), renderer, consumer));
        } finally {
            conversionSlot.release();
        }
    }

    @FunctionalInterface
    public interface PageConsumer {
        void accept(int pageNumber, ConversionResult image);
    }

    private <T> T convertDocument(byte[] input, String filename, ConversionOptions options, PageEncoder<T> encoder) {
        validate(input, filename, options);
        try {
            return selectConverter(input).convert(input, options, encoder);
        } catch (ConsumerFailure failure) {
            // Storage/network failures belong to the caller's retry policy, not document validation.
            throw failure.original;
        } catch (ConversionException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ConversionException(422, "INVALID_DOCUMENT",
                    "The document is damaged or contains content that cannot be rendered.", failure);
        }
    }

    private InputConverter selectConverter(byte[] input) {
        // Trust input content, never a supplied filename or content type.
        return inputConverters.stream().filter(converter -> converter.supports(input)).findFirst()
                .orElseThrow(ConversionService::unsupported);
    }

    private ConversionResult encode(int totalPages, String filename, ConversionOptions options,
                                     String format, PageRenderer renderer) throws IOException {
        validatePages(totalPages, options);
        String base = safeBasename(filename);
        if (options.page() != null) {
            byte[] image = encodePage(renderer, options.page() - 1, format);
            return new ConversionResult(image, "image/" + format,
                    base + "-" + entryName(options.page(), format), 1);
        }
        LimitedOutputStream output = new LimitedOutputStream(limits.maxOutputBytes());
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (int index = 0; index < totalPages; index++) {
                // Each image is released before the following page is rendered.
                byte[] image = encodePage(renderer, index, format);
                ZipEntry entry = new ZipEntry(entryName(index + 1, format));
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(image);
                zip.closeEntry();
            }
        }
        return new ConversionResult(output.toByteArray(), "application/zip", base + ".zip", totalPages);
    }

    private int emitPages(int totalPages, ConversionOptions options, String format,
                          PageRenderer renderer, PageConsumer consumer) throws IOException {
        validatePages(totalPages, options);
        int firstPage = options.page() == null ? 1 : options.page();
        int lastPage = options.page() == null ? totalPages : options.page();
        long totalBytes = 0;
        for (int index = firstPage - 1; index < lastPage; index++) {
            int page = index + 1;
            // encodePage enforces the per-image limit before the aggregate limit below.
            byte[] image = encodePage(renderer, index, format);
            if (image.length > limits.maxOutputBytes() - totalBytes) {
                throw limit("OUTPUT_LIMIT_EXCEEDED", "The result exceeds the configured output size limit.");
            }
            totalBytes += image.length;
            try {
                consumer.accept(page, new ConversionResult(image, "image/" + format, entryName(page, format), 1));
            } catch (RuntimeException failure) {
                throw new ConsumerFailure(failure);
            }
        }
        return lastPage - firstPage + 1;
    }

    private void validatePages(int totalPages, ConversionOptions options) {
        if (totalPages < 1) {
            throw new ConversionException(422, "EMPTY_DOCUMENT", "The document contains no pages.");
        }
        if (totalPages > limits.maxPages()) {
            throw limit("PAGE_LIMIT_EXCEEDED", "The document exceeds the configured page limit.");
        }
        if (options.page() != null && options.page() > totalPages) {
            throw new ConversionException(400, "INVALID_PAGE", "The requested page does not exist.");
        }
    }

    private byte[] encodePage(PageRenderer renderer, int index, String format) throws IOException {
        BufferedImage image = renderer.render(index);
        try {
            LimitedOutputStream output = new LimitedOutputStream(limits.maxOutputBytes());
            ImageWriter writer = ImageIO.getImageWritersByFormatName(format).next();
            try (MemoryCacheImageOutputStream imageOutput = new MemoryCacheImageOutputStream(output)) {
                writer.setOutput(imageOutput);
                writer.write(image);
                imageOutput.flush();
            } finally {
                writer.dispose();
            }
            return output.toByteArray();
        } finally {
            image.flush();
        }
    }

    /** Lightweight request validation; full document structure is checked during conversion. */
    public void validate(byte[] input, String filename, ConversionOptions options) {
        if (input == null || input.length == 0) {
            throw new ConversionException(400, "EMPTY_INPUT", "A non-empty document is required.");
        }
        if (input.length > limits.maxInputBytes()) {
            throw limit("INPUT_LIMIT_EXCEEDED", "The document exceeds the configured input size limit.");
        }
        if (options == null || (options.width() != null && options.width() < 1) || options.format() == null
                || !(options.format().equalsIgnoreCase("png") || options.format().equalsIgnoreCase("jpeg"))) {
            throw new ConversionException(400, "INVALID_OPTIONS", "Width, when specified, must be positive; png or jpeg format is required.");
        }
        if (options.page() != null && options.page() < 1) {
            throw new ConversionException(400, "INVALID_PAGE", "Page numbers start at 1.");
        }
        selectConverter(input);
    }

    private static String safeBasename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "converted";
        }
        String name = filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.replaceAll("[^A-Za-z0-9_-]", "_");
        return name.isBlank() ? "converted" : name.substring(0, Math.min(name.length(), 100));
    }

    private static String entryName(int page, String format) {
        return String.format(Locale.ROOT, "page-%04d.%s", page, format);
    }

    private static ConversionException unsupported() {
        return new ConversionException(415, "UNSUPPORTED_DOCUMENT", "Only PowerPoint (PPT/PPTX) and PDF are supported.");
    }

    private static ConversionException limit(String code, String message) {
        return new ConversionException(413, code, message);
    }

    /** Marks failures from the transport callback while the document's resources are still open. */
    private static final class ConsumerFailure extends RuntimeException {
        private final RuntimeException original;

        private ConsumerFailure(RuntimeException original) {
            super(null, original, false, false);
            this.original = original;
        }
    }

    /** Enforces encoded image and final ZIP size limits while bytes are being written. */
    private static final class LimitedOutputStream extends ByteArrayOutputStream {
        private final long maximum;

        private LimitedOutputStream(long maximum) {
            this.maximum = maximum;
        }

        @Override
        public synchronized void write(int value) {
            check(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            check(length);
            super.write(bytes, offset, length);
        }

        private void check(int length) {
            if ((long) count + length > maximum) {
                throw limit("OUTPUT_LIMIT_EXCEEDED", "The result exceeds the configured output size limit.");
            }
        }
    }
}
