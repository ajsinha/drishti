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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A statement laid out on paper (2.6.0): its pages, each a list of positioned text, rules and fills in PDF points (1/72 inch, the origin at the bottom left
 * of the page). The PDF writer ({@link StatementPdf}) and the BI Studio's page-by-page preview draw the same pages, so what the preview shows is what prints.
 */
public record StatementPages(double width, double height, List<Page> pages) {

    public StatementPages {
        pages = List.copyOf(pages);
    }

    /** One drawing operation. */
    public sealed interface Op permits Text, Rule, Fill {}

    /**
     * A line of text, its left end at {@code x} on the baseline {@code y}.
     *
     * @param role what it is, for the preview and tests: {@code header}, {@code footer}, {@code title}, {@code head} (column titles), {@code cell}, {@code text}
     */
    public record Text(double x, double y, double size, boolean bold, int rgb, String text, String role) implements Op {}

    /** A straight line. */
    public record Rule(double x1, double y1, double x2, double y2, double width, int rgb) implements Op {}

    /** A filled rectangle, its lower left corner at {@code (x, y)}. */
    public record Fill(double x, double y, double w, double h, int rgb) implements Op {}

    /** One page: its number (from 1) and what is drawn on it, in order. */
    public record Page(int number, List<Op> ops) {

        public Page {
            ops = List.copyOf(ops);
        }

        /** The texts of the page with the given role, top to bottom (header, footer, cell, ...). */
        public List<String> texts(String role) {
            return ops.stream().filter(o -> o instanceof Text t && t.role().equals(role)).map(o -> ((Text) o).text()).toList();
        }

        /** Every text of the page, in drawing order. */
        public List<String> texts() {
            return ops.stream().filter(Text.class::isInstance).map(o -> ((Text) o).text()).toList();
        }
    }

    /** The pages as the preview reads them: sizes in points and each page's operations as small objects. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("width", round(width));
        m.put("height", round(height));
        m.put("pageCount", pages.size());
        List<Object> ps = new ArrayList<>();
        for (Page p : pages) {
            List<Object> ops = new ArrayList<>(p.ops().size());
            for (Op o : p.ops()) {
                Map<String, Object> x = new LinkedHashMap<>();
                switch (o) {
                    case Text t -> {
                        x.put("t", "text");
                        x.put("x", round(t.x()));
                        x.put("y", round(t.y()));
                        x.put("size", round(t.size()));
                        x.put("bold", t.bold());
                        x.put("rgb", String.format("#%06x", t.rgb() & 0xFFFFFF));
                        x.put("text", t.text());
                        x.put("role", t.role());
                    }
                    case Rule r -> {
                        x.put("t", "rule");
                        x.put("x1", round(r.x1()));
                        x.put("y1", round(r.y1()));
                        x.put("x2", round(r.x2()));
                        x.put("y2", round(r.y2()));
                        x.put("w", round(r.width()));
                        x.put("rgb", String.format("#%06x", r.rgb() & 0xFFFFFF));
                    }
                    case Fill f -> {
                        x.put("t", "fill");
                        x.put("x", round(f.x()));
                        x.put("y", round(f.y()));
                        x.put("w", round(f.w()));
                        x.put("h", round(f.h()));
                        x.put("rgb", String.format("#%06x", f.rgb() & 0xFFFFFF));
                    }
                }
                ops.add(x);
            }
            ps.add(Map.of("number", p.number(), "ops", ops));
        }
        m.put("pages", ps);
        return m;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
