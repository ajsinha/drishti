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
package com.ash.drishti.server.pivot;

import com.ash.drishti.bicompute.core.AggregationPlan;
import com.ash.drishti.bicompute.core.Dim;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.sql.DuckDbExecutor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A view over entity documents (PIVOT_ENGINE.md section 6.7): its Rachana paths turned into field ids, its grain (the finest list its
 * rows, columns and values use), coarser figures counted once per parent, two separate lists never crossed, filters per grain; and the
 * relation one plan reads: the documents unnested down to the grain, every path field extracted by DuckDB's JSON functions, the parents'
 * keys beside them.
 */
final class DocView {

    /** The suffix of a filter that keeps an entity only when ALL of its elements match ({@code LEGS.CURRENCY~all}). */
    static final String ALL = "~all";
    private static final Pattern CALL = Pattern.compile("\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)\\s*", Pattern.DOTALL);
    private static final Set<String> ONCE = Set.of("sum", "count", "avg");
    private static final Set<String> SAME_ONCE = Set.of("min", "max", "count_distinct", "distinct");

    private final Map<String, DocPath> paths;
    private final Set<String> promoted;
    private final Function<String, DocPath> register;

    /**
     * @param paths the document fields by id (the field list's and those typed before)
     * @param promoted the promoted columns' names (fields of the entity itself)
     * @param register makes a typed path a field (counted for the administrator's suggestions) and returns it
     */
    DocView(Map<String, DocPath> paths, Set<String> promoted, Function<String, DocPath> register) {
        this.paths = paths;
        this.promoted = promoted;
        this.register = register;
    }

    /**
     * The fields the engine needs beside the person's: each depth's parent key ({@code _p0} the entity, {@code _p1} a list element …),
     * which a once-per-parent figure reads, and the {@code ~all} twin of every list field, which an all-elements filter names. The field
     * list does not show them (a name starting {@code _} or holding {@code ~}).
     */
    static List<com.ash.drishti.bicompute.spi.PivotProvider.Field> engineFields(Map<String, DocPath> paths, Map<String, String> types) {
        List<com.ash.drishti.bicompute.spi.PivotProvider.Field> out = new ArrayList<>();
        int deepest = paths.values().stream().mapToInt(p -> p.grainLevels().size()).max().orElse(0);
        for (int i = 0; i <= deepest; i++) {
            out.add(new com.ash.drishti.bicompute.spi.PivotProvider.Field("_p" + i, "string", "parent key " + i, "the key of each parent at depth "
                    + i + " (once-per-parent figures)"));
        }
        for (DocPath p : paths.values()) {
            if (!p.grainLevels().isEmpty()) {
                out.add(new com.ash.drishti.bicompute.spi.PivotProvider.Field(p.id() + ALL, types.getOrDefault(p.id(), "string"), p.label() + " (all)",
                        "keeps an entity when all of its elements match"));
            }
        }
        return out;
    }

    /** The grain of a field id: {@code ""} for the entity. */
    String grain(String id) {
        String base = id.endsWith(ALL) ? id.substring(0, id.length() - ALL.length()) : id;
        DocPath p = paths.get(base);
        return p == null ? "" : p.grain();
    }

    private static int depth(String grain) {
        return grain.isEmpty() ? 0 : grain.split("\\.").length;
    }

    private static boolean prefix(String a, String b) {
        return a.isEmpty() || a.equals(b) || b.startsWith(a + ".");
    }

    /** A field reference as its id: a plain id stays, a path becomes its field (a promoted column when the path names one). */
    String id(String ref) {
        if (ref == null || !DocPath.looksLikePath(ref)) {
            return ref;
        }
        boolean all = ref.endsWith(ALL);
        String text = all ? ref.substring(0, ref.length() - ALL.length()) : ref;
        DocPath p = DocPath.parse(text);
        if (p.topLevel() && promoted.contains(p.leaf().get(0).field())) {
            return p.leaf().get(0).field() + (all ? ALL : "");
        }
        return register.apply(text).id() + (all ? ALL : "");
    }

