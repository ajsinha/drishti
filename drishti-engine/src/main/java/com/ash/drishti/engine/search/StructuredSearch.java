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
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.ElException;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.format.Formats;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Runs a {@link SearchQuery}: lists the kind's entities from every source that can (within the scan limit and time
 * budget), reads them concurrently on virtual threads, keeps those for which the condition holds, sorts and limits.
 * The condition sees each document as the caller may see it (redacted), so a hidden field cannot be probed through
 * a search. Stateless and thread-safe.
 */
public final class StructuredSearch {

    /**
     * @param ref the entity
     * @param title its title in the search index
     * @param values the query's fields for this entity, by path
     */
    public record Row(EntityRef ref, String title, Map<String, Object> values) {}

    /**
     * @param kind the kind searched
     * @param condition the condition as Rachana-EL (null for all)
     * @param columns the document paths shown for each row
     * @param scanned entities read
     * @param matched entities for which the condition held (before the limit)
     * @param partial true when the scan limit, the budget or a failing source may have left entities out
     */
    public record Result(String kind, String condition, String orderBy, List<String> columns, List<Row> rows, int scanned, int matched,
            boolean partial, double elapsedMs) {}

    /** A matching entity and its sort value. */
    private record Match(Row row, Object key) {}

    private final SourceRouter router;
    private final Mnemonics mnemonics;
    private final ElCompiler el;
    private final Formats formats;
    private final SearchProperties props;

    public StructuredSearch(SourceRouter router, Mnemonics mnemonics, ElCompiler el, Formats formats, SearchProperties props) {
        this.router = router;
        this.mnemonics = mnemonics;
        this.el = el;
        this.formats = formats;
        this.props = props;
    }

    /** The kind a query's head names: a mnemonic ({@code TRD}) or a kind ({@code trade}). */
    public String kindOf(SearchQuery q) {
        var m = mnemonics.of(q.head().toUpperCase(Locale.ROOT));
        if (m.isPresent()) {
            return m.get().kind();
        }
        String kind = q.head().toLowerCase(Locale.ROOT);
        if (mnemonics.all().values().stream().noneMatch(x -> x.kind().equals(kind))) {
            throw new DrishtiException(ErrorCode.BAD_SEARCH, "'" + q.head() + "' is neither a mnemonic nor a kind; type it alone to see suggestions");
        }
        return kind;
    }

    public Result run(SearchQuery q, AsOf asOf, UnaryOperator<DataNode> redact) {
        long t0 = System.nanoTime();
        String kind = kindOf(q);
        Expr condition = compile(q.condition(), "condition");
        Expr order = compile(q.orderBy(), "order by");
        Optional<Result> fast = columnar(q, kind, condition, order, asOf, redact, t0);
        if (fast.isPresent()) {
            return fast.get();
        }
        // a pick list names what it wants (TRD T-100): the sources' own indexes narrow by it, so a large book is not
        // cut at maxScan before the match is found; a wildcard (T-1*0) is filtered here
        String narrow = q.idPattern() != null && !q.idPattern().contains("*") ? q.idPattern() : "";
        List<EntityHit> hits = router.search(kind, narrow, props.maxScan() + 1, props.budget(), asOf);
        if (q.idPattern() != null) {                            // only what the word names, before any read
            hits = hits.stream().filter(h -> SearchQuery.matches(q.idPattern(), h.ref().id(), h.title())).toList();
        }
        boolean partial = hits.size() > props.maxScan();
        if (partial) {
            hits = hits.subList(0, props.maxScan());
        }
        Map<EntityRef, String> titles = new LinkedHashMap<>();
        hits.forEach(h -> titles.put(h.ref(), h.title()));
        Map<EntityRef, EntityDocument> docs = router.fetchAll(titles.keySet(), props.budget(), asOf);
        partial |= docs.size() < titles.size();
        List<String> paths = columnsFor(q, kind, docs.values(), redact);
        List<Expr> columns = paths.stream().map(el::compile).toList();
        List<Match> matches = new ArrayList<>();
        for (Map.Entry<EntityRef, EntityDocument> e : docs.entrySet()) {
            EvalContext ctx = EvalContext.of(redact.apply(e.getValue().data()), formats);
            boolean keep;
            try {
                keep = condition == null || Values.truthy(condition.eval(ctx));
            } catch (RuntimeException ex) {
                keep = false;                              // a document that cannot answer the condition does not match
            }
            if (!keep) {
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                values.put(paths.get(i), leaf(safe(columns.get(i), ctx)));
            }
            matches.add(new Match(new Row(e.getKey(), titles.get(e.getKey()), values), order == null ? null : leaf(safe(order, ctx))));
        }
        if (order != null) {
            int sign = q.descending() ? -1 : 1;
            matches.sort((a, b) -> a.key() == null || b.key() == null
                    ? (a.key() == null ? 1 : 0) - (b.key() == null ? 1 : 0)   // no sort value: last, either direction
                    : sign * compare(a.key(), b.key()));
        } else {
            matches.sort(Comparator.comparing(m -> m.row().ref().id()));
        }
        List<Row> rows = matches.stream().limit(q.limit()).map(Match::row).toList();
        return new Result(kind, q.condition(), q.orderBy(), paths, rows, docs.size(), matches.size(), partial,
                Math.round((System.nanoTime() - t0) / 1e4) / 100.0);
    }

