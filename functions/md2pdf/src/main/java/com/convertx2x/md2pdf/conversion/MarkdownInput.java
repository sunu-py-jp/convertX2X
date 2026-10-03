package com.convertx2x.md2pdf.conversion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipFile;

/** ZIPs are read with a bound on total expanded bytes; entries are never extracted. */
record MarkdownInput(String markdown, String documentPath, Map<String, byte[]> assets) {
    static MarkdownInput read(byte[] input, String format, ConversionLimits limits) {
        if (!format.equals("zip")) return new MarkdownInput(decode(input), "document.md", Map.of());
        Path temporary = null;
        try {
            temporary = Files.createTempFile("md2pdf-input-", ".zip");
            Files.write(temporary, input);
            Map<String, byte[]> entries = new LinkedHashMap<>();
            long total = 0;
            try (ZipFile zip = new ZipFile(temporary.toFile(), StandardCharsets.UTF_8)) {
                var iterator = zip.entries();
                byte[] buffer = new byte[8192];
                while (iterator.hasMoreElements()) {
                    var entry = iterator.nextElement();
                    String name = safeEntry(entry.getName());
                    if (entry.isDirectory()) continue;
                    if (entries.containsKey(name)) throw invalid("ZIPに同じパスのファイルが複数あります。");
                    try (var stream = zip.getInputStream(entry); var bytes = new ByteArrayOutputStream()) {
                        int count;
                        while ((count = stream.read(buffer)) != -1) {
                            total += count;
                            if (total > limits.maxInputBytes())
                                throw ConversionWorkspace.limit("INPUT_BYTES_LIMIT", "ZIP展開後の合計サイズが入力上限を超えました。");
                            bytes.write(buffer, 0, count);
                        }
                        entries.put(name, bytes.toByteArray());
                    }
                }
            }
            var documents = entries.keySet().stream().filter(MarkdownInput::isMarkdown).toList();
            String selected;
            if (entries.containsKey("document.md")) selected = "document.md";
            else if (documents.size() == 1) selected = documents.getFirst();
            else throw invalid("ZIPにはルートのdocument.md、またはMarkdownファイルを1つだけ含めてください。");
            return new MarkdownInput(decode(entries.get(selected)), selected, entries);
        } catch (IOException | IllegalArgumentException failure) {
            throw new ConversionException(422, "INVALID_ARCHIVE", "ZIPファイルを読み取れません。形式と内容を確認してください。", failure);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException failure) { temporary.toFile().deleteOnExit(); }
            }
        }
    }

    private static String decode(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\ufeff")) text = text.substring(1);
            if (text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
                throw new ConversionException(422, "INVALID_ENCODING", "MarkdownはUTF-8のテキストで指定してください。");
            return text;
        } catch (CharacterCodingException failure) {
            throw new ConversionException(422, "INVALID_ENCODING", "MarkdownはUTF-8で指定してください。", failure);
        }
    }

    private static boolean isMarkdown(String name) {
        String value = name.toLowerCase(Locale.ROOT);
        return value.endsWith(".md") || value.endsWith(".markdown");
    }

    private static String safeEntry(String name) {
        if (name.isBlank() || name.startsWith("/") || name.contains("\\") || name.contains(":")
                || name.codePoints().anyMatch(Character::isISOControl)) throw invalid("ZIP内のパスが不正です。");
        for (String part : name.split("/"))
            if (part.equals("..") || part.equals(".")) throw invalid("ZIP内の相対パスが不正です。");
        return name;
    }

    private static ConversionException invalid(String message) { return new ConversionException(422, "INVALID_ARCHIVE", message); }
}
