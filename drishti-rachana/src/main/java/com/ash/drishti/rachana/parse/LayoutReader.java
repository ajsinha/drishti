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
import com.ash.drishti.rachana.model.ScreenLayout;
import com.ash.drishti.rachana.model.SourceLocation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a screen's {@code layout:} (RACHANA_REFERENCE.md 13) and checks it against the panels: {@code DRS-2059} for every layout problem
 * (an unknown area, an area that is not a rectangle, rows of different widths, columns that do not sum to 12, a split of areas that
 * are not side by side, tabs or a sticky area that does not exist, a phone order naming nothing, sections with areas).
 */
final class LayoutReader {

    static final String CODE = "DRS-2059";
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Set<String> KEYS = Set.of("areas", "columns", "split", "tabs", "sections", "sticky", "user-can-arrange", "phone", "tablet");

    private final String file;
    private final List<SutraProblem> problems;

    LayoutReader(String file, List<SutraProblem> problems) {
        this.file = file;
        this.problems = problems;
    }

    /** The layout, or null when the screen has none (or it cannot be read: the problems say why). */
    ScreenLayout read(PNode n) {
        if (n == null || n.value() == null) {
            return null;
        }
        if (!n.isMap()) {
            problem("'layout' is a mapping: areas, columns, split, tabs, sections, sticky, user-can-arrange, phone, tablet", n);
            return null;
        }
        Map<String, PNode> m = n.map();
        m.forEach((k, v) -> {
            if (!KEYS.contains(k)) {
                problem("unknown key '" + k + "' in layout; expected one of areas, columns, split, tabs, sections, sticky, user-can-arrange, phone, tablet", v);
            }
        });
        List<String> areas = picture(m.get("areas"), "areas");
        List<Integer> columns = new ArrayList<>();
        PNode c = m.get("columns");
        if (c != null && c.value() != null) {
            if (c.value() instanceof Long l) {
                if (l != ScreenLayout.GRID) {
                    problem("'columns' is the 12-column grid (columns: 12), or one width a column of the areas, such as [7, 5]", c);
                }
            } else {
                columns = units(c, "columns");
            }
        }
        checkPicture(areas, columns, m.get("areas"), m.get("columns"));
        ScreenLayout.Split split = split(m.get("split"));
        List<String> tabs = names(m.get("tabs"), "tabs");
        List<ScreenLayout.Section> sections = sections(m.get("sections"));
        String sticky = m.get("sticky") == null ? null : m.get("sticky").text();
        PNode arrange = m.get("user-can-arrange");
        if (arrange != null && arrange.value() != null && !(arrange.value() instanceof Boolean)) {
            problem("'user-can-arrange' is true or false", arrange);
        }
        List<String> phone = names(m.get("phone"), "phone");
        List<String> tablet = List.of();
        List<Integer> tabletColumns = List.of();
        PNode t = m.get("tablet");
        if (t != null && t.value() != null) {
            if (!t.isMap()) {
                problem("'tablet' is a mapping: { areas: [...], columns: [...] }, the picture at tablet width", t);
            } else {
                t.map().forEach((k, v) -> {
                    if (!Set.of("areas", "columns").contains(k)) {
                        problem("unknown key '" + k + "' in layout.tablet; expected areas, columns", v);
                    }
                });
                tablet = picture(t.map().get("areas"), "tablet areas");
                tabletColumns = t.map().get("columns") == null ? List.of() : units(t.map().get("columns"), "tablet columns");
                checkPicture(tablet, tabletColumns, t.map().get("areas"), t.map().get("columns"));
                if (!tablet.isEmpty() && !new HashSet<>(ScreenLayout.names(tablet)).equals(new HashSet<>(ScreenLayout.names(areas)))) {
                    problem("the tablet picture must hold the same areas as 'areas' (" + String.join(", ", ScreenLayout.names(areas)) + ")", t);
                }
            }
        }
        return new ScreenLayout(areas, columns, split, tabs, sections, sticky, arrange != null && Boolean.TRUE.equals(arrange.value()),
                phone, tablet, tabletColumns);
    }