    /**
     * The search over columns, when the source that serves the kind keeps every field the query and its result
     * columns read as columns (a Delta table laid out by its pack): every entity of the date is considered, exactly,
     * without reading a document. Empty when it cannot be answered so (the documents are read instead).
     */
    private Optional<Result> columnar(SearchQuery q, String kind, Expr condition, Expr order, AsOf asOf, UnaryOperator<DataNode> redact, long t0) {
        List<String> shown = new ArrayList<>(new java.util.LinkedHashSet<>(columnsFor(q, kind, List.of(), redact)));
        java.util.LinkedHashSet<String> needed = new java.util.LinkedHashSet<>(shown);
        if (condition != null) {
            condition.paths(needed::add);
        }
        if (order != null) {
            order.paths(needed::add);
        }
        if (shown.isEmpty() || needed.stream().anyMatch(p -> !p.startsWith("$.") || p.length() < 3)) {
            return Optional.empty();
        }
        List<String> plain = needed.stream().map(p -> p.substring(2)).toList();
        if (!router.columnar(kind).containsAll(plain)) {
            return Optional.empty();
        }
        // a business day's columns load once and are kept: the first search of a day may wait for them
        Optional<ColumnSet> got = router.columns(kind, plain, asOf, props.budget().compareTo(COLUMNS_BUDGET) > 0 ? props.budget() : COLUMNS_BUDGET);
        if (got.isEmpty()) {
            return Optional.empty();
        }
        ColumnSet c = got.get();
        Map<String, Object> masked = masks(plain, redact);           // what the caller's role may not see, per path
        List<String> conditionPaths = new ArrayList<>();
        if (condition != null) {
            condition.paths(p -> conditionPaths.add(p.substring(2)));
        }
        List<String> orderPaths = new ArrayList<>();
        if (order != null) {
            order.paths(p -> orderPaths.add(p.substring(2)));
        }
        List<Match> matches = new ArrayList<>();
        for (int i = 0; i < c.size(); i++) {
            if (q.idPattern() != null && !SearchQuery.matches(q.idPattern(), c.ids()[i], null)) {
                continue;
            }
            if (condition != null) {
                EvalContext ctx = EvalContext.of(row(c, i, conditionPaths, masked), formats);
                boolean keep;
                try {
                    keep = Values.truthy(condition.eval(ctx));
                } catch (RuntimeException ex) {
                    keep = false;
                }
                if (!keep) {
                    continue;
                }
            }
            Object key = null;
            if (order != null) {
                key = leaf(safe(order, EvalContext.of(row(c, i, orderPaths, masked), formats)));
            }
            matches.add(new Match(new Row(EntityRef.of(kind, c.ids()[i]), c.ids()[i], Map.of("__row", i)), key));
        }
        if (order != null) {
            int sign = q.descending() ? -1 : 1;
            matches.sort((a, b) -> a.key() == null || b.key() == null
                    ? (a.key() == null ? 1 : 0) - (b.key() == null ? 1 : 0)
                    : sign * compare(a.key(), b.key()));
        } else {
            matches.sort(Comparator.comparing(m -> m.row().ref().id()));
        }
        List<Row> rows = new ArrayList<>();
        for (Match m : matches.subList(0, Math.min(q.limit(), matches.size()))) {
            int i = (Integer) m.row().values().get("__row");
            Map<String, Object> values = new LinkedHashMap<>();
            for (String path : shown) {
                String plainPath = path.substring(2);
                values.put(path, masked.containsKey(plainPath) ? masked.get(plainPath) : number(c.value(plainPath, i)));
            }
            rows.add(new Row(m.row().ref(), m.row().title(), values));
        }
        return Optional.of(new Result(kind, q.condition(), q.orderBy(), shown, rows, c.size(), matches.size(), false,
                Math.round((System.nanoTime() - t0) / 1e4) / 100.0));
    }

