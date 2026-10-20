package com.convertx2x.office2md.conversion;

import com.convertx2x.office2md.ocr.OcrClient;
import com.convertx2x.office2md.ocr.OcrException;
import com.convertx2x.office2md.ocr.OcrResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompositeImageWorkspaceTest {
    private static final byte[] SOURCE = {1, 2, 3};
    private static final Map<String, Object> META = Map.of("type", "画像", "text", "");

    @Test void ignoreWritesOnlyTheCompositeWithSourceProvenance() throws Exception {
        List<byte[]> calls = new ArrayList<>();
        try (var workspace = new ConversionWorkspace(ConversionLimits.defaults(), ImageMode.IGNORE, client(calls))) {
            String md = workspace.compositeImage(new byte[]{9}, META,
                    List.of(source("picture-1", true)), "Sheet", "A1:F8");
            assertEquals(1, workspace.files().size());
            assertTrue(md.contains("images/image-0001.png"));
            assertFalse(md.contains("OCR"));
            assertEquals(0, calls.size());
            JsonNode report = report(workspace);
            assertEquals(1, report.path("assets").size());
            assertEquals("compositeImage", report.path("assets").get(0).path("sourceKind").asText());
            JsonNode block = report.path("blocks").get(0);
            assertEquals("compositeImage", block.path("type").asText());
            assertEquals("A1:F8", block.path("range").asText());
            assertEquals("picture-1", block.path("sourceImages").get(0).path("metadata").path("id").asText());
            assertEquals(ConversionWorkspace.sha256(SOURCE),
                    block.path("sourceImages").get(0).path("sha256").asText());
        }
    }

    @Test void differentAnnotationsReuseOriginalOcrAndDoNotSendTheComposites() throws Exception {
        List<byte[]> calls = new ArrayList<>();
        try (var workspace = new ConversionWorkspace(ConversionLimits.defaults(), ImageMode.OCR, client(calls))) {
            String first = workspace.compositeImage(new byte[]{9}, META,
                    List.of(source("first", true)), "Sheet", "A1:F8");
            String second = workspace.compositeImage(new byte[]{8}, META,
                    List.of(source("second", true)), "Sheet", "A10:F18");
            assertTrue(first.contains("images/image-0001.png"));
            assertTrue(second.contains("images/image-0002.png"));
            assertTrue(first.contains("&lt;source&gt;"));
            assertFalse(first.contains("<source>"));
            assertTrue(second.contains("&lt;source&gt;"));
            assertEquals(1, calls.size());
            assertArrayEquals(SOURCE, calls.getFirst());
            assertEquals(2, workspace.files().size(), "No separate original-picture file is emitted");
            assertEquals("succeeded", report(workspace).path("blocks").get(1)
                    .path("sourceImages").get(0).path("ocr").path("status").asText());
        }
    }

    @Test void plainPicturesAndCompositesShareOcrButKeepDistinctAssetKinds() throws Exception {
        List<byte[]> calls = new ArrayList<>();
        try (var workspace = new ConversionWorkspace(ConversionLimits.defaults(), ImageMode.OCR, client(calls))) {
            workspace.embeddedImage(SOURCE, "png", "image/png", META, "Sheet", "A1", true);
            workspace.compositeImage(SOURCE, META, List.of(source("same-pixels", true)), "Sheet", "B1");
            assertEquals(1, calls.size());
            JsonNode assets = report(workspace).path("assets");
            assertEquals(2, assets.size());
            assertEquals("embeddedImage", assets.get(0).path("sourceKind").asText());
            assertEquals("compositeImage", assets.get(1).path("sourceKind").asText());
        }
    }

    @Test void identicalPicturesWithinOneCompositeEmitOneOcrParagraph() throws Exception {
        List<byte[]> calls = new ArrayList<>();
        try (var workspace = new ConversionWorkspace(ConversionLimits.defaults(), ImageMode.OCR, client(calls))) {
            String md = workspace.compositeImage(new byte[]{9}, META,
                    List.of(source("first", true), source("second", true)), "Sheet", "A1:J8");
            assertEquals(1, calls.size());
            assertEquals(1, md.split("画像内の文字（OCR）:", -1).length - 1);
            assertEquals(2, report(workspace).path("blocks").get(0).path("sourceImages").size());
        }
    }

    @Test void FailedOrUnsupportedOcrDoesNotRemoveTheComposite() throws Exception {
        OcrClient failing = new OcrClient() {
            public boolean configured() { return true; }
            public OcrResult recognize(byte[] bytes, String type) { throw new OcrException("OCR_TIMEOUT"); }
        };
        try (var workspace = new ConversionWorkspace(ConversionLimits.defaults(), ImageMode.OCR, failing)) {
            String md = workspace.compositeImage(new byte[]{9}, META,
                    List.of(source("unsupported", false), source("failed", true)), "Sheet", "A1:J8");
            assertTrue(md.contains("images/image-0001.png"));
            assertFalse(md.contains("画像内の文字"));
            JsonNode sources = report(workspace).path("blocks").get(0).path("sourceImages");
            assertEquals("skipped", sources.get(0).path("ocr").path("status").asText());
            assertEquals("failed", sources.get(1).path("ocr").path("status").asText());
        }
    }

    @Test void repeatedCompositeReferencesStillRespectMarkdownBudget() {
        var defaults = ConversionLimits.defaults();
        var limits = new ConversionLimits(defaults.maxInputBytes(), defaults.maxSections(), defaults.maxReadItems(),
                defaults.maxTableCells(), 1024, defaults.maxImages(), defaults.maxImageBytes(), defaults.maxOutputBytes(),
                defaults.maxShapes(), defaults.maxGroupDepth(), defaults.maxImagePixels());
        try (var workspace = new ConversionWorkspace(limits, ImageMode.OCR, client(new ArrayList<>()))) {
            var error = assertThrows(ConversionException.class, () -> {
                for (int index = 0; index < 100; index++)
                    workspace.compositeImage(new byte[]{9}, META,
                            List.of(source("same", true)), "Sheet", "A1:F8");
            });
            assertEquals("MARKDOWN_BYTES_LIMIT", error.code());
        }
    }

    private static ConversionWorkspace.CompositeImageSource source(String id, boolean eligible) {
        return new ConversionWorkspace.CompositeImageSource(SOURCE, "png", "image/png",
                Map.of("id", id, "type", "画像", "text", ""), eligible);
    }

    private static OcrClient client(List<byte[]> calls) {
        return new OcrClient() {
            public boolean configured() { return true; }
            public OcrResult recognize(byte[] bytes, String type) {
                calls.add(bytes.clone());
                return new OcrResult("<source>画面内の文字");
            }
        };
    }

    private static JsonNode report(ConversionWorkspace workspace) throws Exception {
        workspace.finishReport("test.xlsx", "input");
        return new ObjectMapper().readTree(workspace.files().get("report.json").toFile());
    }
}
