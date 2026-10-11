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

import java.util.List;

/**
 * An export as the body of an email: inline HTML (tables with inline styles, no scripts, no remote images, every value escaped) and a plain
 * text twin. Tables are cut at {@code maxRows} with a line that says how many rows follow in the attachment or the link.
 */
public final class DocHtml {

    private static final String TH = "text-align:left;padding:4px 10px;border-bottom:1px solid #d5dae1;background:#eef1f5;font-size:12px;";
    private static final String TD = "padding:3px 10px;border-bottom:1px solid #eef1f5;font-size:12px;";

    private DocHtml() {}

    public static String html(ExportDoc doc, int maxRows) {
        StringBuilder b = new StringBuilder();
        b.append("<h2 style=\"margin:0 0 4px 0;\">").append(esc(doc.title())).append("</h2>");
        if (doc.subtitle() != null && !doc.subtitle().isBlank()) {
            b.append("<p style=\"margin:0 0 4px 0;color:#6b7686;\">").append(esc(doc.subtitle())).append("</p>");
        }
        for (String n : doc.notes()) {
            b.append("<p style=\"margin:0 0 2px 0;color:#6b7686;font-size:12px;\">").append(esc(n)).append("</p>");
        }
        for (ExportDoc.Section s : doc.sections()) {
            b.append("<h3 style=\"margin:18px 0 4px 0;\">").append(esc(s.title())).append("</h3>");
            if (s.note() != null && !s.note().isBlank()) {
                b.append("<p style=\"margin:0 0 4px 0;color:#6b7686;font-size:12px;\">").append(esc(s.note())).append("</p>");
            }
            if (s.rows().isEmpty()) {
                b.append("<p style=\"color:#6b7686;\">No data</p>");
                continue;
            }
            if (ExportDoc.CARD.equals(s.type())) {
                cards(b, s);
                continue;
            }
            b.append("<table style=\"border-collapse:collapse;\"><tr>");
            for (int c = 0; c < s.columns().size(); c++) {
                b.append("<th style=\"").append(TH).append(s.isNumeric(c) ? "text-align:right;" : "").append("\">").append(esc(s.columns().get(c))).append("</th>");
            }
            b.append("</tr>");
            int shown = Math.min(s.rows().size(), maxRows);
            for (int r = 0; r < shown; r++) {
                b.append("<tr>");
                List<Object> row = s.rows().get(r);
                for (int c = 0; c < s.columns().size(); c++) {
                    Object v = c < row.size() ? row.get(c) : null;
                    b.append("<td style=\"").append(TD).append(v instanceof Number ? "text-align:right;" : "").append("\">").append(esc(DocFmt.cell(v))).append("</td>");
                }
                b.append("</tr>");
            }
            b.append("</table>");
            if (s.rows().size() > shown) {
                b.append("<p style=\"color:#6b7686;font-size:12px;\">… ").append(s.rows().size() - shown).append(" more rows, in the full report</p>");
            }
        }
        return b.toString();
    }

    private static void cards(StringBuilder b, ExportDoc.Section s) {
        List<Object> row = s.rows().get(0);
        b.append("<table style=\"border-collapse:collapse;\"><tr>");
        for (int c = 0; c < s.columns().size(); c++) {
            b.append("<td style=\"padding:6px 18px 6px 0;\"><div style=\"color:#6b7686;font-size:12px;\">").append(esc(s.columns().get(c)))
                    .append("</div><div style=\"font-size:20px;font-weight:bold;\">").append(esc(cardValue(s, row, c))).append("</div></td>");
        }
        b.append("</tr></table>");
    }

    /** The body of a mail that carries the report as a file: title, as-of, filters, what the file holds. */
    public static String summaryHtml(ExportDoc doc, String file) {
        StringBuilder b = new StringBuilder("<h2 style=\"margin:0 0 4px 0;\">").append(esc(doc.title())).append("</h2>");
        if (doc.subtitle() != null && !doc.subtitle().isBlank()) {
            b.append("<p style=\"margin:0 0 4px 0;color:#6b7686;\">").append(esc(doc.subtitle())).append("</p>");
        }
        doc.notes().forEach(n -> b.append("<p style=\"margin:0 0 2px 0;color:#6b7686;font-size:12px;\">").append(esc(n)).append("</p>"));
        b.append("<p style=\"margin:14px 0 4px 0;\">The report is attached: <b>").append(esc(file)).append("</b>.</p><ul>");
        for (ExportDoc.Section s : doc.sections()) {
            b.append("<li>").append(esc(s.title())).append(": ").append(s.rows().size()).append(s.rows().size() == 1 ? " row" : " rows").append("</li>");
        }
        return b.append("</ul>").toString();
    }

    public static String summaryText(ExportDoc doc, String file) {
        StringBuilder b = new StringBuilder(doc.title()).append('\n');
        if (doc.subtitle() != null && !doc.subtitle().isBlank()) {
            b.append(doc.subtitle()).append('\n');
        }
        doc.notes().forEach(n -> b.append(n).append('\n'));
        b.append("\nThe report is attached: ").append(file).append("\n");
        doc.sections().forEach(s -> b.append("- ").append(s.title()).append(": ").append(s.rows().size()).append(" rows\n"));
        return b.toString();
    }

    /** A card's value as a page shows it; for a masked measure with nothing to show, the word that says why. */
    static String cardValue(ExportDoc.Section s, List<Object> row, int c) {
        String v = DocFmt.cell(c < row.size() ? row.get(c) : null);
        return v.isEmpty() && s.masked().contains(s.columns().get(c)) ? "(masked)" : v;
    }

    public static String text(ExportDoc doc, int maxRows) {
        StringBuilder b = new StringBuilder(doc.title()).append('\n');
        if (doc.subtitle() != null && !doc.subtitle().isBlank()) {
            b.append(doc.subtitle()).append('\n');
        }
        doc.notes().forEach(n -> b.append(n).append('\n'));
        for (ExportDoc.Section s : doc.sections()) {
            b.append('\n').append(s.title()).append('\n');
            if (s.note() != null && !s.note().isBlank()) {
                b.append(s.note()).append('\n');
            }
            if (s.rows().isEmpty()) {
                b.append("No data\n");
                continue;
            }
            if (ExportDoc.CARD.equals(s.type())) {
                for (int c = 0; c < s.columns().size(); c++) {
                    b.append(s.columns().get(c)).append(": ").append(cardValue(s, s.rows().get(0), c)).append('\n');
                }
                continue;
            }
            b.append(String.join(" | ", s.columns())).append('\n');
            int shown = Math.min(s.rows().size(), maxRows);
            for (int r = 0; r < shown; r++) {
                List<Object> row = s.rows().get(r);
                StringBuilder line = new StringBuilder();
                for (int c = 0; c < s.columns().size(); c++) {
                    line.append(c > 0 ? " | " : "").append(DocFmt.cell(c < row.size() ? row.get(c) : null));
                }
                b.append(line).append('\n');
            }
            if (s.rows().size() > shown) {
                b.append("… ").append(s.rows().size() - shown).append(" more rows, in the full report\n");
            }
        }
        return b.toString();
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replaceAll("[\\p{Cntrl}&&[^\n\t]]", " ");
    }
}
