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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A statement's pages ({@link StatementPages}) as a PDF 1.4 file, written by hand like the other exports ({@link PdfWriter}: the standard Helvetica fonts,
 * Flate-compressed pages, nothing downloaded): every page has exactly the size and the content the layout gave it, so the paper is page-exact. The document's
 * title is set in its information dictionary.
 */
final class StatementPdf {

    private StatementPdf() {}

    static byte[] write(StatementPages doc, String title) {
        List<byte[]> objects = new ArrayList<>();
        int pages = doc.pages().size();
        // object numbers: 1 catalog, 2 pages, 3 Helvetica, 4 Helvetica-Bold, 5 resources, 6 info, then (content, page) pairs
        int firstPage = 7;
        objects.add(PdfWriter.bytes("<< /Type /Catalog /Pages 2 0 R >>"));
        StringBuilder kids = new StringBuilder();
        for (int k = 0; k < pages; k++) {
            kids.append(firstPage + 2 * k + 1).append(" 0 R ");
        }
        objects.add(PdfWriter.bytes("<< /Type /Pages /Kids [" + kids + "] /Count " + pages + " >>"));
        objects.add(PdfWriter.bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));
        objects.add(PdfWriter.bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"));
        objects.add(PdfWriter.bytes("<< /Font << /F1 3 0 R /F2 4 0 R >> >>"));
        objects.add(PdfWriter.bytes("<< /Title (" + PdfWriter.escape(title) + ") /Producer (Drishti statements) >>"));
        String box = "[0 0 " + PdfWriter.f(doc.width()) + " " + PdfWriter.f(doc.height()) + "]";
        for (StatementPages.Page p : doc.pages()) {
            byte[] packed = PdfWriter.deflate(content(p));
            ByteArrayOutputStream o = new ByteArrayOutputStream(packed.length + 64);
            PdfWriter.write(o, "<< /Length " + packed.length + " /Filter /FlateDecode >>\nstream\n");
            o.writeBytes(packed);
            PdfWriter.write(o, "\nendstream");
            objects.add(o.toByteArray());
            objects.add(PdfWriter.bytes("<< /Type /Page /Parent 2 0 R /MediaBox " + box + " /Resources 5 0 R /Contents " + (objects.size()) + " 0 R >>"));
        }
        byte[] pdf = PdfWriter.assemble(objects);
        // the trailer names the information dictionary (object 6)
        String text = new String(pdf, StandardCharsets.ISO_8859_1).replace("/Root 1 0 R >>", "/Root 1 0 R /Info 6 0 R >>");
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] content(StatementPages.Page p) {
        StringBuilder c = new StringBuilder(8192);
        for (StatementPages.Op op : p.ops()) {
            switch (op) {
                case StatementPages.Fill f -> c.append(PdfWriter.color(f.rgb(), "rg")).append(PdfWriter.f(f.x())).append(' ').append(PdfWriter.f(f.y())).append(' ')
                        .append(PdfWriter.f(f.w())).append(' ').append(PdfWriter.f(f.h())).append(" re f\n");
                case StatementPages.Rule r -> c.append(PdfWriter.color(r.rgb(), "RG")).append(PdfWriter.f(r.width())).append(" w ").append(PdfWriter.f(r.x1())).append(' ')
                        .append(PdfWriter.f(r.y1())).append(" m ").append(PdfWriter.f(r.x2())).append(' ').append(PdfWriter.f(r.y2())).append(" l S\n");
                case StatementPages.Text t -> c.append(PdfWriter.textOp(t.bold() ? 2 : 1, t.size(), t.rgb(), t.x(), t.y(), 0, t.text()));
            }
        }
        return c.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
