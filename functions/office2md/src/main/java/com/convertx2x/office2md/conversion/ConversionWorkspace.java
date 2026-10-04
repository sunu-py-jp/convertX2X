package com.convertx2x.office2md.conversion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.convertx2x.office2md.drawing.DiagramMetadata;
import com.convertx2x.office2md.ocr.OcrClient;
import com.convertx2x.office2md.ocr.OcrException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** One conversion owns its files, counters and report; no Azure dependencies. */
public final class ConversionWorkspace implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ConversionLimits limits;
    private final ImageMode imageMode;
    private final OcrClient ocrClient;
    private final Map<String, ImageOcr> imageOcr = new HashMap<>();
    private final Map<String, byte[]> retainedImageBytes = new HashMap<>();
    private final Path directory;
    private final Map<String, Path> files = new LinkedHashMap<>();
    private final List<Map<String, Object>> warnings = new ArrayList<>();
    private final List<Map<String, Object>> information = new ArrayList<>();
    private final List<Map<String, Object>> blocks = new ArrayList<>();
    private final List<Map<String, Object>> assets = new ArrayList<>();
    private final Map<String, String> assetHashes = new HashMap<>();
    private final Map<String, Integer> sequences = new HashMap<>();
    private long outputBytes;
    private long cachedOcrBytes;
    private long embeddedImageMarkdownBytes;
    private long retainedImageByteCount;
    private long images, shapes;
    private int sectionCount;

    public ConversionWorkspace(ConversionLimits limits) {
        this(limits, ImageMode.IGNORE, OcrClient.disabled());
    }

    public ConversionWorkspace(ConversionLimits limits, ImageMode imageMode, OcrClient ocrClient) {
        this.limits = Objects.requireNonNull(limits);
        this.imageMode = Objects.requireNonNull(imageMode);
        this.ocrClient = Objects.requireNonNull(ocrClient);
        if (imageMode == ImageMode.OCR && !ocrClient.configured())
            throw new ConversionException(503, "OCR_NOT_CONFIGURED", "Document Intelligence の接続設定がないためOCRを実行できません。");
        try { directory = Files.createTempDirectory("office2md-"); }
        catch (IOException e) { throw io(e); }
    }

    public ConversionLimits limits() { return limits; }
    public Path directory() { return directory; }
    public Map<String, Path> files() { return Collections.unmodifiableMap(files); }
    public int warningCount() { return warnings.size(); }
    public int sectionCount() { return sectionCount; }

    public void warning(String code, String sheet, String range, String message) {
        warnings.add(diagnostic(code, sheet, range, message));
        checkReportBudget();
    }
    public void info(String code, String sheet, String range, String message) {
        information.add(diagnostic(code, sheet, range, message));
        checkReportBudget();
    }
    private Map<String, Object> diagnostic(String code, String sheet, String range, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        if (sheet != null) item.put("section", sheet);
        if (range != null) item.put("range", range);
        item.put("message", message);
        return item;
    }
    private void checkReportBudget() {
        // Apply the optional aggregate count only when every contributing count is bounded.
        if (ConversionLimits.exceeds((long) warnings.size() + information.size(), limits.maxReportEntries()))
            throw limit("REPORT_LIMIT", "変換情報の件数が上限を超えました。");
    }
    public void block(Map<String, Object> block) {
        if (ConversionLimits.exceeds(blocks.size() + 1L, limits.maxReadItems())) throw limit("BLOCK_LIMIT", "出力ブロック数が上限を超えました。");
        blocks.add(new LinkedHashMap<>(block));
    }
    public void sectionIncluded() {
        if (ConversionLimits.exceeds(++sectionCount, limits.maxSections())) throw limit("SECTION_LIMIT", "シート・スライド数が上限を超えました。");
    }

    public void imagePlacement() {
        if (ConversionLimits.exceeds(++images, limits.maxImages())) throw limit("IMAGE_LIMIT", "画像の配置数が上限を超えました。");
    }
    public void shapeVisited(int depth) {
        if (ConversionLimits.exceeds(++shapes, limits.maxShapes())) throw limit("SHAPE_LIMIT", "図形数が上限を超えました。");
        if (depth > limits.maxGroupDepth()) throw limit("GROUP_DEPTH_LIMIT", "図形のグループ階層が上限を超えました。");
    }

    /** Drawing readers retain pictures until layout is complete. Share identical buffers and bound them early. */
    public byte[] retainImageBytes(byte[] bytes) {
        if (bytes.length > limits.maxImageBytes()) throw limit("IMAGE_BYTES_LIMIT", "画像1件のサイズが上限を超えました。");
        String hash = sha256(bytes);
        byte[] previous = retainedImageBytes.get(hash);
        if (previous != null && Arrays.equals(previous, bytes)) return previous;
        if (bytes.length > limits.maxOutputBytes() - retainedImageByteCount)
            throw limit("OUTPUT_BYTES_LIMIT", "画像処理の合計サイズが上限を超えました。");
        retainedImageByteCount += bytes.length;
        retainedImageBytes.put(hash, bytes);
        return bytes;
    }

    public String addAsset(String category, byte[] bytes, String extension, String contentType) {
        if (!Set.of("image", "diagram").contains(category) || !extension.matches("[a-z0-9]{1,8}"))
            throw new IllegalArgumentException("Invalid generated asset name");
        if (bytes.length > limits.maxImageBytes()) throw limit("IMAGE_BYTES_LIMIT", "画像1件のサイズが上限を超えました。");
        String hash = sha256(bytes);
        String existing = assetHashes.get(category + ":" + hash);
        if (existing != null) {
            try { if (Arrays.equals(bytes, Files.readAllBytes(files.get(existing)))) return existing; }
            catch (IOException e) { throw io(e); }
        }
        int number = sequences.merge(category, 1, Integer::sum);
        String path = "images/" + category + "-" + String.format(Locale.ROOT, "%04d", number) + "." + extension;
        write(path, bytes);
        assetHashes.put(category + ":" + hash, path);
        assets.add(Map.of("path", path, "contentType", contentType, "sizeBytes", bytes.length, "sha256", hash,
                "sourceKind", category.equals("image") ? "embeddedImage" : "renderedDiagram"));
        return path;
    }

    /** Only original, visibly prepared Office pictures enter this path; generated diagrams never do. */
    public String embeddedImage(byte[] bytes, String extension, String contentType, Map<String, Object> metadata,
                                String section, String range, boolean ocrEligible) {
        String path = addAsset("image", bytes, extension, contentType);
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "embeddedImage");
        if (section != null) block.put("section", section);
        if (range != null) block.put("range", range);
        block.put("path", path);
        block.put("metadata", new LinkedHashMap<>(metadata));
        ImageOcr ocr = new ImageOcr("notRequested", "", null);
        if (imageMode == ImageMode.OCR) {
            if (!ocrEligible) ocr = new ImageOcr("skipped", "", "OCR_IMAGE_UNSUPPORTED");
            else ocr = imageOcr.computeIfAbsent(sha256(bytes), key -> recognizeImage(bytes, contentType));
        }
        Map<String, Object> ocrMetadata = new LinkedHashMap<>();
        ocrMetadata.put("status", ocr.status());
        if (imageMode == ImageMode.OCR) ocrMetadata.put("model", "prebuilt-read");
        if (ocr.code() != null) {
            ocrMetadata.put("code", ocr.code());
            warning(ocr.code(), section, range, switch (ocr.status()) {
                case "skipped" -> "可視領域を安全にOCRできない画像形式・加工のため、画像の参照だけを残しました。";
                default -> "画像のOCRに失敗したため、画像の参照だけを残しました。";
            });
        }
        block.put("ocr", ocrMetadata);
        block(block);
        // Unsupported attachments remain downloadable without advertising an unsupported inline format.
        boolean inline = Set.of("png", "jpg", "jpeg", "gif", "webp").contains(extension);
        String markdown = (inline ? "!" : "") + "[" + DiagramMetadata.imageAlt(metadata) + "](" + path + ")";
        if (!ocr.text().isBlank()) markdown += "\n\n画像内の文字（OCR）:\n\n"
                + Markdown.escape(ocr.text()).replace("\n", "  \n");
        // Drawing extractors collect blocks before assembling the document. Bound every placement,
        // including repeated references to cached OCR, before those intermediate strings accumulate.
        int markdownBytes = markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (markdownBytes > limits.maxMarkdownBytes() - embeddedImageMarkdownBytes)
            throw limit("MARKDOWN_BYTES_LIMIT", "画像参照とOCRテキストの合計サイズが上限を超えました。");
        embeddedImageMarkdownBytes += markdownBytes;
        return markdown;
    }

    private ImageOcr recognizeImage(byte[] bytes, String contentType) {
        try {
            String text = Objects.requireNonNullElse(ocrClient.recognize(bytes, contentType).text(), "").strip();
            int textBytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (textBytes > limits.maxMarkdownBytes() - cachedOcrBytes)
                throw limit("MARKDOWN_BYTES_LIMIT", "OCRテキストのサイズが上限を超えました。");
            cachedOcrBytes += textBytes;
            return new ImageOcr(text.isEmpty() ? "noText" : "succeeded", text, null);
        } catch (OcrException failure) {
            if (Thread.currentThread().isInterrupted())
                throw new ConversionException(503, "CONVERSION_INTERRUPTED", "変換が中断されました。");
            return new ImageOcr("failed", "", failure.code());
        }
    }

    private record ImageOcr(String status, String text, String code) { }

    public void write(String relativePath, byte[] bytes) {
        if (!relativePath.matches("(?:document\\.md|report\\.json|images/[a-z]+-[0-9]+\\.[a-z0-9]+)"))
            throw new IllegalArgumentException("Invalid output path");
        if (files.containsKey(relativePath)) throw new IllegalArgumentException("Duplicate output path");
        if (relativePath.equals("document.md") && bytes.length > limits.maxMarkdownBytes())
            throw limit("MARKDOWN_BYTES_LIMIT", "Markdownのサイズが上限を超えました。");
        if (bytes.length > limits.maxOutputBytes() - outputBytes)
            throw limit("OUTPUT_BYTES_LIMIT", "出力の合計サイズが上限を超えました。");
        try {
            Path target = directory.resolve(relativePath);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            files.put(relativePath, target);
            outputBytes += bytes.length;
        } catch (IOException e) { throw io(e); }
    }

    public void finishReport(String filename, String sourceHash) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("specVersion", 1);
        String format = filename.toLowerCase(Locale.ROOT).replaceFirst("^.*\\.", "");
        report.put("source", Map.of("filename", filename, "sha256", sourceHash, "format", format));
        report.put("sectionKind", switch (format) { case "docx" -> "document"; case "pptx" -> "slide"; default -> "sheet"; });
        report.put("sectionCount", sectionCount);
        report.put("warnings", warnings);
        report.put("information", information);
        report.put("blocks", blocks);
        report.put("assets", assets);
        report.put("imageMode", imageMode.wireValue());
        try { writeReport(JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report)); }
        catch (IOException e) { throw io(e); }
    }

    private void writeReport(byte[] bytes) throws IOException {
        Path existing = files.get("report.json");
        if (existing == null) {
            write("report.json", bytes);
            return;
        }
        long oldSize = Files.size(existing);
        if (bytes.length > limits.maxOutputBytes() - (outputBytes - oldSize))
            throw limit("OUTPUT_BYTES_LIMIT", "出力の合計サイズが上限を超えました。");
        Files.write(existing, bytes);
        outputBytes += bytes.length - oldSize;
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static ConversionException limit(String code, String message) {
        return new ConversionException(413, code, message);
    }
    public static ConversionException io(Throwable cause) {
        return new ConversionException(500, "CONVERSION_IO_ERROR", "変換中のファイル操作に失敗しました。", cause);
    }
    @Override public void close() {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException e) { throw io(e); }
        finally { retainedImageBytes.clear(); imageOcr.clear(); }
    }
}
