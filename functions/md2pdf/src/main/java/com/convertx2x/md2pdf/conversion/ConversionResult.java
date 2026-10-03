package com.convertx2x.md2pdf.conversion;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Temporary PDF and report remain available until the caller closes this result. */
public final class ConversionResult implements AutoCloseable {
    private final ConversionWorkspace workspace;
    public ConversionResult(ConversionWorkspace workspace) { this.workspace = workspace; }
    public Path directory() { return workspace.directory(); }
    public Map<String, Path> files() { return workspace.files(); }
    public int warningCount() { return workspace.warningCount(); }
    public int sectionCount() { return workspace.sectionCount(); }
    public int pageCount() { return workspace.pageCount(); }
    public byte[] pdfBytes() {
        try { return Files.readAllBytes(files().get("document.pdf")); }
        catch (IOException failure) { throw ConversionWorkspace.io(failure); }
    }
    public byte[] zipBytes() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) { writeZip(bytes); return bytes.toByteArray(); }
        catch (IOException failure) { throw ConversionWorkspace.io(failure); }
    }
    public void writeZip(OutputStream output) throws IOException {
        OutputStream bounded = new FilterOutputStream(output) {
            private long count;
            private void reserve(int length) {
                if (length > workspace.limits().maxOutputBytes() - count)
                    throw ConversionWorkspace.limit("OUTPUT_BYTES_LIMIT", "ZIPのサイズが上限を超えました。");
                count += length;
            }
            @Override public void write(int value) throws IOException { reserve(1); out.write(value); }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException { reserve(length); out.write(bytes, offset, length); }
        };
        try (ZipOutputStream zip = new ZipOutputStream(bounded)) {
            for (var entry : files().entrySet()) {
                ZipEntry item = new ZipEntry(entry.getKey());
                item.setTime(0L);
                zip.putNextEntry(item);
                Files.copy(entry.getValue(), zip);
                zip.closeEntry();
            }
        }
    }
    @Override public void close() { workspace.close(); }
}
