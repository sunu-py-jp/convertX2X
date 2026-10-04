import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.DocumentDocument;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.FootnotesDocument;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.NumberingDocument;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.StylesDocument;

/** Standalone editable DOCX fixture. Run from functions/office2md with the staged runtime jars. */
public final class WordSample {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length == 0 ? "target/fixtures/sample.docx" : args[0]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (XWPFDocument document = new XWPFDocument()) {
            document.createStyles().setStyles(StylesDocument.Factory.parse("<w:styles xmlns:w=\"" + W + "\">" + """
                    <w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Noto Sans CJK JP" w:eastAsia="Noto Sans CJK JP"/><w:sz w:val="24"/></w:rPr></w:rPrDefault></w:docDefaults>
                    <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:pPr><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:color w:val="16324F"/><w:sz w:val="40"/></w:rPr></w:style>
                    <w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:pPr><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:color w:val="087F8C"/><w:sz w:val="30"/></w:rPr></w:style>
                    </w:styles>
                    """).getStyles());
            document.createNumbering().setNumbering(NumberingDocument.Factory.parse("<w:numbering xmlns:w=\"" + W + "\">" + """
                    <w:abstractNum w:abstractNumId="1"><w:lvl w:ilvl="0"><w:start w:val="3"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl></w:abstractNum>
                    <w:num w:numId="1"><w:abstractNumId w:val="1"/></w:num>
                    </w:numbering>
                    """).getNumbering());
            String body = """
                    <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Word → Markdown の表示確認</w:t></w:r></w:p>
                    <w:p><w:r><w:t>日本語の通常文です。見た目の文字サイズから見出しは推測しません。</w:t></w:r></w:p>
                    <w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>文字と最終版の変更履歴</w:t></w:r></w:p>
                    <w:p><w:r><w:t>太字：</w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>重要な日本語</w:t></w:r>
                    <w:r><w:t> ／ </w:t></w:r><w:hyperlink r:id="rOfficial"><w:r><w:t>Apache POI 公式</w:t></w:r></w:hyperlink>
                    <w:r><w:rPr><w:strike/></w:rPr><w:t>WORD_STRIKE_SECRET_REMOVED</w:t></w:r></w:p>
                    <w:p><w:r><w:t>保存済みの最終版：</w:t></w:r><w:ins w:id="1" w:author="Example"><w:r><w:t>追加した文を保持します。</w:t></w:r></w:ins>
                    <w:del w:id="2" w:author="Example"><w:r><w:delText>WORD_REVISION_SECRET_REMOVED</w:delText></w:r></w:del></w:p>
                    <w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>罫線のない Word の表</w:t></w:r></w:p>
                    <w:tbl><w:tblPr><w:tblBorders><w:top w:val="nil"/><w:left w:val="nil"/><w:bottom w:val="nil"/><w:right w:val="nil"/><w:insideH w:val="nil"/><w:insideV w:val="nil"/></w:tblBorders></w:tblPr>
                    <w:tblGrid><w:gridCol w:w="3500"/><w:gridCol w:w="5000"/></w:tblGrid>
                    <w:tr><w:trPr><w:tblHeader/></w:trPr><w:tc><w:p><w:r><w:t>項目</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>結果</w:t></w:r></w:p></w:tc></w:tr>
                    <w:tr><w:tc><w:p><w:r><w:t>表の構造</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>罫線がなくても Markdown 表になります。</w:t></w:r></w:p></w:tc></w:tr>
                    <w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>結合セルは左上に文字を保持します。</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                    <w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>判定条件と複数段落の表</w:t></w:r></w:p>
                    <w:p><w:r><w:t>二つ目の表では、3列の判定、セル内改行、結合した補足行を同時に確認します。</w:t></w:r></w:p>
                    <w:tbl><w:tblPr><w:tblBorders><w:top w:val="single"/><w:bottom w:val="single"/><w:insideH w:val="single"/><w:insideV w:val="single"/></w:tblBorders></w:tblPr>
                    <w:tblGrid><w:gridCol w:w="2600"/><w:gridCol w:w="2600"/><w:gridCol w:w="3300"/></w:tblGrid>
                    <w:tr><w:trPr><w:tblHeader/></w:trPr><w:tc><w:p><w:r><w:t>条件</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>担当</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>判断</w:t></w:r></w:p></w:tc></w:tr>
                    <w:tr><w:tc><w:p><w:r><w:t>通常申請</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>受付</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>当日確認</w:t></w:r></w:p></w:tc></w:tr>
                    <w:tr><w:tc><w:p><w:r><w:t>添付不足</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>申請者</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>差し戻し</w:t></w:r></w:p><w:p><w:r><w:t>再提出後に再判定</w:t></w:r></w:p></w:tc></w:tr>
                    <w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>共通の補足：期限は営業日で数えます。</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>要確認</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                    <w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>番号と脚注</w:t></w:r></w:p>
                    <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>番号は元の開始値 3 を保持します。</w:t></w:r></w:p>
                    <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>参照付きの脚注です。</w:t><w:footnoteReference w:id="1"/></w:r></w:p>
                    <w:p><w:r><w:t>判定表の例外条件にも補足を付けます。</w:t><w:footnoteReference w:id="2"/></w:r></w:p>
                    <w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>時計回りに回転した日本語の図形</w:t></w:r></w:p>
                    <w:p><w:r><w:pict><v:roundrect style="width:280pt;height:100pt;rotation:15" arcsize="0.15" fillcolor="#e4f4f1" strokecolor="#087f8c">
                    <v:textbox><w:txbxContent><w:p><w:r><w:rPr><w:b/><w:sz w:val="32"/></w:rPr><w:t>日本語の図形</w:t></w:r></w:p>
                    <w:p><w:r><w:t>時計回り 15 度</w:t></w:r><w:r><w:rPr><w:strike/></w:rPr><w:t>WORD_SHAPE_SECRET_REMOVED</w:t></w:r></w:p></w:txbxContent></v:textbox>
                    </v:roundrect></w:pict></w:r></w:p>
                    <w:p><w:r><w:pict><v:oval style="width:190pt;height:95pt" fillcolor="#fff0d6" strokecolor="#b1792e">
                    <v:textbox><w:txbxContent><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>追加の確認図形</w:t></w:r></w:p>
                    <w:p><w:r><w:t>表の例外を人が確認</w:t></w:r></w:p></w:txbxContent></v:textbox>
                    </v:oval></w:pict></w:r></w:p>
                    <w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>埋め込み PNG 画像</w:t></w:r></w:p>
                    """;
            document.getDocument().setBody(DocumentDocument.Factory.parse("<w:document xmlns:w=\"" + W + "\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:v=\"urn:schemas-microsoft-com:vml\"><w:body>" + body + "</w:body></w:document>").getDocument().getBody());
            document.getPackagePart().addExternalRelationship("https://poi.apache.org/", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink", "rOfficial");
            document.createFootnotes();
            document.addFootnote(FootnotesDocument.Factory.parse("<w:footnotes xmlns:w=\"" + W + "\"><w:footnote w:id=\"1\"><w:p><w:r><w:footnoteRef/><w:t>脚注は本文の参照順に末尾へまとめます。</w:t></w:r></w:p></w:footnote></w:footnotes>").getFootnotes().getFootnoteArray(0));
            document.addFootnote(FootnotesDocument.Factory.parse("<w:footnotes xmlns:w=\"" + W + "\"><w:footnote w:id=\"2\"><w:p><w:r><w:footnoteRef/><w:t>例外条件は再提出された内容を確認してから処理します。</w:t></w:r></w:p></w:footnote></w:footnotes>").getFootnotes().getFootnoteArray(0));
            BufferedImage image = new BufferedImage(540, 180, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 540, 180);
                graphics.setColor(new Color(22, 50, 79)); graphics.fillRoundRect(16, 16, 152, 148, 28, 28);
                graphics.setColor(new Color(8, 127, 140)); graphics.fillOval(195, 16, 148, 148);
                graphics.setColor(new Color(211, 160, 54)); graphics.fillPolygon(new int[]{375, 525, 450}, new int[]{164, 164, 16}, 3);
            } finally { graphics.dispose(); }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png); image.flush();
            document.createParagraph().createRun().addPicture(new ByteArrayInputStream(png.toByteArray()), XWPFDocument.PICTURE_TYPE_PNG, "shapes.png", Units.toEMU(360), Units.toEMU(120));
            try (var stream = Files.newOutputStream(output)) { document.write(stream); }
        }
        System.out.println(output.toAbsolutePath());
    }
}
