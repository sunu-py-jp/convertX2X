import org.apache.poi.sl.usermodel.LineDecoration;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.sl.usermodel.TableCell;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.sl.usermodel.VerticalAlignment;
import org.apache.poi.xslf.usermodel.*;
import org.openxmlformats.schemas.presentationml.x2006.main.CTConnector;
import org.openxmlformats.schemas.presentationml.x2006.main.CTShape;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Editable, self-authored Japanese RAG workflow fixture. No external assets or network access.
 * Compile with office2md's Maven dependency classpath, then run RagFlowSample output.pptx.
 * All verified relationships have native OOXML connector endpoint IDs; slide 3 deliberately
 * contains a visually plausible arrow without those IDs, which must not imply a relationship.
 */
public final class RagFlowSample {
    private static final Color INK = new Color(24, 43, 68);
    private static final Color MUTED = new Color(88, 108, 131);
    private static final Color BLUE = new Color(29, 89, 174);
    private static final Color BLUE_FILL = new Color(229, 240, 255);
    private static final Color TEAL = new Color(10, 117, 111);
    private static final Color TEAL_FILL = new Color(224, 246, 239);
    private static final Color ORANGE = new Color(166, 83, 20);
    private static final Color ORANGE_FILL = new Color(255, 241, 222);
    private static final Color PURPLE = new Color(102, 72, 169);
    private static final Color PURPLE_FILL = new Color(242, 235, 255);
    private static final String FONT = "Noto Sans CJK JP";

    public static void main(String[] args) throws Exception {
        Path target = Path.of(args.length == 0 ? "target/fixtures/rag-flow.pptx" : args[0]);
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        try (XMLSlideShow deck = new XMLSlideShow()) {
            deck.setPageSize(new Dimension(960, 540));
            purchaseApproval(deck);
            groupedConnections(deck);
            uncertainConnections(deck);
            try (var out = Files.newOutputStream(target)) { deck.write(out); }
        }
        System.out.println(target.toAbsolutePath());
    }

    private static void purchaseApproval(XMLSlideShow deck) {
        XSLFSlide slide = slide(deck, "購入申請：分岐と差戻し", "01  /  VERIFIED WORKFLOW");
        text(slide, "条件は図形内に記載。保存された接続情報から処理の関係を取り出します。",
                48, 109, 862, 40, 18, MUTED);

        var apply = node(slide, "申請者\n購入申請を作成", 48, 182, 154, 80, BLUE, BLUE_FILL);
        var check = node(slide, "上長\n申請内容を確認", 260, 182, 154, 80, BLUE, BLUE_FILL);
        var low = node(slide, "承認\n10万円以下", 472, 142, 180, 80, TEAL, TEAL_FILL);
        var order = node(slide, "購買担当\n発注を確定", 734, 182, 176, 80, TEAL, TEAL_FILL);
        var returned = node(slide, "差戻し\n記載に不備あり", 260, 340, 180, 80, ORANGE, ORANGE_FILL);
        var fix = node(slide, "申請者\n修正して再申請", 48, 340, 154, 80, ORANGE, ORANGE_FILL);
        var high = node(slide, "部長確認へ\n10万円超", 472, 340, 180, 80, PURPLE, PURPLE_FILL);
        var approve = node(slide, "部長\n追加承認を記録", 734, 340, 176, 80, PURPLE, PURPLE_FILL);

        connected(slide, apply, 3, check, 1, 202, 222, 260, 222, BLUE, false, true);
        connected(slide, check, 3, low, 1, 414, 222, 472, 182, TEAL, false, true);
        connected(slide, low, 3, order, 1, 652, 182, 734, 222, TEAL, false, true);
        connected(slide, check, 2, returned, 0, 337, 262, 350, 340, ORANGE, false, true);
        connected(slide, returned, 1, fix, 3, 260, 380, 202, 380, ORANGE, false, true);
        connected(slide, fix, 0, apply, 2, 125, 340, 125, 262, ORANGE, false, true);
        connected(slide, check, 3, high, 1, 414, 222, 472, 380, PURPLE, false, true);
        connected(slide, high, 3, approve, 1, 652, 380, 734, 380, PURPLE, false, true);
        connected(slide, approve, 0, order, 2, 822, 340, 822, 262, PURPLE, false, true);

        text(slide, "RAGで検索したいこと：少額購入の承認経路／高額購入の追加承認／不備時の戻り先",
                48, 464, 862, 43, 17, MUTED);
    }

