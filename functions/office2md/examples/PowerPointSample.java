import org.apache.poi.sl.usermodel.*;
import org.apache.poi.xslf.usermodel.*;
import org.openxmlformats.schemas.presentationml.x2006.main.CTShape;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Generates native editable PowerPoint text, tables and drawings for actual conversion checks. */
public final class PowerPointSample {
    public static void main(String[] args) throws Exception {
        Path target = Path.of(args.length == 0 ? "target/fixtures/sample.pptx" : args[0]);
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        try (XMLSlideShow deck = new XMLSlideShow()) {
            deck.setPageSize(new Dimension(960, 540));
            XSLFSlide overview = deck.createSlide();
            title(overview, "PowerPointからMarkdownへ");
            text(overview, "日本語の本文・箇条書き・表・図・画像の実変換サンプルです。", 40, 100, 850, 60);
            XSLFTextBox bullets = text(overview, "保存した番号を保つ", 40, 180, 750, 200);
            bullets.getTextParagraphs().getFirst().setBulletAutoNumber(AutoNumberingScheme.arabicPeriod, 3);
            var next = bullets.addNewTextParagraph(); next.setBulletAutoNumber(AutoNumberingScheme.arabicPeriod, 4);
            next.addNewTextRun().setText("外部コンテンツを取得しない");
            var third = bullets.addNewTextParagraph(); third.setBullet(true); third.addNewTextRun().setText("日本語と English ABC123 を保持");
            var struck = third.addNewTextRun(); struck.setText("削除対象の本文"); struck.setStrikethrough(true);
            text(overview, "資料へのリンク", 40, 400, 350, 50).getTextParagraphs().getFirst().getTextRuns().getFirst()
                    .createHyperlink().setAddress("https://example.com/reference");
            deck.getNotesSlide(overview).createTextBox().setText("ノートは出力しない");

            XSLFSlide tables = deck.createSlide(); title(tables, "罫線なしの表もMarkdown表に");
            XSLFTable table = tables.createTable(4, 3); table.setAnchor(new Rectangle2D.Double(40, 120, 870, 280));
            String[][] values = {{"担当", "内容", "状態"}, {"開発", "日本語と保存済み書式", "完了"}, {"検証", "入力ファイルから実際に変換", "実施中"}, {"結合セルは開始セルだけに残す", "", ""}};
            for (int r = 0; r < values.length; r++) {
                table.setRowHeight(r, 60);
                for (int c = 0; c < 3; c++) {
                    table.setColumnWidth(c, 290);
                    table.getCell(r, c).setText(values[r][c]).setFontSize(22.0);
                    for (var edge : TableCell.BorderEdge.values()) table.getCell(r, c).removeBorder(edge);
                }
            }
            table.mergeCells(3, 3, 0, 2);

            XSLFSlide diagram = deck.createSlide(); title(diagram, "図は画像と説明文として残す");
            XSLFGroupShape group = diagram.createGroup(); group.setAnchor(new Rectangle2D.Double(60, 130, 800, 230));
            group.setInteriorAnchor(new Rectangle2D.Double(0, 0, 800, 230));
            XSLFAutoShape first = group.createAutoShape(); first.setShapeType(ShapeType.ROUND_RECT);
            first.setAnchor(new Rectangle2D.Double(0, 40, 280, 140)); first.setFillColor(new Color(225, 232, 255));
            first.setText("入力ファイル").setFontSize(28.0); first.setRotation(5);
            first.getTextParagraphs().getFirst().getTextRuns().getFirst().createHyperlink().setAddress("https://example.com/input");
            var removed = first.getTextParagraphs().getFirst().addNewTextRun(); removed.setText("取消線の秘密"); removed.setStrikethrough(true);
            XSLFAutoShape second = group.createAutoShape(); second.setShapeType(ShapeType.ELLIPSE);
            second.setAnchor(new Rectangle2D.Double(480, 40, 280, 140)); second.setFillColor(new Color(235, 222, 255));
            second.setText("Markdownと画像").setFontSize(26.0);
            XSLFConnectorShape connector = group.createConnector(); connector.setAnchor(new Rectangle2D.Double(280, 110, 200, 0)); connector.setLineColor(new Color(50, 40, 100)); connector.setLineWidth(3);
            XSLFTextBox hidden = group.createTextBox(); hidden.setText("非表示の秘密"); hidden.setAnchor(new Rectangle2D.Double(300, 10, 200, 40));
            ((CTShape) hidden.getXmlObject()).getNvSpPr().getCNvPr().setHidden(true);
            text(diagram, "図内の文字は画像説明に含め、本文に重ねて出力しません。", 50, 410, 860, 60);

            XSLFSlide images = deck.createSlide(); title(images, "保存済みの埋め込み画像");
            BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics(); graphics.setColor(new Color(35, 36, 75)); graphics.fillRect(0, 0, 320, 180);
            graphics.setColor(new Color(159, 120, 245)); graphics.fillOval(120, 35, 110, 110); graphics.dispose();
            ByteArrayOutputStream png = new ByteArrayOutputStream(); ImageIO.write(image, "png", png); image.flush();
            XSLFPictureShape picture = images.createPicture(deck.addPicture(png.toByteArray(), PictureData.PictureType.PNG));
            picture.setAnchor(new Rectangle2D.Double(80, 130, 640, 360));
            ((org.openxmlformats.schemas.presentationml.x2006.main.CTPicture) picture.getXmlObject()).getNvPicPr().getCNvPr().setDescr("紺色背景と紫色の円");

            XSLFSlide excluded = deck.createSlide(); excluded.setHidden(true); title(excluded, "非表示のスライドは出力しない");
            try (var stream = Files.newOutputStream(target)) { deck.write(stream); }
        }
        System.out.println(target.toAbsolutePath());
    }
    private static void title(XSLFSlide slide, String text) {
        XSLFTextBox box = text(slide, text, 40, 25, 880, 60); box.setPlaceholder(Placeholder.TITLE);
        box.getTextParagraphs().getFirst().getTextRuns().getFirst().setFontSize(32.0);
    }
    private static XSLFTextBox text(XSLFSlide slide, String text, double x, double y, double width, double height) {
        XSLFTextBox box = slide.createTextBox(); box.setAnchor(new Rectangle2D.Double(x, y, width, height));
        box.setText(text).setFontSize(24.0); return box;
    }
}
