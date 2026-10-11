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
package com.ash.drishti.server.bi.report;

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.Resolver;
import com.ash.drishti.server.bi.report.ReportParser.Problem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a report against its dataset (RUPAKA.md section 8): every field and measure a visual or filter names exists, each well holds what it should
 * and is filled as the type needs, hierarchies, pages, slicers and bookmarks the report points at exist. {@link #check} adds the quality checks of the
 * designer's Check button: accessibility (a text alternative for each chart, table and image, contrast of a chosen colour, visuals that overlap).
 * Errors carry {@code DRS-9031} (a name that does not exist) or {@code DRS-9032} (a visual built wrongly); the structure was checked by {@link ReportParser}.
 */
public final class ReportValidator {

    private static final Pattern HEX = Pattern.compile("#([0-9a-fA-F]{6})");

    private final Report report;
    private final Dataset ds;
    private final Resolver resolver;
    private final List<Problem> out = new ArrayList<>();

    private ReportValidator(Report report, Dataset ds) {
        this.report = report;
        this.ds = ds;
        this.resolver = new Resolver(ds);
    }

    /** What the report names inside its dataset, and the shape of every visual. */
    public static List<Problem> validate(Report report, Dataset ds) {
        ReportValidator v = new ReportValidator(report, ds);
        v.run();
        return v.out;
    }

    /** {@link #validate} plus the accessibility and layout checks (warnings). */
    public static List<Problem> check(Report report, Dataset ds) {
        List<Problem> all = new ArrayList<>(validate(report, ds));
        all.addAll(accessibility(report));
        return all;
    }

    private void run() {
        Set<String> pageIds = new java.util.HashSet<>();
        report.pages().forEach(p -> pageIds.add(p.id()));
        filters(report.filters(), "filters");
        for (int pi = 0; pi < report.pages().size(); pi++) {
            Report.Page page = report.pages().get(pi);
            String pp = "pages[" + pi + "]";
            filters(page.filters(), pp + ".filters");
            if (page.drillThrough() != null) {
                page.drillThrough().fields().forEach(f -> field(f, pp + ".drillthrough.fields", true));
            }
            for (int vi = 0; vi < page.visuals().size(); vi++) {
                visual(page.visuals().get(vi), pp + ".visuals[" + vi + "]", pageIds);
            }
        }
        for (int bi = 0; bi < report.bookmarks().size(); bi++) {
            Report.Bookmark b = report.bookmarks().get(bi);
            String p = "bookmarks[" + bi + "]";
            Report.Page page = report.page(b.page());
            if (page == null) {
                err(ReportParser.REFERENCE, p + ".page", "the report has no page '" + b.page() + "' (pages: " + String.join(", ", pageIds) + ")");
                continue;
            }
            for (String slicer : b.slicers().keySet()) {
                boolean found = report.pages().stream().flatMap(x -> x.visuals().stream()).anyMatch(x -> x.id().equals(slicer) && "slicer".equals(x.type()));
                if (!found) {
                    err(ReportParser.REFERENCE, p + ".slicers." + slicer, "no slicer with the id '" + slicer + "' in the report");
                }
            }
            filters(b.filters(), p + ".filters");
        }
    }

    private void visual(Report.Visual v, String p, Set<String> pageIds) {
        VisualCatalogue.Type spec = VisualCatalogue.type(v.type());
        for (var e : spec.wells().entrySet()) {
            VisualCatalogue.Well well = e.getValue();
            List<String> items = v.well(e.getKey());
            String wp = p + ".wells." + e.getKey();
            if (well.required() && items.isEmpty()) {
                err(ReportParser.VISUAL, wp, "a " + v.type() + " needs at least one item in its " + e.getKey() + " well");
            }
            if (well.max() > 0 && items.size() > well.max()) {
                err(ReportParser.VISUAL, wp, "the " + e.getKey() + " well of a " + v.type() + " holds " + well.max() + " item" + (well.max() == 1 ? "" : "s") + ", not " + items.size());
            }
            for (String item : items) {
                boolean isMeasure = ds.measures().containsKey(item);
                switch (well.kind()) {
                    case MEASURE -> {
                        if (!isMeasure) {
                            if (looksLikeField(item)) {
                                err(ReportParser.VISUAL, wp, "'" + item + "' is a field; the " + e.getKey() + " well takes measures (" + measureNames() + ")");
                            } else {
                                err(ReportParser.REFERENCE, wp, "no measure '" + item + "' in dataset '" + ds.name() + "' (measures: " + measureNames() + ")");
                            }
                        }
                    }
                    case FIELD -> {
                        if (isMeasure) {
                            err(ReportParser.VISUAL, wp, "'" + item + "' is a measure; the " + e.getKey() + " well takes fields (table.column)");
                        } else {
                            field(item, wp, false);
                        }
                    }
                    default -> {
                        if (!isMeasure) {
                            field(item, wp, false);
                        }
                    }
                }
            }
        }
        filters(v.filters(), p + ".filters");
        if (v.sort() != null && !v.wells().values().stream().anyMatch(l -> l.contains(v.sort().by()))) {
            err(ReportParser.REFERENCE, p + ".sort.by", "'" + v.sort().by() + "' is not a field or measure of this visual");
        }
        if (v.hierarchy() != null) {
            List<String> levels = ds.hierarchies().get(v.hierarchy());
            if (levels == null) {
                err(ReportParser.REFERENCE, p + ".hierarchy", "no hierarchy '" + v.hierarchy() + "' in the dataset (hierarchies: " + String.join(", ", ds.hierarchies().keySet()) + ")");
            } else if (!"chart".equals(spec.group()) && !"table".equals(spec.group())) {
                err(ReportParser.VISUAL, p + ".hierarchy", "only charts, tables and matrices drill down a hierarchy");
            } else if (v.well("axis").isEmpty() && v.well("rows").isEmpty() || !levels.contains(first(v))) {
                out.add(new Problem("warning", ReportParser.REFERENCE, p + ".hierarchy", "the first field of the visual (" + first(v) + ") is not a level of '" + v.hierarchy()
                        + "': drilling starts from the hierarchy's first level"));
            }
        }
        if (v.drillThrough() != null) {
            Report.Page target = report.page(v.drillThrough().page());
            field(v.drillThrough().field(), p + ".drillthrough.field", true);
            if (target == null) {
                err(ReportParser.REFERENCE, p + ".drillthrough.page", "the report has no page '" + v.drillThrough().page() + "' (pages: " + String.join(", ", pageIds) + ")");
            } else if (target.drillThrough() == null || !target.drillThrough().fields().contains(v.drillThrough().field())) {
                err(ReportParser.REFERENCE, p + ".drillthrough.page", "page '" + target.id() + "' does not take " + v.drillThrough().field() + " (list it under that page's drillthrough: fields)");
            }
        }
        if ("button".equals(v.type())) {
            Object nav = v.action().get("navigate");
            Object bm = v.action().get("bookmark");
            if (nav == null && bm == null) {
                err(ReportParser.VISUAL, p + ".action", "a button needs an action: { navigate: <page id> } or { bookmark: <name> }");
            }
            if (nav != null && report.page(String.valueOf(nav)) == null) {
                err(ReportParser.REFERENCE, p + ".action.navigate", "the report has no page '" + nav + "'");
            }
            if (bm != null && report.bookmarks().stream().noneMatch(b -> b.name().equals(String.valueOf(bm)))) {
                err(ReportParser.REFERENCE, p + ".action.bookmark", "the report has no bookmark '" + bm + "'");
            }
        }
        if ("slicer".equals(v.type()) && "range".equals(v.format().get("mode")) && !v.well("field").isEmpty()) {
            String type = typeOf(v.well("field").get(0));
            if (type != null && !Set.of("integer", "decimal", "date", "timestamp").contains(type)) {
                err(ReportParser.VISUAL, p + ".format.mode", "a range slicer needs a number or date field; " + v.well("field").get(0) + " is " + type);
            }
        }
    }

    private static String first(Report.Visual v) {
        List<String> l = v.well("axis").isEmpty() ? v.well("rows") : v.well("axis");
        return l.isEmpty() ? "" : l.get(0);
    }

    private void filters(List<Report.Filter> list, String path) {
        for (int i = 0; i < list.size(); i++) {
            Report.Filter f = list.get(i);
            String p = path + "[" + i + "]";
            if (ds.measures().containsKey(f.field())) {
                if ("top".equals(f.op())) {
                    err(ReportParser.VISUAL, p, "a top filter is on a field (the values to keep), not on the measure " + f.field());
                }
                continue;
            }
            field(f.field(), p + ".field", false);
            if ("top".equals(f.op()) && !ds.measures().containsKey(f.by())) {
                err(ReportParser.REFERENCE, p + ".by", "no measure '" + f.by() + "' to rank by (measures: " + measureNames() + ")");
            }
        }
    }

    /** Checks a dotted field name; {@code plain} fields may not carry a date part (a drill-through carries whole values). */
    private void field(String name, String path, boolean plain) {
        try {
            Resolver.ColRef ref = resolver.resolve(List.of(name.split("\\.")), null);
            if (ref.column() == null) {
                err(ReportParser.REFERENCE, path, "'" + name + "' is a table; a field is table.column");
            } else if (plain && ref.part() != null) {
                err(ReportParser.REFERENCE, path, "'" + name + "' is a date part; carry the whole column");
            }
        } catch (IllegalArgumentException e) {
            err(ReportParser.REFERENCE, path, e.getMessage());
        }
    }

    private String typeOf(String field) {
        try {
            Resolver.ColRef ref = resolver.resolve(List.of(field.split("\\.")), null);
            if (ref.part() != null) {
                return "integer";
            }
            Dataset.Column c = ref.column() == null ? null : resolver.column(ref.table(), ref.column());
            return c == null ? null : c.type();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean looksLikeField(String item) {
        try {
            return resolver.resolve(List.of(item.split("\\.")), null).column() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String measureNames() {
        return String.join(", ", ds.measures().keySet());
    }

    private void err(String code, String path, String message) {
        out.add(ReportParser.error(code, path, message));
    }

    // ---- quality ---------------------------------------------------------------------------------------------------

    /** Basic accessibility and layout checks, as warnings: text alternatives, contrast of chosen colours, overlap, a title for each visual. */
    public static List<Problem> accessibility(Report report) {
        List<Problem> out = new ArrayList<>();
        String background = report.theme().getOrDefault("background", "#ffffff");
        for (int pi = 0; pi < report.pages().size(); pi++) {
            Report.Page page = report.pages().get(pi);
            for (int vi = 0; vi < page.visuals().size(); vi++) {
                Report.Visual v = page.visuals().get(vi);
                String p = "pages[" + pi + "].visuals[" + vi + "]";
                VisualCatalogue.Type spec = VisualCatalogue.type(v.type());
                boolean data = spec != null && ("chart".equals(spec.group()) || "table".equals(spec.group()) || "card".equals(spec.group()));
                if ((data || "image".equals(v.type())) && v.title().isBlank() && v.alt().isBlank()) {
                    out.add(new Problem("warning", "A11Y", p, "the " + v.type() + " has no title and no alt text: a screen reader announces nothing about it"));
                }
                if ("image".equals(v.type()) && v.alt().isBlank()) {
                    out.add(new Problem("warning", "A11Y", p + ".alt", "an image needs alt text"));
                }
                Object colour = v.format().get("colour");
                if (colour instanceof String c && HEX.matcher(c).matches() && contrast(c, background) < 3.0) {
                    out.add(new Problem("warning", "A11Y", p + ".format.colour", String.format(Locale.ROOT, "colour %s on %s has a contrast ratio of %.1f; at least 3 is needed for marks and 4.5 for text",
                            c, background, contrast(c, background))));
                }
                for (int wi = vi + 1; wi < page.visuals().size(); wi++) {
                    Report.Visual w = page.visuals().get(wi);
                    if (overlaps(v.at(), w.at())) {
                        out.add(new Problem("warning", "LAYOUT", p, "overlaps '" + w.id() + "' (" + w.type() + "): the keyboard reading order and the picture disagree"));
                    }
                }
            }
        }
        return out;
    }

    static boolean overlaps(Report.Grid a, Report.Grid b) {
        return a.x() < b.x() + b.w() && b.x() < a.x() + a.w() && a.y() < b.y() + b.h() && b.y() < a.y() + a.h();
    }

    /** The WCAG contrast ratio between two {@code #rrggbb} colours. */
    static double contrast(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(String hex) {
        Matcher m = HEX.matcher(hex);
        if (!m.matches()) {
            return 1.0;
        }
        int rgb = Integer.parseInt(m.group(1), 16);
        double[] c = {(rgb >> 16 & 255) / 255.0, (rgb >> 8 & 255) / 255.0, (rgb & 255) / 255.0};
        for (int i = 0; i < 3; i++) {
            c[i] = c[i] <= 0.03928 ? c[i] / 12.92 : Math.pow((c[i] + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }
}