    /**
     * The layout checked against the screen's panels: each panel's {@code area} names an area of the picture (or main/right without
     * one), {@code section} a section, {@code span} fits; split, tabs, sticky and phone name what exists.
     */
    void check(ScreenLayout layout, List<Panel> panels, PNode at) {
        Set<String> areaNames = new HashSet<>(layout == null ? List.of() : layout.areaNames());
        Set<String> ids = new HashSet<>();
        Map<String, Integer> perArea = new HashMap<>();
        for (Panel p : panels) {
            ids.add(p.id());
            String named = p.option(Panel.GRID_AREA).orElse(null);
            SourceLocation where = p.location();
            if (named != null) {
                if (layout == null || !layout.hasAreas()) {
                    problems.add(new SutraProblem("DRS-2027", "area '" + named + "' is not 'main' or 'right': name areas in layout.areas first", where));
                } else if (!areaNames.contains(named)) {
                    problems.add(new SutraProblem(CODE, "panel '" + p.id() + "' is placed in area '" + named + "', which layout.areas does not have ("
                            + String.join(", ", layout.areaNames()) + ")", where));
                }
            }
            if (layout != null && layout.hasAreas()) {
                perArea.merge(placeOf(layout, p), 1, Integer::sum);
            }
            String section = p.option(Panel.SECTION).orElse(null);
            if (section != null && (layout == null || layout.section(section) == null)) {
                problems.add(new SutraProblem(CODE, "panel '" + p.id() + "' names section '" + section + "', which layout.sections does not have", where));
            }
        }
        if (layout == null) {
            return;
        }
        if (layout.hasAreas() && !layout.sections().isEmpty()) {
            problem("sections arrange the flow grid; a screen with areas places panels by area (use tabs, or collapsible: true on a panel)", at);
        }
        ScreenLayout.Split s = layout.split();
        if (s != null) {
            if (!layout.hasAreas()) {
                problem("a split divides two areas: name them in layout.areas first", at);
            } else if (!areaNames.contains(s.left()) || !areaNames.contains(s.right())) {
                problem("the split names an area layout.areas does not have: " + s.left() + ", " + s.right(), at);
            } else if (!sideBySide(layout.areas(), s.left(), s.right())) {
                problem("the split's areas '" + s.left() + "' and '" + s.right() + "' are not side by side, left then right, in layout.areas", at);
            }
        }
        for (String t : layout.tabs()) {
            if (!areaNames.contains(t)) {
                problem("tabs name area '" + t + "', which layout.areas does not have", at);
            } else if (perArea.getOrDefault(t, 0) < 2) {
                problem("area '" + t + "' is shown as tabs but holds " + perArea.getOrDefault(t, 0) + " panel(s): tabs need two or more", at);
            }
        }
        if (layout.sticky() != null && !areaNames.contains(layout.sticky()) && !ids.contains(layout.sticky())) {
            problem("sticky names '" + layout.sticky() + "', which is neither an area nor a panel of this screen", at);
        }
        for (String ph : layout.phone()) {
            if (!areaNames.contains(ph) && !ids.contains(ph)) {
                problem("phone lists '" + ph + "', which is neither an area nor a panel of this screen", at);
            }
        }
    }

    /** The area a panel is drawn in: its named area, else main or right mapped onto the picture (an area so named, else the first or last). */
    static String placeOf(ScreenLayout layout, Panel p) {
        String named = p.option(Panel.GRID_AREA).orElse(null);
        List<String> names = layout.areaNames();
        if (named != null && names.contains(named)) {
            return named;
        }
        String flow = p.area() == null ? "main" : p.area().name().toLowerCase(java.util.Locale.ROOT);
        return names.contains(flow) ? flow : "right".equals(flow) ? names.get(names.size() - 1) : names.get(0);
    }

    private List<String> picture(PNode n, String what) {
        List<String> out = new ArrayList<>();
        if (n == null || n.value() == null) {
            return out;
        }
        if (!n.isList()) {
            problem("'" + what + "' is a list of rows, each a string of area names: [\"header header\", \"trades detail\"]", n);
            return out;
        }
        for (PNode r : n.list()) {
            if (!(r.value() instanceof String s)) {
                problem("a row of '" + what + "' is a string of area names, such as \"trades detail\"", r);
                continue;
            }
            for (String cell : ScreenLayout.cells(s)) {
                if (!".".equals(cell) && !NAME.matcher(cell).matches()) {
                    problem("'" + cell + "' is not an area name (lower-case letters, digits and -, or . for an empty cell)", r);
                }
            }
            out.add(String.join(" ", ScreenLayout.cells(s)));
        }
        return out;
    }

    private void checkPicture(List<String> rows, List<Integer> columns, PNode at, PNode colsAt) {
        if (rows.isEmpty()) {
            if (!columns.isEmpty()) {
                problem("column widths size the columns of layout.areas: name the areas first", colsAt);
            }
            return;
        }
        int width = ScreenLayout.cells(rows.get(0)).size();
        for (String r : rows) {
            if (ScreenLayout.cells(r).size() != width) {
                problem("every row of the picture has the same number of cells: '" + r + "' has " + ScreenLayout.cells(r).size() + ", the first " + width, at);
                return;
            }
        }
        if (width > ScreenLayout.GRID) {
            problem("the picture is " + width + " cells wide: at most 12", at);
        }
        if (!columns.isEmpty()) {
            if (columns.size() != width) {
                problem("columns gives " + columns.size() + " widths for " + width + " columns of areas", colsAt);
            } else if (columns.stream().mapToInt(Integer::intValue).sum() != ScreenLayout.GRID) {
                problem("the column widths add up to " + columns.stream().mapToInt(Integer::intValue).sum() + ": they share the 12-column grid", colsAt);
            }
        }
        for (String name : ScreenLayout.names(rows)) {          // each area a rectangle: no L-shapes, no area cut in two
            int top = Integer.MAX_VALUE, left = Integer.MAX_VALUE, bottom = -1, right = -1, count = 0;
            for (int r = 0; r < rows.size(); r++) {
                List<String> cells = ScreenLayout.cells(rows.get(r));
                for (int c = 0; c < cells.size(); c++) {
                    if (cells.get(c).equals(name)) {
                        top = Math.min(top, r);
                        bottom = Math.max(bottom, r);
                        left = Math.min(left, c);
                        right = Math.max(right, c);
                        count++;
                    }
                }
            }
            if (count != (bottom - top + 1) * (right - left + 1)) {
                problem("area '" + name + "' is not a rectangle: its cells overlap another area's or leave a gap", at);
            }
        }
    }

