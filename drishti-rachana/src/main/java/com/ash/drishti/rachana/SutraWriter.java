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
package com.ash.drishti.rachana;

import com.ash.drishti.rachana.model.Column;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.StripItem;
import com.ash.drishti.rachana.model.Sutra;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes a {@link Sutra} back to Rachana YAML. Sutra Studio uses it to turn an inferred layout into a
 * starting Sutra ("start from inference"), so a person edits a working layout instead of a blank page.
 * The output parses back to an equal model.
 */
public final class SutraWriter {

    public String write(Sutra s, String name, int version) {
        return write(s, name, version, "Started from inference for " + s.match().kind() + ". Edit freely.");
    }

    /** As {@link #write(Sutra, String, int)} with the given description (a one-line, plain sentence). */
    public String write(Sutra s, String name, int version, String description) {
        StringBuilder y = new StringBuilder();
        y.append("rachana: ").append(com.ash.drishti.rachana.parse.SutraParser.LANGUAGE).append('\n');
        y.append("sutra: ").append(name).append('\n');
        y.append("version: ").append(version).append('\n');
        y.append("description: ").append(description.matches("[A-Za-z0-9 .,()/-]*") ? description : q(description)).append('\n');
        y.append("match: { kind: ").append(s.match().kind());
        if (s.match().where() != null) {
            y.append(", where: ").append(q(s.match().where()));
        }
        y.append(", priority: ").append(Math.max(1, s.match().priority())).append(" }\n");
        if (s.title() != null) {
            y.append("title: { ");
            if (s.title().pill() != null) {
                y.append("pill: ").append(q(s.title().pill())).append(", ");
            }
            y.append("id: ").append(q(s.title().id()));
            if (s.title().with() != null) {
                y.append(", with: ").append(q(s.title().with()));
            }
            y.append(" }\n");
        }
        if (!s.strip().isEmpty()) {
            y.append("strip:\n");
            for (StripItem i : s.strip()) {
                y.append("  - { label: ").append(q(i.label())).append(", bind: ").append(q(i.bind()));
                opt(y, "fmt", i.fmt());
                opt(y, "tone", i.tone());
                if (i.emphasis()) {
                    y.append(", emphasis: true");
                }
                y.append(" }\n");
            }
        }
        y.append("panels:\n");
        for (Panel p : s.panels()) {
            panel(y, p, "  - ", "    ");
        }
        if (!s.keys().isEmpty()) {
            y.append("keys: {");
            boolean first = true;
            for (Map.Entry<String, String> e : new java.util.TreeMap<>(s.keys()).entrySet()) {
                y.append(first ? " " : ", ").append(e.getKey()).append(": ").append(q(e.getValue()));
                first = false;
            }
            y.append(" }\n");
        }
        return y.toString();
    }

    private void panel(StringBuilder y, Panel p, String first, String ind) {
        y.append(first).append("id: ").append(p.id()).append('\n');
        y.append(ind).append("kind: ").append(p.kind().id()).append('\n');
        if (p.title() != null) {
            y.append(ind).append("title: ").append(q(p.title())).append('\n');
        }
        line(y, ind, "key", p.key());
        line(y, ind, "code", p.code());
        if (p.area() != null && !"MAIN".equals(p.area().name())) {
            y.append(ind).append("area: ").append(p.area().name().toLowerCase(Locale.ROOT)).append('\n');
        }
        for (Map.Entry<String, Object> e : p.options().entrySet()) {
            y.append(ind).append(e.getKey()).append(": ").append(value(e.getValue())).append('\n');
        }
        if (!p.columns().isEmpty()) {
            y.append(ind).append("columns:\n");
            for (Column c : p.columns()) {
                y.append(ind).append("  - { label: ").append(q(c.label())).append(", bind: ").append(q(c.bind()));
                opt(y, "fmt", c.fmt());
                opt(y, "tone", c.tone());
                if (c.total()) {
                    y.append(", total: true");
                }
                if (c.link()) {
                    y.append(", link: true");
                }
                y.append(" }\n");
            }
        }
        if (p.body() != null) {
            y.append(ind).append("body:\n");
            Panel b = p.body();
            y.append(ind).append("  kind: ").append(b.kind().id()).append('\n');
            if (!b.columns().isEmpty()) {
                y.append(ind).append("  columns:\n");
                for (Column c : b.columns()) {
                    y.append(ind).append("    - { label: ").append(q(c.label())).append(", bind: ").append(q(c.bind()));
                    opt(y, "fmt", c.fmt());
                    y.append(" }\n");
                }
            }
        }
    }

    private static String value(Object v) {
        if (v instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(value(l.get(i)));
            }
            return sb.append(']').toString();
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sb.append(first ? " " : ", ").append(e.getKey()).append(": ").append(value(e.getValue()));
                first = false;
            }
            return sb.append(" }").toString();
        }
        return v instanceof Number || v instanceof Boolean ? v.toString() : q(String.valueOf(v));
    }

    private static void line(StringBuilder y, String ind, String k, String v) {
        if (v != null) {
            y.append(ind).append(k).append(": ").append(q(v)).append('\n');
        }
    }

    private static void opt(StringBuilder y, String k, String v) {
        if (v != null) {
            y.append(", ").append(k).append(": ").append(q(v));
        }
    }

    /** Double-quoted YAML scalar, escaped. */
    static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
