package com.convertx2x.office2md.conversion;

import com.convertx2x.office2md.ocr.OcrClient;
import com.convertx2x.office2md.ocr.OcrException;
import com.convertx2x.office2md.ocr.OcrResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddedImageOcrTest {
    private static final byte[] PICTURE = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a6XQAAAAASUVORK5CYII=");
    private static final Map<String, Object> METADATA = Map.of("type", "画像", "text", "領収書");

    @Test void ignorePreservesTheImageAndReferenceEvenWhenOcrIsConfigured() throws Exception {
        var calls = new AtomicInteger();
        try (var workspace = new ConversionWorkspace(ConversionLimits.defaults(), ImageMode.IGNORE,
                client(calls, "外部サービスを呼ばない"))) {
            String md = image(workspace, true);
            assertTrue(md.startsWith("!["));
            assertTrue(md.contains("images/image-0001.png"));
            assertFalse(md.contains("OCR"));
            assertArrayEquals(PICTURE, Files.readAllBytes(workspace.files().get("images/image-0001.png")));
            assertEquals(0, calls.get());
            var report = report(workspace);
            assertEquals("ignore", report.path("imageMode").asText());
            assertEquals("embeddedImage", report.path("assets").get(0).path("sourceKind").asText());
            assertEquals("notRequested", report.path("blocks").get(0).path("ocr").path("status").asText());
        }
    }

    @Test void ocrIsPlacedAfterEachImageAndIdenticalVisibleImagesAreRecognizedOnce() throws Exception {
        var calls = new AtomicInteger();
        try (var workspace = new ConversionWorkspace(ConversionLimits.defaults(), ImageMode.OCR,
                client(calls, "請求金額：1,200円\n<script>危険</script>\n![外部](https://example.invalid/a.png)"))) {
            String first = image(workspace, true), second = image(workspace, true);
            assertEquals(first, second);
            assertTrue(first.indexOf("image-0001.png") < first.indexOf("請求金額"));
            assertTrue(first.contains("image-0001.png)\n\n画像内の文字（OCR）:\n\n請求金額"));
            assertTrue(first.contains("&lt;script&gt;"));
            assertFalse(first.contains("<script>"));
            assertFalse(first.contains("![外部]"));
            assertEquals(1, calls.get());
            var report = report(workspace);
            assertEquals(1, report.path("assets").size());
            assertEquals(2, report.path("blocks").size());
            assertEquals("succeeded", report.path("blocks").get(1).path("ocr").path("status").asText());
            assertEquals("ocr", report.path("imageMode").asText());
        }
    }

    @Test void generatedDiagramsAreNeverSentToOcr() throws Exception {
        var calls = new AtomicInteger();
        try (var workspace = new ConversionWorkspace(ConversionLimits.defaults(), ImageMode.OCR, client(calls, "文字"))) {
            workspace.addAsset("diagram", PICTURE, "png", "image/png");
            assertEquals(0, calls.get());
            assertEquals("renderedDiagram", report(workspace).path("assets").get(0).path("sourceKind").asText());
        }
    }

    @Test void failedOcrKeepsTheImageAndSafeWarningAndDoesNotRetryDuplicatePlacement() throws Exception {
        var calls = new AtomicInteger();
        OcrClient client = new OcrClient() {
            public boolean configured() { return true; }
            public OcrResult recognize(byte[] image, String contentType) {
                calls.incrementAndGet();
                throw new OcrException("OCR_TIMEOUT");
            }
        };
        try (var workspace = new ConversionWorkspace(ConversionLimits.defaults(), ImageMode.OCR, client)) {
            assertTrue(image(workspace, true).contains("image-0001.png"));
            assertTrue(image(workspace, true).contains("image-0001.png"));
            assertEquals(1, calls.get());
            var report = report(workspace);
            assertEquals("failed", report.path("blocks").get(0).path("ocr").path("status").asText());
            assertEquals("OCR_TIMEOUT", report.path("warnings").get(0).path("code").asText());
        }
    }

    @Test void unsafeVisibleImagePreparationSkipsOcrWithoutDroppingTheReference() throws Exception {
        var calls = new AtomicInteger();
        try (var workspace = new ConversionWorkspace(ConversionLimits.defaults(), ImageMode.OCR, client(calls, "hidden text"))) {
            assertTrue(image(workspace, false).contains("image-0001.png"));
            assertEquals(0, calls.get());
            var report = report(workspace);
            assertEquals("skipped", report.path("blocks").get(0).path("ocr").path("status").asText());
            assertEquals("OCR_IMAGE_UNSUPPORTED", report.path("warnings").get(0).path("code").asText());
        }
    }

    @Test void emptyOcrDoesNotInventTextAndRetainsThePicture() throws Exception {
        try (var workspace = new ConversionWorkspace(ConversionLimits.defaults(), ImageMode.OCR,
                client(new AtomicInteger(), " \n "))) {
            String md = image(workspace, true);
            assertTrue(md.contains("image-0001.png"));
            assertFalse(md.contains("画像内の文字"));
            assertEquals("noText", report(workspace).path("blocks").get(0).path("ocr").path("status").asText());
            assertEquals(0, workspace.warningCount());
        }
    }

    @Test void unconfiguredOcrFailsExplicitlyAndPdfIsNoLongerAnArtifact() {
        var service = new OfficeMarkdownService(ConversionLimits.defaults());
        var error = assertThrows(ConversionException.class, () -> service.validateImageMode(ImageMode.OCR));
        assertEquals(503, error.statusCode());
        assertEquals("OCR_NOT_CONFIGURED", error.code());
        assertDoesNotThrow(() -> service.validateImageMode(ImageMode.IGNORE));
        try (var workspace = new ConversionWorkspace(ConversionLimits.defaults())) {
            assertThrows(IllegalArgumentException.class, () -> workspace.write("document.pdf", new byte[1]));
        }
    }

    @Test void repeatedCachedTextIsBoundedBeforeIntermediateDrawingBlocksAccumulate() {
        var defaults = ConversionLimits.defaults();
        var limits = new ConversionLimits(defaults.maxInputBytes(), defaults.maxSections(), defaults.maxReadItems(),
                defaults.maxTableCells(), 1024, defaults.maxImages(), defaults.maxImageBytes(), defaults.maxOutputBytes(),
                defaults.maxShapes(), defaults.maxGroupDepth(), defaults.maxImagePixels());
        var calls = new AtomicInteger();
        try (var workspace = new ConversionWorkspace(limits, ImageMode.OCR, client(calls, "請求書の文字".repeat(20)))) {
            image(workspace, true);
            var error = assertThrows(ConversionException.class, () -> {
                for (int i = 0; i < 20; i++) image(workspace, true);
            });
            assertEquals("MARKDOWN_BYTES_LIMIT", error.code());
            assertEquals(1, calls.get(), "Placement limits must work even with a single cached OCR response");
        }
    }

    @Test void drawingReadersShareDuplicateBuffersAndBoundUniquePreparedImages() {
        var defaults = ConversionLimits.defaults();
        var limits = new ConversionLimits(defaults.maxInputBytes(), defaults.maxSections(), defaults.maxReadItems(),
                defaults.maxTableCells(), defaults.maxMarkdownBytes(), defaults.maxImages(), defaults.maxImageBytes(),
                PICTURE.length + 1, defaults.maxShapes(), defaults.maxGroupDepth(), defaults.maxImagePixels());
        try (var workspace = new ConversionWorkspace(limits)) {
            byte[] first = workspace.retainImageBytes(PICTURE.clone());
            assertSame(first, workspace.retainImageBytes(PICTURE.clone()));
            byte[] different = PICTURE.clone(); different[0] = 0;
            assertEquals("OUTPUT_BYTES_LIMIT", assertThrows(ConversionException.class,
                    () -> workspace.retainImageBytes(different)).code());
        }
    }

    private static String image(ConversionWorkspace workspace, boolean eligible) {
        return workspace.embeddedImage(PICTURE, "png", "image/png", METADATA, "Sheet1", "B2", eligible);
    }
    private static com.fasterxml.jackson.databind.JsonNode report(ConversionWorkspace workspace) throws Exception {
        workspace.finishReport("test.xlsx", "source");
        return new ObjectMapper().readTree(workspace.files().get("report.json").toFile());
    }
    private static OcrClient client(AtomicInteger calls, String text) {
        return new OcrClient() {
            public boolean configured() { return true; }
            public OcrResult recognize(byte[] image, String contentType) {
                calls.incrementAndGet();
                return new OcrResult(text);
            }
        };
    }
}
