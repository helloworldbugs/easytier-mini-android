// IconGen — generates the legacy raster launcher icons for easytier-mini.
//
// Why this exists: the app's icon used to be a plain <shape> ShapeDrawable,
// which has no intrinsic size (getIntrinsicWidth() == -1). Some third-party
// APK installers rasterise android:icon and crash with
//   IllegalArgumentException: width and height must be > 0
// So the launcher icon must be a real PNG with concrete pixels in every
// density bucket. This program is the source of truth for those PNGs.
//
// Design: a three-node mesh (EasyTier runs a peer-to-peer network core),
// drawn flat in white (#FFFFFF) on the brand blue (#1E6FD9, matching
// res/values/ic_launcher_background.xml). The same geometry is mirrored in
// res/drawable/ic_launcher_foreground.xml for the adaptive icon.
//
// Build & run (WSL, JDK 21):
//   javac IconGen.java
//   java IconGen /path/to/android/app/src/main/res
//
// The program also reads every PNG back and verifies its dimensions before
// reporting success, so a silent encoder failure cannot slip through.

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public final class IconGen {

    // ---- Brand palette -----------------------------------------------------
    static final Color BG   = new Color(0x1E, 0x6F, 0xD9); // #1E6FD9
    static final Color MARK = new Color(0xFF, 0xFF, 0xFF); // #FFFFFF

    // ---- Legacy geometry, as fractions of the canvas size S -----------------
    // 6% padding keeps the rounded square clear of circular/rounded masks.
    static final double PAD      = 0.06;
    // Rounded-corner radius = 20% of S.
    static final double CORNER_R = 0.20;
    // Equilateral-triangle circumradius (node-centre circle).
    static final double TRI_R    = 0.27;
    // Node dot radius.
    static final double NODE_R   = 0.11;
    // Mesh edge stroke width.
    static final double STROKE_W = 0.065;

    static final String[] DIRS  = {
        "mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"
    };
    static final int[] SIZES = { 48, 72, 96, 144, 192 };

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java IconGen <res-dir>");
            System.exit(2);
        }
        File res = new File(args[0]);
        if (!res.isDirectory()) {
            System.err.println("not a directory: " + res);
            System.exit(2);
        }

        System.out.printf("%-42s %11s  %8s%n", "file", "dimensions", "bytes");
        for (int i = 0; i < SIZES.length; i++) {
            int size = SIZES[i];
            File dir = new File(res, DIRS[i]);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir);
            }
            File out = new File(dir, "ic_launcher.png");

            BufferedImage img = render(size);
            if (!ImageIO.write(img, "png", out)) {
                throw new IOException("no PNG writer available for " + out);
            }

            // Read back and confirm what actually landed on disk.
            BufferedImage check = ImageIO.read(out);
            if (check == null) {
                throw new IOException("written file is not a readable PNG: " + out);
            }
            if (check.getWidth() != size || check.getHeight() != size) {
                throw new IOException("dimension mismatch: " + out
                        + " is " + check.getWidth() + "x" + check.getHeight()
                        + ", expected " + size + "x" + size);
            }
            System.out.printf("%-42s %5dx%-5d  %8d%n",
                    out.getPath(), check.getWidth(), check.getHeight(), out.length());
        }
        System.out.println("OK: all icons written and verified.");
    }

    /** Renders one legacy icon (background baked in, transparent outside the rounded square). */
    static BufferedImage render(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);

        // Rounded-square background. RoundRectangle2D takes the FULL arc extent,
        // i.e. 2 * radius.
        double pad = PAD * s;
        double radius = CORNER_R * s;
        RoundRectangle2D plate = new RoundRectangle2D.Double(
                pad, pad, s - 2 * pad, s - 2 * pad, 2 * radius, 2 * radius);
        g.setColor(BG);
        g.fill(plate);

        // Mesh geometry, bounding box centred on the canvas.
        double cx = s / 2.0, cy = s / 2.0;
        double R  = TRI_R * s;        // circumradius
        double nr = NODE_R * s;       // node radius
        double w  = STROKE_W * s;     // stroke width
        double halfBase = Math.sqrt(3.0) / 2.0 * R;

        double apexY = cy - 0.75 * R;
        double baseY = cy + 0.75 * R;
        double[] top = { cx, apexY };
        double[] bl  = { cx - halfBase, baseY };
        double[] br  = { cx + halfBase, baseY };

        Path2D triangle = new Path2D.Double();
        triangle.moveTo(top[0], top[1]);
        triangle.lineTo(bl[0], bl[1]);
        triangle.lineTo(br[0], br[1]);
        triangle.closePath();

        g.setColor(MARK);
        g.setStroke(new BasicStroke((float) w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(triangle);

        // Node dots on top of the joins so every vertex reads as a solid node.
        for (double[] p : new double[][] { top, bl, br }) {
            g.fill(new Ellipse2D.Double(p[0] - nr, p[1] - nr, 2 * nr, 2 * nr));
        }

        g.dispose();
        return img;
    }

    private IconGen() { }
}
