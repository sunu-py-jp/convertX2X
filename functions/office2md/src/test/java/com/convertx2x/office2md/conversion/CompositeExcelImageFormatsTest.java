package com.convertx2x.office2md.conversion;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import org.apache.poi.ss.usermodel.ShapeTypes;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** Format support is verified through saved XLSX bytes, not only the isolated image decoder. */
class CompositeExcelImageFormatsTest {
    @Test void svgGifBmpTiffRemainVisibleWithTheirOverlaidShapes() throws Exception {
        for (String format : new String[]{"svg", "gif", "bmp", "dib", "tiff"}) {
            try (var book = new XSSFWorkbook()) {
                byte[] source = source(format);
                int type = switch (format) {
                    case "gif" -> 8; case "tiff" -> 9; case "bmp" -> 11; case "dib" -> 7; default -> 6;
                };
                int index = book.addPicture(source, type);
                if (format.equals("svg")) book.getAllPictures().get(index).getPackagePart().setContentType("image/svg+xml");
                var drawing = book.createSheet(format).createDrawingPatriarch();
                drawing.createPicture(anchor(0, 0, 120, 80), index);
                var rectangle = drawing.createSimpleShape(anchor(80, 20, 60, 40));
                rectangle.setShapeType(ShapeTypes.RECT);
                rectangle.setFillColor(255, 0, 0);
                rectangle.setText("注釈");
                ByteArrayOutputStream saved = new ByteArrayOutputStream(); book.write(saved);
                if (format.equals("svg")) {
                    try (var read = new XSSFWorkbook(new java.io.ByteArrayInputStream(saved.toByteArray()))) {
                        var pic = (org.apache.poi.xssf.usermodel.XSSFPicture) read.getSheetAt(0).getDrawingPatriarch().getShapes().getFirst();
                        var owner = pic.getDrawing().getPackagePart();
                        var relation = owner.getRelationship(pic.getCTPicture().getBlipFill().getBlip().getEmbed());
                        var part = owner.getRelatedPart(relation);
                        assertEquals("image/svg+xml", part.getContentType());
                        try (var stream = part.getInputStream()) { assertArrayEquals(source, stream.readAllBytes()); }
                    }
                }
                try (var result = new OfficeMarkdownService(ConversionLimits.defaults())
                        .convert(saved.toByteArray(), "formats.xlsx")) {
                    var report = new ObjectMapper().readTree(result.files().get("report.json").toFile());
                    assertEquals(1, report.path("assets").size(), format + ": " + report);
                    var asset = report.path("assets").get(0);
                    assertEquals("compositeImage", asset.path("sourceKind").asText(), format + ": " + report);
                    assertTrue(Files.readString(result.files().get("document.md")).contains("注釈"));
                    assertFalse(report.toString().contains("UNSUPPORTED_IMAGE_FORMAT"), format + ": " + report);
                    BufferedImage output = ImageIO.read(result.files().get(asset.path("path").asText()).toFile());
                    try {
                        long green = 0, red = 0, clear = 0;
                        for (int y = 0; y < output.getHeight(); y++) for (int x = 0; x < output.getWidth(); x++) {
                            Color color = new Color(output.getRGB(x, y), true);
                            if (color.getAlpha() == 0) clear++;
                            else if (color.getGreen() > 200 && color.getRed() < 50) green++;
                            else if (color.getRed() > 200 && color.getGreen() < 50) red++;
                        }
                        assertTrue(green > 1000, format + ": source image must be visible");
                        assertTrue(red > 1000, format + ": annotation must be visible");
                        assertTrue(clear > 100, format + ": expanded empty canvas must be transparent");
                    } finally { output.flush(); }
                }
            }
        }
    }

    private static byte[] source(String format) throws Exception {
        if (format.equals("svg")) return ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"120\" height=\"80\">"
                + "<rect width=\"120\" height=\"80\" fill=\"#00ff00\"/></svg>").getBytes(StandardCharsets.UTF_8);
        BufferedImage image = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.GREEN); graphics.fillRect(0, 0, 120, 80); graphics.dispose();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(image, format.equals("dib") ? "bmp" : format, bytes));
            byte[] encoded = bytes.toByteArray();
            return format.equals("dib") ? java.util.Arrays.copyOfRange(encoded, 14, encoded.length) : encoded;
        } finally { image.flush(); }
    }

    private static XSSFClientAnchor anchor(int x, int y, int width, int height) {
        return new XSSFClientAnchor(Units.toEMU(x), Units.toEMU(y), Units.toEMU(x + width),
                Units.toEMU(y + height), 0, 0, 0, 0);
    }
}
