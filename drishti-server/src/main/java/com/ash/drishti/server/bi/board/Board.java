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
package com.ash.drishti.server.bi.board;

import com.ash.drishti.server.bi.report.Report;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A board (RUPAKA.md section 12, docs/guides/REPORTS.md): a wall of tiles, each one a visual of a report, laid out on the report grid, in pages that can rotate
 * by themselves on a screen on the wall. A tile does not copy its visual: it names a report and a visual and the board asks that report for the data, as the
 * person looking, so the report's dataset, row rules and masks apply to every tile. Immutable; produced by {@link BoardParser}.
 *
 * @param rotateSeconds seconds a page is shown before the next one in kiosk mode (null: the viewer's default, {@code bi.board_rotate_seconds})
 */
public record Board(String name, int version, String title, String description, boolean sample, Integer rotateSeconds, List<Page> pages, Notify tell) {

    /** Without board-wide notification defaults. */
    public Board(String name, int version, String title, String description, boolean sample, Integer rotateSeconds, List<Page> pages) {
        this(name, version, title, description, sample, rotateSeconds, pages, null);
    }

    /**
     * When a tile's colour raises a notification, and to whom it goes. {@code level} is the colour that notifies ({@code crit}, or {@code warn} for amber and red);
     * {@code channels} are {@code inbox}, {@code email} and {@code chat} (the chat bridges); {@code trigger} is {@code live}, {@code after-load} or a schedule text;
     * {@code cooldown} and {@code severity} may be null (the server's default and {@code warn}).
     */
    public record Notify(String level, List<String> channels, String cooldown, String trigger, String severity) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", level);
            m.put("channels", channels);
            if (cooldown != null) {
                m.put("cooldown", cooldown);
            }
            if (trigger != null) {
                m.put("trigger", trigger);
            }
            if (severity != null) {
                m.put("severity", severity);
            }
            return m;
        }
    }

    /** A whole number is written as one (150000000, not 1.5E8). */
    static Object number(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? (Object) (long) v : (Object) v;
    }

    /** A comparison against a number: {@code lt le gt ge eq ne}. */
    public record Rule(String op, double value) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("op", op);
            m.put("value", number(value));
            return m;
        }
    }

    /**
     * The colour a tile takes when its value crosses a threshold: {@code crit} first, then {@code warn}. {@code on} names the measure (or column) whose values
     * are compared; empty means the first number the tile shows. {@code hysteresis} is how far back inside a threshold the value must be before the tile drops
     * to a lower level (the same idea as a measure alert's hysteresis): a value hovering at the edge does not flip the colour; 0 is none.
     */
    public record Alert(String on, Rule warn, Rule crit, Notify tell, double hysteresis) {

        public Alert(String on, Rule warn, Rule crit, Notify tell) {
            this(on, warn, crit, tell, 0);
        }

        public Alert(String on, Rule warn, Rule crit) {
            this(on, warn, crit, null, 0);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (!on.isBlank()) {
                m.put("on", on);
            }
            if (warn != null) {
                m.put("warn", warn.toMap());
            }
            if (crit != null) {
                m.put("crit", crit.toMap());
            }
            if (hysteresis > 0) {
                m.put("hysteresis", number(hysteresis));
            }
            if (tell != null) {
                m.put("notify", tell.toMap());
            }
            return m;
        }
    }

    /** One tile: the report, its page (empty: the page that holds the visual) and the visual, where it sits and how it is called. */
    public record Tile(String id, String report, String page, String visual, String title, Report.Grid at, Alert alert) {}

    public record Page(String id, String title, List<Tile> tiles) {}

    /** The structure the YAML has, in a stable key order (what the viewer reads). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rachana", 1);
        m.put("board", name);
        m.put("version", version);
        m.put("title", title);
        if (!description.isBlank()) {
            m.put("description", description);
        }
        if (sample) {
            m.put("sample", true);
        }
        if (rotateSeconds != null) {
            m.put("rotate", rotateSeconds + "s");
        }
        if (tell != null) {
            m.put("notify", tell.toMap());
        }
        List<Object> ps = new ArrayList<>();
        for (Page p : pages) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", p.id());
            x.put("title", p.title());
            List<Object> ts = new ArrayList<>();
            for (Tile t : p.tiles()) {
                Map<String, Object> y = new LinkedHashMap<>();
                y.put("id", t.id());
                y.put("report", t.report());
                if (!t.page().isBlank()) {
                    y.put("page", t.page());
                }
                y.put("visual", t.visual());
                if (!t.title().isBlank()) {
                    y.put("title", t.title());
                }
                y.put("at", t.at().toMap());
                if (t.alert() != null) {
                    y.put("alert", t.alert().toMap());
                }
                ts.add(y);
            }
            x.put("tiles", ts);
            ps.add(x);
        }
        m.put("pages", ps);
        return m;
    }
}
