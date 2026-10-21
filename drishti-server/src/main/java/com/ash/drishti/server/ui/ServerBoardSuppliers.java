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
package com.ash.drishti.server.ui;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.live.TopicHub;
import com.ash.drishti.engine.search.SearchQuery;
import com.ash.drishti.engine.search.StructuredSearch;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.rachana.ui.BoardDoc;
import com.ash.drishti.server.bi.DatasetService;
import com.ash.drishti.server.bi.runs.Run;
import com.ash.drishti.server.bi.runs.RunService;
import com.ash.drishti.server.bi.runs.RunSources;
import com.ash.drishti.server.bi.runs.RunState;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The server's suppliers, every one read as the caller (docs/architecture/UI_BOARD.md 7.2):
 * <ul>
 * <li>{@code connector: <pack>/<kind>} opened by an id: the entity as the caller's redactor leaves it, refused (DRS-2074) for a kind they may not open;</li>
 * <li>{@code connector: <pack>/<kind>-search} (or a kind with no id): the structured search as the caller, its query's keys as {@code field = value};</li>
 * <li>{@code model: <model>}: a BI run of the model query as the caller (roles, row rules, masks); a question past the chip budget answers with its run
 * card and the rows follow; an answer is shared for {@code reuse} with viewers of the same access profile, as report visuals' are (BI_RUNS 9.3a);</li>
 * <li>{@code live: <pack>/<kind>}: the record by id, then each new version through the live hub, redacted per frame.</li>
 * </ul>
 */
public final class ServerBoardSuppliers implements BoardSuppliers {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** An access profile's shared answer. */
    private record Shared(Rows rows, long until) {}

    private final SourceRouter router;
    private final StructuredSearch search;
    private final Entitlements entitlements;
    private final RunSources runSources;
    private final DatasetService datasets;
    private final TopicHub hub;
    private final RenderSettings settings;
    private final Executor exec;
    private final Map<String, Shared> shared = new ConcurrentHashMap<>();

    public ServerBoardSuppliers(SourceRouter router, StructuredSearch search, Entitlements entitlements, RunSources runSources, DatasetService datasets,
            TopicHub hub, RenderSettings settings, Executor exec) {
        this.router = router;
        this.search = search;
        this.entitlements = entitlements;
        this.runSources = runSources;
        this.datasets = datasets;
        this.hub = hub;
        this.settings = settings;
        this.exec = exec;
    }

    @Override
    public Rows read(Principal who, Ask ask) {
        BoardDoc.Supply s = ask.supply();
        return switch (s.kind()) {
            case MODEL -> model(who, ask);
            case CONNECTOR, LIVE -> ask.id() != null || s.kind() == BoardDoc.SupplyKind.LIVE ? entity(who, kindOf(s.name()), idOf(ask)) : search(who, ask);
            case DATASET -> throw new DrishtiException(ErrorCode.BAD_REQUEST, "a 'dataset' supply is not rendered in 2.7.0: name its model ('model: <model>')");
        };
    }

    @Override
    public AutoCloseable follow(Principal who, Ask ask, Consumer<Rows> frame) {
        if (ask.supply().kind() != BoardDoc.SupplyKind.LIVE || hub == null) {
            return null;
        }
        String kind = kindOf(ask.supply().name());
        return hub.subscribe(EntityRef.of(kind, idOf(ask)), doc -> {
            if (entitlements.mayOpen(who, kind) && !doc.deleted()) {
                frame.accept(rowsOf(who, doc));
            }
        });
    }

    // ---- connectors -----------------------------------------------------------------------------------------------------

    /** {@code trading/trade} and {@code trading/trade-search} both name the kind {@code trade}. */
    static String kindOf(String name) {
        String k = name.contains("/") ? name.substring(name.indexOf('/') + 1) : name;
        return k.endsWith("-search") ? k.substring(0, k.length() - "-search".length()) : k;
    }

    private static String idOf(Ask ask) {
        if (ask.id() != null) {
            return ask.id();
        }
        JsonNode q = ask.query();
        if (q != null && q.path("id").isValueNode()) {
            return q.path("id").asText();
        }
        throw new DrishtiException(ErrorCode.UI_CHECK_FAILED, ask.supply().key() + " needs an id (a trace into an id pin, or query.id)");
    }

    private Rows entity(Principal who, String kind, String id) {
        return rowsOf(who, fetch(who, kind, id));
    }

    /**
     * The record an entity supply reads, with the caller's masks applied (UI_BOARD.md 7.2): the document a converted page's chips paint. Phase P5's
     * proof reads it here, so a converted page goes through the same supplier as a render.
     */
    public EntityDocument document(Principal who, String kind, String id) {
        EntityDocument doc = fetch(who, kind, id);
        return new EntityDocument(doc.ref(), entitlements.redact(who, doc.data()), doc.provenance(), doc.deleted());
    }

