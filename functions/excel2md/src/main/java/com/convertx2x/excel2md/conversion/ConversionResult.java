package com.convertx2x.excel2md.conversion;

import java.io.*;
import java.nio.file.*;
import java.util.Map;
import java.util.zip.*;

/** Temporary artifacts remain available until the caller closes the result. */
public final class ConversionResult implements AutoCloseable {
    private final ConversionWorkspace workspace;
    public ConversionResult(ConversionWorkspace workspace) { this.workspace = workspace; }
    public Path directory() { return workspace.directory(); }
    public Map<String, Path> files() { return workspace.files(); }
    public int warningCount() { return workspace.warningCount(); }
    public int sheetCount() { return workspace.sheetCount(); }
    public byte[] zipBytes() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            writeZip(bytes);
            return bytes.toByteArray();
        } catch (IOException e) { throw ConversionWorkspace.io(e); }
    }
    public void writeZip(OutputStream output) {
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
            for (var file : files().entrySet()) {
                ZipEntry entry = new ZipEntry(file.getKey());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                Files.copy(file.getValue(), zip);
                zip.closeEntry();
            }
        } catch (IOException e) { throw ConversionWorkspace.io(e); }
    }
    @Override public void close() { workspace.close(); }
}