    private static void groupedConnections(XMLSlideShow deck) {
        XSLFSlide slide = slide(deck, "グループ・双方向・始点の矢印", "02  /  DIRECTION & GROUPS");
        text(slide, "グループ内外の接続を保持し、矢印がどちらの端に付いているかも区別します。",
                48, 109, 862, 42, 18, MUTED);
        text(slide, "社内の確認グループ", 48, 170, 555, 38, 18, BLUE);
        text(slide, "連携先", 706, 170, 204, 38, 18, TEAL);

        XSLFGroupShape group = slide.createGroup();
        group.setAnchor(new Rectangle2D.Double(48, 225, 565, 201));
        group.setInteriorAnchor(new Rectangle2D.Double(0, 0, 565, 201));
        var create = node(group, "開発\n連携データを作成", 0, 0, 190, 88, BLUE, BLUE_FILL);
        var review = node(group, "審査\n内容を確認", 345, 0, 190, 88, BLUE, BLUE_FILL);
        connected(group, create, 3, review, 1, 190, 44, 345, 44, BLUE, true, true);
        text(group, "補足：双方向の確認。担当者との連絡は別途行います。",
                0, 139, 558, 54, 17, MUTED);

        var receive = node(slide, "受信\n連携結果を受領", 706, 225, 204, 88, TEAL, TEAL_FILL);
        var resend = node(slide, "再送\n保留データを送信", 706, 366, 204, 72, ORANGE, ORANGE_FILL);
        connected(slide, review, 3, receive, 1, 583, 269, 706, 269, TEAL, false, true);
        // Saved start = receive, saved end = resend, arrowhead at the start: resend -> receive.
        connected(slide, receive, 2, resend, 0, 808, 313, 808, 366, ORANGE, true, false);
        text(slide, "検証点：開発と審査は双方向／審査から受信へ接続／下の再送から上の受信へ矢印",
                48, 464, 862, 43, 17, MUTED);
    }

    private static void uncertainConnections(XMLSlideShow deck) {
        XSLFSlide slide = slide(deck, "つながりを推測しない", "03  /  EVIDENCE BEFORE INFERENCE");
        text(slide, "接続先が保存されていない矢印や、近くの独立ラベルから関係を作りません。",
                48, 109, 862, 42, 18, MUTED);
        var apply = node(slide, "受付\n申請を受領", 65, 198, 180, 80, BLUE, BLUE_FILL);
        var check = node(slide, "担当者\n内容を確認", 390, 198, 180, 80, BLUE, BLUE_FILL);
        var done = node(slide, "処理完了\n記録を保存", 715, 198, 180, 80, TEAL, TEAL_FILL);
        // Intentionally loose: plausible placement is not proof that apply is connected to check.
        line(slide, 254, 238, 380, 238, MUTED, false, true);
        connected(slide, check, 3, done, 1, 570, 238, 715, 238, TEAL, false, true);
        text(slide, "承認", 277, 169, 84, 36, 18, ORANGE);
        text(slide, "至急", 604, 169, 82, 36, 18, ORANGE);
        text(slide, "左の矢印：接続先IDなし", 65, 292, 365, 36, 17, MUTED);
        text(slide, "右の矢印：接続先IDあり／「至急」は独立ラベル", 459, 292, 440, 36, 17, MUTED);

        XSLFTable table = slide.createTable(3, 2);
        table.setAnchor(new Rectangle2D.Double(65, 350, 530, 108));
        table.getCTTable().getTblPr().setFirstRow(true);
        String[][] values = {{"情報", "扱い"}, {"保存された接続", "関係として出力"}, {"近さ・独立ラベル", "関係を推測しない"}};
        table.setColumnWidth(0, 225); table.setColumnWidth(1, 305);
        for (int r = 0; r < values.length; r++) {
            table.setRowHeight(r, 36);
            for (int c = 0; c < 2; c++) {
                XSLFTableCell cell = table.getCell(r, c);
                cell.setText(values[r][c]); cell.setFillColor(r == 0 ? BLUE : Color.WHITE);
                cell.setVerticalAlignment(VerticalAlignment.MIDDLE);
                cell.setLeftInset(12); cell.setRightInset(12); cell.setTopInset(4); cell.setBottomInset(4);
                styleText(cell, 16, r == 0 ? Color.WHITE : INK, r == 0, TextParagraph.TextAlign.LEFT);
                for (var edge : TableCell.BorderEdge.values()) {
                    cell.setBorderColor(edge, new Color(210, 222, 236)); cell.setBorderWidth(edge, 1.0);
                }
            }
        }
        XSLFTextBox note = text(slide, "監査メモ：\n公開する本文だけ保持", 650, 351, 259, 80, 18, INK);
        var struck = note.getTextParagraphs().getLast().addNewTextRun();
        struck.setText(" STRIKE_SECRET_RAG_DEMO"); struck.setStrikethrough(true);
        struck.setFontSize(9.0); struck.setFontFamily(FONT); struck.setFontColor(MUTED);
        XSLFTextBox hidden = text(slide, "HIDDEN_SECRET_RAG_DEMO", 650, 430, 259, 30, 14, INK);
        ((CTShape) hidden.getXmlObject()).getNvSpPr().getCNvPr().setHidden(true);
        text(slide, "文字・表は保持。根拠のない意味付けを避け、検索結果から元ページを確認できます。",
                48, 474, 862, 34, 17, MUTED);
    }

