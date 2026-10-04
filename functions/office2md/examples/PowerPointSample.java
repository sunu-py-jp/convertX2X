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

            XSLFSlide diagram = deck.createSlide(); title(diagram, "図中の文字を検索可能な本文へ");
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
            text(diagram, "図内の文字は本文へ。接続先が保存されていない線の関係は推測しません。", 50, 410, 860, 60);

            XSLFSlide images = deck.createSlide(); title(images, "保存済みの埋め込み画像");
            BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics(); graphics.setColor(new Color(35, 36, 75)); graphics.fillRect(0, 0, 320, 180);
            graphics.setColor(new Color(159, 120, 245)); graphics.fillOval(120, 35, 110, 110); graphics.dispose();
            ByteArrayOutputStream png = new ByteArrayOutputStream(); ImageIO.write(image, "png", png); image.flush();
            XSLFPictureShape picture = images.createPicture(deck.addPicture(png.toByteArray(), PictureData.PictureType.PNG));
            picture.setAnchor(new Rectangle2D.Double(80, 130, 640, 360));
            ((org.openxmlformats.schemas.presentationml.x2006.main.CTPicture) picture.getXmlObject()).getNvPicPr().getCNvPr().setDescr("紺色背景と紫色の円");
            textSize(images, "画像は埋め込みデータを参照し、代替説明も残します。", 735, 150, 190, 280, 19.0);

            XSLFSlide decisions = deck.createSlide(); title(decisions, "条件別の対応を表で比較");
            textSize(decisions, "区分・条件・担当・処理を1行ずつ対応付けます。結合行の値は開始セルに保持します。", 40, 90, 870, 48, 20.0);
            XSLFTable decisionTable = decisions.createTable(5, 4);
            decisionTable.setAnchor(new Rectangle2D.Double(40, 150, 870, 275));
            String[][] decisionValues = {
                {"区分", "条件", "担当", "処理"},
                {"通常", "資料あり", "受付", "当日確認"},
                {"差戻し", "添付不足", "申請者", "再提出"},
                {"共通の補足", "", "管理者", "期限は営業日"},
                {"例外", "期限超過", "責任者", "翌朝判断"}
            };
            for (int r = 0; r < decisionValues.length; r++) {
                decisionTable.setRowHeight(r, 55);
                for (int c = 0; c < 4; c++) {
                    decisionTable.setColumnWidth(c, c == 1 ? 240 : 210);
                    XSLFTableCell cell = decisionTable.getCell(r, c);
                    cell.setText(decisionValues[r][c]).setFontSize(20.0);
                    if (r == 0) cell.setFillColor(new Color(218, 233, 242));
                }
            }
            decisionTable.mergeCells(3, 3, 0, 1);
            textSize(decisions, "補足：差戻し後は同じ申請番号で再判定します。", 45, 455, 850, 44, 20.0);

            XSLFSlide path = deck.createSlide(); title(path, "レビュー経路の図形と補足");
            XSLFGroupShape review = path.createGroup();
            review.setAnchor(new Rectangle2D.Double(60, 130, 830, 250));
            review.setInteriorAnchor(new Rectangle2D.Double(0, 0, 830, 250));
            XSLFAutoShape request = review.createAutoShape(); request.setShapeType(ShapeType.ROUND_RECT);
            request.setAnchor(new Rectangle2D.Double(0, 55, 210, 130)); request.setFillColor(new Color(220, 239, 255));
            request.setText("申請を受付").setFontSize(26.0);
            XSLFAutoShape check = review.createAutoShape(); check.setShapeType(ShapeType.DIAMOND);
            check.setAnchor(new Rectangle2D.Double(310, 30, 190, 180)); check.setFillColor(new Color(255, 239, 209));
            check.setText("資料を確認").setFontSize(24.0);
            XSLFAutoShape complete = review.createAutoShape(); complete.setShapeType(ShapeType.ROUND_RECT);
            complete.setAnchor(new Rectangle2D.Double(610, 55, 210, 130)); complete.setFillColor(new Color(222, 244, 225));
            complete.setText("結果を通知").setFontSize(26.0);
            XSLFConnectorShape firstLine = review.createConnector();
            firstLine.setAnchor(new Rectangle2D.Double(210, 120, 100, 0)); firstLine.setLineWidth(3);
            XSLFConnectorShape secondLine = review.createConnector();
            secondLine.setAnchor(new Rectangle2D.Double(500, 120, 110, 0)); secondLine.setLineWidth(3);
            textSize(path, "図形のラベルを本文から検索できます。線の関係は確定できた場合だけ記録します。", 55, 420, 860, 72, 21.0);

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
    private static XSLFTextBox textSize(XSLFSlide slide, String value, double x, double y,
                                        double width, double height, double size) {
        XSLFTextBox box = text(slide, value, x, y, width, height);
        box.getTextParagraphs().getFirst().getTextRuns().getFirst().setFontSize(size);
        return box;
    }
}
