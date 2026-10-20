package com.convertx2x.excel2img.conversion;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.poi.ss.util.CellRangeAddress;

/** Immutable transport-neutral selection. A range never contains a sheet or an unbounded column/row. */
public record RenderOptions(String format, String sheet, String range) {
    private static final Pattern RECTANGLE = Pattern.compile(
            "\\$?[A-Za-z]{1,3}\\$?[1-9][0-9]{0,6}(?::\\$?[A-Za-z]{1,3}\\$?[1-9][0-9]{0,6})?");

    public RenderOptions {
        format = format == null || format.isBlank() ? "png" : format.trim().toLowerCase(Locale.ROOT);
        if (!format.equals("png") && !format.equals("pdf"))
            throw invalid("format は png または pdf を指定してください。");
        sheet = sheet == null || sheet.isEmpty() ? null : sheet;
        if (sheet != null && (sheet.length() > 31 || sheet.chars().anyMatch(c -> Character.isISOControl(c) ||
                ":/\\?*[]".indexOf(c) >= 0) || sheet.startsWith("'") || sheet.endsWith("'")))
            throw invalid("sheet は有効な Excel シート名を指定してください。");
        range = range == null || range.isBlank() ? null : range.trim();
        if (range != null) {
            if (sheet == null) throw invalid("range を指定する場合は sheet も指定してください。");
            if (!RECTANGLE.matcher(range).matches()) throw invalid("range は A1:D20 のような有限のセル範囲で指定してください。");
            try {
                CellRangeAddress address = CellRangeAddress.valueOf(range);
                address.validate(org.apache.poi.ss.SpreadsheetVersion.EXCEL2007);
                range = address.formatAsString();
            } catch (IllegalArgumentException failure) {
                throw invalid("range が Excel のセル範囲を超えているか、始点と終点が逆です。");
            }
        }
    }

    public static RenderOptions defaults() { return new RenderOptions("png", null, null); }
    public static RenderOptions from(Map<String, String> parameters) {
        if (parameters == null) return defaults();
        return new RenderOptions(parameters.get("format"), parameters.get("sheet"), parameters.get("range"));
    }
    public static RenderOptions parse(Map<String, String> parameters) { return from(parameters); }
    private static ConversionException invalid(String message) {
        return new ConversionException(400, "INVALID_RENDER_OPTIONS", message);
    }
}