    private EntityDocument fetch(Principal who, String kind, String id) {
        if (!entitlements.mayOpen(who, kind)) {
            throw new DrishtiException(ErrorCode.UI_FORBIDDEN, "you may not open " + kind);
        }
        EntityDocument doc;
        try {
            doc = router.fetch(EntityRef.of(kind, id), AsOf.LATEST).get(settings.chipBudget().toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new DrishtiException(ErrorCode.UI_SUPPLIER_TIMEOUT, kind + " " + id + " did not answer in time");
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no " + kind + " " + id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DrishtiException(ErrorCode.UI_RENDER_ENDED, "stopped");
        }
        if (doc == null || doc.deleted()) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no " + kind + " " + id);
        }
        return doc;
    }

    private Rows rowsOf(Principal who, EntityDocument doc) {
        DataNode data = entitlements.redact(who, doc.data());
        Map<String, Object> row = new LinkedHashMap<>();
        Set<String> masked = new LinkedHashSet<>();
        if (data != null && data.unwrap() instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                DataNode n = data.get(String.valueOf(k));
                if (n != null && n.isMasked()) {
                    masked.add(String.valueOf(k));
                }
                row.put(String.valueOf(k), v instanceof DataNode d ? d.unwrap() : v);
            });
        }
        row.putIfAbsent("id", doc.ref().id());
        return new Rows(List.copyOf(row.keySet()), List.of(row), List.of(doc.ref().id()), masked, 1, null, false, null);
    }

    private Rows search(Principal who, Ask ask) {
        String kind = kindOf(ask.supply().name());
        if (!entitlements.mayOpen(who, kind)) {
            throw new DrishtiException(ErrorCode.UI_FORBIDDEN, "you may not open " + kind);
        }
        List<String> cond = new ArrayList<>();
        Map<String, Object> eq = new LinkedHashMap<>();
        if (ask.query() != null) {
            ask.query().fields().forEachRemaining(e -> eq.put(e.getKey(), e.getValue().asText()));
        }
        eq.putAll(ask.filters());
        eq.forEach((k, v) -> cond.add(k + " = '" + String.valueOf(v).replace("'", "''") + "'"));
        String text = kind + (cond.isEmpty() ? "" : " where " + String.join(" and ", cond)) + " limit " + Math.min(ask.maxRows() + 1, SearchQuery.MAX_LIMIT);
        StructuredSearch.Result r = search.run(SearchQuery.parse(text), AsOf.LATEST, entitlements.redactor(who));
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> refs = new ArrayList<>();
        Set<String> masked = new LinkedHashSet<>();
        for (StructuredSearch.Row row : r.rows()) {
            Map<String, Object> m = new LinkedHashMap<>(row.values());
            m.putIfAbsent("id", row.ref().id());
            m.forEach((k, v) -> {
                if (DataNode.MASK.equals(v)) {
                    masked.add(k);
                }
            });
            rows.add(m);
            refs.add(row.ref().id());
        }
        return new Rows(r.columns(), rows, refs, masked, r.matched(), null, false, null);
    }

    // ---- models -------------------------------------------------------------------------------------------------------

    /** The board's model query as BI's: {@code select} as written, or {@code dimensions} and {@code measures}; trace filters as {@code eq}. */
    static ObjectNode mq(String model, JsonNode query, Map<String, Object> filters, int maxRows) {
        ObjectNode mq = JSON.createObjectNode();
        mq.put("mq", 1);
        mq.put("model", model);
        JsonNode q = query == null ? JSON.createObjectNode() : query;
        if (q.has("select")) {
            mq.set("select", q.get("select").deepCopy());
        } else {
            ObjectNode sel = mq.putObject("select");
            sel.set("fields", q.has("dimensions") ? q.get("dimensions").deepCopy() : JSON.createArrayNode());
            sel.set("measures", q.has("measures") ? q.get("measures").deepCopy() : JSON.createArrayNode());
        }
        ArrayNode where = q.has("where") && q.get("where").isArray() ? (ArrayNode) q.get("where").deepCopy() : JSON.createArrayNode();
        filters.forEach((f, v) -> where.addObject().put("field", f).put("op", "eq").set("value", JSON.valueToTree(v)));
        if (!where.isEmpty()) {
            mq.set("where", where);
        }
        for (String k : List.of("time", "sort", "params", "perspective")) {
            if (q.has(k)) {
                mq.set(k, q.get(k).deepCopy());
            }
        }
        mq.put("limit", maxRows + 1);
        return mq;
    }

    private Rows model(Principal who, Ask ask) {
        String model = ask.supply().name();
        ObjectNode mq = mq(model, ask.query(), ask.filters(), ask.maxRows());
        String profileKey = profile(who, model).map(sig -> sig + "|" + mq).orElse(null);
        if (profileKey != null) {
            Shared hit = shared.get(profileKey);
            if (hit != null && hit.until > System.currentTimeMillis()) {
                return hit.rows.asReused();
            }
        }
        ObjectNode source = JSON.createObjectNode();
        source.put("type", "mq");
        source.set("mq", mq);
        ObjectNode options = JSON.createObjectNode();
        options.put("title", "UI board: " + model);
        options.put("interaction", true);                                   // a person waiting on a screen: unknown sizes run interactively (BI_RUNS 5.3)
        RunService.Question q = runSources.of(who, source, options);
        RunService.Outcome o = runSources.runs().submit(q, new RunService.Options(settings.chipBudget(), false));
        if (o.inline()) {
            Rows rows = rowsOf(o.answer().body()).withRun(o.run() == null ? null : o.run().id());
            if (o.run() != null && o.run().state() == RunState.REUSED) {
                rows = rows.asReused();
            }
            keep(profileKey, rows);
            return rows;
        }
        String runId = o.run().id();
        CompletableFuture<Rows> pending = CompletableFuture.supplyAsync(() -> waitFor(runId, q.signature()), exec)
                .thenApply(r -> {
                    keep(profileKey, r);
                    return r;
                });
        return new Rows(List.of(), List.of(), null, Set.of(), -1, runId, false, pending);
    }

    private void keep(String profileKey, Rows rows) {
        if (profileKey != null && !settings.reuse().isZero()) {
            shared.put(profileKey, new Shared(rows, System.currentTimeMillis() + settings.reuse().toMillis()));
            if (shared.size() > 2048) {
                long now = System.currentTimeMillis();
                shared.values().removeIf(s -> s.until < now);
            }
        }
    }

    /** The caller's access profile on the model; empty (the answer stays theirs) when it is personal or cannot be told. */
    private Optional<String> profile(Principal who, String model) {
        if (datasets == null || settings.reuse().isZero()) {
            return Optional.empty();
        }
        try {
            return datasets.accessProfile(who, DatasetService.modelRef(model)).filter(a -> !a.personal()).map(a -> a.signature() + "|" + a.view());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private Rows waitFor(String runId, String signature) {
        RunService runs = runSources.runs();
        long deadline = System.nanoTime() + Duration.ofMinutes(30).toNanos();
        while (System.nanoTime() < deadline) {
            Run r = runs.get(runId).orElseThrow(() -> new DrishtiException(ErrorCode.UI_RENDER_ENDED, "the run " + runId + " is gone"));
            if (r.state() == RunState.DONE || r.state() == RunState.REUSED) {
                return rowsOf(runs.result(r, signature)).withRun(runId);
            }
            if (r.state().finished()) {
                throw new DrishtiException(ErrorCode.UI_SUPPLIER_TIMEOUT, "the run " + runId + " ended " + r.state().name().toLowerCase(java.util.Locale.ROOT));
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DrishtiException(ErrorCode.UI_RENDER_ENDED, "stopped");
            }
        }
        throw new DrishtiException(ErrorCode.UI_SUPPLIER_TIMEOUT, "the run " + runId + " did not finish");
    }

    /** A model answer's {@code columns} and {@code rows} as rows by column name; a masked column's values are null in the answer. */
    @SuppressWarnings("unchecked")
    static Rows rowsOf(Map<String, Object> body) {
        List<String> names = new ArrayList<>();
        Set<String> masked = new LinkedHashSet<>();
        Object cols = body.get("columns");
        if (cols instanceof List<?> l) {
            for (Object c : l) {
                if (c instanceof Map<?, ?> m) {
                    String n = String.valueOf(m.get("name"));
                    names.add(n);
                    if (Boolean.TRUE.equals(m.get("masked"))) {
                        masked.add(n);
                    }
                } else {
                    names.add(String.valueOf(c));
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        Object rs = body.get("rows");
        if (rs instanceof List<?> l) {
            for (Object r : l) {
                Map<String, Object> m = new LinkedHashMap<>();
                if (r instanceof List<?> vals) {
                    for (int i = 0; i < names.size() && i < vals.size(); i++) {
                        m.put(names.get(i), vals.get(i));
                    }
                } else if (r instanceof Map<?, ?> mm) {
                    ((Map<String, Object>) mm).forEach(m::put);
                }
                rows.add(m);
            }
        }
        Object total = body.get("rowCount");
        return new Rows(names, rows, null, masked, total instanceof Number n ? n.longValue() : rows.size(), null, false, null);
    }
}
