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
import java.util.Map;
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
    private final Dataset home;
    private final DatasetSet sets;
    private Dataset ds;
    private Resolver resolver;
    private final List<Problem> out = new ArrayList<>();

    private ReportValidator(Report report, Dataset ds, DatasetSet sets) {
        this.report = report;
        this.home = ds;
        this.sets = sets;
        use(ds);
    }

    private void use(Dataset d) {
        this.ds = d;
        this.resolver = new Resolver(d);
    }

    /** What the report names inside its dataset, and the shape of every visual. */
    public static List<Problem> validate(Report report, Dataset ds) {
        return validate(report, ds, null);
    }

    /**
     * As above for a report whose visuals may name a dataset of their own: each visual is checked against its own dataset ({@code sets}), the report's and the
     * pages' filters and the bookmarks against the report's, and every {@code link} against the datasets it connects.
     */
    public static List<Problem> validate(Report report, Dataset ds, DatasetSet sets) {
        ReportValidator v = new ReportValidator(report, ds, sets);
        v.run();
        return v.out;
    }

    /** {@link #validate} plus the accessibility and layout checks (warnings). */
    public static List<Problem> check(Report report, Dataset ds) {
        return check(report, ds, null);
    }

    public static List<Problem> check(Report report, Dataset ds, DatasetSet sets) {
        List<Problem> all = new ArrayList<>(validate(report, ds, sets));
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
                Report.Visual v = page.visuals().get(vi);
                DatasetSet.Entry own = sets == null ? null : sets.of(report.datasetOf(v));
                use(own == null ? home : own.dataset());
                if (own == null && sets != null && v.dataset() != null) {
                    err(ReportParser.REFERENCE, pp + ".visuals[" + vi + "].dataset", "no dataset '" + v.dataset() + "' (a catalogue id, a file name such as trades-basic, or the dataset's name)");
                }
                visual(v, pp + ".visuals[" + vi + "]", pageIds);
                use(home);
            }
        }
        links();
        phoneLayouts(pageIds);
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

    /**
     * Each link connects fields of different datasets: every field is {@code table.column} of at least one dataset the report reads, no two fields of a link are
     * the same dataset's, and a link that no pair of datasets meets is of no use (a warning).
     */
    private void links() {
        for (int i = 0; i < report.links().size(); i++) {
            Report.Link link = report.links().get(i);
            String p = "links[" + i + "].fields";
            List<String> where = new ArrayList<>();
            for (String f : link.fields()) {
                List<String> in = new ArrayList<>();
                if (sets != null) {
                    sets.all().forEach((ref, e) -> {
                        if (resolvesIn(e.dataset(), f) && in.stream().noneMatch(x -> sets.same(x, ref))) {
                            in.add(ref);
                        }
                    });
                } else if (resolvesIn(home, f)) {
                    in.add(report.dataset());
                }
                if (in.isEmpty()) {
                    err(ReportParser.REFERENCE, p, "'" + f + "' is not a field (table.column) of any dataset the report reads (" + String.join(", ", sets == null ? List.of(report.dataset()) : report.datasets()) + ")");
                }
                where.addAll(in);
            }
            if (where.size() > 1 && sets != null && where.stream().distinct().count() == 1) {
                out.add(new Problem("warning", ReportParser.REFERENCE, p, "every field of this link is in one dataset (" + where.get(0) + "): a link connects fields of different datasets"));
            }
        }
    }

    private static boolean resolvesIn(Dataset d, String field) {
        try {
            return new Resolver(d).resolve(List.of(field.split("\\.")), null).column() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Every page and visual a phone or tablet layout names exists, and a visual is not ordered twice nor both ordered and hidden. */
    private void phoneLayouts(Set<String> pageIds) {
        for (String device : Report.DEVICES) {
            deviceLayouts(device, pageIds);
        }
    }

    private void deviceLayouts(String device, Set<String> pageIds) {
        report.layouts().getOrDefault(device, Map.of()).forEach((pageId, lay) -> {
            String p = "layouts." + device + "." + pageId;
            Report.Page page = report.page(pageId);
            if (page == null) {
                err(ReportParser.REFERENCE, p, "the report has no page '" + pageId + "' (pages: " + String.join(", ", pageIds) + ")");
                return;
            }
            Set<String> seen = new java.util.HashSet<>();
            for (String id : lay.order()) {
                if (page.visual(id) == null) {
                    err(ReportParser.REFERENCE, p + ".order", "page '" + pageId + "' has no visual '" + id + "'");
                } else if (!seen.add(id)) {
                    err(ReportParser.REFERENCE, p + ".order", "'" + id + "' is listed twice");
                }
            }
            for (String id : lay.hide()) {
                if (page.visual(id) == null) {
                    err(ReportParser.REFERENCE, p + ".hide", "page '" + pageId + "' has no visual '" + id + "'");
                } else if (seen.contains(id) && lay.order().contains(id)) {
                    err(ReportParser.REFERENCE, p + ".hide", "'" + id + "' is both ordered and hidden: leave it out of order or out of hide");
                }
            }
            lay.span().keySet().stream().filter(id -> page.visual(id) == null)
                    .forEach(id -> err(ReportParser.REFERENCE, p + ".span." + id, "page '" + pageId + "' has no visual '" + id + "'"));
            lay.wide().stream().filter(id -> page.visual(id) == null)
                    .forEach(id -> err(ReportParser.REFERENCE, p + ".wide", "page '" + pageId + "' has no visual '" + id + "'"));
        });
    }

    /**
     * A python visual names its Python item in {@code format.python} (an item that returns a chart, or one that computes a measure), or shows a Python measure of
     * the dataset from its values well; the rows it hands over are the measure's columns or its columns well.
     */
    private void pythonVisual(Report.Visual v, String p) {
        List<String> values = v.well("values");
        Dataset.Measure pm = values.isEmpty() ? null : ds.measures().get(values.get(0));
        if (!values.isEmpty() && (pm == null || pm.python() == null)) {
            err(ReportParser.VISUAL, p + ".wells.values", "a python visual's values well takes a Python measure of the dataset ('" + values.get(0) + "' is not one)");
        }
        Object item = v.format().get("python");
        if (pm == null && (!(item instanceof String s) || s.isBlank())) {
            err(ReportParser.VISUAL, p + ".format.python", "a python visual names its Python item in format: { python: <item> } (or shows a Python measure from its values well)");
        }
        if (pm == null && v.well("columns").isEmpty()) {
            err(ReportParser.VISUAL, p + ".wells.columns", "a python visual needs the columns it hands to Python, or a Python measure in its values well");
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
        if (v.type().startsWith("perspective-")) {
            perspective(v, p);
        }
        if ("map".equals(v.type())) {
            mapPlaces(v, p);
        }
        if ("python".equals(v.type())) {
            pythonVisual(v, p);
        }
        if ("histogram".equals(v.type()) && v.format().get("bins") != null) {
            Object b = v.format().get("bins");
            if (!(b instanceof Number n) || n.intValue() < 2 || n.intValue() > Distributions.MAX_BINS) {
                err(ReportParser.VISUAL, p + ".format.bins", "bins is the number of bars of a histogram, 2 to " + Distributions.MAX_BINS);
            }
        }
        if ("live-chart".equals(v.type()) && v.format().get("window") != null) {
            Object w = v.format().get("window");
            if (!(w instanceof Number n) || n.intValue() < 5 || n.intValue() > 1000) {
                err(ReportParser.VISUAL, p + ".format.window", "window is the number of points a live chart keeps, 5 to 1000");
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
        if ("slicer".equals(v.type()) && "relative-date".equals(v.format().get("mode")) && !v.well("field").isEmpty()) {
            String type = typeOf(v.well("field").get(0));
            if (type != null && !"date".equals(type) && !"timestamp".equals(type)) {
                err(ReportParser.VISUAL, p + ".format.mode", "a relative-date slicer needs a date or timestamp field; " + v.well("field").get(0) + " is " + type);
            }
        }
        if ("slicer".equals(v.type()) && "range".equals(v.format().get("mode")) && !v.well("field").isEmpty()) {
            String type = typeOf(v.well("field").get(0));
            if (type != null && !Set.of("integer", "decimal", "date", "timestamp").contains(type)) {
                err(ReportParser.VISUAL, p + ".format.mode", "a range slicer needs a number or date field; " + v.well("field").get(0) + " is " + type);
            }
        }
    }

    /** A map is a choropleth by a country code ({@code location}) or points at {@code latitude} and {@code longitude}; not both, and a point needs both coordinates. */
    private void mapPlaces(Report.Visual v, String p) {
        boolean place = !v.well("location").isEmpty();
        boolean lat = !v.well("latitude").isEmpty();
        boolean lon = !v.well("longitude").isEmpty();
        if (!place && !lat && !lon) {
            err(ReportParser.VISUAL, p + ".wells", "a map needs a location (a country code or ISO 3166-2 region code field) or a latitude and a longitude");
        } else if (place && (lat || lon)) {
            err(ReportParser.VISUAL, p + ".wells", "a map shades countries (location) or marks points (latitude and longitude), not both");
        } else if (lat != lon) {
            err(ReportParser.VISUAL, p + ".wells", "a points map needs both a latitude and a longitude");
        }
        for (String w : new String[] {"latitude", "longitude"}) {
            for (String item : v.well(w)) {
                String type = typeOf(item);
                if (type != null && !Set.of("integer", "decimal").contains(type)) {
                    err(ReportParser.VISUAL, p + ".wells." + w, w + " is a number; " + item + " is " + type);
                }
            }
        }
        Object mode = v.format().get("mode");
        if (mode != null && !Set.of("countries", "points").contains(String.valueOf(mode))) {
            err(ReportParser.VISUAL, p + ".format.mode", "a map's mode is countries or points");
        }
        Object level = v.format().get("level");
        if (level != null && (!(level instanceof String lv) || lv.isBlank())) {
            err(ReportParser.VISUAL, p + ".format.level", "a map's level is world (the default) or the country it opens at: an ISO code or the English name");
        }
        Object drill = v.format().get("drill");
        if (drill != null && !(drill instanceof Boolean)) {
            err(ReportParser.VISUAL, p + ".format.drill", "a map's drill is true (the default: a click on a country opens its regions) or false");
        }
    }

    private static final Set<String> AGGREGATES = Set.of("sum", "avg", "count", "distinct count", "min", "max", "first", "last", "median", "dominant", "unique");

    /** A Perspective visual shows the rows of ONE table, unaggregated: its fields must all be columns of the same table; {@code format.aggregates} names columns of the pivot. */
    private void perspective(Report.Visual v, String p) {
        Set<String> tables = new java.util.LinkedHashSet<>();
        v.wells().values().forEach(l -> l.forEach(item -> {
            try {
                Resolver.ColRef ref = resolver.resolve(List.of(item.split("\\.")), null);
                if (ref.part() != null) {
                    err(ReportParser.VISUAL, p + ".wells", "'" + item + "' is a date part; Perspective shows the table's columns as they are");
                } else if (ref.column() != null) {
                    tables.add(ref.table());
                }
            } catch (IllegalArgumentException e) {
                // reported by the well check
            }
        }));
        if (tables.size() > 1) {
            err(ReportParser.VISUAL, p + ".wells", "a " + v.type() + " shows the rows of one table, not " + String.join(" and ", tables) + ": join them in a dataset table, or use a matrix");
        }
        Object agg = v.format().get("aggregates");
        if (agg != null) {
            if (!(agg instanceof java.util.Map<?, ?> m)) {
                err(ReportParser.VISUAL, p + ".format.aggregates", "aggregates maps a column to sum, avg, count, min, max, ...");
            } else {
                m.forEach((col, how) -> {
                    if (!v.well("values").contains(String.valueOf(col))) {
                        err(ReportParser.REFERENCE, p + ".format.aggregates." + col, "'" + col + "' is not in the values well of this " + v.type());
                    }
                    if (!AGGREGATES.contains(String.valueOf(how))) {
                        err(ReportParser.VISUAL, p + ".format.aggregates." + col, "'" + how + "' is not an aggregate; use " + String.join(", ", new java.util.TreeSet<>(AGGREGATES)));
                    }
                });
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
                boolean data = spec != null && Set.of("chart", "table", "card", "perspective", "live", "python").contains(spec.group());
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
