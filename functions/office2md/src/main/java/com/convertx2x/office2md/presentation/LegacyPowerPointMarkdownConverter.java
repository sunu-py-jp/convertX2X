package com.convertx2x.office2md.presentation;

import com.convertx2x.office2md.conversion.*;
import com.convertx2x.office2md.ocr.OcrClient;
import java.io.ByteArrayInputStream;
import java.util.Objects;
import org.apache.poi.OldFileFormatException;
import org.apache.poi.hslf.record.Record;
import org.apache.poi.hslf.record.RecordTypes;
import org.apache.poi.hslf.record.VBAInfoAtom;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/**
 * PowerPoint 97-2003 (.ppt) entry. It validates the binary container and then uses the same
 * {@link SlideMarkdownReader} as PPTX, with {@link BinarySlideDialect} for the format-specific reads.
 */
public final class LegacyPowerPointMarkdownConverter implements OfficeMarkdownConverter {
    private static final String NOT_BINARY_POWERPOINT = "PowerPoint 97-2003形式（.ppt）のファイルを指定してください。";
    private final ConversionLimits limits;

    public LegacyPowerPointMarkdownConverter(ConversionLimits limits) { this.limits = Objects.requireNonNull(limits); }

    public ConversionResult convert(byte[] input, String filename) {
        return convert(input, filename, ImageMode.IGNORE, OcrClient.disabled());
    }

    @Override
    public ConversionResult convert(byte[] input, String filename, ImageMode imageMode, OcrClient ocrClient) {
        SlideMarkdownReader.checkInput(input, limits);
        if (FileMagic.valueOf(input) != FileMagic.OLE2)
            throw new ConversionException(415, "UNSUPPORTED_DOCUMENT", NOT_BINARY_POWERPOINT);
        ConversionWorkspace workspace = new ConversionWorkspace(limits, imageMode, ocrClient);
        try (POIFSFileSystem filesystem = new POIFSFileSystem(new ByteArrayInputStream(input))) {
            // An OLE2 container also wraps password-protected OOXML and the other binary Office formats.
            if (filesystem.getRoot().hasEntry("EncryptedPackage"))
                throw new ConversionException(422, "ENCRYPTED_DOCUMENT", "暗号化されたファイルは変換できません。");
            if (!filesystem.getRoot().hasEntry("PowerPoint Document"))
                throw new ConversionException(415, "UNSUPPORTED_DOCUMENT", NOT_BINARY_POWERPOINT);
            try (HSLFSlideShow presentation = new HSLFSlideShow(filesystem)) {
                if (hasMacros(presentation))
                    throw new ConversionException(415, "UNSUPPORTED_DOCUMENT", "マクロを含むPowerPointファイルは変換対象外です。");
                new SlideMarkdownReader(limits, new BinarySlideDialect()).read(presentation, workspace);
            }
            workspace.finishReport(SlideMarkdownReader.safeFilename(filename, "presentation.ppt"), ConversionWorkspace.sha256(input));
            return new ConversionResult(workspace);
        } catch (OldFileFormatException failure) {
            throw SlideMarkdownReader.failed(new ConversionException(415, "UNSUPPORTED_DOCUMENT",
                    "PowerPoint 95以前の形式には対応していません。PowerPoint 97-2003形式（.ppt）または .pptx で保存し直してください。", failure), workspace);
        } catch (Throwable failure) {
            throw SlideMarkdownReader.failed(failure, workspace);
        }
    }

    /**
     * The document's information list records whether a VBA project is stored. Every file has that record,
     * so only a reference to a project storage or the macro flag counts; the storage itself is never opened.
     */
    private static boolean hasMacros(HSLFSlideShow presentation) {
        for (Record record : presentation.getDocumentRecord().getChildRecords()) {
            if (record.getRecordType() != RecordTypes.List.typeID || record.getChildRecords() == null) continue;
            for (Record entry : record.getChildRecords()) {
                if (entry.getRecordType() != RecordTypes.VBAInfo.typeID || entry.getChildRecords() == null) continue;
                for (Record atom : entry.getChildRecords())
                    if (atom instanceof VBAInfoAtom info && (info.isHasMacros() || info.getPersistIdRef() != 0)) return true;
            }
        }
        return false;
    }
}
