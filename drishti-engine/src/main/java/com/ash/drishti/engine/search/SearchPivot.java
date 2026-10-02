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
package com.ash.drishti.engine.search;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.pivot.PivotCube;
import com.ash.drishti.engine.pivot.PivotProperties;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.inference.Semantics;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.ElException;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.rachana.model.PivotSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The server engine of the Pivot tab on search results and pick lists (a pack opts a kind in with {@code pivot:}): every
 * entity the search matches on the business date, over the day's promoted columns (the source router's
 * {@code columns()}), grouped and combined here, so only the cells travel. Fields the caller's role may not see are
 * masked exactly as in a search: grouped under the mask, never added up. A field that no source keeps as a column is
 * refused with the reason, unless the caller asks for documents, which are then read (at most
 * {@link PivotProperties#documentScan()}, the result marked partial when that cuts it short). Stateless and thread-safe.
 */
public final class SearchPivot {

    /** The rows one scan visits: an id and its values, in the order of the paths asked for. */
    @FunctionalInterface
    interface Sink {
        void accept(String id, Object[] values);
    }

    /** How a scan went. */
    record Scan(String source, int total, boolean partial, List<String> masked) {}

    private final SourceRouter router;
    private final StructuredSearch search;
    private final ElCompiler el;
    private final Formats formats;
    private final SearchProperties props;
    private final PivotProperties pivots;

    public SearchPivot(SourceRouter router, StructuredSearch search, ElCompiler el, Formats formats, SearchProperties props,
            PivotProperties pivots) {
        this.router = router;
        this.search = search;
        this.el = el;
        this.formats = formats;
        this.props = props;
        this.pivots = pivots;
    }

    /** The kind a mnemonic or kind names ({@code TRD} or {@code trade}). */
    public String kindOf(String head) {
        return search.kindOf(new SearchQuery(head, null, null, false, 1, List.of()));
    }

    /** The pivot a kind's results offer, if its pack opts in (and pivots are on). */
    public Optional<PivotSpec> offered(String kind) {
        return pivots.enabled() ? props.pivotOf(kind) : Optional.empty();
    }

    /**
     * What a search result carries for the Pivot tab: the fields (each with its label and whether a source keeps it as a
     * column, so the server engine can read it whole) and the arrangement it opens with; empty when the kind does not opt in.
     */
    public Optional<Map<String, Object>> describe(String kind) {
        return offered(kind).map(spec -> {
            Set<String> kept = router.columnar(kind);
            Map<String, Object> out = spec.toMap();
            List<Map<String, Object>> fields = new ArrayList<>();
            for (PivotSpec.Field f : spec.fields()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", f.name());
                m.put("label", f.label() != null ? f.label() : label(f.name()));
                m.put("fmt", f.fmt());
                m.put("promoted", kept.contains(f.name()));
                fields.add(m);
            }
            out.put("fields", fields);
            out.put("maxRowKeys", pivots.maxRowKeys());
            out.put("maxColumnKeys", pivots.maxColumnKeys());
            out.put("documentScan", pivots.documentScan());
            return out;
        });
    }

    /**
     * The cube of a search's matches.
     *
     * @param kind the kind (from the path)
     * @param q the search (its head must name the same kind; null or blank: every entity of the kind)
     * @param arrangement rows, columns, values, filters, heat, chart (as JSON reads them)
     * @param documents read documents for fields that are not columns (at most {@code document-scan})
     */
    public Map<String, Object> pivot(String kind, String q, Map<String, Object> arrangement, boolean documents, AsOf asOf,
            UnaryOperator<DataNode> redact) {
        long t0 = System.nanoTime();
        PivotSpec a = arrangement(kind, arrangement);
        List<String> paths = PivotCube.fieldsOf(a);
        PivotCube cube = new PivotCube(a, labels(a), pivots.maxRowKeys(), pivots.maxColumnKeys());
        Map<String, Integer> at = index(paths);
        Scan scan = scan(kind, query(kind, q), paths, documents, asOf, redact, (id, values) -> {
            PivotCube.Row row = field -> values[at.get(field)];
            if (cube.accepts(row)) {
                cube.add(row);
            }
        });
        return cube.result(scan.source(), scan.total(), scan.partial(), scan.masked(), Math.round((System.nanoTime() - t0) / 1e4) / 100.0);
    }

    /**
     * The entities behind one cell (row and column key prefixes; empty prefixes are totals), a page at a time, with the
     * pivot's fields as values.
     */
    public Map<String, Object> drill(String kind, String q, Map<String, Object> arrangement, List<String> rowKeys, List<String> columnKeys,
            int offset, int size, boolean documents, AsOf asOf, UnaryOperator<DataNode> redact) {
        PivotSpec a = arrangement(kind, arrangement);
        LinkedHashSet<String> wanted = new LinkedHashSet<>(PivotCube.fieldsOf(a));
        Set<String> kept = router.columnar(kind);
        for (PivotSpec.Field f : offered(kind).orElseThrow().fields()) {
            if (documents || kept.contains(f.name())) {
                wanted.add(f.name());                      // the other fields on offer, when they cost nothing more
            }
        }
        List<String> paths = List.copyOf(wanted);
        Map<String, Integer> at = index(paths);
        PivotCube cube = new PivotCube(a, Map.of(), 1, 1);
        int from = Math.max(0, offset);
        int page = Math.max(1, Math.min(size <= 0 ? 50 : size, pivots.drillPage()));
        List<Map<String, Object>> rows = new ArrayList<>();
        int[] matched = {0};
        Scan scan = scan(kind, query(kind, q), paths, documents, asOf, redact, (id, values) -> {
            if (!cube.matches(field -> values[at.get(field)], rowKeys, columnKeys)) {
                return;
            }
            int n = matched[0]++;
            if (n >= from && n < from + page) {
                Map<String, Object> vs = new LinkedHashMap<>();
                for (int i = 0; i < paths.size(); i++) {
                    vs.put(paths.get(i), values[i]);
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", id);
                row.put("kind", kind);
                row.put("values", vs);
                rows.add(row);
            }
        });
        Map<String, Object> labels = new LinkedHashMap<>();
        paths.forEach(p -> labels.put(p, label(p, a)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fields", paths);
        out.put("labels", labels);
        out.put("rows", rows);
        out.put("total", matched[0]);
        out.put("offset", from);
        out.put("size", page);
        out.put("partial", scan.partial());
        out.put("masked", scan.masked());
        out.put("source", scan.source());
        return out;
    }

    /** A field's values among the search's matches, with how often each occurs (a filter's pick list). */
    public Map<String, Object> values(String kind, String q, String field, boolean documents, AsOf asOf, UnaryOperator<DataNode> redact) {
        PivotSpec offered = offered(kind).orElseThrow(() -> notOffered(kind));
        if (offered.field(field) == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'" + field + "' is not one of the pivot's fields (" + String.join(", ", offered.names()) + ")");
        }
        Map<String, long[]> counts = new HashMap<>();
        double[] range = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        boolean[] numeric = {true};
        Scan scan = scan(kind, query(kind, q), List.of(field), documents, asOf, redact, (id, values) -> {
            Object v = values[0];
            counts.computeIfAbsent(PivotCube.key(v), k -> new long[1])[0]++;
            if (v instanceof Number n) {
                range[0] = Math.min(range[0], n.doubleValue());
                range[1] = Math.max(range[1], n.doubleValue());
            } else if (v != null) {
                numeric[0] = false;
            }
        });
        List<String> keys = new ArrayList<>(counts.keySet());
        keys.sort((x, y) -> PivotCube.compareKeys(List.of(x), List.of(y)));
        int max = 500;
        List<Map<String, Object>> vs = new ArrayList<>();
        for (String k : keys.subList(0, Math.min(max, keys.size()))) {
            vs.add(Map.of("value", k, "count", counts.get(k)[0]));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("field", field);
        out.put("values", vs);
        out.put("more", keys.size() > max);
        boolean num = numeric[0] && Double.isFinite(range[0]);
        out.put("numeric", num);
        out.put("min", num ? Values.normalise(range[0]) : null);
        out.put("max", num ? Values.normalise(range[1]) : null);
        out.put("partial", scan.partial());
        out.put("source", scan.source());
        return out;
    }

    // ---- the scan ------------------------------------------------------------------------------------------------

    /**
     * Visits every entity of the kind the query matches, with its values of {@code paths}: over columns when a source
     * keeps them all (and the query's), else over documents when asked, else refused with the reason.
     */
    Scan scan(String kind, SearchQuery q, List<String> paths, boolean documents, AsOf asOf, UnaryOperator<DataNode> redact, Sink sink) {
        Expr condition = compile(q == null ? null : q.condition());
        String idPattern = q == null ? null : q.idPattern();
        LinkedHashSet<String> needed = new LinkedHashSet<>(paths);
        List<String> conditionPaths = new ArrayList<>();
        boolean plainCondition = true;
        if (condition != null) {
            List<String> cp = new ArrayList<>();
            condition.paths(cp::add);
            for (String p : cp) {
                if (!p.startsWith("$.") || p.length() < 3) {
                    plainCondition = false;
                } else {
                    conditionPaths.add(p.substring(2));
                }
            }
            needed.addAll(conditionPaths);
        }
        Set<String> kept = router.columnar(kind);
        List<String> missing = needed.stream().filter(p -> !kept.contains(p)).toList();
        Map<String, Object> masked = StructuredSearch.masks(List.copyOf(needed), redact);
        List<String> maskedShown = paths.stream().filter(masked::containsKey).toList();
        if (plainCondition && missing.isEmpty() && !kept.isEmpty()) {
            return columns(kind, condition, idPattern, paths, List.copyOf(needed), conditionPaths, masked, maskedShown, asOf, sink);
        }
        if (!documents) {
            String reason = kept.isEmpty() ? "no source of " + kind + " keeps its fields as columns here"
                    : "not kept as columns for " + kind + ": " + missing + "; these are: " + kept.stream().sorted().toList();
            throw new DrishtiException(ErrorCode.BAD_REQUEST, reason + ". Ask for a document read (documents: true) to read up to "
                    + pivots.documentScan() + " " + kind + " documents instead; the result is then partial if there are more");
        }
        return documents(kind, condition, idPattern, paths, redact, maskedShown, asOf, sink);
    }

    private Scan columns(String kind, Expr condition, String idPattern, List<String> paths, List<String> needed, List<String> conditionPaths,
            Map<String, Object> masked, List<String> maskedShown, AsOf asOf, Sink sink) {
        com.ash.drishti.engine.source.SourceFailures failures = new com.ash.drishti.engine.source.SourceFailures();
        Optional<ColumnSet> got = router.columns(kind, needed, asOf, pivots.budget(), failures);
        if (got.isEmpty()) {
            String why = failures.asMap().entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).reduce((a, b) -> a + "; " + b).orElse(null);
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, why != null ? "the source of " + kind + " could not answer with columns: " + why
                    : "the source of " + kind + " did not answer with columns within " + pivots.budget().toSeconds() + " s (or does not hold that date)");
        }
        ColumnSet c = got.get();
        Object[] values = new Object[paths.size()];
        for (int i = 0; i < c.size(); i++) {
            if (idPattern != null && !SearchQuery.matches(idPattern, c.ids()[i], null)) {
                continue;
            }
            if (condition != null && !holds(condition, EvalContext.of(StructuredSearch.row(c, i, conditionPaths, masked), formats))) {
                continue;
            }
            for (int k = 0; k < values.length; k++) {
                String p = paths.get(k);
                values[k] = masked.containsKey(p) ? masked.get(p) : StructuredSearch.number(c.value(p, i));
            }
            sink.accept(c.ids()[i], values);
        }
        return new Scan("columns", c.size(), !failures.isEmpty(), maskedShown);   // a day the source could not read whole: partial
    }

    private Scan documents(String kind, Expr condition, String idPattern, List<String> paths, UnaryOperator<DataNode> redact,
            List<String> maskedShown, AsOf asOf, Sink sink) {
        int cap = pivots.documentScan();
        String narrow = idPattern != null && !idPattern.contains("*") ? idPattern : "";
        com.ash.drishti.engine.source.SourceFailures failures = new com.ash.drishti.engine.source.SourceFailures();
        List<EntityHit> hits = router.list(kind, narrow, cap + 1, pivots.budget(), asOf, failures).hits();
        if (idPattern != null) {
            hits = hits.stream().filter(h -> SearchQuery.matches(idPattern, h.ref().id(), h.title())).toList();
        }
        boolean partial = hits.size() > cap;
        if (partial) {
            hits = hits.subList(0, cap);
        }
        List<EntityRef> refs = hits.stream().map(EntityHit::ref).toList();
        Map<EntityRef, EntityDocument> docs = router.fetchAll(refs, pivots.budget(), asOf, failures);
        partial |= docs.size() < refs.size() || !failures.isEmpty();     // a failing source: not every entity was seen
        List<Expr> exprs = paths.stream().map(p -> el.compile("$." + p)).toList();
        Object[] values = new Object[paths.size()];
        for (EntityRef ref : refs) {
            EntityDocument d = docs.get(ref);
            if (d == null) {
                continue;
            }
            EvalContext ctx = EvalContext.of(redact.apply(d.data()), formats);
            if (condition != null && !holds(condition, ctx)) {
                continue;
            }
            for (int k = 0; k < values.length; k++) {
                values[k] = leaf(exprs.get(k), ctx);
            }
            sink.accept(ref.id(), values);
        }
        return new Scan("documents", refs.size(), partial, maskedShown);
    }

    private static boolean holds(Expr condition, EvalContext ctx) {
        try {
            return Values.truthy(condition.eval(ctx));
        } catch (RuntimeException e) {
            return false;                                  // a row that cannot answer the condition does not match
        }
    }

    private static Object leaf(Expr e, EvalContext ctx) {
        Object v;
        try {
            v = Values.simplify(e.eval(ctx));
        } catch (RuntimeException ex) {
            return null;
        }
        if (v instanceof DataNode n) {
            return n.isNull() ? null : n.size();
        }
        return v instanceof Double d ? StructuredSearch.number(d) : v;
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private PivotSpec arrangement(String kind, Map<String, Object> arrangement) {
        PivotSpec offered = offered(kind).orElseThrow(() -> notOffered(kind));
        PivotSpec.Parsed p = PivotSpec.arrangement(arrangement == null ? Map.of() : arrangement, offered);
        if (!p.problems().isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, String.join("; ", p.problems()));
        }
        return p.spec();
    }

    private static DrishtiException notOffered(String kind) {
        return new DrishtiException(ErrorCode.BAD_REQUEST, kind + " search results do not offer a pivot (its pack opts a kind in with pivot: in pack.yaml)");
    }

    private SearchQuery query(String kind, String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        SearchQuery query;
        try {
            query = SearchQuery.pick(q);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_SEARCH, e.getMessage());
        }
        String named = search.kindOf(query);
        if (!named.equals(kind)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the search names " + named + ", not " + kind);
        }
        return search.named(query, kind);                     // field names in any case, as the search reads them
    }

    private Expr compile(String source) {
        if (source == null) {
            return null;
        }
        try {
            return el.compile(source);
        } catch (ElException e) {
            throw new DrishtiException(ErrorCode.BAD_SEARCH, "cannot read the condition: " + e.getMessage());
        }
    }

    private static Map<String, Integer> index(List<String> paths) {
        Map<String, Integer> at = new HashMap<>();
        for (int i = 0; i < paths.size(); i++) {
            at.put(paths.get(i), i);
        }
        return at;
    }

    private static Map<String, String> labels(PivotSpec a) {
        Map<String, String> out = new HashMap<>();
        for (String p : PivotCube.fieldsOf(a)) {
            out.put(p, label(p, a));
        }
        return out;
    }

    private static String label(String path, PivotSpec a) {
        PivotSpec.Field f = a.field(path);
        return f != null && f.label() != null ? f.label() : label(path);
    }

    /** A field's name in words; a generic last part keeps its parent ({@code counterparty.name}: Counterparty name). */
    private static String label(String path) {
        String[] parts = path.split("\\.");
        String last = Semantics.humanize(parts[parts.length - 1]);
        if (parts.length > 1 && java.util.Set.of("name", "id", "type", "code", "value").contains(parts[parts.length - 1])) {
            return Semantics.humanize(parts[parts.length - 2]) + " " + last.toLowerCase(java.util.Locale.ROOT);
        }
        return last;
    }
}
