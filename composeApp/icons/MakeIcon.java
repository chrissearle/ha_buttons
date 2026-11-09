// Renders the app icon (Home Assistant logomark on a macOS-style rounded square).
// Usage: java MakeIcon.java ha-logomark.svg out.png
// The Home Assistant logo is a trademark of the Open Home Foundation - personal, non-commercial use only.
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.*;
import javax.imageio.ImageIO;

public class MakeIcon {
    public static void main(String[] args) throws Exception {
        String svg = Files.readString(new File(args[0]).toPath());
        Matcher m = Pattern.compile("<path d=\"([^\"]+)\" fill=\"(#[0-9A-Fa-f]{6})\"").matcher(svg);
        java.util.List<Path2D> paths = new ArrayList<>();
        java.util.List<Color> fills = new ArrayList<>();
        while (m.find()) { paths.add(parse(m.group(1))); fills.add(Color.decode(m.group(2))); }

        int size = 1024;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // macOS icon grid: 824px body centred in 1024 canvas, ~185px corner radius.
        Color blue = Color.decode("#18BCF2");
        g.setColor(blue);
        g.fill(new RoundRectangle2D.Double(100, 100, 824, 824, 370, 370));

        // The SVG is the "on light" variant (blue house, white tree). On the blue tile we want the
        // inverse, like the official square logo: so swap the two fills.
        Collections.reverse(fills);

        // Logomark (240x240 viewBox) scaled to ~50% of the body, centred.
        double scale = 420.0 / 240.0;
        AffineTransform at = new AffineTransform();
        at.translate((size - 240 * scale) / 2, (size - 240 * scale) / 2);
        at.scale(scale, scale);
        for (int i = 0; i < paths.size(); i++) {
            g.setColor(fills.get(i));
            g.fill(at.createTransformedShape(paths.get(i)));
        }
        g.dispose();
        ImageIO.write(img, "png", new File(args[1]));
    }

    // Minimal SVG path parser: absolute M, L, H, V, C, Z (all the logomark uses).
    static Path2D parse(String d) {
        Path2D p = new Path2D.Double();
        Matcher t = Pattern.compile("[MLHVCZ]|-?[0-9]*\\.?[0-9]+").matcher(d);
        java.util.List<String> tok = new ArrayList<>();
        while (t.find()) tok.add(t.group());
        int i = 0; char cmd = 'M'; double x = 0, y = 0;
        while (i < tok.size()) {
            String s = tok.get(i);
            if (Character.isLetter(s.charAt(0))) { cmd = s.charAt(0); i++; if (cmd == 'Z') { p.closePath(); } continue; }
            switch (cmd) {
                case 'M' -> { x = num(tok, i++); y = num(tok, i++); p.moveTo(x, y); cmd = 'L'; }
                case 'L' -> { x = num(tok, i++); y = num(tok, i++); p.lineTo(x, y); }
                case 'H' -> { x = num(tok, i++); p.lineTo(x, y); }
                case 'V' -> { y = num(tok, i++); p.lineTo(x, y); }
                case 'C' -> {
                    double x1 = num(tok, i++), y1 = num(tok, i++), x2 = num(tok, i++), y2 = num(tok, i++);
                    x = num(tok, i++); y = num(tok, i++);
                    p.curveTo(x1, y1, x2, y2, x, y);
                }
                default -> throw new IllegalStateException("Unsupported path command " + cmd);
            }
        }
        return p;
    }

    static double num(java.util.List<String> tok, int i) { return Double.parseDouble(tok.get(i)); }
}