    private Dim dim(Dim d) {
        return d == null ? null : new Dim(id(d.field()), d.group());
    }

    /** {@code fn(arg, arg)} with each path argument as its id (commas inside brackets, parentheses and quotes are the path's own). */
    private String measure(String text) {
        if (text != null && (text.strip().startsWith("fn:") || com.ash.drishti.bicompute.formula.FormulaParser.isFormula(paths(text))
                && !simpleCall(text))) {
            return paths(text);                     // a custom function's call or a formula: every path in it as its id
        }
        Matcher m = CALL.matcher(text == null ? "" : text);
        if (!m.matches()) {
            return text;
        }
        List<String> args = split(m.group(2));
        List<String> out = new ArrayList<>();
        for (String a : args) {
            out.add(DocPath.looksLikePath(a) || paths.containsKey(a) ? id(a) : a);
        }
        return m.group(1) + "(" + String.join(", ", out) + ")";
    }

    /** True for {@code fn(arg, ...)} whose arguments are fields, paths or numbers (no arithmetic around the call). */
    private static boolean simpleCall(String text) {
        Matcher m = CALL.matcher(text);
        if (!m.matches()) {
            return false;
        }
        int depth = 0;
        boolean quote = false;
        String body = m.group(2);
        for (char c : body.toCharArray()) {
            if (c == '\'' || c == '"') {
                quote = !quote;
            } else if (!quote && (c == '(' || c == '[')) {
                depth++;
            } else if (!quote && (c == ')' || c == ']')) {
                if (--depth < 0) {
                    return false;                       // the call closes before the end: a formula such as sum(x) / sum(y)
                }
            }
        }
        return true;
    }

