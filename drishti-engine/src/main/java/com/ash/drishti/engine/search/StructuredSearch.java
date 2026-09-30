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
        return mnemonics.of(q.head().toUpperCase(Locale.ROOT)).map(m -> m.kind()).orElse(q.head().toLowerCase(Locale.ROOT));
    }

    public Result run(SearchQuery q, AsOf asOf, UnaryOperator<DataNode> redact) {
        long t0 = System.nanoTime();
        String kind = kindOf(q);
        Expr condition = compile(q.condition(), "condition");
        Expr order = compile(q.orderBy(), "order by");
        List<EntityHit> hits = router.search(kind, "", props.maxScan() + 1, props.budget(), asOf);
        boolean partial = hits.size() > props.maxScan();
        if (partial) {
            hits = hits.subList(0, props.maxScan());
        }
        Map<EntityRef, String> titles = new LinkedHashMap<>();
        hits.forEach(h -> titles.put(h.ref(), h.title()));
        Map<EntityRef, EntityDocument> docs = router.fetchAll(titles.keySet(), props.budget(), asOf);
        partial |= docs.size() < titles.size();
        List<Expr> columns = q.fields().stream().map(el::compile).toList();
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
                values.put(q.fields().get(i), leaf(safe(columns.get(i), ctx)));
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
        return new Result(kind, q.condition(), q.orderBy(), q.fields(), rows, docs.size(), matches.size(), partial,
                Math.round((System.nanoTime() - t0) / 1e4) / 100.0);
    }

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
