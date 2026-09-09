package com.convertx2x.excel2md.drawing;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DrawingTextLayoutTest {
    private static DrawingText.Run run(String text, Color color) {
        return new DrawingText.Run(text,"Noto Sans CJK JP",20,false,false,null,color,false,0);
    }
    private static DrawingText.Paragraph paragraph(List<DrawingText.Run> runs,int align) {
        return new DrawingText.Paragraph(runs,align,0,0,0,100,true,0,0);
    }
    private static DrawingText.Content body(List<DrawingText.Paragraph> paragraphs,int vertical,boolean wrap,boolean shrink,double fontScale) {
        return new DrawingText.Content(paragraphs,vertical,wrap,fontScale,0,shrink,0,"horz",List.of());
    }
    private static BufferedImage render(DrawingText.Content content) {
        BufferedImage image=new BufferedImage(440,280,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            DrawingTextLayout.draw(g,content,new Rectangle2D.Double(20,20,400,240),1);
        } finally { g.dispose(); }
        return image;
    }
    private static Rectangle pixels(BufferedImage image,Color target) {
        Rectangle bounds=null;
        for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) {
            int rgba=image.getRGB(x,y);
            if((rgba>>>24)>70 && (rgba&0xffffff)==(target.getRGB()&0xffffff)) {
                if(bounds==null) bounds=new Rectangle(x,y,1,1); else bounds.add(new Rectangle(x,y,1,1));
            }
        }
        assertNotNull(bounds,"Expected rendered pixels of "+target);return bounds;
    }
    @Test void preservesDifferentRunColorsAndPositionsEveryParagraphIndependently() {
        var content=body(List.of(
                paragraph(List.of(run("左の赤文字",Color.RED)),1),
                paragraph(List.of(run("中央の青文字",Color.BLUE)),2),
                paragraph(List.of(run("右の緑文字",Color.GREEN)),3)),1,true,false,1);
        BufferedImage image=render(content);
        Rectangle red=pixels(image,Color.RED), blue=pixels(image,Color.BLUE), green=pixels(image,Color.GREEN);
        assertTrue(red.x<30);
        assertEquals(220,blue.getCenterX(),8);
        assertTrue(green.getMaxX()>410);
        assertTrue(red.getMaxY()<blue.y && blue.getMaxY()<green.y);
    }
    @Test void verticalCenterAndBottomUseTheHeightOfAllLines() {
        var paragraphs=List.of(paragraph(List.of(run("日本語\n2行目",Color.RED)),2));
        Rectangle top=pixels(render(body(paragraphs,1,true,false,1)),Color.RED);
        Rectangle center=pixels(render(body(paragraphs,2,true,false,1)),Color.RED);
        Rectangle bottom=pixels(render(body(paragraphs,3,true,false,1)),Color.RED);
        assertEquals(top.width,center.width); assertEquals(top.height,bottom.height);
        assertTrue(center.y-top.y>75); assertTrue(bottom.y-center.y>75);
        assertTrue(bottom.getMaxY()<=260);
    }
    @Test void keepsSavedFontScaleAndShrinksOverflowWhenNormalAutofitIsEnabled() {
        var paragraphs=List.of(paragraph(List.of(run("保存サイズ",Color.BLUE)),1));
        Rectangle original=pixels(render(body(paragraphs,1,true,false,1)),Color.BLUE);
        Rectangle half=pixels(render(body(paragraphs,1,true,false,.5)),Color.BLUE);
        assertEquals(original.width/2d,half.width,3);
        assertEquals(original.height/2d,half.height,3);
        var longText=List.of(paragraph(List.of(run("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789",Color.RED)),1));
        Rectangle clipped=pixels(render(body(longText,1,false,false,1)),Color.RED);
        Rectangle fitted=pixels(render(body(longText,1,false,true,1)),Color.RED);
        assertTrue(fitted.height<clipped.height);
        assertTrue(fitted.getMaxX()<=420);
    }
    @Test void wrappingPreservesExplicitBreaksAndNoWrapKeepsOneLine() {
        String text="日本語の長い文章を折り返して表示します。".repeat(3);
        var paragraphs=List.of(paragraph(List.of(run(text,Color.RED)),1));
        Rectangle wrapped=pixels(render(body(paragraphs,1,true,false,1)),Color.RED);
        Rectangle unwrapped=pixels(render(body(paragraphs,1,false,false,1)),Color.RED);
        assertTrue(wrapped.height>unwrapped.height*2);
        assertEquals(pixels(render(body(List.of(paragraph(List.of(run("日本語\n改行",Color.RED)),1)),1,false,false,1)),Color.RED).height,
                pixels(render(body(List.of(paragraph(List.of(run("日本語\n改行",Color.RED)),1)),1,true,false,1)),Color.RED).height);
    }
    @Test void paragraphInsetsAndSpacingMoveTextAndTrackingChangesAdvance() {
        var normal=paragraph(List.of(run("間隔を確認",Color.RED)),1);
        var inset=new DrawingText.Paragraph(normal.runs(),1,35,10,15,100,true,22,0);
        Rectangle before=pixels(render(body(List.of(normal),1,true,false,1)),Color.RED);
        Rectangle after=pixels(render(body(List.of(inset),1,true,false,1)),Color.RED);
        assertEquals(50,after.x-before.x,1); assertEquals(22,after.y-before.y,1);
        var spaced=new DrawingText.Run("間隔を確認","Noto Sans CJK JP",20,false,false,null,Color.RED,true,4);
        Rectangle tracked=pixels(render(body(List.of(paragraph(List.of(spaced),1)),1,true,false,1)),Color.RED);
        assertTrue(tracked.width>before.width+12); assertTrue(tracked.height>before.height);
    }
    @Test void rotatesTheTextBodyWithoutChangingTheShapeCoordinates() {
        var paragraphs=List.of(paragraph(List.of(run("回転する日本語",Color.RED)),2));
        Rectangle normal=pixels(render(body(paragraphs,2,true,false,1)),Color.RED);
        var rotated=new DrawingText.Content(paragraphs,2,true,1,0,false,90,"horz",List.of());
        Rectangle vertical=pixels(render(rotated),Color.RED);
        assertEquals(normal.width,vertical.height,2);assertEquals(normal.height,vertical.width,2);
        assertEquals(220,vertical.getCenterX(),15);assertEquals(140,vertical.getCenterY(),15);
    }
    @Test void lineSpacingReductionSubtractsPercentagePointsAndDoesNotReduceFixedSpacing() {
        var runs=List.of(run("一行目\n",Color.RED),run("二行目",Color.BLUE));
        BufferedImage normal=render(body(List.of(paragraph(runs,1)),1,true,false,1));
        var percentage=new DrawingText.Paragraph(runs,1,0,0,0,150,true,0,0);
        BufferedImage reduced=render(new DrawingText.Content(List.of(percentage),1,true,1,.2,false,0,"horz",List.of()));
        double normalStep=pixels(normal,Color.BLUE).y-pixels(normal,Color.RED).y;
        assertEquals(normalStep*1.3,pixels(reduced,Color.BLUE).y-pixels(reduced,Color.RED).y,2);
        var fixed=new DrawingText.Paragraph(runs,1,0,0,0,40,false,0,0);
        BufferedImage fixedImage=render(new DrawingText.Content(List.of(fixed),1,true,1,.5,false,0,"horz",List.of()));
        assertEquals(40,pixels(fixedImage,Color.BLUE).y-pixels(fixedImage,Color.RED).y,1);
    }
    @Test void lastLineIsNotClippedWhenBottomAlignedWithReducedLineSpacing() {
        var paragraph=new DrawingText.Paragraph(List.of(run("下部の文字",Color.BLUE)),1,0,0,0,100,true,0,0);
        Rectangle top=pixels(render(body(List.of(paragraph),1,true,false,1)),Color.BLUE);
        Rectangle bottom=pixels(render(new DrawingText.Content(List.of(paragraph),3,true,1,.5,false,0,"horz",List.of())),Color.BLUE);
        assertEquals(top.height,bottom.height);
        assertTrue(bottom.getMaxY()<=260);
    }
    @Test void rejectsAnUnboundedLabelBeforePreparingGlyphLayouts() {
        var content=body(List.of(paragraph(List.of(run("a".repeat(200_001),Color.RED)),1)),1,false,true,1);
        var exception=assertThrows(com.convertx2x.excel2md.conversion.ConversionException.class,()->render(content));
        assertEquals("DRAWING_TEXT_LIMIT",exception.code());
    }
}
