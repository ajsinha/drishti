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

import com.ash.drishti.common.Branding;
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
 * The datasets of a model whose rows are not a pack kind (docs/architecture/BI_DESIGN.md › The model layer: the specification of wave W1, sections 1.2 B to D and 5.4, wave W1 package S4): a
 * <b>connector query</b> (its SQL run in its SQL-capable connector, only the four named dates bound), a <b>Python Transform</b> over model-query inputs, or
 * <b>another model's answer</b>. Every inner query runs <em>as the reader</em> (their row rules and masks apply inside it) at the outer query's date when it
 * names none, and the answer becomes the rows of the dataset's table for that one query: the model's relationships, the dataset's own rules and masks and the
 * measures then work over it like over any table. Columns are filled by name (a declared column's {@code path}, else its name, else the last part of an
 * answer column's name; a declared column the answer lacks is {@code DRS-9108}). A model that reaches itself through a model source is refused
 * ({@code DRS-9109}), by the Check and the bind (published items) and at run time (anything else).
 */
final class DatasetSources implements StagedTables {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEPTH = 8;
    private static final ThreadLocal<Set<String>> ASKING = ThreadLocal.withInitial(HashSet::new);
    private static final Set<String> STAGED = Set.of(DatasetDef.Source.MODEL, DatasetDef.Source.CONNECTOR, DatasetDef.Source.PYTHON, DatasetDef.Source.SQL,
            DatasetDef.Source.RUN);
    private static final java.util.regex.Pattern PARAM = java.util.regex.Pattern.compile("(?<![:\\w]):([A-Za-z][A-Za-z0-9_]*)");

    private final ModelCatalog catalog;
    private final Principal reader;
    private final ModelView view;
    private final QueryRequest outer;
    private final Map<String, MqService.Bound> bound = new LinkedHashMap<>();
    private final Map<String, TransformInputs.Asked> askedInputs = new LinkedHashMap<>();
    private String fingerprint;

    DatasetSources(ModelCatalog catalog, Principal reader, ModelView view, QueryRequest outer) {
        this.catalog = catalog;
        this.reader = reader;
        this.view = view;
        this.outer = outer;
    }

    /** The aliases of a model whose dataset's rows are staged (a connector query, a Python Transform, a model's answer). */
    static Set<String> aliases(ModelView v) {
        Set<String> out = new LinkedHashSet<>();
        v.datasets().forEach((alias, r) -> {
            if (type(r) != null) {
                out.add(alias);
            }
        });
        return out;
    }

    /** The staging of a dataset whose rows come from a pack kind and get Python columns ({@code derive: { x: { python } }}, until wave B). */
    static final String PYTHON_COLUMNS = "python-columns";

    private static String type(ResolvedDataset r) {
        if (r != null && !r.def().derivation().python().isEmpty()) {
            return PYTHON_COLUMNS;
        }
        return r != null && r.def().source() != null && STAGED.contains(r.def().source().type()) ? r.def().source().type() : null;
    }

    private static boolean isModel(ResolvedDataset r) {
        return DatasetDef.Source.MODEL.equals(type(r)) || DatasetDef.Source.SQL.equals(type(r));
    }

    @Override
    public boolean has(String table) {
        return type(view.datasets().get(table)) != null;
    }

    @Override
    public synchronized String fingerprint() {
        if (fingerprint == null) {
            StringBuilder b = new StringBuilder(reader.user()).append('|').append(new TreeSet<>(reader.roles())).append('|').append(outer.from()).append('~').append(outer.to());
            for (String alias : aliases(view)) {
                DatasetDef.Source s = view.datasets().get(alias).def().source();
                b.append('|').append(alias).append('=');
                if (PYTHON_COLUMNS.equals(type(view.datasets().get(alias)))) {
                    DatasetDef pd = view.datasets().get(alias).def();
                    b.append(pd.derivation().python()).append(innerKey(inner(alias + "/rows", baseRef(pd))));
                    continue;
                }
                switch (s.type()) {
                    case DatasetDef.Source.MODEL -> b.append(innerKey(inner(alias, s.model())));
                    case DatasetDef.Source.SQL -> b.append(innerKey(inner(alias, s.model()))).append('#').append(ModelCatalog.hash(s.query()));
                    case DatasetDef.Source.RUN -> b.append("run:").append(s.runName()).append('@').append(snapshot(view.datasets().get(alias).def()).version());
                    case DatasetDef.Source.PYTHON -> {
                        b.append(s.python()).append(s.pythonParams());
                        s.inputs().forEach((name, q) -> {
                            TransformInputs.Asked a = asked(alias + "/" + name, q);
                            b.append(',').append(name).append(':').append(innerKey(a.bound())).append(a.kind() ? "#" + ModelCatalog.hash(q.toString()) : "");
                        });
                    }
                    default -> b.append(s.connector()).append('#').append(ModelCatalog.hash(s.query() + s.params()));
                }
            }
            fingerprint = b.toString();
        }
        return fingerprint;
    }

    private String innerKey(MqService.Bound inner) {
        return inner.view().id() + "#" + inner.view().hash() + "g" + catalog.generation(reader, inner.view()) + "m" + inner.context().masks().hashCode();
    }

    @Override
    public Answer answer(String table) {
        DatasetDef d = view.datasets().get(table).def();
        if (PYTHON_COLUMNS.equals(type(view.datasets().get(table)))) {
            return fromPythonColumns(table, d);
        }
        return switch (d.source().type()) {
            case DatasetDef.Source.MODEL -> fromModel(table, d);
            case DatasetDef.Source.SQL -> fromSql(table, d);
            case DatasetDef.Source.RUN -> fromRun(table, d);
            case DatasetDef.Source.PYTHON -> fromPython(table, d);
            default -> fromConnector(table, d);
        };
    }

    // ---- a connector query -------------------------------------------------------------------------------------------

    private Answer fromConnector(String table, DatasetDef d) {
        DatasetDef.Source s = d.source();
        List<Object> values = new ArrayList<>();
        String sql = bind(s.query(), s.params(), outerDate(), outer.from(), outer.to(), values);
        QueryService.Result r = catalog.datasets().connectorQuery(reader, s.connector(), sql, values, catalog.datasets().maxRows());
        return new Answer(r, columns(table, d, r, "the connector query"), outerDate());
    }

    /**
     * The SQL with each {@code :name} that {@code params} binds replaced by a placeholder, in order, its value one of the four named dates ({@code as-of} and
     * {@code business-date} the outer query's date; {@code from} and {@code to} its window, else that date). Never text from a user.
     */
    static String bind(String sql, Map<String, String> params, java.time.LocalDate date, java.time.LocalDate from, java.time.LocalDate to, List<Object> values) {
        java.util.regex.Matcher m = PARAM.matcher(sql);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String which = params.get(m.group(1));
            if (which == null) {
                m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(m.group()));
                continue;
            }
            java.time.LocalDate v = switch (which) {
                case "from" -> from != null ? from : date;
                case "to" -> to != null ? to : from != null ? from : date;
                default -> date;
            };
            values.add(java.sql.Date.valueOf(v));
            m.appendReplacement(out, "?");
        }
        m.appendTail(out);
        return out.toString();
    }

    private java.time.LocalDate outerDate() {
        return outer.to() != null ? outer.to() : outer.from() != null ? outer.from() : catalog.datasets().currentDate();
    }

    // ---- a Python Transform ------------------------------------------------------------------------------------------

    private Answer fromPython(String table, DatasetDef d) {
        DatasetDef.Source s = d.source();
        Map<String, QueryService.Result> inputs = new LinkedHashMap<>();
        java.time.LocalDate[] asOf = {null};
        for (Map.Entry<String, JsonNode> e : s.inputs().entrySet()) {
            TransformInputs.Asked a = asked(table + "/" + e.getKey(), e.getValue());
            inputs.put(e.getKey(), guarded(d, a.bound(), () -> {
                MqService.Ran ran = catalog.mq().run(a.bound(), a.query(), false, false);
                asOf[0] = later(asOf[0], asOfOf(ran));
                return TransformInputs.bare(ran.shown(), a.alias());
            }));
        }
        Map<String, String> declared = new LinkedHashMap<>();
        d.columns().values().forEach(c -> declared.put(c.name(), c.type()));
        QueryService.Result r = catalog.datasets().transform(reader, view.key() + "/" + table, d.name(), s.python(), inputs, s.pythonParams(), declared, fingerprint());
        return new Answer(r, columns(table, d, r, "the Transform"), asOf[0] != null ? asOf[0] : outerDate());   // one as-of rule: the date its inputs were asked at
    }

    /**
     * An input of a Transform bound for the reader (TransformInputs): a model query as the dataset states it, or a pack kind read through a connector (2.4.0,
     * docs/architecture/BI_DESIGN.md section 11.1, item P5), asked as the reader at the outer query's date. Kept per key, as the inner models are, so the fingerprint and the run agree.
     */
    private TransformInputs.Asked asked(String key, JsonNode in) {
        TransformInputs.Asked a = askedInputs.get(key);
        if (a == null) {
            a = TransformInputs.isKind(in) ? TransformInputs.ask(catalog, reader, in, timeOf(), catalog.datasets().maxRows())
                    : new TransformInputs.Asked(inner(key, modelOf(in)), query(in, modelOf(in)), null, false, modelOf(in));
            askedInputs.put(key, a);
        }
        return a;
    }

    /** The date an inner answer is as of, by the rule a model's own answer uses (MqService meta.asOf): the last date its rows came from, else the asked date. */
    private static java.time.LocalDate asOfOf(MqService.Ran ran) {
        QueryService.Meta m = ran.shown().meta();
        if (m != null && !m.dates().isEmpty()) {
            Object last = m.dates().get(m.dates().size() - 1);
            try {
                return last instanceof java.time.LocalDate l ? l : java.time.LocalDate.parse(String.valueOf(last));
            } catch (java.time.format.DateTimeParseException e) {
                // not a day: the asked date
            }
        }
        return ran.compiled().asOf();
    }

    private static java.time.LocalDate later(java.time.LocalDate a, java.time.LocalDate b) {
        return a == null ? b : b == null || !b.isAfter(a) ? a : b;
    }

    private static String modelOf(JsonNode q) {
        return q == null ? "" : q.path("model").asText("");
    }

    // ---- another model's answer --------------------------------------------------------------------------------------

    private Answer fromModel(String table, DatasetDef d) {
        MqService.Bound inner = inner(table, d.source().model());
        MqService.Ran[] ran = new MqService.Ran[1];
        QueryService.Result shown = guarded(d, inner, () -> {
            ran[0] = catalog.mq().run(inner, query(d.source().modelQuery(), d.source().model()), false, false);
            return ran[0].shown();
        });
        List<String> warnings = new ArrayList<>(ran[0].warnings());
        List<String> masked = shown.meta() == null ? List.of() : shown.meta().masked();
        if (!masked.isEmpty()) {
            warnings.add(masked + " " + (masked.size() == 1 ? "is" : "are") + " masked for you in the source '" + d.source().model() + "': empty here");
        }
        QueryService.Meta m = shown.meta();
        QueryService.Result result = new QueryService.Result(shown.columns(), shown.rows(), m == null ? null
                : new QueryService.Meta(m.layer(), m.rollup(), m.rows(), m.millis(), m.cached(), m.dates(), m.tables(), m.masked(), warnings, m.sql(), m.generation(), m.tableDates()));
        return new Answer(result, columns(table, d, result, "the source query"), ran[0].compiled().asOf());
    }

    // ---- Python columns ---------------------------------------------------------------------------------------------

    /** The implicit model of the dataset a dataset with Python columns extends (its rows, as the reader). */
    private static String baseRef(DatasetDef d) {
        String ref = d.extendsRef() == null ? d.name() : d.extendsRef();
        int at = ref.indexOf('@');
        return ImplicitModels.QUERY_PREFIX + (at < 0 ? ref : ref.substring(0, at));
    }

    /**
     * A dataset with Python columns (docs/architecture/BI_DESIGN.md section 3.1, "python via the existing Transform runner until B"): the base's rows are asked
     * as the reader (a row extract of every stored column of the dataset), handed to each Python item as the frame {@code rows} (a Transform that returns the
     * frame with its column added), and the result is the table. The dataset's own rules, masks and derived expressions then apply to it as to any table.
     */
    private Answer fromPythonColumns(String table, DatasetDef d) {
        MqService.Bound base = inner(table + "/rows", baseRef(d));
        String alias = base.view().def().datasets().keySet().iterator().next();
        List<String> fields = new ArrayList<>();
        Map<String, String> declared = new LinkedHashMap<>();
        for (DatasetDef.Col c : d.columns().values()) {
            if (c.expr() == null && base.view().flat().tables().get(alias).columns().containsKey(c.name())) {
                fields.add(alias + "." + c.name());
                declared.put(c.name(), c.type());
            }
        }
        ModelQuery q = new ModelQuery(1, baseRef(d), Map.of(), fields, List.of(), List.of(), List.of(), timeOf(), List.of(), catalog.datasets().maxRows(), null, null, ModelQuery.ROWS,
                Map.of(), ModelQuery.Options.DEFAULT);
        java.time.LocalDate[] asOf = {null};
        QueryService.Result rows = guarded(d, base, () -> {
            MqService.Ran ran = catalog.mq().run(base, q, false, false);
            asOf[0] = asOfOf(ran);
            return ran.shown();
        });
        rows = renamed(rows, alias);
        QueryService.Result r = rows;
        for (Map.Entry<String, DatasetDef.PyColumn> e : d.derivation().python().entrySet()) {
            declared.put(e.getKey(), e.getValue().type());
            Map<String, QueryService.Result> inputs = Map.of("rows", r);
            r = catalog.datasets().transform(reader, view.key() + "/" + table + "/" + e.getKey(), d.name(), e.getValue().item(), inputs, Map.of("column", e.getKey(),
                    "inputs", e.getValue().inputs()), new LinkedHashMap<>(declared), fingerprint());
        }
        return new Answer(r, columns(table, d, r, "the Python columns"), asOf[0] != null ? asOf[0] : outerDate());
    }

    private ModelQuery.Time timeOf() {
        if (outer.from() == null) {
            return ModelQuery.Time.DEFAULT;
        }
        return outer.from().equals(outer.to()) || outer.to() == null ? new ModelQuery.Time(outer.from(), null, null, null, false) : new ModelQuery.Time(null, outer.from(), outer.to(),
                null, false);
    }

    /** An answer's columns without the alias ({@code trade.notional} is {@code notional}): the frame a Python item reads. */
    private static QueryService.Result renamed(QueryService.Result r, String alias) {
        List<ArrowIpcWriter.Column> cols = r.columns().stream().map(c -> new ArrowIpcWriter.Column(c.name().startsWith(alias + ".") ? c.name().substring(alias.length() + 1) : c.name(),
                c.kind(), c.values())).toList();
        return new QueryService.Result(cols, r.rows(), r.meta());
    }

    // ---- a promoted run's snapshot -------------------------------------------------------------------------------------

    private final Map<String, ModelCatalog.Snapshot> snapshots = new LinkedHashMap<>();

    /** The snapshot a dataset reads, once per query (the fingerprint and the rows agree on the version). */
    private ModelCatalog.Snapshot snapshot(DatasetDef d) {
        return snapshots.computeIfAbsent(d.name(), n -> {
            ModelCatalog.Snapshots s = catalog.snapshots();
            if (s == null) {
                throw new MqException(MqException.Problem.of("DRS-9108", "source.run", "run snapshots are not available on this server (BI runs are off)"));
            }
            return s.read(d.name(), d.source().runName(), d.source().runVersion(), d.source().runToken());
        });
    }

    /**
     * A promoted run's rows (BI_DESIGN.md 9.4): the promoter's view as stored, masks included (a masked value is stored as the mask, never the value); the
     * dataset's own rules and masks then apply to its readers on top, as on any table.
     */
    private Answer fromRun(String table, DatasetDef d) {
        ModelCatalog.Snapshot s = snapshot(d);
        return new Answer(s.result(), columns(table, d, s.result(), "the run snapshot"), s.asOf() != null ? s.asOf() : outerDate());
    }

    // ---- a SQL view ---------------------------------------------------------------------------------------------------

    /** A SQL view's answer: its Drishti SQL statement asked of its model as the reader (at the outer query's date when it names none). */
    private Answer fromSql(String table, DatasetDef d) {
        ModelCatalog.SqlViews views = catalog.sqlViews();
        if (views == null) {
            throw new MqException(MqException.Problem.of("DRS-9108", "source.sql", Branding.sqlName() + " is not available on this server (drishti.bi.sql.enabled)"));
        }
        MqService.Bound inner = inner(table, d.source().model());
        java.time.LocalDate date = outer.from() != null && (outer.to() == null || outer.from().equals(outer.to())) ? outer.from() : null;
        QueryService.Result r = guarded(d, inner, () -> views.answer(inner, d.source().model(), d.source().query(), date));
        return new Answer(r, columns(table, d, r, "the SQL view"), date != null ? date : outerDate());
    }

    /** Runs an inner query with the run-time cycle guard: a model being asked on this thread is not asked again through itself. */
    private QueryService.Result guarded(DatasetDef d, MqService.Bound inner, java.util.function.Supplier<QueryService.Result> run) {
        Set<String> asking = ASKING.get();
        if (asking.contains(inner.view().id())) {
            throw cycle("'" + d.name() + "' reads '" + inner.view().def().name() + "', which is being asked already: a model cannot read its own answer");
        }
        boolean mine = asking.add(view.id());
        try {
            return run.get();
        } finally {
            if (mine) {
                asking.remove(view.id());
            }
        }
    }

    /** For each physical column of the table, the answer's column that fills it; a declared column the answer lacks is DRS-9108. */
    private Map<String, String> columns(String table, DatasetDef d, QueryService.Result r, String what) {
        Dataset.Table t = view.flat().tables().get(table);
        List<String> names = r.columns().stream().map(ArrowIpcWriter.Column::name).toList();
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
            throw new MqException(new MqException.Problem("DRS-9108", "source", "the answer of " + what + " of '" + d.name() + "' has no column for " + missing
                    + " (it has " + names + "): name the column's path after a column of the answer", Map.of("columns", names)));
        }
        return columns;
    }

    private MqService.Bound inner(String key, String model) {
        return bound.computeIfAbsent(key, a -> catalog.bind(reader, model));
    }

    /** An inner query as the dataset states it (its {@code model} filled in), at the outer query's date when it names none. */
    private ModelQuery query(JsonNode stated, String model) {
        ObjectNode o = stated != null && stated.isObject() ? ((ObjectNode) stated).deepCopy() : JSON.createObjectNode();
        if (!o.has("mq")) {
            o.put("mq", 1);
        }
        if (!o.has("model")) {
            o.put("model", model);
        }
        ModelQuery q = MqParser.parse(o);
        if (q.time().equals(ModelQuery.Time.DEFAULT) && outer.from() != null) {
            ModelQuery.Time t = outer.from().equals(outer.to()) || outer.to() == null ? new ModelQuery.Time(outer.from(), null, null, null, false)
                    : new ModelQuery.Time(null, outer.from(), outer.to(), null, false);
            q = new ModelQuery(q.mq(), q.model(), q.define(), q.fields(), q.measures(), q.where(), q.having(), t, q.sort(), q.limit(), q.totals(), q.drill(), q.shape(),
                    q.params(), q.options(), q.ext());
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
