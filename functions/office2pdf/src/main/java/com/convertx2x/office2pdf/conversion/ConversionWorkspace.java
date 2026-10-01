package com.convertx2x.office2pdf.conversion;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** One conversion owns its temporary files and machine-readable report. */
public final class ConversionWorkspace implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ConversionLimits limits;
    private final Path directory;
    private final Map<String, Path> files = new LinkedHashMap<>();
    private final List<Map<String, Object>> warnings = new ArrayList<>();
    private int sectionCount;
    private int pageCount;
    private long outputBytes;

    public ConversionWorkspace(ConversionLimits limits) {
        this.limits = Objects.requireNonNull(limits);
        try { directory = Files.createTempDirectory("office2pdf-"); }
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
        pageCount = pages;
        if (ConversionLimits.exceeds(pages, limits.maxPages()))
            throw limit("PAGE_LIMIT", "生成されたPDFのページ数が上限を超えました。");
    }

    public void warning(String code, String section, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        if (section != null) item.put("section", section);
        item.put("message", message);
        warnings.add(item);
    }

    public void write(String name, byte[] bytes) {
        if (!name.equals("document.pdf") && !name.equals("report.json"))
            throw new IllegalArgumentException("Invalid output path");
        if (files.containsKey(name)) throw new IllegalArgumentException("Duplicate output path");
        if (bytes.length > limits.maxOutputBytes() - outputBytes)
            throw limit("OUTPUT_BYTES_LIMIT", "PDFと変換情報の合計サイズが上限を超えました。");
        try {
            Path target = directory.resolve(name);
            Files.write(target, bytes);
            files.put(name, target);
            outputBytes += bytes.length;
        } catch (IOException failure) { throw io(failure); }
    }

    public void finishReport(String filename, String sourceHash, String format, String sectionKind) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("specVersion", 1);
        report.put("source", Map.of("filename", filename, "sha256", sourceHash, "format", format));
        report.put("output", Map.of("path", "document.pdf", "contentType", "application/pdf",
                "sizeBytes", sizeOf("document.pdf"), "sha256", hashOf("document.pdf"), "pageCount", pageCount));
        report.put("sectionKind", sectionKind);
        report.put("sectionCount", sectionCount);
        report.put("warnings", warnings);
        report.put("information", List.of(Map.of("code", "NORMALIZED_PDF",
                "message", "Document Intelligence向けに内容を再配置したPDFです。Officeの印刷結果とは一致しない場合があります。")));
        try { write("report.json", JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report)); }
        catch (IOException failure) { throw io(failure); }
    }

    private long sizeOf(String name) {
        try { return Files.size(files.get(name)); }
        catch (IOException failure) { throw io(failure); }
    }

    private String hashOf(String name) {
        try { return sha256(Files.readAllBytes(files.get(name))); }
        catch (IOException failure) { throw io(failure); }
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    public static ConversionException limit(String code, String message) { return new ConversionException(413, code, message); }
    public static ConversionException io(Throwable cause) { return new ConversionException(500, "CONVERSION_IO_ERROR", "PDF生成中のファイル操作に失敗しました。", cause); }

    @Override public void close() {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException failure) { throw io(failure); }
    }
}
