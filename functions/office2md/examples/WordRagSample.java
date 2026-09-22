import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.DocumentDocument;

/** Native editable DrawingML: text, explicit connections and a deliberately unconnected arrow. */
public final class WordRagSample {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final int EMU = 12700;

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length == 0 ? "target/fixtures/word-rag-flow.docx" : args[0]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        String nodes = shape(10, 16, 32, "申請者", "申請を作成", "DCECF9", false)
                + shape(20, 206, 32, "担当者", "内容を確認", "DDF3ED", false)
                + shape(30, 396, 32, "完了", "登録・通知", "EEE8FC", false)
                + shape(40, 206, 172, "申請者", "修正して再申請", "FFF0D6", true)
                + shape(90, 396, 172, "HIDDEN_SECRET_RAG_DEMO", "非表示", "FFFFFF", false)
                    .replace("id='90' name='shape-90'", "id='90' name='shape-90' hidden='1'");
        String connectors = connector(50, 136, 62, 206, 62, "10", "20")
                + connector(51, 326, 62, 396, 62, "20", "30")
                + connector(52, 266, 92, 266, 172, "20", "40")
                + connector(53, 206, 202, 76, 92, "40", "10")
                + connector(54, 40, 282, 470, 282, null, null);
        String drawing = "<w:p><w:r><w:drawing><wp:inline distT='0' distB='0' distL='0' distR='0'>"
                + "<wp:extent cx='" + 540 * EMU + "' cy='" + 330 * EMU + "'/><wp:docPr id='1' name='購入申請フロー'/>"
                + "<a:graphic><a:graphicData uri='http://schemas.microsoft.com/office/word/2010/wordprocessingGroup'>"
                + "<wpg:wgp><wpg:cNvGrpSpPr/><wpg:grpSpPr><a:xfrm><a:off x='0' y='0'/>"
                + "<a:ext cx='" + 540 * EMU + "' cy='" + 330 * EMU + "'/><a:chOff x='0' y='0'/>"
                + "<a:chExt cx='" + 540 * EMU + "' cy='" + 330 * EMU + "'/></a:xfrm></wpg:grpSpPr>"
                + nodes + connectors + "</wpg:wgp></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>";
        String body = paragraph("Word / 購入申請フロー", true)
                + paragraph("本文の前後関係を保ち、図形の文字と保存された接続関係を検索可能な本文に残す例です。", false)
                + drawing
                + paragraph("図の後の説明：上段は確認から完了、下段は修正・再申請です。最下段の矢印は接続先を保存していないため、不明として扱います。", false)
                + "<w:tbl><w:tblGrid><w:gridCol w:w='3000'/><w:gridCol w:w='6000'/></w:tblGrid>"
                + row("記録", "保存される内容", true) + row("Markdown", "図中の文字・確定した関係・不明な接続", false)
                + row("report.json", "図形ID・種類・座標・接続先・方向", false) + "</w:tbl>"
                + "<w:sectPr><w:pgSz w:w='12240' w:h='15840'/><w:pgMar w:top='720' w:right='720' w:bottom='720' w:left='720'/></w:sectPr>";
        String xml = "<w:document xmlns:w='" + W + "' xmlns:wp='http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing'"
                + " xmlns:a='http://schemas.openxmlformats.org/drawingml/2006/main'"
                + " xmlns:wps='http://schemas.microsoft.com/office/word/2010/wordprocessingShape'"
                + " xmlns:wpg='http://schemas.microsoft.com/office/word/2010/wordprocessingGroup'><w:body>" + body + "</w:body></w:document>";
        try (var document = new XWPFDocument()) {
            document.getDocument().setBody(DocumentDocument.Factory.parse(xml).getDocument().getBody());
            try (var stream = Files.newOutputStream(output)) { document.write(stream); }
        }
        System.out.println(output.toAbsolutePath());
    }

    private static String paragraph(String text, boolean heading) {
        return "<w:p>" + (heading ? "<w:pPr><w:outlineLvl w:val='0'/></w:pPr>" : "")
                + "<w:r><w:rPr><w:rFonts w:ascii='Noto Sans CJK JP' w:eastAsia='Noto Sans CJK JP'/>"
                + (heading ? "<w:b/><w:sz w:val='36'/>" : "<w:sz w:val='22'/>")
                + "</w:rPr><w:t>" + text + "</w:t></w:r></w:p>";
    }
    private static String row(String a, String b, boolean header) {
        return "<w:tr>" + (header ? "<w:trPr><w:tblHeader/></w:trPr>" : "")
                + "<w:tc>" + paragraph(a, false) + "</w:tc><w:tc>" + paragraph(b, false) + "</w:tc></w:tr>";
    }
    private static String shape(int id, int x, int y, String title, String subtitle, String color, boolean strike) {
        String text = "<w:p><w:pPr><w:jc w:val='center'/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val='26'/>"
                + "<w:color w:val='18344E'/><w:rFonts w:ascii='Noto Sans CJK JP' w:eastAsia='Noto Sans CJK JP'/></w:rPr><w:t>"
                + title + "</w:t></w:r></w:p><w:p><w:pPr><w:jc w:val='center'/></w:pPr><w:r><w:rPr><w:sz w:val='20'/></w:rPr><w:t>"
                + subtitle + "</w:t></w:r>" + (strike ? "<w:r><w:rPr><w:strike/></w:rPr><w:t>STRIKE_SECRET_RAG_DEMO</w:t></w:r>" : "") + "</w:p>";
        return "<wps:wsp><wps:cNvPr id='" + id + "' name='shape-" + id + "'/><wps:cNvSpPr/>"
                + "<wps:spPr><a:xfrm><a:off x='" + x * EMU + "' y='" + y * EMU + "'/><a:ext cx='" + 120 * EMU + "' cy='" + 60 * EMU + "'/></a:xfrm>"
                + "<a:prstGeom prst='roundRect'><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val='" + color + "'/></a:solidFill>"
                + "<a:ln w='19050'><a:solidFill><a:srgbClr val='39758B'/></a:solidFill></a:ln></wps:spPr>"
                + "<wps:txbx><w:txbxContent>" + text + "</w:txbxContent></wps:txbx><wps:bodyPr anchor='ctr'/></wps:wsp>";
    }
    private static String connector(int id, int x1, int y1, int x2, int y2, String start, String end) {
        String targets = start == null ? "" : "<a:stCxn id='" + start + "' idx='3'/><a:endCxn id='" + end + "' idx='1'/>";
        return "<wps:wsp><wps:cNvPr id='" + id + "' name='connector-" + id + "'/><wps:cNvCnPr>" + targets + "</wps:cNvCnPr>"
                + "<wps:spPr><a:xfrm flipH='" + (x2 < x1 ? "1" : "0") + "' flipV='" + (y2 < y1 ? "1" : "0") + "'>"
                + "<a:off x='" + Math.min(x1, x2) * EMU + "' y='" + Math.min(y1, y2) * EMU + "'/>"
                + "<a:ext cx='" + Math.abs(x2 - x1) * EMU + "' cy='" + Math.abs(y2 - y1) * EMU + "'/></a:xfrm>"
                + "<a:prstGeom prst='line'><a:avLst/></a:prstGeom><a:noFill/><a:ln w='25400'>"
                + "<a:solidFill><a:srgbClr val='39758B'/></a:solidFill><a:tailEnd type='triangle'/></a:ln></wps:spPr><wps:bodyPr/></wps:wsp>";
    }
}
