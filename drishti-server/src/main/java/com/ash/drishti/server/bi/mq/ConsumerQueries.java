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
package com.ash.drishti.server.bi.mq;

import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.bi.DatasetService;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.Resolver;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The consumers' path onto the model query language (docs/architecture/BI_MODEL_SPEC.md sections 4.1 and 5.9, wave W1 package S5). A report visual (and
 * through it exports, subscriptions, boards, live tiles and embeds), an Analyse extract, an alert, a dataset test and an AI answer still build the engine
 * request they always built; here it becomes a model query on the dataset's model (the implicit model of a {@code rachana: 2} dataset, the compatibility
 * reader's model of a version-1 one) and goes through the one validator and compiler, as the reader. The endpoints keep their shapes: the answer is the
 * engine's result with the query's warnings added, plus the canonical model query that ran.
 */
public final class ConsumerQueries implements DatasetService.ModelGateway {

    private final ModelCatalog catalog;
    private final MqService mq;

    public ConsumerQueries(ModelCatalog catalog, MqService mq) {
        this.catalog = catalog;
        this.mq = mq;
    }

    @Override
    public DatasetService.Asked ask(Principal p, BiStore.Item item, QueryRequest q, boolean explain) {
        try {
            MqService.Bound b = catalog.bindItem(p, item);
            MqService.Ran ran = mq.run(b, of(b.view(), q), explain, true);
            return new DatasetService.Asked(ran.withWarnings(), ran.canonical());
        } catch (MqException e) {
            throw e.legacy();                                          // the endpoint's code as before; the problems keep the language's codes and fixes
        }
    }

    /**
     * An engine request as a model query on a model: fields and measures as they are (a field without its table gets the table that has it), a filter on a
     * measure becomes a {@code having} ({@code between} two of them), the dates the {@code time} (one date an as-of, two a window), the sort, limit and layer
     * as they are.
     */
    public static ModelQuery of(ModelView v, QueryRequest q) {
        Dataset ds = v.flat();
        Resolver resolver = new Resolver(ds);
        List<ModelQuery.Where> where = new ArrayList<>();
        List<ModelQuery.Having> having = new ArrayList<>();
        for (QueryRequest.Filter f : q.filters()) {
            if (ds.measures().containsKey(f.field())) {
                if ("between".equals(f.op()) && f.values().size() == 2) {
                    having.add(new ModelQuery.Having(f.field(), "ge", List.of(number(f.values().get(0)))));
                    having.add(new ModelQuery.Having(f.field(), "le", List.of(number(f.values().get(1)))));
                } else {
                    having.add(new ModelQuery.Having(f.field(), f.op(), f.values().stream().map(ConsumerQueries::number).toList()));
                }
            } else {
                where.add(ModelQuery.Where.of(qualify(resolver, f.field()), f.op(), f.values()));
            }
        }
        ModelQuery.Time time = ModelQuery.Time.DEFAULT;
        if (q.from() != null) {
            time = q.to() == null || q.from().equals(q.to()) ? new ModelQuery.Time(q.from(), null, null, null, false) : new ModelQuery.Time(null, q.from(), q.to(), null, false);
        }
        List<String> fields = q.fields().stream().map(f -> qualify(resolver, f)).toList();
        List<ModelQuery.Sort> sort = q.orderBy().stream().map(s -> new ModelQuery.Sort(ds.measures().containsKey(s.by()) ? s.by() : qualify(resolver, s.by()), s.desc())).toList();
        return new ModelQuery(1, v.id(), Map.of(), fields, q.measures(), where, having, time, sort, q.limit(), null, null,
                ModelQuery.GROUPED, Map.of(), new ModelQuery.Options(q.layer(), false, "json"));
    }

    /** A field as {@code table.column[.part]}: as written when it names its table, else with the table that has the column (left as written when none or several do). */
    static String qualify(Resolver r, String field) {
        if (field == null || field.isBlank()) {
            return field;
        }
        try {
            Resolver.ColRef ref = r.resolve(List.of(field.split("\\.", -1)), null);
            if (ref.column() != null && !field.startsWith(ref.table() + ".")) {
                return ref.table() + "." + field;
            }
        } catch (IllegalArgumentException e) {
            // the validator names the problem with its fix
        }
        return field;
    }

    private static Object number(Object v) {
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.strip());
            } catch (NumberFormatException e) {
                return v;
            }
        }
        return v;
    }
}
