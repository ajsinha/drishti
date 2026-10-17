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
package com.ash.drishti.server.bi;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.graph.ReferenceCatalog;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.User;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.bi.dataset.LegacySources;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.dataset.UserAttributes;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.engine.Layers;
import com.ash.drishti.server.bi.engine.QueryProperties;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.engine.SourceGateway;
import com.ash.drishti.server.bi.engine.SqlCompiler;
import com.ash.drishti.server.bi.model.PackModels;
import com.ash.drishti.server.loads.LoadRecord;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.SecurityProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Datasets for the people who use them (RUPAKA.md sections 6 and 7): finds the dataset item the caller may read, parses it (once per version of
 * its text), checks it, and runs queries against it as that caller: their row rules, their masks, the kinds they may open. The BI shell's
 * catalogue and workspaces decide who sees which item ({@link BiService#readableItem}); this class decides what the data shows them.
 */
public final class DatasetService {

    private static final Logger LOG = LoggerFactory.getLogger(DatasetService.class);

    private record Parsed(String hash, DatasetParser.Result result) {}

    private final BiService bi;
    private final QueryService queries;
    private final QueryProperties props;
    private final Entitlements entitlements;
    private final SecurityProperties security;
    private final UserService users;
    private final AuditLog audit;
    private final PackRegistry packs;
    private final ReferenceCatalog graph;
    private final SourceGateway sources;
    private final Map<String, Parsed> parsed = new ConcurrentHashMap<>();
    private volatile DatasetRefresher refresher;
    private volatile FrameService frames;

    public DatasetService(BiService bi, QueryService queries, QueryProperties props, Entitlements entitlements, SecurityProperties security, UserService users, AuditLog audit,
            PackRegistry packs, ReferenceCatalog graph, SourceGateway sources) {
        this.bi = bi;
        this.queries = queries;
        this.props = props;
        this.entitlements = entitlements;
        this.security = security;
        this.users = users;
        this.audit = audit;
        this.packs = packs;
        this.graph = graph;
        this.sources = sources;
    }

    /** The current business date and the business-day calendar, for the dates a report's relative-date slicers mean. */
    public java.time.LocalDate currentDate() {
        return sources.currentDate();
    }

    public com.ash.drishti.common.BusinessCalendar calendar() {
        return sources.calendar();
    }

    /** The service that asks the sources of composed and derived datasets (set once at start-up). */
    public void attach(FrameService frames) {
        this.frames = frames;
        frames.attach(this);
    }

    /** The scheduler that rebuilds datasets on their {@code refresh:} (set once at start-up); the dataset page shows its state. */
    public void attach(DatasetRefresher refresher) {
        this.refresher = refresher;
    }

    /**
     * The one path every consumer's question takes (docs/architecture/BI_MODEL_SPEC.md section 5.9): an engine request on a dataset item becomes a model query
     * on the item's model (the implicit model of a {@code rachana: 2} dataset, the compatibility reader's model of a version-1 one), validated and compiled by the
     * model query compiler and run as the reader. Set once at start-up by the model layer.
     */
    public interface ModelGateway {
        Asked ask(Principal p, BiStore.Item item, QueryRequest q, boolean explain);

        /** As {@link #ask} on a model named by reference ({@code trades@1}, {@code dataset:<name>}): a report bound with {@code model:}. */
        Asked askModel(Principal p, String model, QueryRequest q, boolean explain);

        /** As {@link #askModel(Principal, String, QueryRequest, boolean)} through a published consumer (its envelope for a reader without model read). */
        default Asked askModel(Principal p, String model, String consumer, QueryRequest q, boolean explain) {
            return askModel(p, model, q, explain);
        }

        /** As {@link #askModel(Principal, String, String, QueryRequest, boolean)} with a report's own measures sent as the model query's {@code define}. */
        default Asked askModel(Principal p, String model, String consumer, Map<String, String> define, QueryRequest q, boolean explain) {
            return askModel(p, model, consumer, q, explain);
        }

        /** As {@link #dataset(Principal, String)} through a published consumer. */
        default Dataset dataset(Principal p, String model, String consumer) {
            return dataset(p, model);
        }

        /** The model's engine dataset as the caller reads it (what a model-bound report's visuals are built against). */
        Dataset dataset(Principal p, String model);

        /** Every published model, flattened, as the scheduler and a data-load signal work over it (its layers are kept under the model's key). */
        default List<QueryService.Loaded> publishedModels() {
            return List.of();
        }

        /** A model that reads a dataset of the model layer, and the tables (aliases) of it that do: what a refresh of that dataset rebuilds. */
        record Reader(QueryService.Loaded loaded, List<String> tables) {}

        /** The published models that read the dataset {@code name}, and its implicit model (what {@code POST /datasets/{id}/refresh} rebuilds). */
        default List<Reader> readersOf(String name) {
            return List.of();
        }

        /** The model as a designer or a reader of a report sees it ({@code GET /models/{id}/describe}). */
        Map<String, Object> describe(Principal p, String model);

        /** As {@link #describe(Principal, String)} through a published consumer: a reader without model read sees the consumer's envelope. */
        default Map<String, Object> describe(Principal p, String model, String consumer) {
            return describe(p, model);
        }

        /** As {@link #describe(Principal, String, String)} through a report's perspective: only its fields and measures (a report's Fields pane). */
        default Map<String, Object> describe(Principal p, String model, String consumer, String perspective) {
            return describe(p, model, consumer);
        }

        /** Why a report bound to the model cannot be published (a draft model, its Check, the security safeguard); empty when it can. */
        List<String> publishProblems(String model);

        /** As {@link #publishProblems(String)} for one publisher: they must also read the model (a report uses a model by reference, with read on it). */
        default List<String> publishProblems(Principal p, String model) {
            return publishProblems(model);
        }
    }

    /** The kind of {@link #modelRef}: an item that stands for a model a consumer is bound to, not a stored item. */
    public static final String MODEL_REF = "model-ref";

    /** A stand-in item for a model reference, so a consumer bound to a model ({@code model:} on a report) asks it through the same calls as a dataset item. */
    public static BiStore.Item modelRef(String model) {
        return modelRef(model, "");
    }

    /** As {@link #modelRef(String)} for a published consumer: a reader without read on the model asks within the consumer's envelope. */
    public static BiStore.Item modelRef(String model, String consumer) {
        return new BiStore.Item(model, "", MODEL_REF, model, consumer == null ? "" : consumer, "", List.of(), "", "published", 1, "none", "", List.of(), List.of(), List.of(), "",
                null, null, null, "", null, "");
    }

    /** The consumer a model reference was taken through, or null. */
    public static String consumerOf(BiStore.Item modelRef) {
        return modelRef == null || modelRef.description() == null || modelRef.description().isBlank() ? null : modelRef.description();
    }

    /** As {@link #modelRef(String, String)} with a report's own measures ({@code define}) and its perspective, carried in the reference's tags. */
    public static BiStore.Item modelRef(String model, String consumer, Map<String, String> define, String perspective) {
        BiStore.Item r = modelRef(model, consumer);
        List<String> tags = new ArrayList<>();
        define.forEach((k, v) -> tags.add(DEFINE + k + "=" + v));
        if (perspective != null) {
            tags.add(PERSPECTIVE + perspective);
        }
        return tags.isEmpty() ? r : new BiStore.Item(r.id(), r.workspace(), r.kind(), r.title(), r.description(), r.owner(), tags, r.body(), r.status(), r.publishedVersion(),
                r.endorsement(), r.endorsedBy(), r.audienceRoles(), r.audienceUsers(), r.audiencePacks(), r.proposedBy(), r.proposedAt(), r.createdAt(), r.updatedAt(),
                r.updatedBy(), r.publishedAt(), r.publishedBy());
    }

    private static final String DEFINE = "define:";
    private static final String PERSPECTIVE = "perspective:";

    /** The report's own measures a model reference carries (name to DEL), in order. */
    public static Map<String, String> defineOf(BiStore.Item modelRef) {
        Map<String, String> out = new LinkedHashMap<>();
        if (modelRef != null) {
            modelRef.tags().stream().filter(t -> t.startsWith(DEFINE)).forEach(t -> {
                String kv = t.substring(DEFINE.length());
                int eq = kv.indexOf('=');
                out.put(kv.substring(0, eq), kv.substring(eq + 1));
            });
        }
        return out;
    }

    /** The perspective a model reference carries, or null. */
    public static String perspectiveOf(BiStore.Item modelRef) {
        return modelRef == null ? null : modelRef.tags().stream().filter(t -> t.startsWith(PERSPECTIVE)).map(t -> t.substring(PERSPECTIVE.length())).findFirst().orElse(null);
    }

    public static boolean isModelRef(BiStore.Item item) {
        return item != null && MODEL_REF.equals(item.kind());
    }

    private ModelGateway requireGateway() {
        ModelGateway g = gateway;
        if (g == null) {
            throw new DrishtiException(ErrorCode.REPORT_DATASET, "the model layer is not running on this server, so a report on a model cannot be read");
        }
        return g;
    }

    /** The engine dataset of a model, as the caller reads it. */
    public Dataset modelDataset(Principal p, String model) {
        return requireGateway().dataset(p, model);
    }

    /** As {@link #modelDataset(Principal, String)} through a published consumer (its envelope for a reader without model read). */
    public Dataset modelDataset(Principal p, String model, String consumer) {
        return requireGateway().dataset(p, model, consumer);
    }

    /**
     * Runs a connector-query dataset's SQL (docs/architecture/BI_MODEL_SPEC.md section 1.2 B) in its SQL-capable connector, the values bound (never
     * interpolated). The reader must be able to open every kind the connector serves (the connector's entitlement); the dataset's own rules and masks apply
     * where the rows are read, in the model.
     */
    public QueryService.Result connectorQuery(Principal p, String connector, String sql, List<Object> values, int maxRows) {
        com.ash.drishti.api.SourcePlugin plugin = sources.plugin(connector, "")
                .orElseThrow(() -> new DrishtiException(ErrorCode.LAYER_DATASET_SOURCE, "no running connector '" + connector + "'"));
        for (String kind : plugin.manifest().kinds()) {
            if (!entitlements.mayOpen(p, kind)) {
                throw new DrishtiException(ErrorCode.QUERY_MASKED, "you may not open kind '" + kind + "' of connector '" + connector + "', so you may not read its queries");
            }
        }
        com.ash.drishti.api.SqlTarget target = plugin.sqlTarget().orElseThrow(() -> new DrishtiException(ErrorCode.LAYER_DATASET_SOURCE,
                "connector '" + connector + "' has no SQL target: a connector query runs in DuckDB or PostgreSQL connectors"));
        long t0 = System.nanoTime();
        List<com.ash.drishti.server.bi.engine.ArrowIpcWriter.Column> cols;
        try {
            cols = target.with(c -> {
                try (java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setQueryTimeout((int) Math.max(1, props.timeout().toSeconds()));
                    for (int i = 0; i < values.size(); i++) {
                        ps.setObject(i + 1, values.get(i));
                    }
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        return com.ash.drishti.server.bi.engine.DuckDbEngine.read(rs, maxRows + 1);
                    }
                }
            });
        } catch (DrishtiException e) {
            throw e;
        } catch (Exception e) {
            throw new DrishtiException(ErrorCode.LAYER_DATASET_SOURCE, "the query of connector '" + connector + "' failed: " + e.getMessage());
        }
        int rows = cols.isEmpty() ? 0 : cols.get(0).values().length;
        if (rows > maxRows) {
            throw new DrishtiException(ErrorCode.QUERY_ROWS, "the connector query answers more than " + maxRows + " rows (drishti.bi.query.max-rows)");
        }
        audit.record(p.user(), "bi-connector-query", connector, rows + " rows");
        return new QueryService.Result(cols, rows, new QueryService.Meta("connector", null, rows, (System.nanoTime() - t0) / 1e6, false, List.of(), List.of(), List.of(), List.of(), null, 0));
    }

    /** Runs a Python-source dataset's Transform over its inputs (model queries already asked as the reader); see {@link FrameService#transformOf}. */
    public QueryService.Result transform(Principal p, String datasetKey, String dataset, String python, Map<String, QueryService.Result> inputs, Map<String, Object> params,
            Map<String, String> declared, String fingerprint) {
        if (frames == null) {
            throw new DrishtiException(ErrorCode.PYTHON_BUSY, "the Python Transform runner is not wired on this server");
        }
        return frames.transformOf(p, datasetKey, dataset, python, inputs, params, declared, fingerprint);
    }

    /** The largest answer one query may give (drishti.bi.query.max-rows). */
    public int maxRows() {
        return props.maxRows();
    }

    /** Why a report bound to the model cannot be published; empty when it can. */
    public List<String> modelPublishProblems(String model) {
        return requireGateway().publishProblems(model);
    }

    /** As above, for one publisher (who must read the model). */
    public List<String> modelPublishProblems(Principal p, String model) {
        return requireGateway().publishProblems(p, model);
    }

    /** The model as the caller sees it. */
    public Map<String, Object> describeModel(Principal p, String model) {
        return requireGateway().describe(p, model);
    }

    /** As {@link #describeModel(Principal, String)} through a published consumer (its envelope for a reader without model read). */
    public Map<String, Object> describeModel(Principal p, String model, String consumer) {
        return consumer == null ? describeModel(p, model) : requireGateway().describe(p, model, consumer);
    }

    /** As {@link #describeModel(Principal, String, String)} through a report's perspective (null: the whole model). */
    public Map<String, Object> describeModel(Principal p, String model, String consumer, String perspective) {
        return perspective == null ? describeModel(p, model, consumer) : requireGateway().describe(p, model, consumer, perspective);
    }

    /** A consumer's answer: the engine's result (the model query's warnings ahead of the engine's) and the canonical model query that ran (null without the gateway). */
    public record Asked(QueryService.Result result, Map<String, Object> query) {}

    private volatile ModelGateway gateway;

    public void attach(ModelGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * Asks a dataset item a consumer's question as the caller, through the model query language (reports, exports, subscriptions, boards, embeds, alerts,
     * extracts, AI). Without the model layer wired (unit tests of one service) the engine is asked directly.
     */
    public Asked asked(Principal p, BiStore.Item item, QueryRequest q, boolean explain) {
        if (isModelRef(item)) {
            return requireGateway().askModel(p, item.id(), consumerOf(item), defineOf(item), q, explain);
        }
        ModelGateway g = gateway;
        return g == null ? new Asked(queryItem(p, item, q, explain), null) : g.ask(p, item, q, explain);
    }

    public QueryService.Result ask(Principal p, BiStore.Item item, QueryRequest q, boolean explain) {
        return asked(p, item, q, explain).result();
    }

    /** As {@link #ask(Principal, BiStore.Item, QueryRequest, boolean)} for a dataset the caller names (id, name or file name) and may read. */
    public QueryService.Result ask(Principal p, String id, QueryRequest q, boolean explain) {
        return ask(p, datasetItem(p, id), q, explain);
    }

    /** Every published dataset that parses, ready to query: what the scheduler and a data-load signal work over. */
    public List<QueryService.Loaded> published() {
        List<QueryService.Loaded> all = new ArrayList<>();
        for (BiStore.Item i : bi.allDatasets()) {
            String body = bi.publishedBody(i);
            DatasetParser.Result res = parse(i.id(), body);
            if (res.ok()) {
                all.add(new QueryService.Loaded(i.id(), Layers.key(i.id()), res.dataset(), hash(body)));
            }
        }
        ModelGateway g = gateway;
        if (g != null) {
            all.addAll(g.publishedModels());                                  // a model's tables keep layers under the model: a load clears and rebuilds them too
        }
        return all;
    }

    /**
     * Adds an error for every table that names a connector no one has configured (a typing slip, a deleted connector file): such a dataset is "has errors",
     * not a good one that fails at its first query. A connector that exists but is stopped is a warning (it may come back).
     */
    DatasetParser.Result withConnectors(DatasetParser.Result r) {
        if (r.dataset() == null) {
            return r;
        }
        List<DatasetParser.Problem> more = new ArrayList<>();
        for (Dataset.Table t : r.dataset().tables().values()) {
            if (Dataset.FRAME.equals(t.mode()) || t.connector() == null || t.connector().isBlank()) {
                continue;
            }
            try {
                if (sources.plugin(t.connector(), t.kind()).isEmpty()) {
                    more.add(new DatasetParser.Problem("error", "DRS-9010", "tables." + t.name() + ".source.connector", "connector '" + t.connector() + "' does not exist: check Admin, Connectors"));
                }
            } catch (DrishtiException e) {
                boolean missing = String.valueOf(e.getMessage()).contains("no such connector");
                more.add(new DatasetParser.Problem(missing ? "error" : "warning", "DRS-9010", "tables." + t.name() + ".source.connector",
                        missing ? "connector '" + t.connector() + "' does not exist: check Admin, Connectors" : e.getMessage()));
            } catch (RuntimeException e) {
                // the gateway cannot say: leave the dataset as the parser found it
            }
        }
        if (more.isEmpty()) {
            return r;
        }
        List<DatasetParser.Problem> all = new ArrayList<>(r.problems());
        all.addAll(more);
        return new DatasetParser.Result(r.dataset(), all);
    }

    // ---- resolving ---------------------------------------------------------------------------------------------------

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private DatasetParser.Result parse(String id, String body) {
        String h = hash(body);
        if (LegacySources.dependsOnOthers(body)) {
            return DatasetParser.parse(body);                      // its model depends on the datasets it is made of, which may have changed: never served from the cache
        }
        Parsed p = parsed.get(id);
        if (p == null || !p.hash().equals(h)) {
            p = new Parsed(h, DatasetParser.parse(body));
            parsed.put(id, p);
        }
        return p.result();
    }

    private BiStore.Item datasetItem(Principal p, String id) {
        BiStore.Item i = bi.readableItem(p, id);
        if (!"dataset".equals(i.kind())) {
            throw new DrishtiException(ErrorCode.BI_NOT_FOUND, "'" + id + "' is not a dataset");
        }
        return i;
    }

    private QueryService.Loaded loaded(Principal p, BiStore.Item item) {
        String body = bi.bodyFor(p, item);
        DatasetParser.Result r = parse(item.id(), body);
        if (!r.ok()) {
            throw new DrishtiException(ErrorCode.DATASET_INVALID, "dataset '" + item.title() + "' has errors: " + r.firstError());
        }
        return new QueryService.Loaded(item.id(), Layers.key(item.id()), r.dataset(), hash(body));
    }

    // ---- the reader --------------------------------------------------------------------------------------------------

    public QueryService.Caller caller(Principal p, Dataset ds) {
        Optional<User> u = security.enabled() ? users.find(p.user()) : Optional.empty();
        Map<String, List<Object>> attrs = UserAttributes.of(p.user(), p.roles(), u.map(User::desk).orElse(""), u.map(User::email).orElse(""));
        Set<String> denied = new java.util.HashSet<>();
        ds.tables().values().stream().filter(t -> !Dataset.FRAME.equals(t.kind())).forEach(t -> {         // a composed or derived dataset's sources are checked in their own queries
            if (!entitlements.mayOpen(p, t.kind())) {
                denied.add(t.name());
            }
        });
        return new QueryService.Caller(p.user(), Set.copyOf(p.roles()), security.enabled(), attrs, masks(p, ds), denied);
    }

    /** A column is masked for the caller when Drishti's field masks name it ({@code drishti.security.redact}) or the dataset lists it and the caller lacks {@code raw}. */
    public SqlCompiler.Masks masks(Principal p, Dataset ds) {
        boolean raw = entitlements.raw(p);
        return (table, column) -> {
            Dataset.Table t = ds.tables().get(table);
            Dataset.Column c = t == null ? null : t.columns().get(column);
            String leaf = c == null ? column : c.path().substring(c.path().lastIndexOf('.') + 1);
            if (entitlements.masked(p, column) || entitlements.masked(p, leaf)) {
                return true;
            }
            return !raw && (ds.security().masked().contains(column) || ds.security().masked().contains(table + "." + column));
        };
    }

    // ---- the API -----------------------------------------------------------------------------------------------------

    /** The datasets the caller may see, with what is wrong with each. */
    public List<Map<String, Object>> list(Principal p) {
        return list(p, false);
    }

    /** As above; with {@code drafts} the caller's own unpublished datasets follow, each marked by its status (a picker greys them out with the reason). */
    public List<Map<String, Object>> list(Principal p, boolean drafts) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<BiStore.Item> seen = new ArrayList<>(bi.datasetItems(p));
        if (drafts) {
            seen.addAll(bi.draftDatasets(p));
        }
        for (BiStore.Item i : seen) {
            DatasetParser.Result r = withConnectors(parse(i.id(), bi.bodyFor(p, i)));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.id());
            m.put("title", i.title());
            m.put("description", i.description());
            m.put("endorsement", i.endorsement());
            m.put("tags", i.tags());
            m.put("sample", i.tags().contains(BiFolder.SAMPLE_TAG));
            m.put("shipped", BiFolder.isShipped(i.id()));
            m.put("valid", r.ok());
            m.put("status", i.status());
            m.put("personal", !BiFolder.isShipped(i.id()) && bi.workspaceOf(i.workspace()).personal());
            m.put("errors", r.problems().stream().filter(DatasetParser.Problem::error).count());
            if (r.dataset() != null) {
                m.put("tables", r.dataset().tables().size());
                m.put("measures", r.dataset().measures().size());
                m.put("relationships", r.dataset().relationships().size());
            }
            out.add(m);
        }
        return out;
    }

    /** One dataset's model as the Model view and the preview need it, for this caller. */
    public Map<String, Object> model(Principal p, String id) {
        return modelOf(p, datasetItem(p, id));
    }

    /** The model of a dataset item the caller has been given (the dataset a report reads, reached through the report). */
    public Map<String, Object> modelOf(Principal p, BiStore.Item item) {
        String id = item.id();
        String body = bi.bodyFor(p, item);
        DatasetParser.Result r = withConnectors(parse(id, body));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", item.title());
        m.put("problems", r.problems().stream().map(x -> Map.of("level", x.level(), "code", x.code(), "path", x.path(), "message", x.message())).toList());
        m.put("valid", r.ok());
        if (r.dataset() == null) {
            return m;
        }
        Dataset d = r.dataset();
        m.put("name", d.name());
        m.put("version", d.version());
        m.put("description", d.description().isBlank() ? item.description() : d.description());
        m.put("sample", d.sample());
        m.put("engine", d.engine());
        m.putAll(shape(p, d));
        Set<String> mine = Set.copyOf(p.roles());
        m.put("security", Map.of("rows", d.security().rows().stream().map(x -> Map.of("role", x.role(), "where", x.where(), "appliesToYou", mine.contains(x.role()))).toList(),
                "masked", d.security().masked(), "rowRulesEnforced", security.enabled()));
        m.put("rollups", d.rollups().stream().map(x -> Map.of("name", x.name(), "table", x.table(), "by", x.by(), "measures", x.measures())).toList());
        if (r.ok() && refresher != null) {
            m.put("refresh", refresher.status(new QueryService.Loaded(id, Layers.key(id), d, "")));
        }
        if (r.ok()) {
            m.put("layers", withLive(Layers.key(id), d, queries.layers().inventory(Layers.key(id), d)));
        }
        return m;
    }

    /** The parts of a dataset a designer draws: tables with their columns, relationships, measures, hierarchies. */
    private Map<String, Object> shape(Principal p, Dataset d) {
        Map<String, Object> m = new LinkedHashMap<>();
        SqlCompiler.Masks masks = masks(p, d);
        List<Map<String, Object>> tables = new ArrayList<>();
        for (Dataset.Table t : d.tables().values()) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("name", t.name());
            tm.put("connector", t.connector());
            tm.put("kind", t.kind());
            tm.put("each", t.each());
            tm.put("mode", t.mode());
            tm.put("onLoad", t.onLoad());
            tm.put("schedule", t.schedule());
            tm.put("description", t.description());
            tm.put("access", entitlements.mayOpen(p, t.kind()) ? "ok" : "no-access");
            List<Map<String, Object>> cols = new ArrayList<>();
            boolean frame = Dataset.FRAME.equals(t.mode());                    // a composed or derived dataset's table has the columns it was built with and no more
            if (!frame) {
                cols.add(column("id", "id", "string", true, null, false, "the entity id"));
            }
            for (Dataset.Column c : t.columns().values()) {
                Map<String, Object> cm = column(c.name(), c.path(), c.type(), c.key(), c.expr(), masks.masked(t.name(), c.name()), c.description());
                cm.put("format", c.format());
                cols.add(cm);
            }
            if (!frame) {
                cols.add(column("businessDate", "businessDate", "date", false, null, false, "the business date of the row"));
            }
            if (t.each() != null) {
                cols.add(column("index", "index", "integer", false, null, false, "the position in the array"));
            }
            tm.put("columns", cols);
            tables.add(tm);
        }
        m.put("tables", tables);
        m.put("relationships", d.relationships().stream().map(x -> {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("from", x.fromTable() + "." + x.fromColumn());
            rm.put("to", x.toTable() + "." + x.toColumn());
            rm.put("cardinality", x.cardinality());
            rm.put("direction", x.direction());
            rm.put("active", x.active());
            return rm;
        }).toList());
        List<Map<String, Object>> measures = new ArrayList<>();
        for (Dataset.Measure x : d.measures().values()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("name", x.name());
            mm.put("expr", x.expr());
            mm.put("python", x.python());
            mm.put("inputs", x.inputs());
            mm.put("by", x.by());
            mm.put("format", x.format());
            mm.put("description", x.description());
            mm.put("rollups", d.rollups().stream().filter(ro -> ro.measures().contains(x.name())).map(Dataset.Rollup::name).toList());
            measures.add(mm);
        }
        m.put("measures", measures);
        m.put("hierarchies", d.hierarchies());
        if (d.framed()) {
            m.put("composition", composition(d));
        }
        return m;
    }

    /** What a composed or derived dataset is made of, for the Model view: its sources with what they are asked, and how they are joined or which Transform reads them. */
    private static Map<String, Object> composition(Dataset d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", d.composed() ? "composed" : "derived");
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Dataset.Input in : LegacySources.sources(d)) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("alias", in.alias());
            s.put("dataset", in.dataset());
            s.put("by", in.by().stream().map(n -> n.as() == null ? n.source() : n.source() + " as " + n.as()).toList());
            s.put("fields", in.fields().stream().map(n -> n.as() == null ? n.source() : n.source() + " as " + n.as()).toList());
            s.put("measures", in.measures().stream().map(n -> n.as() == null ? n.source() : n.source() + " as " + n.as()).toList());
            s.put("filters", in.filters().stream().map(f -> f.field() + " " + f.op() + " " + f.values()).toList());
            sources.add(s);
        }
        m.put("sources", sources);
        if (d.composed()) {
            m.put("joins", d.compose().joins().stream().map(j -> {
                Map<String, Object> jm = new LinkedHashMap<>();
                jm.put("left", j.left());
                jm.put("right", j.right());
                jm.put("on", j.on());
                jm.put("type", j.type());
                jm.put("allowRepeat", j.allowRepeat());
                return jm;
            }).toList());
            m.put("frame", d.compose().frame().stream().map(f -> Map.of("name", f.name(), "from", f.alias(), "type", f.type(), "measure", f.measure())).toList());
        } else {
            m.put("transform", d.derived().transform());
            m.put("params", d.derived().params());
        }
        return m;
    }

    private static Map<String, Object> column(String name, String path, String type, boolean key, String expr, boolean masked, String description) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("path", path);
        c.put("type", type);
        c.put("key", key);
        c.put("calculated", expr != null);
        c.put("expr", expr);
        c.put("masked", masked);
        c.put("description", description);
        return c;
    }

    /** Checks YAML without storing it. */
    public Map<String, Object> validate(Principal p, String yaml) {
        DatasetParser.Result r = withConnectors(DatasetParser.parse(yaml));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("valid", r.ok());
        m.put("problems", r.problems().stream().map(x -> Map.of("level", x.level(), "code", x.code(), "path", x.path(), "message", x.message())).toList());
        if (r.dataset() != null) {
            m.put("tables", r.dataset().tables().keySet());
            m.put("measures", r.dataset().measures().keySet());
            if (p != null) {
                m.put("model", shape(p, r.dataset()));            // the same tables, columns and relationships the dataset page shows: what the Model view draws from a draft
            }
        }
        return m;
    }

    /** Runs a query against a dataset as the caller. */
    public QueryService.Result query(Principal p, String id, QueryRequest q, boolean explain) {
        return queryItem(p, datasetItem(p, id), q, explain);
    }

    /** The parsed dataset with this id, name or file name, as the caller reads it, or DRS-9010 when it has errors. */
    public Dataset parsed(Principal p, String id) {
        return parsedDataset(p, datasetItem(p, id));
    }

    /** The parsed dataset of an item, or DRS-9010 when it has errors. */
    public Dataset parsedDataset(Principal p, BiStore.Item item) {
        return loaded(p, item).dataset();
    }

    /** The state of a dataset's live tables (generation, last update, state, counters), starting them when they are not running: {@code state: none} without live tables. */
    public Map<String, Object> liveTick(Principal p, BiStore.Item item) {
        return queries.liveTick(loaded(p, item));
    }

    /** A reader of the dataset's live state for a stream: the dataset is resolved once; each call reads the tables' counters (and starts a table that was closed as idle). */
    public java.util.function.Supplier<Map<String, Object>> liveWatch(Principal p, BiStore.Item item) {
        QueryService.Loaded ds = loaded(p, item);
        queries.liveTick(ds);
        return () -> queries.liveTick(ds);
    }

    /** Runs a query against a dataset item the caller reaches through something else (a report): the row rules and masks are still the caller's. */
    public QueryService.Result queryItem(Principal p, BiStore.Item item, QueryRequest q, boolean explain) {
        String id = item.id();
        QueryService.Loaded ds = loaded(p, item);
        QueryService.Result r = queries.query(ds, q, caller(p, ds.dataset()), explain, framesOf(p, ds, q));
        audit.record(p.user(), "bi-query", id, q.fields().size() + " fields, " + q.measures().size() + " measures, " + r.rows() + " rows from " + r.meta().layer()
                + (r.meta().cached() ? " (cached)" : ""));
        return r;
    }

    /**
     * Runs a query against a dataset item as the caller with the dataset widened (a model query's local measures, the currency guard's counts): the item's
     * own layers, frames and security; {@code salt} keeps such answers apart in the result cache.
     */
    public QueryService.Result queryWith(Principal p, BiStore.Item item, Dataset ds, String salt, QueryRequest q, boolean explain) {
        QueryService.Loaded base = loaded(p, item);
        QueryService.Loaded wide = new QueryService.Loaded(base.id(), base.key(), ds, base.hash() + "|" + salt);
        QueryService.Result r = queries.query(wide, q, caller(p, ds), explain, framesOf(p, wide, q));
        audit.record(p.user(), "bi-query", item.id(), "model query: " + q.fields().size() + " fields, " + q.measures().size() + " measures, " + r.rows() + " rows from " + r.meta().layer());
        return r;
    }

    /** Runs a query against a data model's flattened dataset as the caller (its security per dataset, the caller's masks and kinds). Audited as {@code bi-mq}. */
    public QueryService.Result queryModel(Principal p, String id, String key, Dataset ds, String hash, QueryRequest q, boolean explain) {
        return queryModel(p, id, key, ds, hash, q, explain, null);
    }

    /** As {@link #queryModel(Principal, String, String, Dataset, String, QueryRequest, boolean)} with tables whose rows are another model's answer ({@code staged}). */
    public QueryService.Result queryModel(Principal p, String id, String key, Dataset ds, String hash, QueryRequest q, boolean explain, com.ash.drishti.server.bi.engine.StagedTables staged) {
        QueryService.Loaded loaded = new QueryService.Loaded(id, key, ds, hash);
        QueryService.Result r = staged == null ? queries.query(loaded, q, caller(p, ds), explain) : queries.query(loaded, q, caller(p, ds), explain, staged);
        audit.record(p.user(), "bi-mq", id, q.fields().size() + " fields, " + q.measures().size() + " measures, " + r.rows() + " rows from " + r.meta().layer()
                + (r.meta().cached() ? " (cached)" : ""));
        return r;
    }

    /** The data generation of a dataset's layers and live tables (starting its live tables): what a standing query watches. */
    /** The generation bus of the query engine (wave B: live batches, loads and refreshes pushed to standing queries). */
    public com.ash.drishti.server.bi.engine.GenerationBus bus() {
        return queries.bus();
    }

    /** A running live table's generation (0 when it does not run). */
    public long liveGeneration(String key, String table) {
        return queries.live().find(key, table).map(com.ash.drishti.server.bi.engine.LiveTable::generation).orElse(0L);
    }

    /** The generation of one business date of a dataset or model's layers (wave B: an as-of answer's cache key; a back-dated load moves only its date). */
    public long generationAt(String key, java.time.LocalDate date) {
        return queries.layers().generation(key, date);
    }

    public long generation(String id, String key, Dataset ds) {
        Object live = queries.liveTick(new QueryService.Loaded(id, key, ds, "")).get("generation");
        return queries.layers().generation(key) * 1_000_003L + (live instanceof Number n ? n.longValue() : 0L);
    }

    private com.ash.drishti.server.bi.engine.Frames framesOf(Principal p, QueryService.Loaded ds, QueryRequest q) {
        return ds.dataset().framed() && frames != null ? frames.framesFor(p, ds, q) : null;
    }

    /**
     * Asks a source of a composed or derived dataset a question as the reader: their row rules, masks and entitlements apply inside it (a source is reached through
     * the dataset that names it, as a report reaches its dataset). Not audited on its own: the query that needed it is.
     */
    public QueryService.Result querySource(Principal p, BiStore.Item source, QueryRequest q) {
        QueryService.Loaded ds = loaded(p, source);
        return queries.query(ds, q, caller(p, ds.dataset()), false, framesOf(p, ds, q));
    }

    /**
     * Runs a query against the text of a draft dataset (a reviewer's data preview): the draft's own layers, the caller's row rules and masks. Not audited
     * as a read of the stored dataset: the reviewer asks to see what would be published.
     */
    public QueryService.Result queryDraft(Principal p, BiStore.Item item, String body, QueryRequest q) {
        DatasetParser.Result r = DatasetParser.parse(body);
        if (!r.ok()) {
            throw new DrishtiException(ErrorCode.DATASET_INVALID, "the draft of '" + item.title() + "' has errors: " + r.firstError());
        }
        QueryService.Loaded ds = new QueryService.Loaded(item.id() + "~review", Layers.key(item.id() + "~review"), r.dataset(), hash(body));
        QueryService.Result res = queries.query(ds, q, caller(p, ds.dataset()), false, framesOf(p, ds, q));
        audit.record(p.user(), "bi-query", item.id(), "review preview: " + res.rows() + " rows from " + res.meta().layer());
        return res;
    }

    /** The answer as JSON for a small result: columns with their types and the rows. */
    public Map<String, Object> json(QueryService.Result r) {
        if (r.rows() > props.jsonMaxRows()) {
            throw new DrishtiException(ErrorCode.QUERY_ROWS, "the answer has " + r.rows() + " rows, more than the " + props.jsonMaxRows()
                    + " an answer in JSON may hold (drishti.bi.query.json-max-rows): ask for format=arrow, or limit it");
        }
        List<Map<String, Object>> cols = new ArrayList<>();
        for (ArrowIpcWriter.Column c : r.columns()) {
            cols.add(Map.of("name", c.name(), "type", switch (c.kind()) {
                case TEXT -> "string";
                case INT64 -> "integer";
                case FLOAT64 -> "decimal";
            }, "masked", r.meta().masked().contains(c.name())));
        }
        List<List<Object>> rows = new ArrayList<>(r.rows());
        for (int i = 0; i < r.rows(); i++) {
            List<Object> row = new ArrayList<>(r.columns().size());
            for (ArrowIpcWriter.Column c : r.columns()) {
                row.add(c.values()[i]);
            }
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", cols);
        out.put("rows", rows);
        out.put("rowCount", r.rows());
        out.put("meta", meta(r));
        return out;
    }

    public Map<String, Object> meta(QueryService.Result r) {
        QueryService.Meta m = r.meta();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("layer", m.layer());
        out.put("rollup", m.rollup());
        out.put("rows", m.rows());
        out.put("millis", m.millis());
        out.put("cached", m.cached());
        out.put("dates", m.dates());
        out.put("tables", m.tables());
        out.put("masked", m.masked());
        out.put("warnings", m.warnings());
        out.put("generation", m.generation());
        if (m.sql() != null) {
            out.put("sql", m.sql());
        }
        return out;
    }

    /** The answer as an Arrow IPC stream; over the byte limit it is DRS-9023. */
    public byte[] arrow(QueryService.Result r) {
        byte[] bytes = ArrowIpcWriter.write(r.columns(), r.rows());
        if (bytes.length > props.maxBytes()) {
            throw new DrishtiException(ErrorCode.QUERY_BYTES, "the answer is " + bytes.length + " bytes, over the " + props.maxBytes() + " allowed (drishti.bi.query.max-bytes): filter or group by less");
        }
        return bytes;
    }

    /** Clears a dataset's layers for the current business date and reads them again, in the background; the dataset's refresh state says how it went. */
    public Map<String, Object> refresh(Principal p, String id) {
        BiStore.Item item = datasetItem(p, id);
        String layerName = layerDatasetName(p, item);
        if (layerName != null) {
            return refreshLayerDataset(p, item, layerName);
        }
        QueryService.Loaded ds = loaded(p, item);
        java.time.LocalDate today = sources.currentDate();
        List<String> touched = ds.dataset().tables().values().stream().filter(t -> "cached".equals(t.mode())).map(Dataset.Table::name).toList();
        audit.record(p.user(), "bi-dataset-refresh", id, "layers of " + today + " cleared and rebuilt");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", id);
        out.put("date", today.toString());
        out.put("refreshing", touched);
        if (refresher != null) {
            out.put("state", refresher.runNow(ds, p.user()));
        } else {
            queries.onLoad(ds.dataset().tables().values().stream().findFirst().map(Dataset.Table::kind).orElse(""), today, List.of(ds));
        }
        return out;
    }

    /** The name of a {@code rachana: 2} dataset item (one of the model layer), or null for a version 1 dataset. */
    private String layerDatasetName(Principal p, BiStore.Item item) {
        String body = bi.bodyFor(p, item);
        if (!com.ash.drishti.server.bi.layer.DatasetDefParser.isVersion2(body)) {
            return null;
        }
        com.ash.drishti.server.bi.layer.DatasetDef d = com.ash.drishti.server.bi.layer.DatasetDefParser.parse(body).dataset();
        if (d == null) {
            throw new DrishtiException(ErrorCode.LAYER_DATASET_INVALID, "'" + item.title() + "' has errors: Check it on its page before a refresh");
        }
        return d.name();
    }

    /**
     * A refresh of a dataset of the model layer: its kept Python Transform results are dropped (the next question runs the Transform again, as its reader),
     * and in every published model that reads it, and its implicit model, the tables that read it are cleared and rebuilt in the background.
     */
    private Map<String, Object> refreshLayerDataset(Principal p, BiStore.Item item, String name) {
        int dropped = frames == null ? 0 : frames.transforms().invalidateNamed(name);
        ModelGateway g = gateway;
        List<ModelGateway.Reader> readers = g == null ? List.of() : g.readersOf(name);
        readers.forEach(r -> queries.layers().bump(r.loaded().key()));
        java.time.LocalDate today = sources.currentDate();
        audit.record(p.user(), "bi-dataset-refresh", item.id(), "dataset " + name + ": " + dropped + " kept Transform result(s) dropped, tables of " + readers.size() + " model(s) rebuilt");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", item.id());
        out.put("date", today.toString());
        out.put("refreshing", readers.stream().flatMap(r -> r.tables().stream().map(t -> r.loaded().id() + "/" + t)).toList());
        out.put("models", readers.stream().map(r -> r.loaded().id()).toList());
        out.put("transformResultsDropped", dropped);
        if (refresher != null) {
            out.put("state", refresher.runNow(item.id(), readers, p.user()));
        } else {
            readers.forEach(r -> queries.refresh(r.loaded(), r.tables(), today));
        }
        return out;
    }

    /** Runs a derived dataset's Transform now for the caller (see {@link DatasetController#runTransform}). */
    public Map<String, Object> runTransform(Principal p, String id) {
        BiStore.Item item = datasetItem(p, id);
        throw new DrishtiException(ErrorCode.QUERY_INVALID, "'" + item.title() + "': derived datasets were removed in 2.2; a Python dataset (source.python) runs its "
                + "Transform when it is asked (drishti.py bi migrate --models converts a derived dataset; docs/architecture/BI_MODEL_SPEC.md section 8.3)");
    }

    /** The refresh state of a dataset the caller may read: its schedule, last run, status, next run, failures. */
    public Map<String, Object> refreshState(Principal p, String id) {
        BiStore.Item item = datasetItem(p, id);
        if (layerDatasetName(p, item) != null) {
            return refresher == null ? Map.of("enabled", false, "scheduled", false) : refresher.status(item.id());
        }
        QueryService.Loaded ds = loaded(p, item);
        return refresher == null ? Map.of("enabled", false, "scheduled", false) : refresher.status(ds);
    }

    public List<Map<String, Object>> layers(Principal p, String id) {
        BiStore.Item item = datasetItem(p, id);
        Dataset d = loaded(p, item).dataset();
        return withLive(Layers.key(id), d, queries.layers().inventory(Layers.key(id), d));
    }

    /** A live table has no partitions on disk: its entry says how the in-memory table is doing (state, rows, entities followed, how many push, last change) or that it is not started. */
    private List<Map<String, Object>> withLive(String key, Dataset d, List<Map<String, Object>> inventory) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : inventory) {
            Map<String, Object> m = new LinkedHashMap<>(e);
            Dataset.Table t = d.tables().get(String.valueOf(e.get("table")));
            if (t != null && t.isHistory() && queries.keepsLive(d)) {
                m.put("history", queries.live().find(key, t.of()).map(x -> x.historyStats()).orElse(Map.of("state", "not started", "table", t.name())));
            }
            if (t != null && t.isLive() && queries.keepsLive(d)) {
                m.put("live", queries.live().find(key, t.name()).map(x -> {
                    Map<String, Object> st = new LinkedHashMap<>(x.status().toMap());
                    st.put("aggregates", x.aggregateStats());
                    return st;
                }).orElse(Map.of("state", "not started", "table", t.name())));
            }
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> engine() {
        Map<String, Object> m = new LinkedHashMap<>(queries.stats());
        m.put("engine", "duckdb");
        m.put("storeDir", queries.layers().root().toString());
        m.put("live", queries.live().stats());
        if (frames != null) {
            m.put("transforms", frames.transforms().stats());
        }
        m.put("limits", Map.of("timeoutSeconds", props.timeout().toSeconds(), "maxRows", props.maxRows(), "maxBytes", props.maxBytes(), "maxConcurrent", props.maxConcurrent(),
                "maxConcurrentPerUser", props.maxConcurrentPerUser(), "jsonMaxRows", props.jsonMaxRows()));
        return m;
    }

    public PackModels models(Principal p) {
        return new PackModels(packs, graph, sources, props, kind -> entitlements.mayOpen(p, kind));
    }

    // ---- data loads --------------------------------------------------------------------------------------------------

    /** A data-load signal: clears and rebuilds the date for every published dataset that reads the kind (those that refresh {@code after-load} are left to the scheduler). */
    public void onLoad(LoadRecord r) {
        List<QueryService.Loaded> all = published().stream().filter(d -> !DatasetRefresher.afterLoad(d.dataset())).toList();
        List<String> touched = queries.onLoad(r.kind(), r.businessDate(), all);
        if (!touched.isEmpty()) {
            LOG.info("data load {} {}: BI layers cleared for {}", r.kind(), r.businessDate(), touched);
        }
        if (refresher != null) {
            refresher.onLoad(r.kind(), r.businessDate());
        }
    }
}
