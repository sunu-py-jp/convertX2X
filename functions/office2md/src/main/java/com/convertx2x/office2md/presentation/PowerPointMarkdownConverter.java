package com.convertx2x.office2md.presentation;

import com.convertx2x.office2md.conversion.*;
import com.convertx2x.office2md.ocr.OcrClient;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

/** RAG-oriented PPTX text and saved connections, with a slide preview. Never follows external content. */
public final class PowerPointMarkdownConverter implements OfficeMarkdownConverter {
    private static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml";
    private final ConversionLimits limits;

    public PowerPointMarkdownConverter(ConversionLimits limits) { this.limits = Objects.requireNonNull(limits); }

    public ConversionResult convert(byte[] input, String filename) {
        return convert(input, filename, ImageMode.IGNORE, OcrClient.disabled());
    }

    @Override
    public ConversionResult convert(byte[] input, String filename, ImageMode imageMode, OcrClient ocrClient) {
        SlideMarkdownReader.checkInput(input, limits);
        if (FileMagic.valueOf(input) == FileMagic.OLE2) {
            try (POIFSFileSystem filesystem = new POIFSFileSystem(new ByteArrayInputStream(input))) {
                if (filesystem.getRoot().hasEntry("EncryptedPackage"))
                    throw new ConversionException(422, "ENCRYPTED_DOCUMENT", "暗号化されたファイルは変換できません。");
            } catch (IOException failure) {
                throw new ConversionException(422, "INVALID_DOCUMENT", "ファイルを読み取れませんでした。", failure);
            }
            throw new ConversionException(415, "UNSUPPORTED_DOCUMENT", "PowerPointのPPTX形式を指定してください。");
        }
        ConversionWorkspace workspace = new ConversionWorkspace(limits, imageMode, ocrClient);
        try (OPCPackage archive = OPCPackage.open(new ByteArrayInputStream(input))) {
            if (archive.getPartsByContentType(CONTENT_TYPE).isEmpty())
                throw new ConversionException(415, "UNSUPPORTED_DOCUMENT", "PowerPointのPPTX形式を指定してください。");
            for (var part : archive.getParts()) {
                String contentType = part.getContentType().toLowerCase(Locale.ROOT);
                String partName = part.getPartName().getName().toLowerCase(Locale.ROOT);
                if (contentType.contains("vbaproject") || partName.endsWith("/vbaproject.bin"))
                    throw new ConversionException(415, "UNSUPPORTED_DOCUMENT", "マクロを含むPowerPointファイルは変換対象外です。");
            }
            try (XMLSlideShow presentation = new XMLSlideShow(archive)) {
                new SlideMarkdownReader(limits, new OoxmlSlideDialect()).read(presentation, workspace);
            }
            workspace.finishReport(SlideMarkdownReader.safeFilename(filename, "presentation.pptx"), ConversionWorkspace.sha256(input));
            return new ConversionResult(workspace);
        } catch (Throwable failure) {
            throw SlideMarkdownReader.failed(failure, workspace);
        }
    }
}
