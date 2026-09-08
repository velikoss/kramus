package com.ramussoft.idef0.textimport;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Renders a {@link DfdDsl.Model} (after {@link DfdLayout#layout}) straight to
 * a PNG — a quick visual check of what the auto-layout produced, drawn from
 * the exact same node positions and {@link DfdLayout#routeFlow} paths that
 * {@code DfdTextImportMain} writes into the .rms sectors. Not a reproduction
 * of Ramus's own DFD shape classes (DFDFunctionEllipse/DataStore/External),
 * just enough of the same visual language (ellipse / open-box / double-corner
 * box) to sanity-check node placement and arrow routing at a glance.
 */
public class DfdPngRenderer {

    public static void render(DfdDsl.Model model, File outFile, double scale) throws IOException {
        int width = (int) Math.round((DfdLayout.PAGE_RIGHT + 150) * scale);
        int height = (int) Math.round((DfdLayout.PAGE_BOTTOM + 150) * scale);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);

        Font titleFont = new Font("SansSerif", Font.BOLD, (int) Math.round(28 * scale));
        Font nodeFont = new Font("SansSerif", Font.PLAIN, (int) Math.round(20 * scale));
        Font labelFont = new Font("SansSerif", Font.ITALIC, (int) Math.round(16 * scale));

        g.setColor(Color.BLACK);
        g.setFont(titleFont);
        g.drawString(model.title, (int) (30 * scale), (int) (40 * scale));

        g.setStroke(new BasicStroke((float) (1.5 * scale)));
        g.setFont(labelFont);
        for (DfdDsl.Flow flow : model.flows) {
            DfdDsl.Node from = model.nodes.get(flow.fromId);
            DfdDsl.Node to = model.nodes.get(flow.toId);
            List<double[]> pts = DfdLayout.routeFlow(from, to);

            GeneralPath path = new GeneralPath();
            path.moveTo(pts.get(0)[0] * scale, pts.get(0)[1] * scale);
            for (int i = 1; i < pts.size(); i++)
                path.lineTo(pts.get(i)[0] * scale, pts.get(i)[1] * scale);
            g.setColor(new Color(60, 60, 60));
            g.draw(path);

            double[] end = pts.get(pts.size() - 1);
            double[] prev = pts.get(pts.size() - 2);
            drawArrowHead(g, prev[0] * scale, prev[1] * scale, end[0] * scale, end[1] * scale, 10 * scale);

            double[] mid = pts.get(pts.size() / 2);
            g.setColor(Color.BLACK);
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(flow.text);
            g.drawString(flow.text, (float) (mid[0] * scale - tw / 2.0), (float) (mid[1] * scale - 8 * scale));
        }

        g.setFont(nodeFont);
        for (DfdDsl.Node node : model.nodes.values()) {
            double x = (node.cx - node.w / 2) * scale;
            double y = (node.cy - node.h / 2) * scale;
            double w = node.w * scale;
            double h = node.h * scale;

            g.setColor(new Color(235, 244, 255));
            g.setStroke(new BasicStroke((float) (2 * scale)));
            switch (node.kind) {
                case "process": {
                    Ellipse2D shape = new Ellipse2D.Double(x, y, w, h);
                    g.fill(shape);
                    g.setColor(Color.BLACK);
                    g.draw(shape);
                    break;
                }
                case "external": {
                    Rectangle2D shape = new Rectangle2D.Double(x, y, w, h);
                    g.fill(shape);
                    g.setColor(Color.BLACK);
                    g.draw(shape);
                    double d = 6 * scale;
                    g.draw(new Line2D.Double(x + d, y, x + d, y + d));
                    g.draw(new Line2D.Double(x, y + d, x + d, y + d));
                    g.draw(new Line2D.Double(x + w - d, y + h, x + w - d, y + h - d));
                    g.draw(new Line2D.Double(x + w, y + h - d, x + w - d, y + h - d));
                    break;
                }
                default: { // store: classic open-ended box (no right edge)
                    GeneralPath shape = new GeneralPath();
                    shape.moveTo(x + w, y);
                    shape.lineTo(x, y);
                    shape.lineTo(x, y + h);
                    shape.lineTo(x + w, y + h);
                    g.setColor(new Color(235, 244, 255));
                    Rectangle2D fillArea = new Rectangle2D.Double(x, y, w, h);
                    g.fill(fillArea);
                    g.setColor(Color.BLACK);
                    g.draw(shape);
                    break;
                }
            }

            g.setColor(Color.BLACK);
            FontMetrics fm = g.getFontMetrics();
            String[] lines = wrap(node.name, fm, (int) (w - 12 * scale));
            int lineHeight = fm.getHeight();
            int totalH = lines.length * lineHeight;
            int ty = (int) (y + h / 2 - totalH / 2.0 + fm.getAscent());
            for (String line : lines) {
                int tw = fm.stringWidth(line);
                g.drawString(line, (int) (x + w / 2 - tw / 2.0), ty);
                ty += lineHeight;
            }
        }

        g.dispose();
        ImageIO.write(image, "png", outFile);
    }

    private static String[] wrap(String text, FontMetrics fm, int maxWidth) {
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String word : words) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(candidate) > maxWidth && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0)
            lines.add(line.toString());
        return lines.toArray(new String[0]);
    }

    private static void drawArrowHead(Graphics2D g, double fromX, double fromY, double toX, double toY, double size) {
        double angle = Math.atan2(toY - fromY, toX - fromX);
        GeneralPath head = new GeneralPath();
        head.moveTo(toX, toY);
        head.lineTo(toX - size * Math.cos(angle - Math.PI / 6), toY - size * Math.sin(angle - Math.PI / 6));
        head.lineTo(toX - size * Math.cos(angle + Math.PI / 6), toY - size * Math.sin(angle + Math.PI / 6));
        head.closePath();
        g.setColor(new Color(60, 60, 60));
        g.fill(head);
    }
}
