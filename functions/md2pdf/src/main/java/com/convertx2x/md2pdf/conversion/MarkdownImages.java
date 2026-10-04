package com.convertx2x.md2pdf.conversion;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.stream.FileCacheImageInputStream;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Only embedded data or registered assets are read; URLs never access network or host files. */
final class MarkdownImages {
    private final MarkdownInput source;
    private final MarkdownFiles fileSource;
    private final ConversionWorkspace workspace;

    MarkdownImages(MarkdownInput source, ConversionWorkspace workspace) {
        this.source = source;
        this.fileSource = null;
        this.workspace = workspace;
    }

    MarkdownImages(MarkdownFiles source, ConversionWorkspace workspace) {
        this.source = null;
        this.fileSource = source;
        this.workspace = workspace;
    }

    BufferedImage resolve(String destination) {
        try {
            if (destination.regionMatches(true, 0, "data:", 0, 5)) {
                int comma = destination.indexOf(',');
                String metadata = comma < 0 ? "" : destination.substring(0, comma).toLowerCase(Locale.ROOT);
                if (!metadata.matches("data:image/(png|jpeg|jpg|gif|bmp);base64"))
                    return unavailable("IMAGE_UNSUPPORTED", "画像のdata URIはPNG/JPEG/GIF/BMPのbase64形式で指定してください。");
                return readBytes(Base64.getDecoder().decode(destination.substring(comma + 1)));
            } else {
                URI uri = URI.create(destination);
                if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getPath() == null || uri.getPath().startsWith("/")
                        || uri.getPath().contains("\\") || uri.getPath().contains(":"))
                    return unavailable("IMAGE_EXTERNAL", "外部URLや絶対パスの画像は取得しません。相対画像またはdata URIを使用してください。");
                Path parent = Path.of(source == null ? "document.md" : source.documentPath()).getParent();
                Path relative = (parent == null ? Path.of(uri.getPath()) : parent.resolve(uri.getPath())).normalize();
                if (relative.isAbsolute() || relative.startsWith(".."))
                    return unavailable("IMAGE_EXTERNAL", "入力の外部を参照する画像は取得しません。");
                String name = relative.toString().replace('\\', '/');
                if (source != null) {
                    byte[] bytes = source.assets().get(name);
                    if (bytes == null) return unavailable("IMAGE_MISSING", "参照画像がありません。Markdownと画像を同じ入力へ含めてください。");
                    return readBytes(bytes);
                }
                Path file = fileSource.image(name);
                if (file == null) return unavailable("IMAGE_MISSING", "参照画像がありません。Markdownと画像を同じ入力へ含めてください。");
                if (Files.size(file) > workspace.limits().maxInputBytes())
                    return unavailable("IMAGE_BYTES_LIMIT", "画像ファイルが入力サイズ上限を超えています。");
                // FileCacheImageInputStream supports ImageIO seeking without loading the asset into a byte[].
                try (var raw = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
                     var input = new FileCacheImageInputStream(raw, null)) {
                    return readImage(input);
                }
            }
        } catch (IOException | IllegalArgumentException failure) {
            return unavailable("IMAGE_INVALID", "画像の内容または参照先を読み取れません。");
        }
    }

    private BufferedImage readBytes(byte[] bytes) throws IOException {
        if (bytes.length > workspace.limits().maxInputBytes())
            return unavailable("IMAGE_BYTES_LIMIT", "画像ファイルが入力サイズ上限を超えています。");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            return readImage(input);
        }
    }

    private BufferedImage readImage(ImageInputStream input) throws IOException {
        var readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) return unavailable("IMAGE_UNSUPPORTED", "画像を読み取れません。PNG/JPEG/GIF/BMPに対応しています。");
        var reader = readers.next();
        try {
            reader.setInput(input, true, true);
            String format = reader.getFormatName().toLowerCase(Locale.ROOT);
            if (!java.util.Set.of("png", "jpeg", "jpg", "gif", "bmp").contains(format))
                return unavailable("IMAGE_UNSUPPORTED", "PNG/JPEG/GIF/BMP以外の画像には対応していません。");
            if ((long) reader.getWidth(0) * reader.getHeight(0) > workspace.limits().maxImagePixels())
                return unavailable("IMAGE_PIXELS_LIMIT", "画像の画素数が設定上限を超えています。");
            return reader.read(0);
        } finally { reader.dispose(); }
    }

    private BufferedImage unavailable(String code, String message) {
        workspace.warning(code, null, message);
        return null;
    }
}
