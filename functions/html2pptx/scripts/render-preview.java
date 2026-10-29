import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

// Diagnostic renderer, not a PowerPoint application screenshot.
class RenderPreview {
  public static void main(String[] args) throws Exception {
    if (args.length != 5) throw new IllegalArgumentException("font-directory input.pptx output-directory width height");
    Path fontDirectory = Path.of(args[0]);
    for (String weight : new String[] {"Regular", "Bold"}) {
      Font font = Font.createFont(Font.TRUETYPE_FONT, fontDirectory.resolve("NotoSansJP-" + weight + ".ttf").toFile());
      GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
    }
    Path output = Path.of(args[2]);
    Files.createDirectories(output);
    int width = Integer.parseInt(args[3]);
    int height = Integer.parseInt(args[4]);
    if (width < 1 || height < 1 || width > 8192 || height > 8192) throw new IllegalArgumentException("Invalid preview dimensions");
    if (args[1].toLowerCase().endsWith(".pdf")) {
      try (PDDocument document = Loader.loadPDF(new File(args[1]))) {
        PDFRenderer renderer = new PDFRenderer(document);
        for (int i = 0; i < document.getNumberOfPages(); i++) {
          var bounds = document.getPage(i).getCropBox();
          BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
          Graphics2D graphics = image.createGraphics();
          try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            renderer.renderPageToGraphics(i, graphics, width / bounds.getWidth(), height / bounds.getHeight());
          } finally { graphics.dispose(); }
          ImageIO.write(image, "PNG", output.resolve(String.format("pptx-%02d.png", i + 1)).toFile());
        }
        System.out.println("Rendered " + document.getNumberOfPages() + " LibreOffice PDF page(s) with PDFBox.");
      }
      return;
    }
    try (XMLSlideShow deck = new XMLSlideShow(Files.newInputStream(Path.of(args[1])))) {
      Dimension size = deck.getPageSize();
      int number = 0;
      for (XSLFSlide slide : deck.getSlides()) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
          graphics.setColor(Color.WHITE);
          graphics.fillRect(0, 0, width, height);
          graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
          graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
          graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
          graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
          graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
          graphics.scale((double) width / size.width, (double) height / size.height);
          slide.draw(graphics);
        } finally { graphics.dispose(); }
        ImageIO.write(image, "PNG", output.resolve(String.format("pptx-%02d.png", ++number)).toFile());
      }
      System.out.println("Rendered " + number + " slide(s) with Apache POI.");
    }
  }
}
