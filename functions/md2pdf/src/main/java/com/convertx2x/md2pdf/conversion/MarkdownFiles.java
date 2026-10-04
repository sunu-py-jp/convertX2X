package com.convertx2x.md2pdf.conversion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** An existing document.md and its registered assets, without creating an input ZIP. */
final class MarkdownFiles {
    private final String markdown;
    private final String sourceHash;
    private final Map<String, Path> files;
    private final Path root;
    private final Path realRoot;

    private MarkdownFiles(String markdown, String sourceHash, Map<String, Path> files, Path root, Path realRoot) {
        this.markdown = markdown;
        this.sourceHash = sourceHash;
        this.files = files;
        this.root = root;
        this.realRoot = realRoot;
    }

    static MarkdownFiles read(Map<String, Path> input, ConversionLimits limits) {
        if (input == null || !input.containsKey("document.md") || input.get("document.md") == null)
            throw new ConversionException(400, "EMPTY_INPUT", "document.mdを指定してください。");
        Path root = input.get("document.md").toAbsolutePath().normalize().getParent();
        if (root == null) throw invalidPath();
        try {
            Path realRoot = root.toRealPath();
            Map<String, Path> checked = new LinkedHashMap<>();
            long total = 0;
            for (var entry : input.entrySet()) {
                String name = checkedName(entry.getKey());
                Path supplied = entry.getValue();
                if (supplied == null) throw invalidPath();
                Path expected = root.resolve(name);
                if (!supplied.toAbsolutePath().normalize().equals(expected)) throw invalidPath();
                Path component = root;
                for (Path part : Path.of(name)) {
                    component = component.resolve(part);
                    if (Files.isSymbolicLink(component)) throw invalidPath();
                }
                if (!Files.isRegularFile(expected, LinkOption.NOFOLLOW_LINKS)
                        || !expected.toRealPath().startsWith(realRoot)) throw invalidPath();
                if (!name.equals("report.json")) {
                    long size = Files.size(expected);
                    if (size > limits.maxInputBytes() - total)
                        throw ConversionWorkspace.limit("INPUT_BYTES_LIMIT", "Markdownと画像の合計サイズが入力上限を超えました。");
                    total += size;
                }
                checked.put(name, expected);
            }
            Path document = checked.get("document.md");
            long documentSize = Files.size(document);
            if (documentSize == 0) throw new ConversionException(400, "EMPTY_INPUT", "Markdownファイルを指定してください。");
            if (documentSize > Integer.MAX_VALUE)
                throw ConversionWorkspace.limit("INPUT_BYTES_LIMIT", "Markdownのサイズが入力上限を超えました。");
            byte[] bytes = Files.readAllBytes(document);
            if (bytes.length > limits.maxInputBytes())
                throw ConversionWorkspace.limit("INPUT_BYTES_LIMIT", "Markdownのサイズが入力上限を超えました。");
            return new MarkdownFiles(MarkdownInput.decode(bytes), ConversionWorkspace.sha256(bytes),
                    Map.copyOf(checked), root, realRoot);
        } catch (IOException failure) {
            throw new ConversionException(422, "INVALID_DOCUMENT", "Markdownまたは画像ファイルを読み取れません。", failure);
        }
    }

    String markdown() { return markdown; }
    String sourceHash() { return sourceHash; }

    /** Recheck paths immediately before opening an image in case the caller changed an asset. */
    Path image(String name) throws IOException {
        Path path = files.get(name);
        if (path == null) return null;
        if (!path.equals(root.resolve(name)) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !path.toRealPath().startsWith(realRoot)) throw new IOException("Image path changed");
        Path component = root;
        for (Path part : Path.of(name)) {
            component = component.resolve(part);
            if (Files.isSymbolicLink(component)) throw new IOException("Image path changed");
        }
        return path;
    }

    private static String checkedName(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.endsWith("/")
                || name.contains("//") || name.contains("\\") || name.contains(":")
                || name.codePoints().anyMatch(Character::isISOControl)) throw invalidPath();
        for (String part : name.split("/"))
            if (part.equals(".") || part.equals("..")) throw invalidPath();
        return name;
    }

    private static ConversionException invalidPath() {
        return new ConversionException(422, "INVALID_FILE_PATH", "Markdownまたは画像ファイルのパスが不正です。");
    }
}
