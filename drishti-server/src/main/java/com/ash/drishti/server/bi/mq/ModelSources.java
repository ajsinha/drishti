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

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.engine.StagedTables;
import com.ash.drishti.server.bi.layer.DatasetDef;
import com.ash.drishti.server.bi.layer.ImplicitModels;
import com.ash.drishti.server.bi.layer.ResolvedDataset;
import com.ash.drishti.server.bi.layer.Semver;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Datasets whose source is another model's answer (docs/architecture/BI_MODEL_SPEC.md sections 1.2 D and 5.4, wave W1 package S4). The inner query runs
 * <em>as the reader</em> (their row rules and masks apply inside it) at the outer query's date when it names none, and its answer becomes the rows of the
 * dataset's table for that one query: the outer model's relationships, rules and measures then work over it like over any table. Columns are filled by name
 * (a declared column's {@code path}, else its name, else the last part of an answer column's name). A model that reaches itself through such a source is
 * refused ({@code DRS-9109}), by the Check and the bind (published items) and at run time (anything else).
 */
final class ModelSources implements StagedTables {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEPTH = 8;
    private static final ThreadLocal<Set<String>> ASKING = ThreadLocal.withInitial(HashSet::new);

    private final ModelCatalog catalog;
    private final Principal reader;
    private final ModelView view;
    private final QueryRequest outer;
    private final Map<String, MqService.Bound> bound = new LinkedHashMap<>();
    private String fingerprint;

    ModelSources(ModelCatalog catalog, Principal reader, ModelView view, QueryRequest outer) {
        this.catalog = catalog;
        this.reader = reader;
        this.view = view;
        this.outer = outer;
    }

    /** The aliases of a model whose dataset's source is a model. */
    static Set<String> aliases(ModelView v) {
        Set<String> out = new LinkedHashSet<>();
        v.datasets().forEach((alias, r) -> {
            if (isModel(r)) {
                out.add(alias);
            }
        });
        return out;
    }

    private static boolean isModel(ResolvedDataset r) {
        return r != null && r.def().source() != null && DatasetDef.Source.MODEL.equals(r.def().source().type());
    }

    @Override
    public boolean has(String table) {
        return isModel(view.datasets().get(table));
    }

    @Override
    public synchronized String fingerprint() {
        if (fingerprint == null) {
            StringBuilder b = new StringBuilder(reader.user()).append('|').append(new TreeSet<>(reader.roles()));
            for (String alias : aliases(view)) {
                MqService.Bound inner = inner(alias);
                b.append('|').append(alias).append('=').append(inner.view().id()).append('#').append(inner.view().hash()).append('g')
                        .append(catalog.generation(reader, inner.view())).append(inner.context().masks().hashCode());
            }
            fingerprint = b.toString();
        }
        return fingerprint;
    }

    @Override
    public Answer answer(String table) {
        DatasetDef d = view.datasets().get(table).def();
        MqService.Bound inner = inner(table);
        Set<String> asking = ASKING.get();
        if (!asking.add(view.id())) {
            throw cycle("'" + d.name() + "' reads the model '" + view.def().name() + "' while it is being asked: a model cannot read its own answer");
        }
        try {
            if (asking.contains(inner.view().id())) {
                throw cycle("'" + d.name() + "' reads '" + d.source().model() + "', which is being asked already: a model cannot read its own answer");
            }
            ModelQuery q = query(d);
            MqService.Ran ran = catalog.mq().run(inner, q, false, false);
            Dataset.Table t = view.flat().tables().get(table);
            List<String> names = ran.shown().columns().stream().map(ArrowIpcWriter.Column::name).toList();
            Map<String, String> columns = new LinkedHashMap<>();
            List<String> missing = new ArrayList<>();
            for (Dataset.Column c : t.physical()) {
                String hit = names.contains(c.path()) ? c.path() : names.contains(c.name()) ? c.name()
                        : names.stream().filter(n -> n.substring(n.lastIndexOf('.') + 1).equals(c.name())).findFirst().orElse(null);
                if (hit == null) {
                    missing.add(c.name());
                } else {
                    columns.put(c.name(), hit);
                }
            }
            if (!missing.isEmpty()) {
                throw new MqException(new MqException.Problem("DRS-9108", "source.query", "the answer of the source query of '" + d.name() + "' has no column for " + missing
                        + " (it has " + names + "): name the column's path after a field or measure of the query", Map.of("columns", names)));
            }
            List<String> warnings = new ArrayList<>(ran.warnings());
            List<String> masked = ran.shown().meta() == null ? List.of() : ran.shown().meta().masked();
            if (!masked.isEmpty()) {
                warnings.add(masked + " " + (masked.size() == 1 ? "is" : "are") + " masked for you in the source '" + d.source().model() + "': empty here");
            }
            QueryService.Meta m = ran.shown().meta();
            QueryService.Result result = new QueryService.Result(ran.shown().columns(), ran.shown().rows(), m == null ? null
                    : new QueryService.Meta(m.layer(), m.rollup(), m.rows(), m.millis(), m.cached(), m.dates(), m.tables(), m.masked(), warnings, m.sql(), m.generation()));
            return new Answer(result, columns, ran.compiled().asOf());
        } finally {
            asking.remove(view.id());
        }
    }

