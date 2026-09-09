package com.slide2image.conversion;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.poi.hslf.usermodel.HSLFAutoShape;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFAutoShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class ConversionServiceTest {
    private final ConversionService service = new ConversionService(ConversionLimits.defaults());

    @ParameterizedTest
    @ValueSource(strings = {"pptx", "ppt", "pdf"})
    void rendersEveryPageIntoZipWithActualContent(String type) throws Exception {
        ConversionResult result = service.convert(document(type), "example." + type,
                new ConversionOptions(200, "png", null));

        assertEquals("application/zip", result.contentType());
        assertEquals("example.zip", result.filename());
        assertEquals(2, result.pageCount());
        Map<String, BufferedImage> images = unzip(result.bytes());
        assertEquals(2, images.size());
        assertImage(images.get("page-0001.png"), 200, 100, Color.RED);
        assertImage(images.get("page-0002.png"), 200, 100, Color.BLUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pptx", "ppt", "pdf"})
    void rendersAtSourcePhysicalSizeWhenWidthIsOmitted(String type) throws Exception {
        ConversionResult result = service.convert(document(type), "example." + type,
                new ConversionOptions(null, "png", null));

        assertEquals("application/zip", result.contentType());
        assertEquals(2, result.pageCount());
        Map<String, BufferedImage> images = unzip(result.bytes());
        assertEquals(2, images.size());
        // 400 x 200 points at 96 dpi, rounding each dimension independently.
        assertImage(images.get("page-0001.png"), 533, 267, Color.RED);
        assertImage(images.get("page-0002.png"), 533, 267, Color.BLUE);
    }

    @Test
    void usesEachPdfPagesPhysicalSizeIncludingCropRotationAndUserUnit() throws Exception {
        byte[] input;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            addPdfPage(document, Color.RED);

            PDPage rotated = new PDPage(new PDRectangle(400, 200));
            rotated.setCropBox(new PDRectangle(50, 25, 300, 150));
            rotated.setRotation(90);
            rotated.setUserUnit(2);
            document.addPage(rotated);
            try (PDPageContentStream content = new PDPageContentStream(document, rotated)) {
                content.setNonStrokingColor(Color.BLUE);
                content.addRect(150, 75, 100, 50);
                content.fill();
            }

            PDPage portrait = new PDPage(new PDRectangle(200, 400));
            document.addPage(portrait);
            try (PDPageContentStream content = new PDPageContentStream(document, portrait)) {
                content.setNonStrokingColor(Color.GREEN);
                content.addRect(50, 100, 100, 200);
                content.fill();
            }
            document.save(bytes);
            input = bytes.toByteArray();
        }

        ConversionResult result = service.convert(input, "mixed.pdf", new ConversionOptions(null, "png", null));
        Map<String, BufferedImage> images = unzip(result.bytes());
        assertEquals(3, result.pageCount());
        assertEquals(3, images.size());
        assertImage(images.get("page-0001.png"), 533, 267, Color.RED);
        assertImage(images.get("page-0002.png"), 400, 800, Color.BLUE);
        assertImage(images.get("page-0003.png"), 267, 533, Color.GREEN);

        ConversionResult selected = service.convert(input, "mixed.pdf", new ConversionOptions(null, "jpeg", 2));
        assertEquals("image/jpeg", selected.contentType());
        assertEquals(1, selected.pageCount());
        assertImage(ImageIO.read(new ByteArrayInputStream(selected.bytes())), 400, 800, Color.BLUE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pptx", "ppt", "pdf"})
    void appliesPixelLimitToAutomaticDimensions(String type) throws Exception {
        byte[] input = document(type);
        ConversionService constrained = new ConversionService(
                new ConversionLimits(1_000_000, 100, 533L * 267 - 1, 1_000_000));

        assertFailure(constrained, input, new ConversionOptions(null, "png", null),
                413, "PIXEL_LIMIT_EXCEEDED");
        // The same source remains convertible when explicitly downscaled below the limit.
        ConversionResult smaller = constrained.convert(input, "example." + type,
                new ConversionOptions(200, "png", 1));
        assertImage(ImageIO.read(new ByteArrayInputStream(smaller.bytes())), 200, 100, Color.RED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pptx", "ppt", "pdf"})
    void returnsRequestedOneBasedPageAsJpegAndUsesContentInsteadOfExtension(String type) throws Exception {
        ConversionResult result = service.convert(document(type), "wrong-name.txt",
                new ConversionOptions(240, "jpeg", 2));

        assertEquals("image/jpeg", result.contentType());
        assertEquals("wrong-name-page-0002.jpeg", result.filename());
        assertEquals(1, result.pageCount());
        assertEquals(0xff, result.bytes()[0] & 0xff);
        assertEquals(0xd8, result.bytes()[1] & 0xff);
        assertImage(ImageIO.read(new ByteArrayInputStream(result.bytes())), 240, 120, Color.BLUE);
    }

    @Test
    void respectsPdfRotationAndCropDimensions() throws Exception {
        byte[] input;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(new PDRectangle(400, 200));
            page.setCropBox(new PDRectangle(50, 25, 300, 150));
            page.setRotation(90);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(Color.RED);
                content.addRect(150, 75, 100, 50);
                content.fill();
            }
            document.save(bytes);
            input = bytes.toByteArray();
        }
        ConversionResult result = service.convert(input, "rotated.pdf", new ConversionOptions(150, "png", 1));
        assertImage(ImageIO.read(new ByteArrayInputStream(result.bytes())), 150, 300, Color.RED);
    }

    @Test
    void rejectsInputPageAndPixelLimitsBeforeRendering() throws Exception {
        byte[] input = document("pdf");
        assertFailure(new ConversionService(new ConversionLimits(input.length - 1L, 100, 1_000_000, 1_000_000)),
                input, new ConversionOptions(200, "png", null), 413, "INPUT_LIMIT_EXCEEDED");
        assertFailure(new ConversionService(new ConversionLimits(1_000_000, 1, 1_000_000, 1_000_000)),
                input, new ConversionOptions(200, "png", 1), 413, "PAGE_LIMIT_EXCEEDED");
        assertFailure(new ConversionService(new ConversionLimits(1_000_000, 100, 19_999, 1_000_000)),
                input, new ConversionOptions(200, "png", null), 413, "PIXEL_LIMIT_EXCEEDED");
    }

    @Test
    void rejectsEncodedImageLimitAndZipOverhead() throws Exception {
        byte[] input = document("pdf");
        assertFailure(new ConversionService(new ConversionLimits(1_000_000, 100, 1_000_000, 10)),
                input, new ConversionOptions(200, "png", 1), 413, "OUTPUT_LIMIT_EXCEEDED");

        byte[] singlePage;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            addPdfPage(document, Color.RED);
            document.save(bytes);
            singlePage = bytes.toByteArray();
        }
        int imageBytes = service.convert(singlePage, "one.pdf", new ConversionOptions(2, "png", 1)).bytes().length;
        int zipBytes = service.convert(singlePage, "one.pdf", new ConversionOptions(2, "png", null)).bytes().length;
        // A tiny PNG stays smaller than its ZIP container: the final ZIP overhead must count too.
        assertTrue(zipBytes > imageBytes);
        ConversionService constrained = new ConversionService(new ConversionLimits(1_000_000, 100, 1_000_000, zipBytes - 1L));
        assertFailure(constrained, singlePage, new ConversionOptions(2, "png", null), 413, "OUTPUT_LIMIT_EXCEEDED");
    }

    @Test
    void rejectsOutOfRangePageAndInvalidOptions() throws Exception {
        byte[] input = document("pdf");
        assertFailure(service, input, new ConversionOptions(200, "png", 0), 400, "INVALID_PAGE");
        assertFailure(service, input, new ConversionOptions(200, "png", 3), 400, "INVALID_PAGE");
        assertFailure(service, input, new ConversionOptions(0, "png", null), 400, "INVALID_OPTIONS");
        assertFailure(service, input, new ConversionOptions(200, "gif", null), 400, "INVALID_OPTIONS");
        assertFailure(service, new byte[0], new ConversionOptions(200, "png", null), 400, "EMPTY_INPUT");
    }

    @Test
    void rejectsWordEvenWhenNamedPowerpointAndRejectsDamagedPdf() throws Exception {
        byte[] word;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("This is a Word document.");
            document.write(bytes);
            word = bytes.toByteArray();
        }
        assertFailure(service, word, new ConversionOptions(200, "png", null), 415, "UNSUPPORTED_DOCUMENT");
        assertFailure(service, "%PDF-1.7\nnot a document".getBytes(StandardCharsets.US_ASCII),
                new ConversionOptions(200, "png", null), 422, "INVALID_DOCUMENT");
        assertFailure(service, "not an office file".getBytes(StandardCharsets.US_ASCII),
                new ConversionOptions(200, "png", null), 415, "UNSUPPORTED_DOCUMENT");
    }

    @Test
    void rejectsEncryptedPdfIncludingEmptyUserPassword() throws Exception {
        for (String userPassword : new String[]{"secret", ""}) {
            byte[] input;
            try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                addPdfPage(document, Color.RED);
                StandardProtectionPolicy protection = new StandardProtectionPolicy("owner", userPassword, new AccessPermission());
                protection.setEncryptionKeyLength(128);
                document.protect(protection);
                document.save(bytes);
                input = bytes.toByteArray();
            }
            assertFailure(service, input, new ConversionOptions(200, "png", null), 422, "ENCRYPTED_DOCUMENT");
        }
    }

    @Test
    void releasesConversionSlotAfterFailure() throws Exception {
        assertFailure(service, "%PDF-1.7\nbroken".getBytes(StandardCharsets.US_ASCII),
                new ConversionOptions(200, "png", null), 422, "INVALID_DOCUMENT");
        assertEquals(1, service.convert(document("pdf"), "valid.pdf", new ConversionOptions(200, "png", 1)).pageCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pptx", "ppt", "pdf"})
    void deliversPagesAsIndividualImagesAtSourceSizeWithoutZip(String type) throws Exception {
        List<Integer> pages = new ArrayList<>();
        List<ConversionResult> images = new ArrayList<>();
        int count = service.convertPages(document(type), "example." + type,
                new ConversionOptions(null, "png", null), (page, image) -> {
                    pages.add(page);
                    images.add(image);
                });

        assertEquals(2, count);
        assertEquals(List.of(1, 2), pages);
        for (int index = 0; index < images.size(); index++) {
            ConversionResult image = images.get(index);
            assertEquals("page-000" + (index + 1) + ".png", image.filename());
            assertEquals("image/png", image.contentType());
            assertEquals(1, image.pageCount());
            assertImage(ImageIO.read(new ByteArrayInputStream(image.bytes())), 533, 267,
                    index == 0 ? Color.RED : Color.BLUE);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pptx", "ppt", "pdf"})
    void selectedPageConsumerReceivesOriginalPageNumberAndJpeg(String type) throws Exception {
        List<Integer> pages = new ArrayList<>();
        List<ConversionResult> images = new ArrayList<>();
        int count = service.convertPages(document(type), "wrong-name.txt",
                new ConversionOptions(240, "jpeg", 2), (page, image) -> {
                    pages.add(page);
                    images.add(image);
                });

        assertEquals(1, count);
        assertEquals(List.of(2), pages);
        ConversionResult image = images.getFirst();
        assertEquals("page-0002.jpeg", image.filename());
        assertEquals("image/jpeg", image.contentType());
        assertEquals(1, image.pageCount());
        assertImage(ImageIO.read(new ByteArrayInputStream(image.bytes())), 240, 120, Color.BLUE);
    }

    @Test
    void pageConsumerChecksPerImageAndAggregateLimitsBeforeDeliveringOverBudgetImage() throws Exception {
        byte[] input = document("pdf");
        ConversionOptions options = new ConversionOptions(200, "png", null);
        List<ConversionResult> baseline = new ArrayList<>();
        service.convertPages(input, "example.pdf", options, (page, image) -> baseline.add(image));
        long combinedBytes = baseline.stream().mapToLong(image -> image.bytes().length).sum();
        assertTrue(baseline.stream().allMatch(image -> image.bytes().length < combinedBytes - 1));

        ConversionService exactLimit = new ConversionService(
                new ConversionLimits(1_000_000, 100, 1_000_000, combinedBytes));
        assertEquals(2, exactLimit.convertPages(input, "example.pdf", options, (page, image) -> {}));

        List<Integer> delivered = new ArrayList<>();
        ConversionService aggregateLimit = new ConversionService(
                new ConversionLimits(1_000_000, 100, 1_000_000, combinedBytes - 1));
        ConversionException aggregateFailure = assertThrows(ConversionException.class,
                () -> aggregateLimit.convertPages(input, "example.pdf", options, (page, image) -> delivered.add(page)));
        assertEquals("OUTPUT_LIMIT_EXCEEDED", aggregateFailure.code());
        assertEquals(413, aggregateFailure.statusCode());
        assertEquals(List.of(1), delivered);

        ConversionService singleLimit = new ConversionService(
                new ConversionLimits(1_000_000, 100, 1_000_000, baseline.getFirst().bytes().length - 1L));
        ConversionException singleFailure = assertThrows(ConversionException.class,
                () -> singleLimit.convertPages(input, "example.pdf", options, (page, image) -> fail("No image may exceed the limit.")));
        assertEquals("OUTPUT_LIMIT_EXCEEDED", singleFailure.code());
        assertEquals(413, singleFailure.statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pptx", "ppt", "pdf"})
    void consumerFailurePropagatesUnchangedAndReleasesTheSharedConversionSlot(String type) throws Exception {
        byte[] input = document(type);
        String otherType = type.equals("pdf") ? "pptx" : "pdf";
        byte[] otherInput = document(otherType);
        ConversionOptions options = new ConversionOptions(200, "png", null);
        RuntimeException storageFailure = new IllegalStateException("Temporary storage write failure");
        AtomicInteger attempts = new AtomicInteger();
        RuntimeException caught = assertThrows(RuntimeException.class,
                () -> service.convertPages(input, "example." + type, options, (page, image) -> {
                    attempts.incrementAndGet();
                    throw storageFailure;
                }));
        assertSame(storageFailure, caught);
        assertEquals(1, attempts.get());

        assertEquals(2, service.convertPages(input, "example." + type, options, (page, image) -> {
            ConversionException syncBusy = assertThrows(ConversionException.class,
                    () -> service.convert(input, "another." + type, options));
            assertEquals(503, syncBusy.statusCode());
            assertEquals("CONVERSION_BUSY", syncBusy.code());
            ConversionException pagesBusy = assertThrows(ConversionException.class,
                    () -> service.convertPages(input, "another." + type, options, (ignoredPage, ignoredImage) -> {}));
            assertEquals("CONVERSION_BUSY", pagesBusy.code());
            ConversionException otherFormatBusy = assertThrows(ConversionException.class,
                    () -> service.convert(otherInput, "another." + otherType, options));
            assertEquals(503, otherFormatBusy.statusCode());
            assertEquals("CONVERSION_BUSY", otherFormatBusy.code());
        }));
        assertEquals(2, service.convert(input, "example." + type, options).pageCount());
        assertEquals(2, service.convert(otherInput, "example." + otherType, options).pageCount());
    }

    @Test
    void consumerRunsBeforeRenderingTheNextPdfPage() throws Exception {
        byte[] input;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            addPdfPage(document, Color.RED);
            // Rendering this page would exceed the configured pixel limit.
            document.addPage(new PDPage(new PDRectangle(10_000, 10_000)));
            document.save(bytes);
            input = bytes.toByteArray();
        }
        RuntimeException storageFailure = new IllegalStateException("Pause before the second page");
        AtomicInteger deliveries = new AtomicInteger();
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> service.convertPages(input, "mixed.pdf", new ConversionOptions(null, "png", null), (page, image) -> {
                    assertEquals(1, page);
                    deliveries.incrementAndGet();
                    throw storageFailure;
                }));
        assertSame(storageFailure, failure);
        assertEquals(1, deliveries.get());
    }

    @Test
    void pageConsumerUsesTheSameDocumentAndPageValidation() throws Exception {
        byte[] input = document("pdf");
        ConversionService.PageConsumer neverCalled = (page, image) -> fail("Invalid requests must not deliver images.");
        ConversionService pageLimit = new ConversionService(new ConversionLimits(1_000_000, 1, 1_000_000, 1_000_000));
        assertEquals("PAGE_LIMIT_EXCEEDED", assertThrows(ConversionException.class,
                () -> pageLimit.convertPages(input, "example.pdf", new ConversionOptions(200, "png", 1), neverCalled)).code());
        assertEquals("INVALID_PAGE", assertThrows(ConversionException.class,
                () -> service.convertPages(input, "example.pdf", new ConversionOptions(200, "png", 3), neverCalled)).code());
        assertEquals("INVALID_DOCUMENT", assertThrows(ConversionException.class,
                () -> service.convertPages("%PDF-1.7\nbroken".getBytes(StandardCharsets.US_ASCII), "broken.pdf",
                        new ConversionOptions(null, "png", null), neverCalled)).code());
    }

    private static byte[] document(String type) throws Exception {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            switch (type) {
                case "pptx" -> {
                    try (XMLSlideShow document = new XMLSlideShow()) {
                        document.setPageSize(new Dimension(400, 200));
                        for (Color color : new Color[]{Color.RED, Color.BLUE}) {
                            XSLFAutoShape shape = document.createSlide().createAutoShape();
                            shape.setShapeType(ShapeType.RECT);
                            shape.setAnchor(new Rectangle2D.Double(100, 50, 200, 100));
                            shape.setFillColor(color);
                            shape.setLineColor(color);
                        }
                        document.write(bytes);
                    }
                }
                case "ppt" -> {
                    try (HSLFSlideShow document = new HSLFSlideShow()) {
                        document.setPageSize(new Dimension(400, 200));
                        for (Color color : new Color[]{Color.RED, Color.BLUE}) {
                            HSLFAutoShape shape = new HSLFAutoShape(ShapeType.RECT);
                            shape.setAnchor(new Rectangle2D.Double(100, 50, 200, 100));
                            shape.setFillColor(color);
                            shape.setLineColor(color);
                            document.createSlide().addShape(shape);
                        }
                        document.write(bytes);
                    }
                }
                case "pdf" -> {
                    try (PDDocument document = new PDDocument()) {
                        addPdfPage(document, Color.RED);
                        addPdfPage(document, Color.BLUE);
                        document.save(bytes);
                    }
                }
                default -> throw new IllegalArgumentException(type);
            }
            return bytes.toByteArray();
        }
    }

    private static void addPdfPage(PDDocument document, Color color) throws Exception {
        PDPage page = new PDPage(new PDRectangle(400, 200));
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.setNonStrokingColor(color);
            content.addRect(100, 50, 200, 100);
            content.fill();
        }
    }

    private static Map<String, BufferedImage> unzip(byte[] bytes) throws Exception {
        Map<String, BufferedImage> images = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                images.put(entry.getName(), ImageIO.read(new ByteArrayInputStream(zip.readAllBytes())));
                zip.closeEntry();
            }
        }
        return images;
    }

    private static void assertImage(BufferedImage image, int width, int height, Color foreground) {
        assertNotNull(image);
        assertEquals(width, image.getWidth());
        assertEquals(height, image.getHeight());
        assertColorNear(Color.WHITE, new Color(image.getRGB(5, 5)));
        assertColorNear(foreground, new Color(image.getRGB(width / 2, height / 2)));
    }

    private static void assertColorNear(Color expected, Color actual) {
        assertTrue(Math.abs(expected.getRed() - actual.getRed()) <= 8, () -> "Red: " + actual);
        assertTrue(Math.abs(expected.getGreen() - actual.getGreen()) <= 8, () -> "Green: " + actual);
        assertTrue(Math.abs(expected.getBlue() - actual.getBlue()) <= 8, () -> "Blue: " + actual);
    }

    private static void assertFailure(ConversionService converter, byte[] input, ConversionOptions options,
                                      int status, String code) {
        ConversionException failure = assertThrows(ConversionException.class,
                () -> converter.convert(input, "input.pptx", options));
        assertEquals(status, failure.statusCode(), () -> failure + ": " + failure.getCause());
        assertEquals(code, failure.code());
    }
}
