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
package com.ash.drishti.server.dist;

import com.ash.drishti.server.collab.snapshot.SnapshotModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * A paged PDF written by hand (PDF 1.4, the standard Helvetica and Courier fonts, Flate-compressed pages) from a {@link SnapshotModel}: the
 * model is a tall picture in pixels; page {@code k} shows the rows {@code k * slice} to {@code (k + 1) * slice}, scaled to the page width,
 * under a running header and a footer with the page number. Nothing is downloaded and no browser is needed. The layout that made the model
 * keeps its blocks inside the slices ({@link DocLayout}); a model from elsewhere is cut at the slice edge.
 */
public final class PdfWriter {

    private static final Charset WIN_ANSI = Charset.forName("windows-1252");
    private static final double MARGIN = 28;
    private static final double TOP = 34;                  // room for the running header
    private static final double BOTTOM = 30;               // room for the footer

    /** The page geometry for a layout of {@code width} pixels: points of the page, and how many model pixels one page shows. */
    public record Geometry(double pageWidth, double pageHeight, int width, int slice) {

        public static Geometry of(boolean landscape, int width) {
            double w = landscape ? 842 : 595;
            double h = landscape ? 595 : 842;
            double scale = (w - 2 * MARGIN) / width;
            return new Geometry(w, h, width, (int) Math.floor((h - TOP - BOTTOM) / scale));
        }

        double scale() {
            return (pageWidth - 2 * MARGIN) / width;
        }
    }

    private PdfWriter() {}

