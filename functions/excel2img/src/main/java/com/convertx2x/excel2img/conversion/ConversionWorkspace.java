package com.convertx2x.excel2img.conversion;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One conversion owns bounded output files and its client-safe report. */
public final class ConversionWorkspace implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ConversionLimits limits;
    private final Path directory;
    private final Map<String, Path> files = new LinkedHashMap<>();
    private final List<Map<String, Object>> warnings = new ArrayList<>();
    private final Set<String> warningKeys = new HashSet<>();
    private int sectionCount;
    private int pageCount;
    private long outputBytes;
    private long omittedWarnings;

    public ConversionWorkspace(ConversionLimits limits) {
        this.limits = Objects.requireNonNull(limits);
        try { directory = Files.createTempDirectory("excel2img-"); }
        catch (IOException failure) { throw io(failure); }
    }
    public ConversionLimits limits() { return limits; }
    public Path directory() { return directory; }
    public Map<String, Path> files() { return Collections.unmodifiableMap(files); }
    public int warningCount() { return warnings.size(); }
    public int sectionCount() { return sectionCount; }
    public int pageCount() { return pageCount; }
    public void sectionIncluded() { sectionCount++; }
    public void pagesGenerated(int pages) {
        if (ConversionLimits.exceeds(pages, limits.maxPages()))
            throw limit("PAGE_LIMIT", "生成ページ数が上限を超えました。");
        pageCount = pages;
    }
    public void warning(String code, String section, String message) {
        String key = code + "\n" + section + "\n" + message;
        if (warnings.size() >= 500) { omittedWarnings++; return; }
        if (!warningKeys.add(key)) return;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        if (section != null) item.put("sheet", section);
        item.put("message", message);
        warnings.add(Collections.unmodifiableMap(item));
    }
    public List<Map<String, Object>> warnings() { return List.copyOf(warnings); }

    /** The output limit is enforced while encoding, before a giant byte array can be built. */
    public OutputStream output(String name) {
        if (!name.equals("document.pdf") && !name.equals("report.json") &&
                !name.matches("pages/page-[0-9]{3,}\\.png")) throw new IllegalArgumentException("Invalid output path");
        if (files.containsKey(name)) throw new IllegalArgumentException("Duplicate output path");
        try {
            Path target = directory.resolve(name);
            Files.createDirectories(target.getParent());
            OutputStream file = Files.newOutputStream(target);
            files.put(name, target);
            return new FilterOutputStream(file) {
                private void reserve(int length) {
                    if (length > limits.maxOutputBytes() - outputBytes)
                        throw limit("OUTPUT_BYTES_LIMIT", "出力ファイルの合計サイズが上限を超えました。");
                    outputBytes += length;
                }
                @Override public void write(int value) throws IOException { reserve(1); out.write(value); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    reserve(length); out.write(bytes, offset, length);
                }
            };
        } catch (IOException failure) { throw io(failure); }
    }
    public void write(String name, byte[] bytes) {
        try (OutputStream output = output(name)) { output.write(bytes); }
        catch (IOException failure) { throw io(failure); }
    }
    public Map<String, Object> report(String filename, String sourceHash, String sourceFormat, RenderOptions options,
                                     List<Map<String, Object>> ranges, List<Map<String, Object>> pages, boolean partial) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("specVersion", 1);
        report.put("source", Map.of("filename", filename == null ? "" : filename, "sha256", sourceHash, "format", sourceFormat));
        report.put("format", options.format());
        Map<String, Object> selection = new LinkedHashMap<>();
        selection.put("format", options.format());
        selection.put("sheet", options.sheet());
        selection.put("range", options.range());
        report.put("options", selection);
        report.put("outcome", partial ? "partial" : "complete");
        report.put("pageCount", pageCount);
        report.put("sectionKind", "sheet");
        report.put("sectionCount", sectionCount);
        report.put("ranges", ranges);
        report.put("pages", pages);
        report.put("warnings", warnings());
        if (omittedWarnings > 0) report.put("omittedWarningCount", omittedWarnings);
        return report;
    }
    public void finishReport(Map<String, Object> report) {
        List<Map<String, Object>> outputs = new ArrayList<>();
        for (var entry : files.entrySet()) {
            try {
                outputs.add(Map.of("path", entry.getKey(), "sizeBytes", Files.size(entry.getValue()),
                        "sha256", sha256(entry.getValue()), "contentType", entry.getKey().endsWith(".pdf") ? "application/pdf" : "image/png"));
            } catch (IOException failure) { throw io(failure); }
        }
        report.put("outputs", outputs);
        try { write("report.json", JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report)); }
        catch (IOException failure) { throw io(failure); }
    }
    public static String sha256(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    private static String sha256(Path path) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] block = new byte[8192];
            for (int size; (size = input.read(block)) >= 0;) digest.update(block, 0, size);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    public static ConversionException limit(String code, String message) { return new ConversionException(413, code, message); }
    public static ConversionException io(Throwable cause) { return new ConversionException(500, "CONVERSION_IO_ERROR", "変換中のファイル操作に失敗しました。", cause); }
    @Override public void close() {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException failure) { throw io(failure); }
    }
}
