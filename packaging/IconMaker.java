import com.hamza.icons.IconDrawing;
import com.hamza.icons.IconSet;

import java.awt.*;
import java.awt.geom.*;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Draws the app icon and writes every file that uses it. Only the drawing is here; the files are written by the
 * app-icons library the programs share (github.com/MohamedSalah-13/app-icons), a test-scope dependency in pom.xml,
 * so Maven has already downloaded its jar. Run from the project root:
 * <pre>java -cp ~/.m2/repository/com/github/MohamedSalah-13/app-icons/v0.1.0/app-icons-v0.1.0.jar packaging/IconMaker.java</pre>
 * (on Windows the jar is under {@code %USERPROFILE%\.m2}).
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

    static final Color TILE_TOP = new Color(0x4F46E5);    // indigo
    static final Color TILE_BOTTOM = new Color(0x0891B2); // teal
    static final Color ARROW = new Color(0x4338CA);
    static final Color BADGE = new Color(0x0F172A);
    static final Color BADGE_BAR = new Color(0xE2E8F0);
    static final Color BADGE_LIGHT = new Color(0x22C55E);

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        for (boolean server : new boolean[]{false, true}) {
            String name = server ? "server" : "client";
            IconSet.of(IconDrawing.grid(256, (g, size) -> draw(g, size, server)))
                    .ico("packaging/" + name + ".ico")
                    .png("packaging/" + name + ".png", 512)
                    .icns("packaging/" + name + ".icns")
                    .pngs("src/main/resources/icons/" + name + "-%d.png")
                    .write(root);
        }
        System.out.println("Icons written under " + root.toAbsolutePath().normalize());
    }

    /** Draws on a 256x256 design grid; {@code size} is the size in pixels, for dropping detail at the small ones. */
    static void draw(Graphics2D g, int size, boolean server) {
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
}