    /**
     * Every Rachana path in a formula or a custom function's call as its field id ({@code sum($.LEGS[*].NOTIONAL) / count()} becomes
     * {@code sum(LEGS.NOTIONAL) / count()}): a path runs from {@code $} to the first space, comma, operator or closing parenthesis outside
     * its brackets and quotes.
     */
    String paths(String text) {
        if (text == null || text.indexOf('$') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '$' || i + 1 >= text.length() || text.charAt(i + 1) != '.' && text.charAt(i + 1) != '[') {
                out.append(c);
                i++;
                continue;
            }
            int j = i;
            int depth = 0;
            boolean quote = false;
            while (j < text.length()) {
                char d = text.charAt(j);
                if (d == '\'' || d == '"') {
                    quote = !quote;
                } else if (!quote && d == '[') {
                    depth++;
                } else if (!quote && d == ']') {
                    depth--;
                } else if (!quote && depth == 0 && (Character.isWhitespace(d) || ",)+-*/<>=!".indexOf(d) >= 0)) {
                    break;
                }
                j++;
            }
            out.append(id(text.substring(i, j)));
            i = j;
        }
        return out.toString();
    }

    /** The fields a formula or a custom function's call reads (its arguments), as ids. */
    private static List<String> fieldsOf(String measure) {
        String t = measure == null ? "" : measure.strip();
        if (t.startsWith("fn:")) {
            int open = t.indexOf('(');
            return open < 0 || !t.endsWith(")") ? List.of() : split(t.substring(open + 1, t.length() - 1));
        }
        try {
            return com.ash.drishti.bicompute.formula.FormulaFunction.of(t).fields();
        } catch (RuntimeException e) {
            return args(t);
        }
    }

    static List<String> split(String args) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean quote = false;
        StringBuilder cur = new StringBuilder();
        for (char c : args.toCharArray()) {
            if (c == '\'' || c == '"') {
                quote = !quote;
            } else if (!quote && (c == '(' || c == '[')) {
                depth++;
            } else if (!quote && (c == ')' || c == ']')) {
                depth--;
            } else if (!quote && depth == 0 && c == ',') {
                out.add(cur.toString().strip());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        if (!cur.toString().isBlank()) {
            out.add(cur.toString().strip());
        }
        return out;
    }

    private static List<String> args(String measure) {
        Matcher m = CALL.matcher(measure);
        return m.matches() ? split(m.group(2)) : List.of();
    }

    /**
     * The view in field ids at one grain. Refuses two separate lists on the same rows, and a figure of a coarser grain that cannot be
     * taken once per parent.
     */
    PivotView prepare(PivotView v) {
        List<Dim> rows = v.rows().stream().map(this::dim).toList();
        List<Dim> cols = v.columns().stream().map(this::dim).toList();
        List<PivotView.Value> values = v.values().stream().map(x -> new PivotView.Value(measure(x.measure()), x.as() == null || x.as().isBlank()
                ? x.measure() : x.as(), x.showAs())).toList();
        Map<String, AggregationPlan.Filter> filters = new LinkedHashMap<>();
        v.filters().forEach((k, f) -> filters.put(dim(Dim.parse(k)).id(), f));
        // the grain: the finest list the rows, columns and values use; every other must be on its way down
        Map<String, String> used = new LinkedHashMap<>();
        rows.forEach(d -> used.put(d.field(), grain(d.field())));
        cols.forEach(d -> used.put(d.field(), grain(d.field())));
        for (PivotView.Value x : values) {
            (simpleCall(x.measure()) ? args(x.measure()) : fieldsOf(x.measure())).stream().filter(a -> a.matches("[A-Za-z_][A-Za-z0-9_.~]*"))
                    .forEach(a -> used.put(a, grain(a)));
        }
        String grain = "";
        for (String g : used.values()) {
            if (g.length() > grain.length()) {
                grain = g;
            }
        }
        for (Map.Entry<String, String> e : used.entrySet()) {
            if (!prefix(e.getValue(), grain)) {
                String a = top(e.getValue());
                String b = top(grain);
                throw PivotView.bad(words(a) + " and " + words(b) + " are separate lists" + (a.equals(b) ? "" : " of each " + v.source())
                        + ": a view that crossed them would count every pair (three legs and four events make twelve rows). Pick one, or "
                        + "compare them side by side as two views");
            }
        }
        // coarser figures once per parent
        final int viewDepth = depth(grain);
        List<PivotView.Value> once = new ArrayList<>();
        for (PivotView.Value x : values) {
            if (!simpleCall(x.measure())) {
                String coarse = fieldsOf(x.measure()).stream().filter(f -> depth(grain(f)) < viewDepth && (promoted.contains(f) || paths.containsKey(f)))
                        .findFirst().orElse(null);
                if (coarse != null) {
                    throw PivotView.bad(x.label() + ": a formula or custom function reading " + coarse + " (one value per "
                            + words(grain(coarse)) + ") in a view of one row per " + words(grain) + " would count each " + words(grain(coarse))
                            + " many times; use the once-per-parent functions (sum_once, count_once, avg_once) or a view at the "
                            + words(grain(coarse)) + " grain");
                }
                once.add(x);
                continue;
            }
            Matcher m = CALL.matcher(x.measure());
            List<String> a = args(x.measure());
            String fn = m.matches() ? m.group(1).toLowerCase(Locale.ROOT) : "";
            String coarse = a.stream().filter(f -> depth(grain(f)) < viewDepth && (promoted.contains(f) || paths.containsKey(f))).findFirst()
                    .orElse(null);
            if (coarse == null || SAME_ONCE.contains(fn)) {
                once.add(x);
            } else if (ONCE.contains(fn) && a.size() == 1) {
                once.add(new PivotView.Value(fn + "_once(" + coarse + ", _p" + depth(grain(coarse)) + ")", x.as(), x.showAs()));
            } else {
                throw PivotView.bad(x.label() + ": " + fn + " of " + coarse + " (one value per " + words(grain(coarse)) + ") in a view of one row "
                        + "per " + words(grain) + " would count each " + words(grain(coarse)) + " many times; use sum, count, avg, min, max or "
                        + "count_distinct, or a view at the " + words(grain(coarse)) + " grain");
            }
        }
        Map<String, PivotView.Sort> sort = new LinkedHashMap<>();
        v.sort().forEach((k, s) -> sort.put(dim(Dim.parse(k)).id(), s));
        Map<String, PivotView.Top> top = new LinkedHashMap<>();
        v.top().forEach((k, t) -> top.put(dim(Dim.parse(k)).id(), t));
        List<PivotView.Expansion> exp = v.expanded().stream().map(e -> new PivotView.Expansion(e.axis(), e.path().stream().map(pp -> pp.isEmpty()
                ? pp : prepend(dim(Dim.parse(pp.get(0))).id(), pp.subList(1, pp.size()))).toList(), e.by() == null ? null : dim(Dim.parse(e.by())).id()))
                .toList();
        List<String> slicers = v.slicers().stream().map(s -> dim(Dim.parse(s)).id()).toList();
        return new PivotView(v.source(), v.date(), v.asOf(), rows, cols, once, filters, exp, v.expandRows(), v.expandColumns(), sort, top,
                v.subtotals(), v.currency(), slicers, v.formats(), v.options());
    }

    private static List<String> prepend(String a, List<String> rest) {
        List<String> out = new ArrayList<>();
        out.add(a);
        out.addAll(rest);
        return out;
    }

    private static String top(String grain) {
        return grain.isEmpty() ? "" : grain.split("\\.")[0];
    }

    /** {@code LEGS} as words: legs; the entity itself: the entity. */
    static String words(String grain) {
        if (grain.isEmpty()) {
            return "entity";
        }
        String last = grain.substring(grain.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT).replace('_', ' ');
        return last.endsWith("s") ? last.substring(0, last.length() - 1) : last;
    }

    /** The finest grain a plan reads (its grouped and filtered fields of a grain on the way down, and its functions' inputs). */
    String grain(AggregationPlan plan) {
        String grain = "";
        List<String> ids = new ArrayList<>();
        plan.dims().values().forEach(d -> ids.add(d.field()));
        plan.functions().forEach(f -> f.inputs().stream().filter(i -> !i.startsWith("_p")).forEach(ids::add));
        Set<String> filterOnly = new LinkedHashSet<>(plan.filters().keySet());
        for (String id : ids) {
            String g = grain(id);
            if (filterOnly.contains(id) && !used(plan, id)) {
                continue;                                         // a filter alone never makes the grain finer
            }
            if (g.length() > grain.length()) {
                grain = g;
            }
        }
        return grain;
    }

    private static boolean used(AggregationPlan plan, String id) {
        if (plan.functions().stream().anyMatch(f -> f.inputs().contains(id))) {
            return true;
        }
        return plan.sets().stream().anyMatch(s -> s.grouping().contains(id));
    }

    /**
     * A BI dataset table over one list of the kind ({@code each: LEGS}), built into the BI {@code cached} layer (Parquet, one row per
     * element): the dataset route of a list's fields (PIVOT_ENGINE.md section 6.7).
     *
     * @param from the layer's files as a FROM item ({@code read_parquet([...])})
     * @param id the entity id column; @param index the element's position in its list
     * @param columns field id ({@code LEGS.NOTIONAL}) to the layer's column
     * @param name the dataset and table, for the route's words
     */
    record Layer(String from, String id, String index, Map<String, String> columns, String name) {}

    /** How the last relation read its fields (the provenance's route words; a test reads it). */
    volatile String route = "";

    /** The plan's fields of one grain (grouped, filtered on, or a function's input). */
    private List<String> fieldsAt(AggregationPlan plan, String grain) {
        Set<String> ids = new LinkedHashSet<>();
        plan.dims().values().forEach(d -> ids.add(d.field()));
        plan.functions().forEach(f -> ids.addAll(f.inputs()));
        return ids.stream().filter(i -> paths.containsKey(i) && paths.get(i).grain().equals(grain)).toList();
    }

    String relation(String base, AggregationPlan plan, List<String> fields, Map<String, String> types) {
        return relation(base, plan, fields, types, g -> null);
    }

    /**
     * The relation of one plan: {@code base} (a table with the promoted columns, {@code _id} and {@code _doc}) unnested down to the grain,
     * every field as a column of its id, the parents' keys {@code _p0} (the entity) and {@code _p1}… (each list element). A one-list grain
     * whose every used field a BI dataset table holds ({@code layers}) reads that table's cached layer instead of unnesting the documents.
     */
    String relation(String base, AggregationPlan plan, List<String> fields, Map<String, String> types, Function<String, Layer> layers) {
        String grain = grain(plan);
        List<DocPath.Level> levels = levelsOf(grain);
        Map<String, String> fromLayer = new LinkedHashMap<>();
        if (levels.size() == 1) {
            Layer l = layers.apply(grain);
            List<String> need = fieldsAt(plan, grain);
            boolean deeper = plan.dims().values().stream().map(Dim::field).anyMatch(f -> grain(f).startsWith(grain + "."));
            if (l != null && !deeper && !need.isEmpty() && need.stream().allMatch(f -> l.columns().containsKey(f)
                    && paths.get(f).grainLevels().get(0).keep() == null)) {
                List<String> picks = new ArrayList<>();
                l.columns().forEach((f, c) -> {
                    fromLayer.put(f, "_d_" + fromLayer.size());
                    picks.add("l." + DuckDbExecutor.quote(c) + " AS \"" + fromLayer.get(f) + "\"");
                });
                String from = "(SELECT t.*, l." + DuckDbExecutor.quote(l.index()) + " AS \"_i1\", " + String.join(", ", picks) + " FROM " + base
                        + " t JOIN " + l.from() + " l ON CAST(l." + DuckDbExecutor.quote(l.id()) + " AS VARCHAR) = CAST(t.\"_id\" AS VARCHAR))";
                route = "dataset " + l.name() + " (the BI cached layer) for " + String.join(", ", need) + "; the documents for the rest";
                return select(from, 1, plan, fields, types, grain, fromLayer);
            }
        }
        String from = base;
        for (int i = 0; i < levels.size(); i++) {
            String parent = i == 0 ? "CAST(\"_doc\" AS JSON)" : "\"_e" + i + "\"";
            String list = DocPath.list(levels.get(i), parent, grain);
            int n = i + 1;
            from = "(SELECT s.*, s.\"_l" + n + "\"[s.\"_i" + n + "\" + 1] AS \"_e" + n + "\" FROM (SELECT *, unnest(range(len(\"_l" + n + "\"))) AS \"_i" + n
                    + "\" FROM (SELECT *, " + list + " AS \"_l" + n + "\" FROM " + from + ")) s)";
        }
        route = levels.isEmpty() ? "the documents' paths (DuckDB JSON over each document) and the promoted columns"
                : "the documents unnested to one row per " + words(grain) + " (DuckDB JSON over each document)";
        return select(from, levels.size(), plan, fields, types, grain, fromLayer);
    }

    private String select(String from, int depth, AggregationPlan plan, List<String> fields, Map<String, String> types, String grain,
            Map<String, String> fromLayer) {
        List<String> cols = new ArrayList<>();
        cols.add("CAST(\"_id\" AS VARCHAR) AS \"_p0\"");
        for (int i = 1; i <= depth; i++) {
            String prev = "CAST(\"_id\" AS VARCHAR)";
            for (int j = 1; j <= i; j++) {
                prev = prev + " || '/' || CAST(\"_i" + j + "\" AS VARCHAR)";
            }
            cols.add(prev + " AS \"_p" + i + "\"");
        }
        for (String f : fields) {
            if (promoted.contains(f)) {
                cols.add(DuckDbExecutor.quote(f));
                continue;
            }
            boolean number = "number".equals(types.get(f));
            String layered = fromLayer.get(f);
            boolean unread = !fromLayer.isEmpty() && layered == null && !grain(f).isEmpty() && (grain(f) + ".").startsWith(grain + ".");
            String expr = unread ? "NULL" : layered == null ? column(f, grain, plan, number)
                    : number ? "TRY_CAST(\"" + layered + "\" AS DOUBLE)" : "CAST(\"" + layered + "\" AS VARCHAR)";
            cols.add(expr + " AS " + DuckDbExecutor.quote(f));
        }
        return "(SELECT " + String.join(", ", cols) + " FROM " + from + ")";
    }

    /** The lists of a grain, reached as the field list's paths reach them (unfiltered: a filtered path keeps its own elements). */
    private List<DocPath.Level> levelsOf(String grain) {
        if (grain.isEmpty()) {
            return List.of();
        }
        for (DocPath p : paths.values()) {
            if (p.grain().equals(grain)) {
                return p.grainLevels().stream().map(l -> new DocPath.Level(l.reach(), null, l.name())).toList();
            }
        }
        throw PivotView.bad("no list reaches the grain " + grain);
    }

    private String column(String id, String grain, AggregationPlan plan, boolean number) {
        boolean all = id.endsWith(ALL);
        DocPath p = paths.get(all ? id.substring(0, id.length() - ALL.length()) : id);
        if (p == null) {
            return "NULL";
        }
        String g = p.grain();
        if (prefix(g, grain) && !all) {
            List<DocPath.Level> lv = p.grainLevels();
            String element = lv.isEmpty() ? "CAST(\"_doc\" AS JSON)" : "\"_e" + lv.size() + "\"";
            String v = p.size() ? p.count(element) : p.value(element, number);
            List<String> keep = new ArrayList<>();
            for (int i = 0; i < lv.size(); i++) {
                if (lv.get(i).keep() != null) {
                    keep.add(DocPath.predicate(lv.get(i).keep(), "\"_e" + (i + 1) + "\"", p.text()));
                }
            }
            return keep.isEmpty() ? v : "CASE WHEN " + String.join(" AND ", keep) + " THEN " + v + " END";
        }
        AggregationPlan.Filter f = plan.filters().get(id);
        if (f == null) {
            return "NULL";                                        // a finer or separate list's field the plan does not read
        }
        // a filter on a finer or a separate list: the entity (or the grain's element) is kept when any (or all) of its elements match
        String common = grain;
        while (!prefix(common, g)) {
            common = common.contains(".") ? common.substring(0, common.lastIndexOf('.')) : "";
        }
        int d = depth(common);
        String element = d == 0 ? "CAST(\"_doc\" AS JSON)" : "\"_e" + d + "\"";
        String values = values(p, d, element, number);
        List<String> conds = new ArrayList<>();
        if (!f.in().isEmpty()) {
            conds.add("list_has_any(" + values + ", " + sqlList(f.in(), number) + ")");
            if (all) {
                conds.add("len(list_filter(" + values + ", v -> NOT list_contains(" + sqlList(f.in(), number) + ", v))) = 0");
            }
        }
        if (!f.notIn().isEmpty()) {
            conds.add("NOT list_has_any(" + values + ", " + sqlList(f.notIn(), number) + ")");
        }
        String keep = f.in().isEmpty() ? "'(kept)'" : DocPath.lit(f.in().get(0));
        return conds.isEmpty() ? "NULL" : "CASE WHEN " + String.join(" AND ", conds) + " THEN " + keep + " END";
    }

    /** Every value of a path under one element (the document, or a list element of depth {@code from}), flattened, as text. */
    private static String values(DocPath p, int from, String element, boolean number) {
        List<DocPath.Level> lv = p.grainLevels().subList(from, p.grainLevels().size());
        return values(p, lv, 0, element);
    }

    private static String values(DocPath p, List<DocPath.Level> lv, int i, String element) {
        if (i == lv.size()) {
            String v = p.size() ? "CAST(" + p.count(element) + " AS VARCHAR)" : p.value(element, false);
            return "[" + v + "]";
        }
        String var = "x" + i;
        return "flatten(list_transform(" + DocPath.list(lv.get(i), element, p.text()) + ", " + var + " -> " + values(p, lv, i + 1, var) + "))";
    }

    private static String sqlList(List<String> vals, boolean number) {
        return "[" + String.join(", ", vals.stream().map(DocPath::lit).toList()) + "]";
    }
}