    private MqService.Bound inner(String alias) {
        return bound.computeIfAbsent(alias, a -> catalog.bind(reader, view.datasets().get(a).def().source().model()));
    }

    /** The inner query as the dataset states it, at the outer query's date when it names none. */
    private ModelQuery query(DatasetDef d) {
        JsonNode stated = d.source().modelQuery();
        ObjectNode o = stated != null && stated.isObject() ? ((ObjectNode) stated).deepCopy() : JSON.createObjectNode();
        if (!o.has("mq")) {
            o.put("mq", 1);
        }
        if (!o.has("model")) {
            o.put("model", d.source().model());
        }
        ModelQuery q = MqParser.parse(o);
        if (q.time().equals(ModelQuery.Time.DEFAULT) && outer.from() != null) {
            ModelQuery.Time t = outer.from().equals(outer.to()) || outer.to() == null ? new ModelQuery.Time(outer.from(), null, null, null, false)
                    : new ModelQuery.Time(null, outer.from(), outer.to(), null, false);
            q = new ModelQuery(q.mq(), q.model(), q.define(), q.fields(), q.measures(), q.where(), q.having(), t, q.sort(), q.limit(), q.totals(), q.drill(), q.shape(),
                    q.params(), q.options());
        }
        return q;
    }

    private static MqException cycle(String detail) {
        return new MqException(MqException.Problem.of("DRS-9109", "source.model", detail));
    }

    // ---- the static check --------------------------------------------------------------------------------------------

    /**
     * Whether a model ({@code model:<name>}) or a dataset's implicit model ({@code dataset:<name>}) reaches itself through datasets whose source is a model:
     * the path, in words, when it does. Walks the published items (at most eight steps).
     */
    static Optional<String> cycle(ModelCatalog catalog, String start, Map<String, ResolvedDataset> datasets) {
        List<String> path = new ArrayList<>();
        path.add(start);
        return walk(catalog, datasets, path);
    }

    private static Optional<String> walk(ModelCatalog catalog, Map<String, ResolvedDataset> datasets, List<String> path) {
        if (path.size() > DEPTH) {
            return Optional.empty();
        }
        for (ResolvedDataset r : datasets.values()) {
            String here = "dataset:" + r.def().name();
            if (path.size() > 1 && path.contains(here)) {
                return Optional.of(words(path, here));
            }
            if (!isModel(r)) {
                continue;
            }
            String ref = r.def().source().model();
            List<String> next = new ArrayList<>(path);
            if (!next.contains(here)) {
                next.add(here);
            }
            List<Map<String, ResolvedDataset>> targets = new ArrayList<>();
            String target;
            if (ref.startsWith(ImplicitModels.QUERY_PREFIX)) {
                String name = ref.substring(ImplicitModels.QUERY_PREFIX.length());
                target = "dataset:" + name;
                catalog.resolveDataset(name, Semver.Range.ANY, new LinkedHashSet<>(), new ArrayList<>()).ifPresent(d -> targets.add(Map.of(name, d)));
            } else {
                Semver.Ref parsed = Semver.Ref.parse(ref);
                String name = parsed == null ? ref : parsed.name();
                target = "model:" + name;
                targets.addAll(catalog.publishedModels(name));
            }
            if (next.contains(target)) {
                return Optional.of(words(next, target));
            }
            next.add(target);
            for (Map<String, ResolvedDataset> t : targets) {
                Optional<String> found = walk(catalog, t, next);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private static String words(List<String> path, String back) {
        List<String> all = new ArrayList<>(path);
        all.add(back);
        return "a model cannot read its own answer: " + String.join(" -> ", all);
    }
}