    private static XSLFSlide slide(XMLSlideShow deck, String heading, String eyebrow) {
        XSLFSlide slide = deck.createSlide();
        slide.getBackground().setFillColor(new Color(247, 250, 255));
        text(slide, eyebrow, 48, 20, 862, 30, 12, BLUE);
        XSLFTextBox title = text(slide, heading, 48, 53, 862, 49, 31, INK);
        title.setPlaceholder(Placeholder.TITLE);
        for (var p : title.getTextParagraphs()) for (var r : p.getTextRuns()) r.setBold(true);
        var footer = text(slide, "convertX2X  /  native editable PowerPoint  /  RAG demo", 48, 514, 862, 18, 9, MUTED);
        footer.setPlaceholder(Placeholder.FOOTER);
        return slide;
    }

    private static XSLFAutoShape node(XSLFShapeContainer container, String value,
                                      double x, double y, double w, double h, Color border, Color fill) {
        XSLFAutoShape node = container.createAutoShape();
        node.setShapeType(ShapeType.ROUND_RECT); node.setAnchor(new Rectangle2D.Double(x, y, w, h));
        node.setFillColor(fill); node.setLineColor(border); node.setLineWidth(1.8);
        node.setText(value); node.setVerticalAlignment(VerticalAlignment.MIDDLE);
        node.setLeftInset(8); node.setRightInset(8); node.setTopInset(4); node.setBottomInset(4);
        styleText(node, 18, INK, false, TextParagraph.TextAlign.CENTER);
        node.getTextParagraphs().getFirst().getTextRuns().getFirst().setBold(true);
        return node;
    }

    private static XSLFTextBox text(XSLFShapeContainer container, String value,
                                    double x, double y, double w, double h, double size, Color color) {
        XSLFTextBox box = container.createTextBox(); box.setAnchor(new Rectangle2D.Double(x, y, w, h));
        box.setText(value); box.setLeftInset(0); box.setRightInset(0); box.setTopInset(0); box.setBottomInset(0);
        box.setVerticalAlignment(VerticalAlignment.MIDDLE);
        styleText(box, size, color, false, TextParagraph.TextAlign.LEFT);
        return box;
    }

    private static void styleText(XSLFTextShape shape, double size, Color color, boolean bold,
                                  TextParagraph.TextAlign alignment) {
        for (var paragraph : shape.getTextParagraphs()) {
            paragraph.setTextAlign(alignment); paragraph.setSpaceAfter(0.0); paragraph.setSpaceBefore(0.0);
            for (var run : paragraph.getTextRuns()) {
                run.setFontFamily(FONT); run.setFontSize(size); run.setFontColor(color); run.setBold(bold);
            }
        }
    }

    private static XSLFConnectorShape connected(XSLFShapeContainer container,
                                                XSLFShape start, int startPort, XSLFShape end, int endPort,
                                                double x1, double y1, double x2, double y2,
                                                Color color, boolean arrowAtStart, boolean arrowAtEnd) {
        var connector = line(container, x1, y1, x2, y2, color, arrowAtStart, arrowAtEnd);
        var endpoints = ((CTConnector) connector.getXmlObject()).getNvCxnSpPr().getCNvCxnSpPr();
        endpoints.addNewStCxn().setId(start.getShapeId()); endpoints.getStCxn().setIdx(startPort);
        endpoints.addNewEndCxn().setId(end.getShapeId()); endpoints.getEndCxn().setIdx(endPort);
        return connector;
    }

    private static XSLFConnectorShape line(XSLFShapeContainer container, double x1, double y1,
                                           double x2, double y2, Color color,
                                           boolean arrowAtStart, boolean arrowAtEnd) {
        var line = container.createConnector();
        line.setAnchor(new Rectangle2D.Double(Math.min(x1, x2), Math.min(y1, y2), Math.abs(x2 - x1), Math.abs(y2 - y1)));
        line.setFlipHorizontal(x2 < x1); line.setFlipVertical(y2 < y1);
        line.setLineColor(color); line.setLineWidth(2.3);
        line.setLineHeadDecoration(arrowAtStart ? LineDecoration.DecorationShape.TRIANGLE : LineDecoration.DecorationShape.NONE);
        line.setLineTailDecoration(arrowAtEnd ? LineDecoration.DecorationShape.TRIANGLE : LineDecoration.DecorationShape.NONE);
        line.setLineHeadLength(LineDecoration.DecorationSize.MEDIUM); line.setLineHeadWidth(LineDecoration.DecorationSize.MEDIUM);
        line.setLineTailLength(LineDecoration.DecorationSize.MEDIUM); line.setLineTailWidth(LineDecoration.DecorationSize.MEDIUM);
        return line;
    }
}
