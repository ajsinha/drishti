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
package com.ash.drishti.rachana.parse;

import com.ash.drishti.rachana.SutraProblem;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.ScreenPhone;
import com.ash.drishti.rachana.model.SourceLocation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the phone display's grammar (docs/guides/BUILD_GUIDE.md section 5): a panel's {@code phone:} (a tier, or a tier with its compact options),
 * a fragment's {@code doc.phone} (brought onto the fragment's panels, marked {@code from: fragment}) and the screen's {@code phone:}
 * block. Every problem is {@code DRS-2059}, the layout family: a word that is not a tier, a compact option that does not fit the panel's
 * kind, a phone order naming a panel that does not exist or is not must or glance.
 */
final class PhoneReader {

    private static final Set<String> PANEL_KEYS = Set.of("tier", "rows", "columns", "sort", "series", "figures", "tab", "from");
    private static final Set<String> SCREEN_KEYS = Set.of("order", "default", "summary");

    private final String file;
    private final List<SutraProblem> problems;

    PhoneReader(String file, List<SutraProblem> problems) {
        this.file = file;
        this.problems = problems;
    }

    /**
     * A panel's {@code phone:} as plain values ({@code {tier, from, rows, columns, sort, series, figures, tab}}), or null when it has none
     * or it cannot be read.
     *
     * @param kind the panel's kind, as the view model names it ({@code table}), to check the compact options against
     */
    Map<String, Object> panel(PNode n, String kind) {
        if (n == null || n.value() == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (n.isScalar()) {
            String tier = tier(n.text(), n);
            if (tier == null) {
                return null;
            }
            out.put("tier", tier);
            out.put("from", "sutra");
            return out;
        }
        if (!n.isMap()) {
            problem("phone is a tier (must, glance, more, desk) or a mapping { tier, rows, columns, sort, series, figures, tab }", n);
            return null;
        }
        Map<String, PNode> m = n.map();
        m.forEach((k, v) -> {
            if (!PANEL_KEYS.contains(k)) {
                problem("unknown key '" + k + "' in phone; expected tier, rows, columns, sort, series, figures, tab", v);
            }
        });
        PNode t = m.get("tier");
        String tier = t == null ? null : tier(t.text(), t);
        if (t == null) {
            problem("phone needs a tier: must, glance, more or desk", n);
        }
        if (tier == null) {
            return null;
        }
        out.put("tier", tier);
        PNode from = m.get("from");
        out.put("from", from != null && "fragment".equals(from.text()) ? "fragment" : "sutra");
        for (String key : List.of("rows", "figures")) {
            PNode v = m.get(key);
            if (v != null && v.value() != null) {
                if (!(v.value() instanceof Long l) || l < 1 || l > 50) {
                    problem(key + " is a whole number from 1 to 50, not '" + v.value() + "'", v);
                } else if (fits(key, kind, v)) {
                    out.put(key, l.intValue());
                }
            }
        }
        for (String key : List.of("columns", "series")) {
            PNode v = m.get(key);
            if (v != null && v.value() != null) {
                List<String> names = texts(v, key);
                if (names != null && fits(key, kind, v)) {
                    out.put(key, names);
                }
            }
        }
        PNode sort = m.get("sort");
        if (sort != null && sort.value() != null) {
            if (sort.text() == null || sort.text().isBlank()) {
                problem("sort names one column, -COLUMN for largest first", sort);
            } else if (fits("sort", kind, sort)) {
                out.put("sort", sort.text().trim());
            }
        }
        PNode tab = m.get("tab");
        if (tab != null && tab.value() != null && fits("tab", kind, tab)) {
            out.put("tab", tab.value() instanceof Long l ? (Object) l.intValue() : tab.text());
        }
        return out;
    }

    /** The screen's {@code phone:} block, or null without one. */
    ScreenPhone screen(PNode n) {
        if (n == null || n.value() == null) {
            return null;
        }
        if (!n.isMap()) {
            problem("the screen's phone is a mapping: { order, default, summary }", n);
            return null;
        }
        Map<String, PNode> m = n.map();
        m.forEach((k, v) -> {
            if (!SCREEN_KEYS.contains(k)) {
                problem("unknown key '" + k + "' in phone; expected order, default, summary", v);
            }
        });
        List<String> order = m.get("order") == null || m.get("order").value() == null ? List.of() : texts(m.get("order"), "order");
        PNode d = m.get("default");
        String def = d == null || d.value() == null ? null : tier(d.text(), d);
        PNode s = m.get("summary");
        String summary = null;
        if (s != null && s.value() != null) {
            if (s.text() == null) {
                problem("summary is one line of text", s);
            } else {
                summary = s.text();
            }
        }
        return new ScreenPhone(order == null ? List.of() : order, def, summary);
    }

    /**
     * The phone order checked against the panels: each name is a top-level panel, and one the Sutra makes must or glance (a panel left
     * to its kind's default is not checked: the site may set it).
     */
    void check(ScreenPhone phone, List<Panel> panels, PNode at) {
        if (phone == null) {
            return;
        }
        Set<String> ids = new HashSet<>();
        Map<String, String> tiers = new LinkedHashMap<>();
        for (Panel p : panels) {
            ids.add(p.id());
            if (p.options().get(ScreenPhone.KEY) instanceof Map<?, ?> ph && ph.get("tier") instanceof String t) {
                tiers.put(p.id(), t);
            } else if (phone.defaultTier() != null) {
                tiers.put(p.id(), phone.defaultTier());
            }
        }
        for (String id : phone.order()) {
            if (!ids.contains(id)) {
                problem("phone.order names '" + id + "', which is not a panel of this screen", at);
            } else if (tiers.containsKey(id) && !Set.of("must", "glance").contains(tiers.get(id))) {
                problem("phone.order names '" + id + "', a " + tiers.get(id) + " panel; the order arranges only must and glance panels", at);
            }
        }
    }

    private boolean fits(String key, String kind, PNode at) {
        Set<String> kinds = ScreenPhone.COMPACT.get(key);
        if (kind != null && kinds != null && !kinds.contains(kind)) {
            problem("'" + key + "' is a compact option of " + String.join(", ", new java.util.TreeSet<>(kinds)) + " panels, not of a " + kind + " panel", at);
            return false;
        }
        return true;
    }

    private String tier(String word, PNode at) {
        if (word == null || !ScreenPhone.TIERS.contains(word)) {
            problem("'" + word + "' is not a phone tier; write must, glance, more or desk", at);
            return null;
        }
        return word;
    }

    private List<String> texts(PNode n, String key) {
        if (n.isScalar() && n.text() != null) {
            return List.of(n.text());
        }
        if (!n.isList()) {
            problem(key + " is a list of names", n);
            return null;
        }
        List<String> out = new ArrayList<>();
        for (PNode x : n.list()) {
            if (x.text() == null || x.text().isBlank()) {
                problem(key + " is a list of names", x);
                return null;
            }
            out.add(x.text().trim());
        }
        return out;
    }

    private void problem(String message, PNode at) {
        problems.add(new SutraProblem(LayoutReader.CODE, message, new SourceLocation(file, at == null ? 0 : at.line(), at == null ? 0 : at.column())));
    }
}
