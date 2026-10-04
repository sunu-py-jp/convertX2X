package com.convertx2x.office2md.conversion;

import static org.junit.jupiter.api.Assertions.*;

import com.convertx2x.office2md.ocr.OcrClient;
import com.convertx2x.office2md.ocr.OcrResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.hssf.usermodel.HSSFClientAnchor;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.Units;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.drawingml.x2006.main.STShapeType;
import org.w3c.dom.Element;

/** The actual Office readers must pass only visible source pixels to OCR. */
class EmbeddedOfficeImageIntegrationTest {
    @Test void excelCropHasIndependentAssetAndOcrNeverSeesHiddenHalf() throws Exception {
        assertVisibleCrop(excel(true, false), "sample.xlsx");
    }

    @Test void wordCropHasIndependentAssetAndOcrNeverSeesHiddenHalf() throws Exception {
        assertVisibleCrop(word(true, false), "sample.docx");
    }

    @Test void powerPointCropHasIndependentAssetAndOcrNeverSeesHiddenHalf() throws Exception {
        assertVisibleCrop(presentation(true, false), "sample.pptx");
    }

    @Test void legacyExcelRectangularPictureIsEligibleForOcr() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ConversionResult result = service(calls, false).convert(legacyExcel(), "sample.xls", ImageMode.OCR)) {
            assertTrue(Files.readString(result.files().get("document.md")).contains("images/image-0001.png"));
            assertEquals(1, calls.get());
            assertEquals("succeeded", imageBlock(report(result)).path("ocr").path("status").asText());
        }
    }

    @Test void nonRectangularPictureMasksAreNeverSentToOcr() throws Exception {
        assertMasked(excel(false, true), "sample.xlsx");
        assertMasked(word(false, true), "sample.docx");
        assertMasked(presentation(false, true), "sample.pptx");
    }

    @Test void ignoreModeStillRetainsTheIndependentImageInAllFormats() throws Exception {
        for (var sample : new Sample[]{new Sample(excel(false, false), "sample.xlsx"),
                new Sample(word(false, false), "sample.docx"),
                new Sample(presentation(false, false), "sample.pptx")}) {
            var calls = new AtomicInteger();
            try (ConversionResult result = service(calls).convert(sample.bytes(), sample.filename(), ImageMode.IGNORE)) {
                String markdown = Files.readString(result.files().get("document.md"));
                assertTrue(markdown.contains("!["), sample.filename());
                assertTrue(markdown.contains("images/image-0001.png"), sample.filename());
                assertFalse(markdown.contains("画像内の文字（OCR）"), sample.filename());
                assertEquals(0, calls.get());
                assertEquals("embeddedImage", report(result).path("assets").get(0).path("sourceKind").asText());
            }
        }
    }

    @Test void commonOfficeUseLocalDpiExtensionDoesNotSuppressOcrButAlphaEffectDoes() throws Exception {
        assertVisibleCrop(withBlipChild(presentation(true, false), false), "sample.pptx");
        assertMasked(withBlipChild(presentation(false, false), true), "sample.pptx");
    }

    private static void assertVisibleCrop(byte[] input, String filename) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ConversionResult result = service(calls).convert(input, filename, ImageMode.OCR)) {
            String markdown = Files.readString(result.files().get("document.md"));
            assertTrue(markdown.contains("images/image-0001.png"), filename);
            assertTrue(markdown.contains("\n\n画像内の文字（OCR）:\n\n青い文字"), filename);
            assertTrue(markdown.contains("青い文字"), filename);
            assertTrue(markdown.indexOf("画像の前") < markdown.indexOf("images/image-0001.png"), filename);
            assertTrue(markdown.indexOf("images/image-0001.png") < markdown.indexOf("画像の後"), filename);
            assertEquals(1, calls.get(), filename);
            JsonNode report = report(result);
            assertEquals("succeeded", imageBlock(report).path("ocr").path("status").asText(), filename);
            BufferedImage output = ImageIO.read(result.files().get("images/image-0001.png").toFile());
            try {
                assertTrue(output.getWidth() > 0 && output.getHeight() > 0);
                assertNoHiddenRed(output);
            } finally { output.flush(); }
        }
    }

    private static void assertMasked(byte[] input, String filename) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ConversionResult result = service(calls).convert(input, filename, ImageMode.OCR)) {
            String markdown = Files.readString(result.files().get("document.md"));
            assertTrue(markdown.contains("images/image-0001.png"), filename);
            assertFalse(markdown.contains("青い文字"), filename);
            assertEquals(0, calls.get(), filename);
            assertEquals("skipped", imageBlock(report(result)).path("ocr").path("status").asText(), filename);
        }
    }

    private static OfficeMarkdownService service(AtomicInteger calls) {
        return service(calls, true);
    }

    private static OfficeMarkdownService service(AtomicInteger calls, boolean requireNoRed) {
        return new OfficeMarkdownService(ConversionLimits.defaults(), new OcrClient() {
            @Override public boolean configured() { return true; }
            @Override public OcrResult recognize(byte[] bytes, String contentType) {
                calls.incrementAndGet();
                assertEquals("image/png", contentType);
                try {
                    BufferedImage visible = ImageIO.read(new ByteArrayInputStream(bytes));
                    assertNotNull(visible);
                    try { if (requireNoRed) assertNoHiddenRed(visible); }
                    finally { visible.flush(); }
                } catch (Exception failure) { throw new AssertionError(failure); }
                return new OcrResult("青い文字");
            }
        });
    }

    private static void assertNoHiddenRed(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            Color pixel = new Color(image.getRGB(x, y), true);
            if (pixel.getAlpha() > 100)
                assertFalse(pixel.getRed() > 160 && pixel.getGreen() < 100 && pixel.getBlue() < 100,
                        "Crop-hidden red pixel reached OCR");
        }
    }

    private static JsonNode report(ConversionResult result) throws Exception {
        return new ObjectMapper().readTree(result.files().get("report.json").toFile());
    }
    private static JsonNode imageBlock(JsonNode report) {
        for (JsonNode block : report.path("blocks"))
            if ("embeddedImage".equals(block.path("type").asText())) return block;
        fail("Embedded image block was not reported");
        return null;
    }

    private static byte[] excel(boolean crop, boolean ellipse) throws Exception {
        try (XSSFWorkbook book = new XSSFWorkbook()) {
            var sheet = book.createSheet("画像");
            sheet.createRow(0).createCell(0).setCellValue("画像の前");
            sheet.createRow(10).createCell(0).setCellValue("画像の後");
            var anchor = book.getCreationHelper().createClientAnchor();
            anchor.setCol1(1); anchor.setRow1(1); anchor.setCol2(4); anchor.setRow2(5);
            XSSFPicture picture = sheet.createDrawingPatriarch().createPicture(anchor,
                    book.addPicture(splitImage(), Workbook.PICTURE_TYPE_PNG));
            if (crop) picture.getCTPicture().getBlipFill().addNewSrcRect().setL(50000);
            if (ellipse) picture.getCTPicture().getSpPr().getPrstGeom().setPrst(STShapeType.ELLIPSE);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); book.write(bytes); return bytes.toByteArray();
        }
    }

    private static byte[] word(boolean crop, boolean ellipse) throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("画像の前");
            XWPFPicture picture = document.createParagraph().createRun().addPicture(new ByteArrayInputStream(splitImage()),
                    Document.PICTURE_TYPE_PNG, "sample.png", Units.toEMU(100), Units.toEMU(50));
            document.createParagraph().createRun().setText("画像の後");
            if (crop) picture.getCTPicture().getBlipFill().addNewSrcRect().setL(50000);
            if (ellipse) picture.getCTPicture().getSpPr().getPrstGeom().setPrst(STShapeType.ELLIPSE);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); document.write(bytes); return bytes.toByteArray();
        }
    }

    private static byte[] legacyExcel() throws Exception {
        try (HSSFWorkbook book = new HSSFWorkbook()) {
            var sheet = book.createSheet("画像");
            var anchor = new HSSFClientAnchor(0, 0, 0, 0, (short) 1, 1, (short) 4, 5);
            sheet.createDrawingPatriarch().createPicture(anchor,
                    book.addPicture(splitImage(), Workbook.PICTURE_TYPE_PNG));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); book.write(bytes); return bytes.toByteArray();
        }
    }

    private static byte[] presentation(boolean crop, boolean ellipse) throws Exception {
        try (XMLSlideShow show = new XMLSlideShow()) {
            var slide = show.createSlide();
            var before = slide.createTextBox(); before.setText("画像の前");
            before.setAnchor(new Rectangle2D.Double(20, 0, 100, 15));
            XSLFPictureShape picture = slide.createPicture(
                    show.addPicture(splitImage(), PictureData.PictureType.PNG));
            picture.setAnchor(new Rectangle2D.Double(20, 20, 100, 50));
            var after = slide.createTextBox(); after.setText("画像の後");
            after.setAnchor(new Rectangle2D.Double(20, 90, 100, 15));
            if (crop) ((org.openxmlformats.schemas.presentationml.x2006.main.CTPicture) picture.getXmlObject())
                    .getBlipFill().addNewSrcRect().setL(50000);
            if (ellipse) ((org.openxmlformats.schemas.presentationml.x2006.main.CTPicture) picture.getXmlObject())
                    .getSpPr().getPrstGeom().setPrst(STShapeType.ELLIPSE);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); show.write(bytes); return bytes.toByteArray();
        }
    }

    private static byte[] splitImage() throws Exception {
        BufferedImage image = new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB);
        try {
            for (int y = 0; y < 50; y++) for (int x = 0; x < 100; x++)
                image.setRGB(x, y, x < 50 ? Color.RED.getRGB() : Color.BLUE.getRGB());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(image, "png", bytes));
            return bytes.toByteArray();
        } finally { image.flush(); }
    }

    private static byte[] withBlipChild(byte[] pptx, boolean alphaEffect) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(pptx));
             ZipOutputStream zip = new ZipOutputStream(output)) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                byte[] bytes = input.readAllBytes();
                if (entry.getName().equals("ppt/slides/slide1.xml")) {
                    var parser = DocumentBuilderFactory.newInstance();
                    parser.setNamespaceAware(true);
                    var document = parser.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
                    String drawing = "http://schemas.openxmlformats.org/drawingml/2006/main";
                    Element blip = (Element) document.getElementsByTagNameNS(drawing, "blip").item(0);
                    assertNotNull(blip);
                    if (alphaEffect) {
                        Element alpha = document.createElementNS(drawing, "a:alphaModFix");
                        alpha.setAttribute("amt", "50000");
                        blip.appendChild(alpha);
                    } else {
                        Element extensions = document.createElementNS(drawing, "a:extLst");
                        Element extension = document.createElementNS(drawing, "a:ext");
                        extension.setAttribute("uri", "{28A0092B-C50C-407E-A947-70E740481C1C}");
                        Element dpi = document.createElementNS(
                                "http://schemas.microsoft.com/office/drawing/2010/main", "a14:useLocalDpi");
                        dpi.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:a14",
                                "http://schemas.microsoft.com/office/drawing/2010/main");
                        dpi.setAttribute("val", "0");
                        extension.appendChild(dpi);
                        extensions.appendChild(extension);
                        blip.appendChild(extensions);
                    }
                    ByteArrayOutputStream xml = new ByteArrayOutputStream();
                    TransformerFactory.newInstance().newTransformer().transform(new DOMSource(document),
                            new StreamResult(xml));
                    bytes = xml.toByteArray();
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(0);
                zip.putNextEntry(copy);
                zip.write(bytes);
                zip.closeEntry();
                input.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private record Sample(byte[] bytes, String filename) { }
}
