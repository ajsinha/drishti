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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.bi.BiAccess;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.DatasetService;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reports for the people who use them (RUPAKA.md section 8): finds the report item the caller may read, parses and checks it against its dataset, and
 * answers one visual's query at a time, under the page's interaction state, through the dataset query engine as that caller: their row rules, their
 * masks. A report is read with the grants of whoever published it: a reader needs the report, not the dataset's catalogue entry (the engine still
 * applies the reader's own rows and masks).
 */
public final class BiReportService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A report with what is needed to use it. */
    private record Loaded(BiStore.Item item, ReportParser.Result parsed, BiStore.Item datasetItem, Dataset dataset, List<ReportParser.Problem> problems, DatasetSet sets,
            Map<String, com.ash.drishti.server.bi.layer.ModelDef.Parameter> params) {

        Loaded(BiStore.Item item, ReportParser.Result parsed, BiStore.Item datasetItem, Dataset dataset, List<ReportParser.Problem> problems, DatasetSet sets) {
            this(item, parsed, datasetItem, dataset, problems, sets, Map.of());
        }

        /** The dataset one visual reads: its own when it names one, else the report's. */
        DatasetSet.Entry of(Report.Visual v) {
            return sets.of(parsed.report().datasetOf(v));
        }
    }

    private final BiService bi;
    private final DatasetService datasets;
    private final BiReportProperties props;

    public BiReportService(BiService bi, DatasetService datasets, BiReportProperties props) {
        this.bi = bi;
        this.datasets = datasets;
        this.props = props;
    }

    // ---- resolving ---------------------------------------------------------------------------------------------------

    private BiStore.Item reportItem(Principal p, String id) {
        BiStore.Item i = bi.readableItem(p, id);
        if (!"report".equals(i.kind())) {
            throw new DrishtiException(ErrorCode.BI_NOT_FOUND, "'" + id + "' is not a report");
        }
        return i;
    }

    /**
     * The dataset a report names: by catalogue id, shipped file name, the dataset's own name or its title. A report someone published reads its dataset with its
     * publisher's grants ({@code viaPublished}: any published dataset); a draft, and the designer's lookups, find only the datasets the caller may read themselves, so
     * a report cannot be used to reach a dataset the catalogue hides from its author.
     */
    BiStore.Item datasetFor(Principal p, String ref, boolean viaPublished) {
        if (ref == null || ref.isBlank()) {
            throw new DrishtiException(ErrorCode.REPORT_DATASET, "the report names no dataset");
        }
        List<BiStore.Item> pool = viaPublished ? bi.allDatasets() : bi.datasetItems(p);
        for (BiStore.Item i : pool) {
            if (i.id().equals(ref) || i.id().equals("file:dataset:" + ref)) {
                return i;
            }
        }
        for (BiStore.Item i : pool) {
            if (i.title().equalsIgnoreCase(ref) || ref.equals(declaredName(p, i))) {
                return i;
            }
        }
        try {
            BiStore.Item mine = bi.readableItem(p, ref);
            if ("dataset".equals(mine.kind())) {
                return mine;
            }
        } catch (DrishtiException e) {
            // not an item the caller can read: the refusal below
        }
        throw new DrishtiException(ErrorCode.REPORT_DATASET, "no dataset '" + ref + "' (a catalogue id, a file name such as trades-basic, or the dataset's name)");
    }

    private String declaredName(Principal p, BiStore.Item i) {
        DatasetParser.Result r = DatasetParser.parse(bi.bodyFor(p, i));
        return r.dataset() == null ? "" : r.dataset().name();
    }

    private Loaded load(Principal p, BiStore.Item item, String yaml, boolean viaPublished) {
        ReportParser.Result parsed = ReportParser.parse(yaml);
        List<ReportParser.Problem> problems = new ArrayList<>(parsed.problems());
        BiStore.Item dsItem = null;
        Dataset ds = null;
        DatasetSet sets = new DatasetSet();
        if (parsed.report() != null && parsed.report().model() != null) {
            String model = parsed.report().model();                             // a report bound to a model: its visuals are model queries on it
            String consumer = viaPublished && item != null ? item.id() : null;  // the published text: a reader without read on the model asks within its envelope
            Map<String, com.ash.drishti.server.bi.layer.ModelDef.Parameter> params = Map.of();
            try {
                ds = consumer == null ? datasets.modelDataset(p, model) : datasets.modelDataset(p, model, consumer);
                params = datasets.modelParameters(p, model, consumer);         // field parameters a well may name (param.<name>, 2.6.0)
                Map<String, String> define = new LinkedHashMap<>();
                for (Report.LocalMeasure lm : parsed.report().measures().values()) {
                    if (ds.measures().containsKey(lm.name())) {
                        problems.add(ReportParser.error("DRS-9154", "measures." + lm.name(), "'" + lm.name() + "' is a measure of the model: a report's own measure takes another name"));
                    } else {
                        define.put(lm.name(), lm.expr());
                    }
                }
                ds = withLocal(ds, parsed.report().measures().values().stream().filter(x -> define.containsKey(x.name())).toList());
                dsItem = DatasetService.modelRef(model, consumer, define, parsed.report().perspective());
                sets.put(parsed.report().dataset(), new DatasetSet.Entry(dsItem, ds));
            } catch (DrishtiException e) {
                problems.add(ReportParser.error(e.errorCode().code(), "model", e.getMessage()));
            }
            if (ds != null) {
                for (String ref : parsed.report().datasets()) {
                    if (sets.of(ref) == null) {
                        problems.add(ReportParser.error("DRS-9141", "dataset", "a visual of a report on a model reads that model; '" + ref + "' is another dataset (add it to the model)"));
                    }
                }
                problems.addAll(ReportValidator.validate(parsed.report(), ds, sets));
                problems.addAll(FieldParams.check(parsed.report(), ds, params));
                problems.addAll(StatementSpec.checkParams(parsed.report(), params.keySet()));      // {param.nope} in a statement (QA2-02)
            }
            return new Loaded(item, parsed, dsItem, ds, problems, sets, params);
        }
        if (parsed.report() != null && !parsed.report().dataset().isBlank()) {
            try {
                dsItem = datasetFor(p, parsed.report().dataset(), viaPublished);
                ds = datasets.parsedDataset(p, dsItem);
                sets.put(parsed.report().dataset(), new DatasetSet.Entry(dsItem, ds));
            } catch (DrishtiException e) {
                problems.add(ReportParser.error(e.errorCode().code(), "dataset", e.getMessage()));
            }
        }
        if (ds != null) {
            for (String ref : parsed.report().datasets()) {                      // the datasets visuals name for themselves
                if (sets.of(ref) != null) {
                    continue;
                }
                try {
                    BiStore.Item other = datasetFor(p, ref, viaPublished);
                    sets.put(ref, new DatasetSet.Entry(other, datasets.parsedDataset(p, other)));
                } catch (DrishtiException e) {
                    problems.add(ReportParser.error(e.errorCode().code(), "dataset", "visual dataset '" + ref + "': " + e.getMessage()));
                }
            }
            problems.addAll(ReportValidator.validate(parsed.report(), ds, sets));
            problems.addAll(FieldParams.check(parsed.report(), ds, Map.of()));     // a field parameter in a well needs a report on a model (DRS-9037)
            problems.addAll(StatementSpec.checkParams(parsed.report(), java.util.Set.of()));
        }
        return new Loaded(item, parsed, dsItem, ds, problems, sets);
    }

    /**
     * The kinds the report's datasets read, for Drishti*net's routing of a report to the node that holds them (DRISHTI_NET.md section 8);
     * empty when the report is not here or cannot be read.
     */
    public java.util.Set<String> kinds(Principal p, String id) {
        try {
            Loaded l = loadItem(p, id);
            java.util.Set<String> out = new java.util.TreeSet<>();
            java.util.List<Dataset> sets = new ArrayList<>();
            if (l.dataset() != null) {
                sets.add(l.dataset());
            }
            if (l.sets() != null) {
                l.sets().all().values().forEach(e -> {
                    if (e.dataset() != null) {
                        sets.add(e.dataset());
                    }
                });
            }
            sets.forEach(d -> d.tables().values().forEach(t -> {
                if (t.kind() != null && !t.kind().isBlank()) {
                    out.add(t.kind());
                }
            }));
            return out;
        } catch (RuntimeException e) {
            return java.util.Set.of();
        }
    }

    private Loaded loadItem(Principal p, String id) {
        BiStore.Item item = reportItem(p, id);
        String body = bi.bodyFor(p, item);
        return load(p, item, body, body.equals(bi.publishedBody(item)));            // the published text reads with its publisher's grants; a draft only with the caller's own
    }

    private static boolean ok(List<ReportParser.Problem> problems) {
        return problems.stream().noneMatch(ReportParser.Problem::error);
    }

    // ---- the API -----------------------------------------------------------------------------------------------------

    /** One report: its model, its problems and its dataset's model (tables, columns, measures, hierarchies) as this caller sees them. */
    public Map<String, Object> get(Principal p, String id) {
        Loaded l = loadItem(p, id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.item().id());
        m.put("title", l.item().title());
        m.put("description", l.item().description());
        m.put("status", l.item().status());
        m.put("shipped", com.ash.drishti.server.bi.BiFolder.isShipped(l.item().id()));
        m.put("sample", l.item().tags().contains(com.ash.drishti.server.bi.BiFolder.SAMPLE_TAG));
        List<ReportParser.Problem> all = new ArrayList<>(l.problems());
        TileCheck tc = tiles == null || l.parsed().report() == null || !l.parsed().report().hasBoard() ? null : tiles.apply(p, l.parsed().report());
        if (tc != null) {
            all.addAll(tc.problems());                                       // board pages (R7): what each tile names, checked for this caller
        }
        if (themes != null && l.parsed().report() != null) {
            all.addAll(themes.problems(p, l.parsed().report()));
        }
        m.put("valid", ok(all));
        m.put("problems", all.stream().map(ReportParser.Problem::toMap).toList());
        if (l.parsed().report() != null) {
            m.put("report", l.parsed().report().toMap());
        }
        if (l.datasetItem() != null && l.dataset() != null) {
            m.put("dataset", datasetSummary(p, l.datasetItem()));
            if (l.sets().all().size() > 1) {                                    // a report of several datasets: the Fields pane needs each one's model
                Map<String, Object> others = new LinkedHashMap<>();
                l.sets().all().forEach((ref, e) -> {
                    if (!ref.equals(l.parsed().report().dataset()) && !l.sets().same(ref, l.parsed().report().dataset())) {
                        others.put(ref, datasetSummary(p, e.item()));
                    }
                });
                m.put("datasets", others);
            }
        }
        if (tc != null && !tc.resolved().isEmpty()) {
            m.put("tiles", tc.resolved());                                       // by page/tile: the report item and page each tile's visual is on, its type, live
        }
        m.put("theme", themes == null ? null : themes.resolve(p, l.parsed().report() == null ? Map.of() : l.parsed().report().theme()));
        m.put("mayEdit", bi.mayAuthor(p));
        return m;
    }

    /** What the tiles of a report's board pages name, for one caller: by {@code <page>/<tile>} the report and page they resolve to, and the problems (report paths). */
    public record TileCheck(Map<String, Object> resolved, List<ReportParser.Problem> problems) {}

    /** Resolves the tiles of a report's board pages for a caller ({@link com.ash.drishti.server.bi.board.BiBoardService} registers it). */
    private java.util.function.BiFunction<Principal, Report, TileCheck> tiles;
    private com.ash.drishti.server.bi.theme.ThemeService themes;

    public void tileResolver(java.util.function.BiFunction<Principal, Report, TileCheck> resolver) {
        this.tiles = resolver;
    }

    public void themes(com.ash.drishti.server.bi.theme.ThemeService themes) {
        this.themes = themes;
    }

    /** A report the caller may read, parsed, for the export and subscription code that asks each visual itself; refused as {@code DRS-9031} when it cannot be read. */
    public record Opened(String id, String title, Report report) {}

    public Opened open(Principal p, String id) {
        Loaded l = loadItem(p, id);
        if (l.parsed().report() == null || !ok(l.problems())) {
            String why = l.problems().stream().filter(ReportParser.Problem::error).findFirst().map(x -> x.code() + " " + x.path() + ": " + x.message()).orElse("the report cannot be read");
            throw new DrishtiException(ErrorCode.REPORT_DATASET, "the report has errors, so it cannot be exported: " + why);
        }
        String t = l.parsed().report().title();
        return new Opened(l.item().id(), t == null || t.isBlank() ? l.item().title() : t, l.parsed().report());
    }

    /** A report as a statement reads it (2.6.0): its id (null for draft YAML), title, parsed form and its model's parameters (empty on a dataset). */
    public record Statement(String id, String title, Report report, Map<String, com.ash.drishti.server.bi.layer.ModelDef.Parameter> params) {}

    /**
     * The stored report {@code id} the caller may read, or (an author's preview) the draft {@code yaml}, for a statement; refused with its first error when it
     * cannot be drawn, as an export is.
     */
    public Statement statement(Principal p, String id, String yaml) {
        Loaded l;
        if (yaml != null) {
            bi.requirePower(p, BiAccess.AUTHOR);
            l = load(p, null, yaml, false);
        } else {
            l = loadItem(p, id);
        }
        if (l.parsed().report() == null || !ok(l.problems())) {
            String why = l.problems().stream().filter(ReportParser.Problem::error).findFirst().map(x -> x.code() + " " + x.path() + ": " + x.message()).orElse("the report cannot be read");
            throw new DrishtiException(ErrorCode.REPORT_DATASET, "the report has errors, so its statement cannot be made: " + why);
        }
        String t = l.parsed().report().title();
        return new Statement(l.item() == null ? null : l.item().id(), t == null || t.isBlank() ? (l.item() == null ? l.parsed().report().name() : l.item().title()) : t,
                l.parsed().report(), l.params());
    }

    /** The model's engine dataset with the report's own measures added (what the visuals are checked and drawn against). */
    private static Dataset withLocal(Dataset ds, List<Report.LocalMeasure> local) {
        if (local.isEmpty()) {
            return ds;
        }
        Map<String, Dataset.Measure> measures = new LinkedHashMap<>(ds.measures());
        local.forEach(x -> measures.put(x.name(), new Dataset.Measure(x.name(), x.expr(), x.format(), x.description() == null ? "the report's own measure" : x.description(), null)));
        return ds.withMeasures(measures);
    }

    private Map<String, Object> datasetSummary(Principal p, BiStore.Item dsItem) {
        if (DatasetService.isModelRef(dsItem)) {
            return datasets.describeModel(p, dsItem.id(), DatasetService.consumerOf(dsItem), DatasetService.perspectiveOf(dsItem));
        }
        Map<String, Object> d = new LinkedHashMap<>(datasets.modelOf(p, dsItem));
        d.remove("layers");
        d.remove("security");
        d.remove("rollups");
        return d;
    }

    /**
     * The dataset a report names, as the designer's Fields pane and the authoring hooks need it (authors). A model of the model layer ({@code name@major}, its
     * item id, or {@code dataset:<name>}) answers its describe, as the caller reads it (masked fields marked, hidden ones absent); a {@code rachana: 2}
     * dataset answers its implicit model's.
     */
    public Map<String, Object> datasetModel(Principal p, String ref) {
        bi.requirePower(p, BiAccess.AUTHOR);
        String model = layerRef(p, ref);
        if (model != null) {
            return datasets.describeModel(p, model);
        }
        BiStore.Item found;
        try {
            found = datasetFor(p, ref, false);
        } catch (DrishtiException e) {
            try {
                return datasets.describeModel(p, ref);                         // a model's name without a major: its latest version
            } catch (DrishtiException notModel) {
                throw e;
            }
        }
        String body = bi.bodyFor(p, found);
        if (com.ash.drishti.server.bi.layer.DatasetDefParser.isVersion2(body)) {
            com.ash.drishti.server.bi.layer.DatasetDef d = com.ash.drishti.server.bi.layer.DatasetDefParser.parse(body).dataset();
            if (d != null) {
                return datasets.describeModel(p, com.ash.drishti.server.bi.layer.ImplicitModels.QUERY_PREFIX + d.name());
            }
        }
        return datasetSummary(p, found);
    }

    /** The model reference {@code ref} is, when it is one: {@code name@range}, {@code dataset:<name>}, or the id of a model item the caller reads; else null. */
    public String layerRef(Principal p, String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        if (ref.startsWith(com.ash.drishti.server.bi.layer.ImplicitModels.QUERY_PREFIX) || ref.contains("@")) {
            return ref;
        }
        try {
            BiStore.Item i = bi.readableItem(p, ref);
            return "model".equals(i.kind()) ? i.id() : null;
        } catch (DrishtiException e) {
            return null;
        }
    }

    /** Checks report YAML without storing it: the problems with their codes, the model, and (when {@code quality}) the accessibility and layout warnings. */
    public Map<String, Object> validate(Principal p, String yaml, boolean quality) {
        bi.requirePower(p, BiAccess.AUTHOR);
        Loaded l = load(p, null, yaml, false);
        List<ReportParser.Problem> problems = new ArrayList<>(l.problems());
        if (quality && l.parsed().report() != null) {
            problems.addAll(ReportValidator.accessibility(l.parsed().report()));
        }
        if (tiles != null && l.parsed().report() != null && l.parsed().report().hasBoard()) {
            problems.addAll(tiles.apply(p, l.parsed().report()).problems());
        }
        if (themes != null && l.parsed().report() != null) {
            problems.addAll(themes.problems(p, l.parsed().report()));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("valid", ok(problems));
        m.put("problems", problems.stream().map(ReportParser.Problem::toMap).toList());
        if (l.parsed().report() != null) {
            m.put("report", l.parsed().report().toMap());
        }
        return m;
    }

    /** The messages of the errors that stop a stored report being drawn (its dataset is missing, a visual names a field that is gone); empty when it draws. */
    public List<String> errors(Principal p, BiStore.Item item) {
        Loaded l = load(p, item, item.body(), false);
        List<String> out = new ArrayList<>(l.problems().stream().filter(ReportParser.Problem::error).map(ReportParser.Problem::message).toList());
        if (l.parsed().report() != null && l.parsed().report().model() != null && out.isEmpty()) {
            out.addAll(datasets.modelPublishProblems(p, l.parsed().report().model()));          // readers reach a published model only, which passed its safeguard
        }
        return out;
    }

    /** Writes a report model (the structure {@link Report#toMap} has) as YAML. */
    public Map<String, Object> format(Principal p, JsonNode model) {
        bi.requirePower(p, BiAccess.BI);
        if (model == null || !model.isObject()) {
            throw new DrishtiException(ErrorCode.REPORT_INVALID, "send the report as an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = JSON.convertValue(model, LinkedHashMap.class);
        return Map.of("yaml", ReportYaml.write(m));
    }

    /** The format schemas per visual family (FormatSchemas): types, families, their sections and properties. */
    public Map<String, Object> formatSchemas(Principal p) {
        bi.requirePower(p, BiAccess.BI);
        return FormatSchemas.get().toMap();
    }

    /** The visual catalogue: types, their groups and wells. */
    public Map<String, Object> catalogue(Principal p) {
        bi.requirePower(p, BiAccess.BI);
        return Map.of("visuals", VisualCatalogue.toMap(), "reserved", VisualCatalogue.RESERVED.stream().sorted().toList(), "slicerModes", VisualCatalogue.SLICER_MODES.stream().sorted().toList(),
                "families", FormatSchemas.get().families());                    // a type's format family: what a theme's visual defaults are keyed by
    }

    /**
     * Runs one visual of a stored report ({@code yaml} null) or of draft YAML ({@code yaml} given; authors only) under the page's state.
     *
     * @return the engine's JSON answer plus {@code highlight} (the same query under the highlighted marks, or null), {@code query} (what was asked of the
     *     dataset), {@code mq} (the model query it ran as, canonical) and {@code visual}
     */
    public Map<String, Object> query(Principal p, String id, String yaml, String pageId, String visualId, JsonNode stateJson, boolean explain) {
        Prepared pre = prepare(p, id, yaml, pageId, visualId, stateJson);
        boolean author = bi.mayAuthor(p);
        Map<String, Object> live = liveOf(p, pre);                  // read before the query: rows that change meanwhile are above its seq, so a client asking "since" misses none
        VisualFit.Fitted fitted = VisualCatalogue.DISTRIBUTIONS.contains(pre.visual().type()) || pre.visual().type().startsWith("perspective-")
                ? new VisualFit.Fitted(datasets.asked(p, pre.dsItem(), pre.built().query(), explain && author), pre.built().query(), null, null, null, false)
                : VisualFit.fit(pre.visual(), pre.dataset(), pre.built().query(), q -> datasets.asked(p, pre.dsItem(), q, explain && author));
        DatasetService.Asked asked = fitted.asked();               // never cut silently: a time series by period, any other with its count in all
        QueryService.Result r = shortNames(pre, asked.result());
        Map<String, Object> out = new LinkedHashMap<>(VisualCatalogue.DISTRIBUTIONS.contains(pre.visual().type()) ? distribution(pre, r) : datasets.json(r));
        if (pre.built().highlight() != null && fitted.period() == null) {
            out.put("highlight", datasets.json(datasets.ask(p, pre.dsItem(), pre.built().highlight(), false)));
        } else {
            out.put("highlight", null);
        }
        out.put("visual", pre.visual().id());
        if (pre.resolved()) {
            out.put("wells", pre.visual().wells());                 // a field parameter's choice in place of param.<name>: what the client draws (2.6.0)
        }
        out.put("query", describe(fitted.query()));
        VisualFit.describe(fitted, out);
        out.put("mq", asked.query());                                // the model query the visual ran (docs/architecture/BI_MODEL_SPEC.md section 5.9)
        List<Map<String, Object>> totals = totals(p, pre);
        if (!totals.isEmpty()) {
            out.put("totals", totals);
        }
        if (live != null) {
            out.put("live", live);
        }
        return out;
    }

    /**
     * Every row of one table-like visual (a table, a matrix, a multi-row card) up to {@code maxRows} + 1, for a statement's band and an export's table: the
     * visual's query asked with that limit and answered past {@code drishti.bi.query.json-max-rows}, so nothing is cut at what one screen shows (QA2-01). The
     * row past {@code maxRows} tells the caller the table goes on, so it says so.
     */
    public Map<String, Object> queryRows(Principal p, String id, String yaml, String pageId, String visualId, JsonNode stateJson, int maxRows) {
        Prepared pre = prepare(p, id, yaml, pageId, visualId, stateJson);
        QueryRequest q0 = pre.built().query();
        int limit = Math.min(Math.max(1, maxRows) + 1, datasets.maxRows());
        QueryRequest q = new QueryRequest(q0.fields(), q0.measures(), q0.filters(), q0.from(), q0.to(), q0.orderBy(), limit, q0.layer(), q0.detail());
        DatasetService.Asked asked;
        String unsorted = null;
        try {
            asked = datasets.asked(p, pre.dsItem(), q, false);
        } catch (DrishtiException e) {
            if (q.orderBy() == null || q.orderBy().isEmpty() || e.getMessage() == null || !e.getMessage().contains("cannot be sorted on")) {
                throw e;
            }
            // sorted by a measure masked for this reader (a statement burst to an analyst, QA2-18): its order would tell the masked figures, so the rows are
            // drawn in the query's own order and the answer says so, rather than no rows at all
            q = new QueryRequest(q0.fields(), q0.measures(), q0.filters(), q0.from(), q0.to(), List.of(), limit, q0.layer(), q0.detail());
            asked = datasets.asked(p, pre.dsItem(), q, false);
            String why = e.getMessage().replaceFirst("^DRS-\\d+ ", "");
            unsorted = "Not sorted: " + why.replace(": it cannot be sorted on", "") + ", and its order would tell the masked figures.";
        }
        Map<String, Object> out = new LinkedHashMap<>(datasets.json(shortNames(pre, asked.result()), limit));
        if (unsorted != null) {
            out.put("unsorted", unsorted);
        }
        out.put("visual", pre.visual().id());
        out.put("query", describe(q));
        out.put("mq", asked.query());
        out.put("limit", limit);
        return out;
    }

    /** A histogram's bins or a box plot's boxes, worked out from the members the query returned; the answer has the shape of any other. */
    private Map<String, Object> distribution(Prepared pre, QueryService.Result r) {
        Report.Visual v = pre.visual();
        String measure = v.well("values").get(0);
        Distributions.Shape shape;
        if ("histogram".equals(v.type())) {
            Object bins = v.format().get("bins");
            shape = Distributions.histogram(r, measure, bins instanceof Number n ? n.intValue() : Distributions.DEFAULT_BINS);
        } else {
            shape = Distributions.box(r, v.well("axis").isEmpty() ? null : v.well("axis").get(0), measure);
        }
        Map<String, Object> meta = new LinkedHashMap<>(datasets.meta(r));
        meta.put("masked", r.meta().masked());
        meta.put("rows", shape.rows().size());
        meta.put("members", r.rows());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", shape.columns());
        out.put("rows", shape.rows());
        out.put("rowCount", shape.rows().size());
        out.put("meta", meta);
        return out;
    }

    /**
     * The true totals of a matrix with subtotals and of a table with {@code totals}: one query per level (see {@link Totals}), as the reader, under the same
     * filters. A level the engine refuses is left out and the client shows {@code –} for it.
     */
    private List<Map<String, Object>> totals(Principal p, Prepared pre) {
        Report.Visual v = pre.visual();
        List<Totals.Level> levels = "matrix".equals(v.type()) ? Totals.matrix(pre.dataset(), v, pre.built().query(), props.totalsMaxRows())
                : "table".equals(v.type()) ? Totals.table(pre.dataset(), v, pre.built().query(), props.totalsMaxRows()) : List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Totals.Level level : levels) {
            try {
                Map<String, Object> j = datasets.json(datasets.ask(p, pre.dsItem(), level.query(), false));
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("by", level.by());
                t.put("columns", ((List<?>) j.get("columns")).stream().map(c -> ((Map<?, ?>) c).get("name")).toList());
                t.put("masked", ((List<?>) j.get("columns")).stream().filter(c -> Boolean.TRUE.equals(((Map<?, ?>) c).get("masked"))).map(c -> ((Map<?, ?>) c).get("name")).toList());
                t.put("rows", j.get("rows"));
                out.add(t);
            } catch (DrishtiException e) {                           // a model query refusal is one too
                // the figure stays a dash on the screen; the visual's own answer is unaffected
            }
        }
        return out;
    }

    /** The rows of one visual as an Arrow IPC stream (for Perspective), with the live state in the headers' place: {@code meta} and {@code live}. */
    public record ArrowAnswer(byte[] bytes, Map<String, Object> meta, Map<String, Object> live) {}

    public ArrowAnswer queryArrow(Principal p, String id, String pageId, String visualId, JsonNode stateJson) {
        Prepared pre = prepare(p, id, null, pageId, visualId, stateJson);
        Map<String, Object> live = liveOf(p, pre);
        QueryService.Result r = shortNames(pre, datasets.ask(p, pre.dsItem(), pre.built().query(), false));
        return new ArrowAnswer(datasets.arrow(r), datasets.meta(r), live);
    }

    /** The state of the live tables behind a report (for the indicator and for a client that keeps rows), or null when the dataset has none. */
    private Map<String, Object> liveOf(Principal p, Prepared pre) {
        if (DatasetService.isModelRef(pre.dsItem()) || pre.dataset().tables().values().stream().noneMatch(Dataset.Table::isLive)) {
            return null;
        }
        return datasets.liveTick(p, pre.dsItem());
    }

    /**
     * A Perspective visual shows the rows of one table, so its columns are named as the table names them ({@code trader}, not {@code trade.trader}): the names
     * Perspective shows as headers and an update's rows are keyed by.
     */
    private static QueryService.Result shortNames(Prepared pre, QueryService.Result r) {
        if (!(pre.visual().type().startsWith("perspective-") || "python".equals(pre.visual().type())) || pre.built().query().fields().isEmpty()) {
            return r;
        }
        String first = pre.built().query().fields().get(0);
        String prefix = first.substring(0, Math.max(0, first.indexOf('.') + 1));
        if (prefix.isEmpty()) {
            return r;
        }
        List<com.ash.drishti.server.bi.engine.ArrowIpcWriter.Column> cols = r.columns().stream()
                .map(c -> c.name().startsWith(prefix) ? new com.ash.drishti.server.bi.engine.ArrowIpcWriter.Column(c.name().substring(prefix.length()), c.kind(), c.values()) : c).toList();
        QueryService.Meta m = r.meta();
        List<String> masked = m.masked().stream().map(x -> x.startsWith(prefix) ? x.substring(prefix.length()) : x).toList();
        return new QueryService.Result(cols, r.rows(), new QueryService.Meta(m.layer(), m.rollup(), m.rows(), m.millis(), m.cached(), m.dates(), m.tables(), masked, m.warnings(), m.sql(),
                m.generation()));
    }

    /** A visual's query, built and ready to run. */
    /** What the AI hooks need of one visual: its dataset (item, model and the reader's summary of it), the visual and the query it asks under the page's state. */
    public record Base(BiStore.Item dsItem, Dataset dataset, Map<String, Object> model, Report.Visual visual, QueryRequest query) {}

    public Base base(Principal p, String id, String pageId, String visualId, JsonNode stateJson) {
        return base(p, id, null, pageId, visualId, stateJson);
    }

    /** As above for a draft held as text ({@code yaml}, an author's), else the stored report. */
    public Base base(Principal p, String id, String yaml, String pageId, String visualId, JsonNode stateJson) {
        Prepared pre = prepare(p, id, yaml, pageId, visualId, stateJson);
        return new Base(pre.dsItem(), pre.dataset(), datasetSummary(p, pre.dsItem()), pre.visual(), pre.built().query());
    }

    /** The dataset a stored report reads, for "ask the data" (read with the report's grants, queried as the caller). */
    public Base target(Principal p, String id) {
        return target(p, id, null);
    }

    /** As above for a draft held as text ({@code yaml}, an author's), else the stored report. */
    public Base target(Principal p, String id, String yaml) {
        if (yaml != null) {
            bi.requirePower(p, BiAccess.AUTHOR);
        }
        Loaded l = yaml != null ? load(p, null, yaml, false) : loadItem(p, id);
        if (l.dataset() == null || !ok(l.problems())) {
            throw new DrishtiException(ErrorCode.REPORT_DATASET, "the report has errors, so its dataset cannot be asked");
        }
        return new Base(l.datasetItem(), l.dataset(), datasetSummary(p, l.datasetItem()), null, null);
    }

    /** One visual ready to ask: {@code visual} with any field parameter's choice in place ({@code resolved} when one was), and the dataset item it asks. */
    private record Prepared(Report report, Report.Visual visual, BiStore.Item dsItem, Dataset dataset, VisualQueries.Built built, boolean resolved) {}

    private Prepared prepare(Principal p, String id, String yaml, String pageId, String visualId, JsonNode stateJson) {
        Loaded l;
        if (yaml != null) {
            bi.requirePower(p, BiAccess.AUTHOR);
            l = load(p, null, yaml, false);
        } else {
            l = loadItem(p, id);
        }
        Report report = l.parsed().report();
        if (report == null || l.dataset() == null) {
            String why = l.problems().stream().filter(ReportParser.Problem::error).findFirst().map(x -> x.code() + " " + x.path() + ": " + x.message()).orElse("the report cannot be read");
            throw new DrishtiException(ErrorCode.REPORT_DATASET, "the report has errors, so it cannot be drawn: " + why);
        }
        Report.Page page = report.page(pageId);
        if (page == null) {
            throw new DrishtiException(ErrorCode.REPORT_STATE, "the report has no page '" + pageId + "'");
        }
        Report.Visual visual = page.visual(visualId);
        if (visual == null) {
            throw new DrishtiException(ErrorCode.REPORT_STATE, "page '" + pageId + "' has no visual '" + visualId + "'");
        }
        String blocked = blocking(l, report, page, visual);
        if (blocked != null) {
            throw new DrishtiException(ErrorCode.REPORT_DATASET, "this visual has errors, so it cannot be drawn (the others are not affected): " + blocked);
        }
        ReportState state = relative(report, ReportState.of(stateJson));
        DatasetSet.Entry own = l.of(visual) == null ? new DatasetSet.Entry(l.datasetItem(), l.dataset()) : l.of(visual);       // the visual's own dataset, else the report's
        Report.Visual drawn = FieldParams.resolve(visual, l.params(), state.params());     // the reader's field-parameter choices (2.6.0)
        BiStore.Item dsItem = DatasetService.withParams(own.item(), state.params());        // and their what-if values, as MQ v2 params
        VisualQueries.Built built = VisualQueries.build(report, page, drawn, state, own.dataset(), props, (field, n, by, desc, context, from, to) -> top(p, dsItem, field, n, by, desc, context, from, to),
                l.sets());
        return new Prepared(report, drawn, dsItem, own.dataset(), built, drawn != visual);
    }

    /**
     * The first error that stops this one visual being drawn, else null: an error in the report as a whole, or in this visual (or its page's filters). An error in
     * another visual never blanks this one - one bad visual is one error card.
     */
    private static String blocking(Loaded l, Report report, Report.Page page, Report.Visual visual) {
        int pi = report.pages().indexOf(page);
        int vi = page.visuals().indexOf(visual);
        String mine = "pages[" + pi + "].visuals[" + vi + "]";
        String ofPage = "pages[" + pi + "]";
        String own = report.datasetOf(visual);
        return l.problems().stream().filter(ReportParser.Problem::error).filter(x -> {
            String path = x.path() == null ? "" : x.path();
            if (path.startsWith("pages[")) {
                return path.equals(mine) || path.startsWith(mine + ".") || path.startsWith(mine + "[") || path.startsWith(ofPage + ".filters");
            }
            return !x.message().startsWith("visual dataset '") || x.message().startsWith("visual dataset '" + own + "'");
        }).findFirst().map(x -> x.code() + " " + x.path() + ": " + x.message()).orElse(null);
    }

    /** Works out the relative-date slicers of the report under the state ({@link RelativeDates#apply}). */
    ReportState relative(Report report, ReportState state) {
        return RelativeDates.apply(report, state, datasets::currentDate, datasets.calendar());
    }

    private volatile com.ash.drishti.server.bi.python.PythonItems pythonItems;

    /** Wired once at start: where a python visual finds its item. */
    public void pythonItems(com.ash.drishti.server.bi.python.PythonItems items) {
        this.pythonItems = items;
    }

    /**
     * What the console's Python runner needs to run one python visual (RUPAKA.md section 20.8): the Python item (manifest and source, as the caller reads it),
     * the Python measure it shows (its name and the columns it reads and groups by, as the table names them) or null, the visual's parameters, and where it
     * may run. The rows come from the same visual query as any other (as an Arrow stream, masked for this caller).
     */
    public Map<String, Object> pythonOf(Principal p, String id, String yaml, String pageId, String visualId) {
        Loaded l;
        if (yaml != null) {
            bi.requirePower(p, BiAccess.AUTHOR);
            l = load(p, null, yaml, false);
        } else {
            l = loadItem(p, id);
        }
        Report report = l.parsed().report();
        Report.Page page = report == null ? null : report.page(pageId);
        Report.Visual visual = page == null ? null : page.visual(visualId);
        if (visual == null || !"python".equals(visual.type()) || l.dataset() == null) {
            throw new DrishtiException(ErrorCode.REPORT_STATE, "page '" + pageId + "' has no python visual '" + visualId + "'");
        }
        Dataset visualDataset = l.of(visual) == null ? l.dataset() : l.of(visual).dataset();
        if (!ok(l.problems())) {
            throw new DrishtiException(ErrorCode.REPORT_INVALID, "the report has errors: " + l.problems().stream().filter(ReportParser.Problem::error).findFirst().map(ReportParser.Problem::message).orElse(""));
        }
        List<String> values = visual.well("values");
        Dataset.Measure pm = values.isEmpty() ? null : visualDataset.measures().get(values.get(0));
        String ref = pm != null && pm.python() != null ? pm.python() : String.valueOf(visual.format().get("python"));
        Map<String, Object> out = new LinkedHashMap<>(pythonItems.describe(p, ref, true));
        if (pm != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", pm.name());
            m.put("inputs", pm.inputs());
            m.put("by", pm.by());
            out.put("measure", m);
        } else {
            out.put("measure", null);
        }
        Object params = visual.format().get("params");
        out.put("params", params instanceof Map<?, ?> ? params : Map.of());
        Object run = visual.format().get("run");
        out.put("run", run == null ? "auto" : String.valueOf(run));
        out.put("visual", visual.id());
        out.put("title", visual.title());
        return out;
    }

    /** True when the report's dataset has a live table (its visuals update in place); nothing is started. */
    public boolean isLive(Principal p, String id) {
        Loaded l = loadItem(p, id);
        return l.dataset() != null && l.dataset().tables().values().stream().anyMatch(Dataset.Table::isLive);
    }

    /**
     * Opens what a stream of a report needs: the dataset's live tables are started when they are not running, and the returned supplier reads their state (cheap:
     * it does not read the report again). {@code null} for a report whose dataset has no live table. The caller's right to read the report is checked here, once.
     */
    public java.util.function.Supplier<Map<String, Object>> liveWatch(Principal p, String id) {
        Loaded l = loadItem(p, id);
        if (l.datasetItem() == null || l.dataset() == null) {
            throw new DrishtiException(ErrorCode.REPORT_DATASET, "the report's dataset cannot be found, so it has no live state");
        }
        if (DatasetService.isModelRef(l.datasetItem()) || l.dataset().tables().values().stream().noneMatch(Dataset.Table::isLive)) {
            return null;                                                         // a report on a model follows its live data through standing queries (W3)
        }
        return datasets.liveWatch(p, l.datasetItem());
    }

    private List<Object> top(Principal p, BiStore.Item dsItem, String field, int n, String by, boolean desc, List<QueryRequest.Filter> context, LocalDate from, LocalDate to) {
        QueryRequest q = new QueryRequest(List.of(field), List.of(by), context, from, to, List.of(new QueryRequest.Sort(by, desc)), Math.min(n, props.topCandidates()), "auto");
        QueryService.Result r = datasets.ask(p, dsItem, q, false);
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < r.rows(); i++) {
            values.add(r.columns().get(0).values()[i]);
        }
        return values;
    }

    /** The query in words, for the designer's preview and for people checking what a visual asked. */
    static Map<String, Object> describe(QueryRequest q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fields", q.fields());
        m.put("measures", q.measures());
        m.put("filters", q.filters().stream().map(f -> f.field() + " " + f.op().toUpperCase(Locale.ROOT) + " " + f.values()).toList());
        m.put("orderBy", q.orderBy().stream().map(s -> s.by() + (s.desc() ? " desc" : "")).toList());
        m.put("limit", q.limit());
        return m;
    }
}