    /** A whole number read from a float64 column shows as one (1875863, not 1875863.0). */
    private static Object number(Object v) {
        return v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) d.longValue() : v;
    }

    /** One row as a small document holding only the given paths, for the expression to read. */
    private static DataNode row(ColumnSet c, int i, List<String> paths, Map<String, Object> masked) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (String path : paths) {
            Object v = masked.containsKey(path) ? masked.get(path) : number(c.value(path, i));
            String[] parts = path.split("\\.");
            Map<String, Object> at = root;
            for (int p = 0; p < parts.length - 1; p++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> next = (Map<String, Object>) at.computeIfAbsent(parts[p], k -> new LinkedHashMap<String, Object>());
                at = next;
            }
            at.put(parts[parts.length - 1], v);
        }
        return DataNode.of(root);
    }

    /**
     * The paths the caller's role would see masked, with what it would see instead: a probe document holding every
     * path is put through the same redaction as documents are.
     */
    private static Map<String, Object> masks(List<String> paths, UnaryOperator<DataNode> redact) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (String path : paths) {
            String[] parts = path.split("\\.");
            Map<String, Object> at = root;
            for (int p = 0; p < parts.length - 1; p++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> next = (Map<String, Object>) at.computeIfAbsent(parts[p], k -> new LinkedHashMap<String, Object>());
                at = next;
            }
            at.put(parts[parts.length - 1], "\u0000probe");
        }
        DataNode seen = redact.apply(DataNode.of(root));
        Map<String, Object> out = new LinkedHashMap<>();
        for (String path : paths) {
            DataNode n = seen;
            for (String part : path.split("\\.")) {
                n = n.get(part);
            }
            Object v = n.isNull() ? null : n.unwrap();
            if (!"\u0000probe".equals(v)) {
                out.put(path, v);
            }
        }
        return out;
    }

    /**
     * The columns: the fields the query reads, then the kind's key fields from its pack. A kind whose pack names none
     * shows the first few plain fields of its documents, so a pick list always says more than the id.
     */
    private List<String> columnsFor(SearchQuery q, String kind, java.util.Collection<EntityDocument> docs, UnaryOperator<DataNode> redact) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(q.fields());
        List<String> key = props.columnsOf(kind);
        out.addAll(key);
        if (key.isEmpty() && !docs.isEmpty()) {
            DataNode first = redact.apply(docs.iterator().next().data());
            if (first instanceof DataNode.Obj o) {
                o.fields().entrySet().stream()
                        .filter(e -> e.getValue() instanceof DataNode.Val && !e.getKey().startsWith("_") && !e.getKey().equalsIgnoreCase("id"))
                        .limit(Math.max(0, AUTO_COLUMNS - out.size()))
                        .forEach(e -> out.add("$." + e.getKey()));
            }
        }
        return List.copyOf(out);
    }

    private static final int AUTO_COLUMNS = 6;
    private static final java.time.Duration COLUMNS_BUDGET = java.time.Duration.ofSeconds(20);

    private Expr compile(String source, String what) {
        if (source == null) {
            return null;
        }
        try {
            return el.compile(source);
        } catch (ElException e) {
            throw new DrishtiException(ErrorCode.BAD_SEARCH, "cannot read the " + what + ": " + e.getMessage());
        }
    }

    private static Object safe(Expr e, EvalContext ctx) {
        try {
            return e.eval(ctx);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** A value fit for a result cell: scalars as they are; lists and objects as their size. */
    private static Object leaf(Object v) {
        Object s = Values.simplify(v);
        if (s instanceof DataNode n) {
            return switch (n.type()) {
                case ARRAY, OBJECT -> n.size();
                default -> n.isNull() ? null : n.unwrap();
            };
        }
        return s;
    }

    private static int compare(Object a, Object b) {
        if (Values.isNumber(a) && Values.isNumber(b)) {
            return Double.compare(Values.number(a), Values.number(b));
        }
        return Values.text(a).compareToIgnoreCase(Values.text(b));
    }
}