    /**
     * @param header the running header's left text (the title) and right text (the as-of line and filters)
     * @param footer the footer's left text (who it was made for, and when)
     */
    public static byte[] write(SnapshotModel model, Geometry g, String headerLeft, String headerRight, String footer) {
        int pages = Math.max(1, (int) Math.ceil(model.height() / (double) g.slice()));
        List<byte[]> objects = new ArrayList<>();
        Map<Float, Integer> alphas = new LinkedHashMap<>();
        List<byte[]> contents = new ArrayList<>();
        for (int k = 0; k < pages; k++) {
            contents.add(page(model, g, k, pages, headerLeft, headerRight, footer, alphas));
        }
        // object numbers: 1 catalog, 2 pages, 3..6 fonts, 7 resources, then alpha states, then (content, page) pairs
        int firstAlpha = 7;                                  // the alpha state numbered i is object 7 + i
        StringBuilder gs = new StringBuilder();
        for (Map.Entry<Float, Integer> e : alphas.entrySet()) {
            gs.append("/GS").append(e.getValue()).append(' ').append(firstAlpha + e.getValue()).append(" 0 R ");
        }
        int firstPage = 8 + alphas.size();
        objects.add(bytes("<< /Type /Catalog /Pages 2 0 R >>"));
        StringBuilder kids = new StringBuilder();
        for (int k = 0; k < pages; k++) {
            kids.append(firstPage + 2 * k + 1).append(" 0 R ");
        }
        objects.add(bytes("<< /Type /Pages /Kids [" + kids + "] /Count " + pages + " >>"));
        for (String font : new String[] {"Helvetica", "Helvetica-Bold", "Courier", "Courier-Bold"}) {
            objects.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /" + font + " /Encoding /WinAnsiEncoding >>"));
        }
        objects.add(bytes("<< /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R /F4 6 0 R >> /ExtGState << " + gs + ">> >>"));
        for (Map.Entry<Float, Integer> e : alphas.entrySet()) {
            objects.add(bytes("<< /Type /ExtGState /ca " + e.getKey() + " /CA " + e.getKey() + " >>"));
        }
        for (int k = 0; k < pages; k++) {
            byte[] raw = contents.get(k);
            byte[] packed = deflate(raw);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            write(o, "<< /Length " + packed.length + " /Filter /FlateDecode >>\nstream\n");
            o.writeBytes(packed);
            write(o, "\nendstream");
            objects.add(o.toByteArray());
            objects.add(bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + g.pageWidth() + " " + g.pageHeight() + "] /Resources 7 0 R /Contents "
                    + (firstPage + 2 * k) + " 0 R >>"));
        }
        return assemble(objects);
    }

    private static byte[] page(SnapshotModel m, Geometry g, int k, int pages, String hl, String hr, String footer, Map<Float, Integer> alphas) {
        double s = g.scale();
        double top = g.pageHeight() - TOP;
        int y0 = k * g.slice();
        int y1 = y0 + g.slice();
        StringBuilder c = new StringBuilder(8192);
        // running header and footer, in the margins
        c.append(textOp(1, 8, 0x6B7686, MARGIN, g.pageHeight() - MARGIN + 6, 0, clip(hl, 60)));
        String right = clip(hr, 110);
        c.append(textOp(1, 8, 0x6B7686, g.pageWidth() - MARGIN - width(right, 8), g.pageHeight() - MARGIN + 6, 0, right));
        c.append("0.82 0.85 0.88 RG 0.5 w ").append(f(MARGIN)).append(' ').append(f(g.pageHeight() - MARGIN + 2)).append(" m ")
                .append(f(g.pageWidth() - MARGIN)).append(' ').append(f(g.pageHeight() - MARGIN + 2)).append(" l S\n");
        c.append(textOp(1, 8, 0x6B7686, MARGIN, MARGIN - 6, 0, clip(footer, 130)));
        String pg = "Page " + (k + 1) + " of " + pages;
        c.append(textOp(1, 8, 0x6B7686, g.pageWidth() - MARGIN - width(pg, 8), MARGIN - 6, 0, pg));
        // the slice of the model, clipped to the content area
        c.append("q ").append(f(MARGIN)).append(' ').append(f(top - g.slice() * s)).append(' ').append(f(g.pageWidth() - 2 * MARGIN)).append(' ')
                .append(f(g.slice() * s)).append(" re W n\n");
        for (SnapshotModel.Item item : m.items()) {
            switch (item) {
                case SnapshotModel.Box b -> {
                    if (b.y() < y1 && b.y() + b.h() > y0) {
                        c.append(color(b.color(), b.fill() ? "rg" : "RG")).append(f(MARGIN + b.x() * s)).append(' ').append(f(top - (b.y() + b.h() - y0) * s)).append(' ')
                                .append(f(b.w() * s)).append(' ').append(f(b.h() * s)).append(b.fill() ? " re f\n" : " re S\n");
                    }
                }
                case SnapshotModel.Line l -> {
                    if (Math.max(l.y1(), l.y2()) >= y0 && Math.min(l.y1(), l.y2()) < y1) {
                        c.append(color(l.color(), "RG")).append("0.75 w ").append(f(MARGIN + l.x1() * s)).append(' ').append(f(top - (l.y1() - y0) * s)).append(" m ")
                                .append(f(MARGIN + l.x2() * s)).append(' ').append(f(top - (l.y2() - y0) * s)).append(" l S\n");
                    }
                }
                case SnapshotModel.Poly p -> {
                    if (p.xs().length > 1) {
                        c.append(color(p.color(), "RG")).append(f(1.4 * s)).append(" w ");
                        for (int i = 0; i < p.xs().length; i++) {
                            c.append(f(MARGIN + p.xs()[i] * s)).append(' ').append(f(top - (p.ys()[i] - y0) * s)).append(i == 0 ? " m " : " l ");
                        }
                        c.append("S\n");
                    }
                }
                case SnapshotModel.Text t -> {
                    boolean rotated = t.angle() != 0;
                    if (rotated ? t.y() > y0 - 140 && t.y() < y1 + 140 : t.y() >= y0 && t.y() - t.size() < y1) {
                        boolean faint = t.alpha() < 1f;
                        if (faint) {
                            c.append("q /GS").append(alphas.computeIfAbsent(t.alpha(), a -> alphas.size() + 1)).append(" gs\n");
                        }
                        c.append(textOp(t.mono() ? (t.bold() ? 4 : 3) : (t.bold() ? 2 : 1), t.size() * s, t.color(), MARGIN + t.x() * s, top - (t.y() - y0) * s, -t.angle(), t.text()));
                        if (faint) {
                            c.append("Q\n");
                        }
                    }
                }
            }
        }
        c.append("Q\n");
        return c.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    static String textOp(int font, double size, int rgb, double x, double y, double degrees, String text) {
        double rad = Math.toRadians(degrees);
        return "BT /F" + font + ' ' + f(size) + " Tf " + color(rgb, "rg") + f(Math.cos(rad)) + ' ' + f(Math.sin(rad)) + ' ' + f(-Math.sin(rad)) + ' ' + f(Math.cos(rad)) + ' '
                + f(x) + ' ' + f(y) + " Tm (" + escape(text) + ") Tj ET\n";
    }

    static String color(int rgb, String op) {
        return f(((rgb >> 16) & 0xFF) / 255.0) + ' ' + f(((rgb >> 8) & 0xFF) / 255.0) + ' ' + f((rgb & 0xFF) / 255.0) + ' ' + op + ' ';
    }

    /** A PDF literal string in WinAnsi, kept as ISO-8859-1 characters so the content stream can be written as bytes. */
    static String escape(String s) {
        byte[] b = (s == null ? "" : s.replaceAll("[\\p{Cntrl}]+", " ")).getBytes(WIN_ANSI);
        StringBuilder out = new StringBuilder(b.length + 8);
        for (byte x : b) {
            char ch = (char) (x & 0xFF);
            if (ch == '(' || ch == ')' || ch == '\\') {
                out.append('\\');
            }
            out.append(ch);
        }
        return out.toString();
    }

    private static double width(String s, double size) {
        return s.length() * size * 0.5;
    }

    private static String clip(String s, int max) {
        String t = s == null ? "" : s;
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    static String f(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    static void write(ByteArrayOutputStream o, String s) {
        o.writeBytes(bytes(s));
    }

    static byte[] deflate(byte[] raw) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(raw.length / 3 + 64);
        try (DeflaterOutputStream d = new DeflaterOutputStream(o, new Deflater(Deflater.BEST_SPEED))) {
            d.write(raw);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return o.toByteArray();
    }

    static byte[] assemble(List<byte[]> objects) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(64 * 1024);
        write(o, "%PDF-1.4\n%âãÏÓ\n");
        int[] offsets = new int[objects.size()];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i] = o.size();
            write(o, (i + 1) + " 0 obj\n");
            o.writeBytes(objects.get(i));
            write(o, "\nendobj\n");
        }
        int xref = o.size();
        write(o, "xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (int off : offsets) {
            write(o, String.format(Locale.ROOT, "%010d 00000 n \n", off));
        }
        write(o, "trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return o.toByteArray();
    }
}
