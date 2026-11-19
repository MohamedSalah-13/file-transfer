import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Draws the app icon and writes every file that uses it. Run from the project root:
 * <pre>java packaging/IconMaker.java</pre>
 * The icon: a chat bubble with an up and a down arrow (send and receive files) on a blue-to-teal
 * tile. The server's icon adds a small server badge in the corner.
 * <ul>
 *   <li>{@code packaging/client.ico}, {@code packaging/server.ico}: the Windows installer's launchers;</li>
 *   <li>{@code packaging/client.png}, {@code packaging/server.png}: the Linux package's menu entries (512 px);</li>
 *   <li>{@code packaging/client.icns}, {@code packaging/server.icns}: the macOS apps;</li>
 *   <li>{@code src/main/resources/icons/(client|server)-N.png}: the windows' title bar and taskbar.</li>
 * </ul>
 */
public class IconMaker {

    static final int[] ICO_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};
    static final int[] WINDOW_SIZES = {16, 24, 32, 48, 64, 128, 256};
    /** The .icns entries that hold a PNG, with the size each one is drawn at. */
    static final String[] ICNS_TYPES = {"ic11", "ic12", "ic07", "ic08", "ic09", "ic10"};
    static final int[] ICNS_SIZES = {32, 64, 128, 256, 512, 1024};

    static final Color TILE_TOP = new Color(0x4F46E5);    // indigo
    static final Color TILE_BOTTOM = new Color(0x0891B2); // teal
    static final Color ARROW = new Color(0x4338CA);
    static final Color BADGE = new Color(0x0F172A);
    static final Color BADGE_BAR = new Color(0xE2E8F0);
    static final Color BADGE_LIGHT = new Color(0x22C55E);

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        Path icons = root.resolve("src/main/resources/icons");
        Files.createDirectories(icons);
        for (boolean server : new boolean[]{false, true}) {
            String name = server ? "server" : "client";
            writeIco(root.resolve("packaging/" + name + ".ico"), server);
            Files.write(root.resolve("packaging/" + name + ".png"), png(draw(512, server)));
            Files.write(root.resolve("packaging/" + name + ".icns"), icns(server));
            for (int size : WINDOW_SIZES) {
                ImageIO.write(draw(size, server), "png", icons.resolve(name + "-" + size + ".png").toFile());
            }
        }
        System.out.println("Icons written under " + root.toAbsolutePath().normalize());
    }

    /** Draws on a 256x256 design grid, scaled to {@code size}. */
    static BufferedImage draw(int size, boolean server) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(size / 256.0, size / 256.0);
        boolean tiny = size <= 24; // fewer, bolder shapes so it still reads at 16 px

        // tile
        RoundRectangle2D tile = new RoundRectangle2D.Double(8, 8, 240, 240, 64, 64);
        g.setPaint(new GradientPaint(8, 8, TILE_TOP, 248, 248, TILE_BOTTOM));
        g.fill(tile);

        // chat bubble with a tail at the bottom left
        Area bubble = new Area(new RoundRectangle2D.Double(40, 50, 176, 130, 52, 52));
        Path2D tail = new Path2D.Double();
        tail.moveTo(70, 160);
        tail.lineTo(58, 214);
        tail.lineTo(118, 172);
        tail.closePath();
        bubble.add(new Area(tail));
        g.setColor(Color.WHITE);
        g.fill(bubble);

        // up arrow (send) and down arrow (receive)
        g.setColor(ARROW);
        double shaft = tiny ? 22 : 16, head = tiny ? 60 : 50;
        g.fill(arrow(98, 72, 160, shaft, head, true));
        g.fill(arrow(158, 72, 160, shaft, head, false));

        if (server) {
            // badge: a small server rack, outlined in white so it stands off the tile
            RoundRectangle2D badge = new RoundRectangle2D.Double(150, 150, 98, 98, 30, 30);
            g.setColor(Color.WHITE);
            g.fill(grow(badge, 8));
            g.setColor(BADGE);
            g.fill(badge);
            if (!tiny) {
                for (int i = 0; i < 3; i++) {
                    double y = 168 + i * 24;
                    g.setColor(BADGE_BAR);
                    g.fill(new RoundRectangle2D.Double(166, y, 66, 14, 8, 8));
                    g.setColor(BADGE_LIGHT);
                    g.fill(new Ellipse2D.Double(171, y + 3, 8, 8));
                }
            }
        }
        g.dispose();
        return img;
    }

    /** A vertical arrow centered on {@code x} between {@code top} and {@code bottom}. */
    static Shape arrow(double x, double top, double bottom, double shaft, double head, boolean up) {
        double headLength = head * 0.8;
        Path2D p = new Path2D.Double();
        if (up) {
            p.moveTo(x, top);
            p.lineTo(x + head / 2, top + headLength);
            p.lineTo(x + shaft / 2, top + headLength);
            p.lineTo(x + shaft / 2, bottom);
            p.lineTo(x - shaft / 2, bottom);
            p.lineTo(x - shaft / 2, top + headLength);
            p.lineTo(x - head / 2, top + headLength);
        } else {
            p.moveTo(x, bottom);
            p.lineTo(x + head / 2, bottom - headLength);
            p.lineTo(x + shaft / 2, bottom - headLength);
            p.lineTo(x + shaft / 2, top);
            p.lineTo(x - shaft / 2, top);
            p.lineTo(x - shaft / 2, bottom - headLength);
            p.lineTo(x - head / 2, bottom - headLength);
        }
        p.closePath();
        return p;
    }

    static RoundRectangle2D grow(RoundRectangle2D r, double by) {
        return new RoundRectangle2D.Double(r.getX() - by, r.getY() - by, r.getWidth() + 2 * by,
                r.getHeight() + 2 * by, r.getArcWidth() + 2 * by, r.getArcHeight() + 2 * by);
    }

    /**
     * An .ico with every size: 256 as PNG (the usual way for that size), the others as 32-bit
     * bitmaps, which every Windows tool reads.
     */
    static void writeIco(Path file, boolean server) throws IOException {
        byte[][] images = new byte[ICO_SIZES.length][];
        for (int i = 0; i < ICO_SIZES.length; i++) {
            BufferedImage img = draw(ICO_SIZES[i], server);
            images[i] = ICO_SIZES[i] == 256 ? png(img) : dib(img);
        }
        int offset = 6 + 16 * images.length;
        int total = offset;
        for (byte[] b : images) total += b.length;
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putShort((short) 0).putShort((short) 1).putShort((short) images.length);
        for (int i = 0; i < images.length; i++) {
            int s = ICO_SIZES[i];
            out.put((byte) (s == 256 ? 0 : s)).put((byte) (s == 256 ? 0 : s)).put((byte) 0).put((byte) 0);
            out.putShort((short) 1).putShort((short) 32).putInt(images[i].length).putInt(offset);
            offset += images[i].length;
        }
        for (byte[] b : images) out.put(b);
        Files.write(file, out.array());
    }

    /** Apple's icon file: a big-endian header, then each size as a PNG under its four-letter type. */
    static byte[] icns(boolean server) throws IOException {
        ByteArrayOutputStream entries = new ByteArrayOutputStream();
        for (int i = 0; i < ICNS_TYPES.length; i++) {
            byte[] image = png(draw(ICNS_SIZES[i], server));
            entries.write(ICNS_TYPES[i].getBytes(StandardCharsets.US_ASCII));
            entries.write(ByteBuffer.allocate(4).putInt(8 + image.length).array());
            entries.write(image);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("icns".getBytes(StandardCharsets.US_ASCII));
        out.write(ByteBuffer.allocate(4).putInt(8 + entries.size()).array());
        entries.writeTo(out);
        return out.toByteArray();
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(img, "png", b);
        return b.toByteArray();
    }

    /** BITMAPINFOHEADER + bottom-up BGRA pixels + an all-zero AND mask (alpha does the work). */
    static byte[] dib(BufferedImage img) {
        int s = img.getWidth();
        int maskRow = ((s + 31) / 32) * 4;
        ByteBuffer b = ByteBuffer.allocate(40 + s * s * 4 + maskRow * s).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(40).putInt(s).putInt(s * 2).putShort((short) 1).putShort((short) 32)
                .putInt(0).putInt(s * s * 4).putInt(0).putInt(0).putInt(0).putInt(0);
        for (int y = s - 1; y >= 0; y--) {
            for (int x = 0; x < s; x++) {
                int argb = img.getRGB(x, y);
                b.put((byte) argb).put((byte) (argb >> 8)).put((byte) (argb >> 16)).put((byte) (argb >>> 24));
            }
        }
        return b.array(); // the mask bytes are already zero
    }
}
