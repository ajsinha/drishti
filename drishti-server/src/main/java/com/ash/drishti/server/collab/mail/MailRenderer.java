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
package com.ash.drishti.server.collab.mail;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds a message from what the recipient may know: the product, a headline, optional detail lines (kind, id, panel, date, the
 * note as they see it) and the link. Never a value from the data. Every inserted piece is escaped for its format, subjects lose
 * CR and LF, and the HTML has no remote images, scripts or tracking.
 */
public final class MailRenderer {

    /** What a message says, before it is poured into a template. {@code note} is already scrubbed for the recipient; null = none. */
    public record Content(String template, String headline, String kind, String entityId, String panel, String when, String note, String link) {}

    private final MailTemplates templates;
    private final String product;
    private final String host;

    public MailRenderer(MailTemplates templates, String product, String host) {
        this.templates = templates;
        this.product = product == null || product.isBlank() ? "Drishti" : product;
        this.host = host == null || host.isBlank() ? "localhost" : host;
    }

    public RenderedMail render(Content c, String to, long seq, String ref) {
        Map<String, String> text = vars(c, false);
        Map<String, String> html = vars(c, true);
        String subject = oneLine(MailTemplates.fill(templates.load(c.template(), "subject"), text));
        return new RenderedMail(oneLine(to), subject, MailTemplates.fill(templates.load(c.template(), "txt"), text),
                MailTemplates.fill(templates.load(c.template(), "html"), html), "<" + seq + "." + safeId(ref) + "@" + host + ">");
    }

    private Map<String, String> vars(Content c, boolean html) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("product", esc(product, html));
        v.put("headline", esc(c.headline(), html));
        v.put("detail", html ? detailHtml(c) : detailText(c));
        v.put("link", esc(c.link(), html));
        return v;
    }

    private String detailText(Content c) {
        StringBuilder b = new StringBuilder();
        line(b, "View", label(c), false);
        line(b, "Panel", c.panel(), false);
        line(b, "As of", c.when(), false);
        if (c.note() != null && !c.note().isBlank()) {
            b.append("\nNote:\n").append(clean(c.note())).append('\n');
        }
        return b.toString();
    }

    private String detailHtml(Content c) {
        StringBuilder b = new StringBuilder();
        line(b, "View", label(c), true);
        line(b, "Panel", c.panel(), true);
        line(b, "As of", c.when(), true);
        if (c.note() != null && !c.note().isBlank()) {
            b.append("<blockquote style=\"margin:12px 0;padding:8px 12px;border-left:3px solid #999;\">")
                    .append(esc(clean(c.note()), true).replace("\n", "<br>")).append("</blockquote>");
        }
        return b.toString();
    }

    private static String label(Content c) {
        if (c.kind() == null || c.kind().isBlank()) {
            return null;
        }
        return c.entityId() == null || c.entityId().isBlank() ? c.kind() : c.kind() + " " + c.entityId();
    }

    private static void line(StringBuilder b, String name, String value, boolean html) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (html) {
            b.append("<p style=\"margin:4px 0;\"><b>").append(name).append(":</b> ").append(esc(oneLine(value), true)).append("</p>");
        } else {
            b.append(name).append(": ").append(oneLine(value)).append('\n');
        }
    }

    /** Text without control characters other than line breaks and tabs. */
    private static String clean(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n').replaceAll("[\\p{Cntrl}&&[^\n\t]]", "");
    }

    static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("[\\p{Cntrl}]+", " ").strip();
    }

    private static String safeId(String ref) {
        return ref == null ? "x" : ref.replaceAll("[^A-Za-z0-9_.-]", "");
    }

    static String esc(String s, boolean html) {
        if (s == null) {
            return "";
        }
        if (!html) {
            return s;
        }
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(ch);
            }
        }
        return b.toString();
    }
}
