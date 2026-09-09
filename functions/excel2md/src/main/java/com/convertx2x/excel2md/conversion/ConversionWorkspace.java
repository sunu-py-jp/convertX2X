package com.convertx2x.excel2md.conversion;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** One conversion owns its files, counters and report; no Azure dependencies. */
public final class ConversionWorkspace implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ConversionLimits limits;
    private final Path directory;
    private final Map<String, Path> files = new LinkedHashMap<>();
    private final List<Map<String, Object>> warnings = new ArrayList<>();
    private final List<Map<String, Object>> information = new ArrayList<>();
    private final List<Map<String, Object>> blocks = new ArrayList<>();
    private final List<Map<String, Object>> assets = new ArrayList<>();
    private final Map<String, String> assetHashes = new HashMap<>();
    private final Map<String, Integer> sequences = new HashMap<>();
    private long outputBytes;
    private int images, shapes, sheetCount;

    public ConversionWorkspace(ConversionLimits limits) {
        this.limits = Objects.requireNonNull(limits);
        try { directory = Files.createTempDirectory("excel2md-"); }
        catch (IOException e) { throw io(e); }
    }

    public ConversionLimits limits() { return limits; }
    public Path directory() { return directory; }
    public Map<String, Path> files() { return Collections.unmodifiableMap(files); }
    public int warningCount() { return warnings.size(); }
    public int sheetCount() { return sheetCount; }

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
        if (sheet != null) item.put("sheet", sheet);
        if (range != null) item.put("range", range);
        item.put("message", message);
        return item;
    }
    private void checkReportBudget() {
        // Bound diagnostic objects as well as the final serialized report.
        if ((long) warnings.size() + information.size() > limits.maxReadCells() + (long) limits.maxShapes() + limits.maxImages() + limits.maxSheets())
            throw limit("REPORT_LIMIT", "変換情報の件数が上限を超えました。");
    }
    public void block(Map<String, Object> block) { blocks.add(new LinkedHashMap<>(block)); }
    public void sheetIncluded() { sheetCount++; }

    public void imagePlacement() {
        if (++images > limits.maxImages()) throw limit("IMAGE_LIMIT", "画像の配置数が上限を超えました。");
    }
    public void shapeVisited(int depth) {
        if (++shapes > limits.maxShapes()) throw limit("SHAPE_LIMIT", "図形数が上限を超えました。");
        if (depth > limits.maxGroupDepth()) throw limit("GROUP_DEPTH_LIMIT", "図形のグループ階層が上限を超えました。");
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
        assets.add(Map.of("path", path, "contentType", contentType, "sizeBytes", bytes.length, "sha256", hash));
        return path;
    }

    public void write(String relativePath, byte[] bytes) {
        if (!relativePath.matches("(?:document\\.md|report\\.json|images/[a-z]+-[0-9]+\\.[a-z0-9]+)"))
            throw new IllegalArgumentException("Invalid output path");
        if (files.containsKey(relativePath)) throw new IllegalArgumentException("Duplicate output path");
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
        report.put("source", Map.of("filename", filename, "sha256", sourceHash));
        report.put("sheetCount", sheetCount);
        report.put("warnings", warnings);
        report.put("information", information);
        report.put("blocks", blocks);
        report.put("assets", assets);
        try { write("report.json", JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report)); }
        catch (IOException e) { throw io(e); }
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
    }
}