    /** Whether {@code a} sits immediately left of {@code b} on some row of the picture. */
    static boolean sideBySide(List<String> rows, String a, String b) {
        for (String r : rows) {
            List<String> cells = ScreenLayout.cells(r);
            for (int c = 0; c + 1 < cells.size(); c++) {
                if (cells.get(c).equals(a) && cells.get(c + 1).equals(b)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<Integer> units(PNode n, String what) {
        List<Integer> out = new ArrayList<>();
        if (!n.isList()) {
            problem("'" + what + "' is a list of widths in grid columns, such as [7, 5]", n);
            return out;
        }
        for (PNode u : n.list()) {
            if (!(u.value() instanceof Long l) || l < 1 || l > ScreenLayout.GRID) {
                problem("a width in '" + what + "' is a whole number of grid columns, 1 to 12, not '" + u.value() + "'", u);
            } else {
                out.add(l.intValue());
            }
        }
        return out;
    }

    private List<String> names(PNode n, String what) {
        List<String> out = new ArrayList<>();
        if (n == null || n.value() == null) {
            return out;
        }
        if (!n.isList()) {
            problem("'" + what + "' is a list of names", n);
            return out;
        }
        for (PNode x : n.list()) {
            if (x.text() == null) {
                problem("'" + what + "' lists names, not '" + x.value() + "'", x);
            } else {
                out.add(x.text());
            }
        }
        return out;
    }

    private ScreenLayout.Split split(PNode n) {
        if (n == null || n.value() == null) {
            return null;
        }
        if (!n.isMap()) {
            problem("'split' is a mapping: { between: [left, right], resizable: true, remember: user }", n);
            return null;
        }
        Map<String, PNode> m = n.map();
        m.forEach((k, v) -> {
            if (!Set.of("between", "resizable", "remember").contains(k)) {
                problem("unknown key '" + k + "' in split; expected between, resizable, remember", v);
            }
        });
        List<String> between = names(m.get("between"), "between");
        if (between.size() != 2 || between.get(0).equals(between.get(1))) {
            problem("a split is between two different areas: between: [left, right]", m.getOrDefault("between", n));
            return null;
        }
        PNode r = m.get("resizable");
        if (r != null && !(r.value() instanceof Boolean)) {
            problem("'resizable' is true or false", r);
        }
        String remember = m.get("remember") == null ? "user" : m.get("remember").text();
        if (!"user".equals(remember) && !"none".equals(remember)) {
            problem("'remember' is user (kept per reader) or none", m.get("remember"));
        }
        return new ScreenLayout.Split(between.get(0), between.get(1), r == null || !Boolean.FALSE.equals(r.value()), remember);
    }

    private List<ScreenLayout.Section> sections(PNode n) {
        List<ScreenLayout.Section> out = new ArrayList<>();
        if (n == null || n.value() == null) {
            return out;
        }
        if (!n.isList()) {
            problem("'sections' is a list: - { id: risk, title: Risk, collapsed: false }", n);
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (PNode s : n.list()) {
            if (!s.isMap()) {
                problem("a section is a mapping: { id, title, collapsed }", s);
                continue;
            }
            Map<String, PNode> m = s.map();
            m.forEach((k, v) -> {
                if (!Set.of("id", "title", "collapsed").contains(k)) {
                    problem("unknown key '" + k + "' in a section; expected id, title, collapsed", v);
                }
            });
            String id = m.get("id") == null ? null : m.get("id").text();
            if (id == null || !NAME.matcher(id).matches()) {
                problem("a section needs an 'id' (lower-case letters, digits and -)", s);
                continue;
            }
            if (!seen.add(id)) {
                problem("two sections are called '" + id + "'", s);
            }
            PNode c = m.get("collapsed");
            if (c != null && !(c.value() instanceof Boolean)) {
                problem("'collapsed' is true or false", c);
            }
            out.add(new ScreenLayout.Section(id, m.get("title") == null ? null : m.get("title").text(), c != null && Boolean.TRUE.equals(c.value())));
        }
        return out;
    }

    private void problem(String message, PNode at) {
        problems.add(new SutraProblem(CODE, message, new SourceLocation(file, at == null ? 0 : at.line(), at == null ? 0 : at.column())));
    }
}
