package com.convertx2x.md2pdf.conversion;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Only embedded data or uploaded ZIP assets are read; URLs never access network or host files. */
final class MarkdownImages {
    private final MarkdownInput source;
    private final ConversionWorkspace workspace;

    MarkdownImages(MarkdownInput source, ConversionWorkspace workspace) {
        this.source = source;
        this.workspace = workspace;
    }

    BufferedImage resolve(String destination) {
        try {
            byte[] bytes;
            if (destination.regionMatches(true, 0, "data:", 0, 5)) {
                int comma = destination.indexOf(',');
                String metadata = comma < 0 ? "" : destination.substring(0, comma).toLowerCase(Locale.ROOT);
                if (!metadata.matches("data:image/(png|jpeg|jpg|gif|bmp);base64"))
                    return unavailable("IMAGE_UNSUPPORTED", "画像のdata URIはPNG/JPEG/GIF/BMPのbase64形式で指定してください。");
                bytes = Base64.getDecoder().decode(destination.substring(comma + 1));
            } else {
                URI uri = URI.create(destination);
                if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getPath() == null || uri.getPath().startsWith("/")
                        || uri.getPath().contains("\\") || uri.getPath().contains(":"))
                    return unavailable("IMAGE_EXTERNAL", "外部URLや絶対パスの画像は取得しません。ZIP内の相対画像またはdata URIを使用してください。");
                Path parent = Path.of(source.documentPath()).getParent();
                Path relative = (parent == null ? Path.of(uri.getPath()) : parent.resolve(uri.getPath())).normalize();
                if (relative.isAbsolute() || relative.startsWith(".."))
                    return unavailable("IMAGE_EXTERNAL", "ZIPの外部を参照する画像は取得しません。");
                bytes = source.assets().get(relative.toString().replace('\\', '/'));
                if (bytes == null) return unavailable("IMAGE_MISSING", "参照画像がありません。Markdownと画像を同じZIPへ含めてください。");
            }
            if (bytes.length > workspace.limits().maxInputBytes())
                return unavailable("IMAGE_BYTES_LIMIT", "画像ファイルが入力サイズ上限を超えています。");
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
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
        } catch (IOException | IllegalArgumentException failure) {
            return unavailable("IMAGE_INVALID", "画像の内容または参照先を読み取れません。");
        }
    }

    private BufferedImage unavailable(String code, String message) {
        workspace.warning(code, null, message);
        return null;
    }
}
