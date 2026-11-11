/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.server.collab.snapshot;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Rasterises a {@link SnapshotModel} to PNG with Java2D, headless, with the JDK's logical fonts ({@code SansSerif},
 * {@code Monospaced}): nothing is downloaded and no browser or display is needed.
 */
public final class SnapshotPainter {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    public byte[] png(SnapshotModel m) throws IOException {
        BufferedImage img = new BufferedImage(m.width(), m.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, m.width(), m.height());
            for (SnapshotModel.Item i : m.items()) {
                draw(g, i);
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        if (!ImageIO.write(img, "png", out)) {
            throw new IOException("no PNG writer");
        }
        return out.toByteArray();
    }

    private static void draw(Graphics2D g, SnapshotModel.Item item) {
        g.setStroke(new BasicStroke(1f));
        switch (item) {
            case SnapshotModel.Box b -> {
                g.setColor(new Color(b.color()));
                if (b.fill()) {
                    g.fillRect(b.x(), b.y(), b.w(), b.h());
                } else {
                    g.drawRect(b.x(), b.y(), b.w(), b.h());
                }
            }
            case SnapshotModel.Line l -> {
                g.setColor(new Color(l.color()));
                g.drawLine(l.x1(), l.y1(), l.x2(), l.y2());
            }
            case SnapshotModel.Poly p -> {
                g.setColor(new Color(p.color()));
                g.setStroke(new BasicStroke(1.6f));
                g.drawPolyline(p.xs(), p.ys(), p.xs().length);
            }
            case SnapshotModel.Text t -> {
                var old = g.getTransform();
                var oldComposite = g.getComposite();
                g.setFont(new Font(t.mono() ? Font.MONOSPACED : Font.SANS_SERIF, t.bold() ? Font.BOLD : Font.PLAIN, t.size()));
                g.setColor(new Color(t.color()));
                if (t.alpha() < 1f) {
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, t.alpha()));
                }
                if (t.angle() != 0) {
                    g.rotate(Math.toRadians(t.angle()), t.x(), t.y());
                }
                g.drawString(t.text(), t.x(), t.y());
                g.setTransform(old);
                g.setComposite(oldComposite);
            }
        }
    }
}
