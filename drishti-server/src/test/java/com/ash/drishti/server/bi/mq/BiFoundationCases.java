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

import com.ash.drishti.server.bi.Layered;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.engine.BiLiveProperties;
import com.ash.drishti.server.bi.engine.DuckDbEngine;
import com.ash.drishti.server.bi.engine.Layers;
import com.ash.drishti.server.bi.engine.QueryProperties;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.layer.ModelDef;
import com.ash.drishti.server.bi.pivot.BiAggregate;
import com.ash.drishti.server.bi.report.BiReportProperties;
import com.ash.drishti.server.bi.report.Report;
import com.ash.drishti.server.bi.report.ReportParser;
import com.ash.drishti.server.bi.report.ReportState;
import com.ash.drishti.server.bi.report.VisualQueries;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The cases of the one parity proof of 2.9.11.e (docs/architecture/BI_DESIGN.md section 5): what BI answers today for every
 * shipped model and report, every person and every date, recorded by {@link BiFoundationRecorder} on the tree before anything moves and
 * compared by {@link BiFoundationParityTest} on every later commit. It extends what {@code BiParityRecorder} (the totals of one test dataset,
 * 2.9.11.b) does, from totals to every answer and every person.
 *
 * <p><b>Compiles and runs on the unchanged tree</b> (develop before 2.9.11.e): it uses only what BI has there and keeps after the switch's
 * rename of its internals is mapped (the model query service {@link MqService#ask}, the model harness {@link MqHarness}, the sample lake
 * {@link SampleLake}, {@link VisualQueries} for a report's visuals, {@link QueryService#query}, {@link BiAggregate} for a matrix's totals,
 * and {@link DatasetParser} for the two live originals); nothing of drishti-tables. The switch maps these calls onto the layer (the live
 * originals' reports onto the converted live models).
 *
 * <ul>
 *   <li><b>Models</b>: every shipped model of {@code config/bi/models} × every person × (the grand total; each hierarchy level alone) × three
 *       times (the later business date, the earlier, the two as a window): the rows of all its measures at once, and the totals.</li>
 *   <li><b>Rows each person sees</b>: model × alias × person: a {@code shape: rows} extract of the alias's columns (the set of rows hashed,
 *       their count, the masked columns).</li>
 *   <li><b>Subtotals, slicer, filter context</b> (2.9.11.d): per model and person, each hierarchy's first two levels with
 *       {@code totals: subtotals}, each measure; each measure under a slicer on the model's {@link Slice}; and {@code calc} with
 *       {@code ignore=ALL}, {@code ignore=[field]}, {@code where=} and {@code share_of} by the slice's field, without and with the slicer.</li>
 *   <li><b>Reports</b>: every shipped report (on a model, and the two live originals on a dataset, asked of a live query service) × page ×
 *       visual × person: the rows the visual's query gives (top-N ranked), its masked columns, the true totals of a matrix or a totalled
 *       table (BiAggregate) and a chart's highlight query; and each page's visuals again with its first list or dropdown slicer set (python
 *       visuals are the console's and are left out).</li>
 *   <li><b>Boards</b>: each tile of a board as the answer of the report visual it shows.</li>
 *   <li><b>Live replay</b>: {@code BiFoundationLive} (a deterministic tick sequence on the test lake's live table, answers at checkpoints).</li>
 *   <li><b>Items' own tests and golden questions</b>: none ship in {@code config/bi} today; the BI suites run unchanged on the new code
 *       (section 5.1), and a test added later is recorded by its suite.</li>
 * </ul>
 *
 * Values are kept as they compare: whole numbers exact, decimals exact ({@code dec:} and the plain digits), floating point to 1e-9 relative,
 * dates and texts exact, null only null, a refusal by its code.
 */
public final class BiFoundationCases {

    /** Where the fixtures live (the module's test resources): one JSON file per model, per report, and the live replay. */
    public static final Path FIXTURES = Path.of("src/test/resources/bi/foundation-parity");

    /** The shipped BI folder, read only. */
    static final Path BI = Path.of("..", "config", "bi");

    /** The shipped table layer (config/tables): the tables and models BI reads since the switch (docs/architecture/BI_DESIGN.md step 5), read only. */
    static final Path TABLES = Path.of("..", "config", "tables");

    /**
     * The seven persons of section 5.1: a desk-head with two desks, a desk-head with no desks (the strict {@code in}: no rows), risk, an
     * analyst, an administrator, a reader without the raw power (masks) and a role no rule names ({@code DRS-9131}: no rows and the reason).
     */
    static List<Map.Entry<String, MqHarness.Reader>> persons(SampleLake lake) {
        String two = lake.first("desk") + "," + lake.first("desk").replace("-0", "-1");
        return List.of(Map.entry("desk-head-two-desks", new MqHarness.Reader("hana", true, two, List.of("desk-head"))),
                Map.entry("desk-head-no-desks", new MqHarness.Reader("dina", true, "", List.of("desk-head"))),
                Map.entry("risk", MqHarness.RITA),
                Map.entry("analyst", MqHarness.ANN),
                Map.entry("admin", new MqHarness.Reader("ada", true, "", List.of("admin"))),
                Map.entry("no-raw-power", new MqHarness.Reader("nora", false, "", List.of("risk"))),
                Map.entry("unnamed-role", new MqHarness.Reader("ulla", true, "", List.of("intern"))));
    }

    /** The three times of section 5.1: the later business date, the earlier one (the day before, for as_of), and both as a window. */
    static final Map<String, String> TIMES = Map.of("as-of-day2", "time: { as-of: " + SampleLake.DAY2 + " }", "as-of-day1",
            "time: { as-of: " + SampleLake.DAY1 + " }", "window", "time: { window: { from: " + SampleLake.DAY1 + ", to: " + SampleLake.DAY2 + " } }");

    private BiFoundationCases() {}

    /** The shipped models with their views, the datasets they read, and the sample lake over every kind they read. */
    /**
     * The shipped models with their views, the {@code rachana: 1} datasets a report on a dataset reads (the two live originals), the sample lake over
     * every kind they read, and what could not be loaded.
     */
    record Shipped(Map<String, ModelView> models, Map<String, Dataset> direct, SampleLake lake, Map<String, String> problems) {}

    static Shipped shipped() throws IOException {
        Map<String, String> datasets = new LinkedHashMap<>();
        Map<String, Dataset> direct = new TreeMap<>();
        Map<String, String> problems = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> s = Files.walk(TABLES)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path f : files) {
            if (f.toString().endsWith(".table.yaml")) {
                datasets.put(f.getFileName().toString(), Files.readString(f));
            }
        }
        Map<String, ModelView> models = new TreeMap<>();
        for (Path f : files) {
            if (!f.toString().endsWith(".model.yaml")) {
                continue;
            }
            String y = Files.readString(f);
            String name = Layered.modelDef(y).model() == null ? f.getFileName().toString() : Layered.modelDef(y).model().name();
            try {
                models.put(name, MqHarness.model(y, k -> Optional.empty(), datasets.values().toArray(String[]::new)));
            } catch (RuntimeException e) {
                problems.put(name, "not loaded: " + e.getMessage());
            }
        }
        List<Dataset> flats = new ArrayList<>(models.values().stream().map(ModelView::flat).filter(d -> !d.framed()).toList());
        return new Shipped(models, direct, new SampleLake(flats), problems);
    }

    /** A query service over the sample lake, snapshots only (the live replay has its own). */
    static QueryService service(Path store, SampleLake lake) {
        QueryProperties props = QueryProperties.defaults(store.toString());
        BiLiveProperties live = new BiLiveProperties(false, null, null, null, null, null, null, null, null, null, null, null);
        return new QueryService(props, live, new DuckDbEngine(props), lake, Clock.systemUTC());
    }

    /**
     * A query service for the reports on a live dataset (the live originals): live tables loaded from the sample lake at their first question,
     * no ticks, the clock fixed, so the answers are the table as loaded.
     */
    static QueryService liveService(Path store, SampleLake lake) throws IOException {
        Files.createDirectories(store);
        QueryProperties props = QueryProperties.defaults(store.toString());
        BiLiveProperties live = BiLiveProperties.defaults().fast(java.time.Duration.ofMillis(20), java.time.Duration.ofHours(1));
        return new QueryService(props, live, new DuckDbEngine(props), lake, Clock.fixed(java.time.Instant.parse("2026-10-09T10:00:00Z"), java.time.ZoneOffset.UTC));
    }

    /**
     * Every case of one model, per person: each measure alone (a model with several facts refuses a field one fact does not reach, so all
     * measures at once would record refusals), on the later date by no field and by each hierarchy level with its grand total, and on the
     * earlier date and the window by no field; then each alias's rows.
     */
    static Map<String, Object> model(QueryService service, ModelView v, SampleLake lake, MqService mq) {
        Map<String, Object> out = new TreeMap<>();
        List<String> measures = v.def().measures().values().stream().filter(m -> m.python() == null).map(ModelDef.Meas::name).toList();
        List<List<String>> groupings = new ArrayList<>();
        groupings.add(List.of());
        v.def().hierarchies().values().forEach(levels -> levels.forEach(l -> {
            if (!groupings.contains(List.of(l))) {
                groupings.add(List.of(l));
            }
        }));
        Slice slice = slice(service, v, mq, measures);
        for (Map.Entry<String, MqHarness.Reader> p : persons(lake)) {
            Map<String, Object> mine = new TreeMap<>();
            for (String measure : measures) {
                for (Map.Entry<String, String> t : TIMES.entrySet()) {
                    for (List<String> by : t.getKey().equals("as-of-day2") ? groupings : List.of(List.<String>of())) {
                        String q = "{ mq: 1, model: x, select: { fields: " + list(by) + ", measures: " + list(List.of(measure))
                                + " }, " + t.getValue() + (by.isEmpty() ? "" : ", totals: grand") + " }";
                        mine.put(measure + " | " + t.getKey() + " | by " + (by.isEmpty() ? "(none)" : by.get(0)), answer(service, v, q, p.getValue(), mq));
                    }
                }
            }
            extras(service, v, p.getValue(), mq, measures, slice, mine);
            for (String alias : v.def().datasets().keySet()) {
                Dataset.Table table = v.flat().tables().get(alias);
                List<String> cols = table == null ? List.of() : table.columns().keySet().stream().map(c -> alias + "." + c).sorted().toList();
                if (cols.isEmpty()) {
                    continue;
                }
                String q = "{ mq: 1, model: x, select: { fields: " + list(cols) + " }, shape: rows, limit: 100000 }";
                mine.put("rows of " + alias, extract(answer(service, v, q, p.getValue(), mq)));
            }
            out.put(p.getKey(), mine);
        }
        return out;
    }

    /**
     * The field the slicer, filter and calc cases of a model cut on and the value they keep: the first hierarchy level that is a text column,
     * else the model's first text column that is not a key, whose grouped answer as the administrator (on the later date) has a value.
     * Null when the model has none (no case is recorded then, and the index says so).
     */
    record Slice(String field, String value) {}

    static Slice slice(QueryService service, ModelView v, MqService mq, List<String> measures) {
        List<String> candidates = new ArrayList<>();
        v.def().hierarchies().values().forEach(levels -> levels.stream().filter(l -> text(v, l)).findFirst().filter(l -> !candidates.contains(l))
                .ifPresent(candidates::add));
        v.flat().tables().forEach((alias, t) -> t.columns().values().stream().filter(c -> "string".equals(c.type()) && !c.key())
                .map(c -> alias + "." + c.name()).filter(f -> !candidates.contains(f)).forEach(candidates::add));
        MqHarness.Reader admin = new MqHarness.Reader("ada", true, "", List.of("admin"));
        for (String f : candidates) {
            for (String m : measures) {
                Map<String, Object> a = answer(service, v, "{ mq: 1, model: x, select: { fields: " + list(List.of(f)) + ", measures: "
                        + list(List.of(m)) + " }, time: { as-of: " + SampleLake.DAY2 + " } }", admin, mq);
                if (a.get("rows") instanceof List<?> rows) {
                    for (Object r : rows) {
                        if (r instanceof List<?> row && !row.isEmpty() && row.get(0) instanceof String s && !s.isBlank()) {
                            return new Slice(f, s);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean text(ModelView v, String field) {
        int dot = field.indexOf('.');
        Dataset.Table t = dot < 0 ? null : v.flat().tables().get(field.substring(0, dot));
        Dataset.Column c = t == null ? null : t.columns().get(field.substring(dot + 1));
        return c != null && "string".equals(c.type());
    }

    /**
     * A model's cases beyond each measure alone, for one person on the later date: subtotals (each hierarchy's first two levels with
     * {@code totals: subtotals}: the grand total, the first level's subtotals and the detail, non-additive measures included); a slicer (each
     * measure under a filter on the slice's field); and the filter-context functions over the first additive measure ({@code calc} with
     * {@code ignore=ALL}, {@code ignore=[field]} and {@code where=}, and {@code share_of}) by the slice's field, without and with the slicer.
     */
    static void extras(QueryService service, ModelView v, MqHarness.Reader who, MqService mq, List<String> measures, Slice slice, Map<String, Object> mine) {
        String day2 = "time: { as-of: " + SampleLake.DAY2 + " }";
        for (Map.Entry<String, List<String>> h : v.def().hierarchies().entrySet()) {
            if (h.getValue().size() < 2) {
                continue;
            }
            List<String> two = h.getValue().subList(0, 2);
            for (String m : measures) {
                mine.put(m + " | subtotals | by " + String.join(", ", two), answer(service, v, "{ mq: 1, model: x, select: { fields: " + list(two)
                        + ", measures: " + list(List.of(m)) + " }, " + day2 + ", totals: subtotals }", who, mq));
            }
        }
        if (slice == null) {
            return;
        }
        String where = "where: [{ field: \"" + slice.field() + "\", op: in, values: [\"" + slice.value().replace("\"", "\\\"") + "\"] }]";
        for (String m : measures) {
            mine.put(m + " | slicer " + slice.field() + " = " + slice.value(), answer(service, v, "{ mq: 1, model: x, select: { measures: "
                    + list(List.of(m)) + " }, " + where + ", " + day2 + " }", who, mq));
        }
        String base = measures.stream().filter(m -> String.valueOf(v.def().measures().get(m).expr()).startsWith("sum(")).findFirst()
                .orElse(measures.isEmpty() ? null : measures.get(0));
        if (base == null) {
            return;
        }
        String ref = "measure('" + base.replace("'", "\\'") + "')";
        String define = "define: { \"Ignore all\": \"calc(" + ref + ", ignore=ALL)\", \"Ignore field\": \"calc(" + ref + ", ignore=[" + slice.field() + "])\", "
                + "\"Share\": \"share_of(" + ref + ", over=ALL)\", \"Only value\": \"calc(" + ref + ", where=" + slice.field() + " == '" + slice.value().replace("'", "\\'") + "')\" }";
        String select = "select: { fields: " + list(List.of(slice.field())) + ", measures: "
                + list(List.of(base, "Ignore all", "Ignore field", "Share", "Only value")) + " }";
        mine.put("calc " + base + " | by " + slice.field(), answer(service, v, "{ mq: 1, model: x, " + define + ", " + select + ", " + day2 + ", totals: grand }", who, mq));
        mine.put("calc " + base + " | by " + slice.field() + " | slicer = " + slice.value(), answer(service, v, "{ mq: 1, model: x, " + define + ", " + select + ", "
                + where + ", " + day2 + ", totals: grand }", who, mq));
    }

    /**
     * Every report's visuals, per person, on the later date: the rows each visual's own query gives (top-N filters ranked as the report
     * service ranks them), the masked columns, the true totals the report service adds (a matrix's grand total and subtotals, a table's
     * totals, through the pivot engine's {@code BiAggregate}) and the highlight query of a chart; then, per page with a list or dropdown
     * slicer, every other visual of the page with the first slicer set to its first value (the value the slicer's own query gives the
     * administrator). A python visual is the console's and is left out; a visual with no query (text, an entity view) says so.
     *
     * @param ds the dataset the visuals read (a model's flat dataset, or a report's own dataset)
     * @param key the loaded key the queries run under
     * @param caller the reader as the query service's caller
     */
    static Map<String, Object> report(QueryService service, Report report, Dataset ds, String key, String hash,
            Function<MqHarness.Reader, QueryService.Caller> caller, SampleLake lake) {
        Map<String, Object> out = new TreeMap<>();
        QueryService.Loaded loaded = new QueryService.Loaded(key, Layers.key(key), ds, hash);
        Map<String, ReportState> slicers = slicerStates(service, report, ds, loaded, caller.apply(new MqHarness.Reader("ada", true, "", List.of("admin"))));
        for (Map.Entry<String, MqHarness.Reader> p : persons(lake)) {
            Map<String, Object> mine = new TreeMap<>();
            QueryService.Caller who = caller.apply(p.getValue());
            for (Report.Page page : report.pages()) {
                for (Report.Visual visual : page.visuals()) {
                    if (visual.type() != null && visual.type().startsWith("python")) {
                        continue;                                           // the console runs a python visual
                    }
                    mine.put(page.id() + "/" + visual.id(), visual(service, report, page, visual, ReportState.NONE, ds, loaded, who));
                }
                ReportState sliced = slicers.get(page.id());
                if (sliced == null) {
                    continue;
                }
                Map.Entry<String, JsonNode> set = sliced.slicers().entrySet().iterator().next();
                for (Report.Visual visual : page.visuals()) {
                    if (visual.type() == null || visual.type().startsWith("python") || "slicer".equals(visual.type())) {
                        continue;
                    }
                    mine.put(page.id() + "/" + visual.id() + " | slicer " + set.getKey() + " = " + set.getValue().get(0).asText(),
                            visual(service, report, page, visual, sliced, ds, loaded, who));
                }
            }
            out.put(p.getKey(), mine);
        }
        return out;
    }

    /** One visual under a state, as one reader: rows, masked, totals and highlight; or the refusal's code. */
    static Map<String, Object> visual(QueryService service, Report report, Report.Page page, Report.Visual visual, ReportState state, Dataset ds,
            QueryService.Loaded loaded, QueryService.Caller who) {
        BiReportProperties props = BiReportProperties.defaults();
        try {
            VisualQueries.Built b = VisualQueries.build(report, page, visual, state, ds, props, (field, n, by, desc, context, from, to) -> {
                QueryRequest q = new QueryRequest(List.of(field), List.of(by), context, from, to, List.of(new QueryRequest.Sort(by, desc)),
                        Math.min(n, props.topCandidates()), "auto");
                QueryService.Result r = service.query(loaded, q, who, false);
                List<Object> values = new ArrayList<>();
                for (int i = 0; i < r.rows(); i++) {
                    values.add(r.columns().get(0).values()[i]);
                }
                return values;
            });
            if (b.query() == null) {
                return Map.of("query", "none");
            }
            QueryService.Result r = service.query(loaded, b.query(), who, false);
            Map<String, Object> a = new TreeMap<>();
            a.put("rows", sorted(norm(MqService.rows(r))));
            a.put("masked", r.meta().masked() == null ? List.of() : r.meta().masked().stream().sorted().toList());
            List<Map<String, Object>> totals = totals(service, visual, ds, b.query(), loaded, who, props);
            if (!totals.isEmpty()) {
                a.put("totals", totals);
            }
            if (b.highlight() != null) {
                a.put("highlight", sorted(norm(MqService.rows(service.query(loaded, b.highlight(), who, false)))));
            }
            return a;
        } catch (DrishtiException e) {
            return Map.of("refused", e.errorCode().code());
        } catch (RuntimeException e) {
            return Map.of("failed", e.getClass().getSimpleName());
        }
    }

    /** The true totals the report service adds to a matrix with subtotals or a table with totals ({@code BiReportService#totals}), per level. */
    private static List<Map<String, Object>> totals(QueryService service, Report.Visual v, Dataset ds, QueryRequest query, QueryService.Loaded loaded,
            QueryService.Caller who, BiReportProperties props) {
        List<List<String>> levels;
        List<String> measures;
        if ("matrix".equals(v.type())) {
            levels = BiAggregate.matrixLevels(v.well("rows"), v.well("columns"));
            measures = v.well("values");
        } else if ("table".equals(v.type()) && v.format() != null && Boolean.TRUE.equals(v.format().get("totals"))) {
            levels = List.of(List.of());
            measures = v.well("columns").stream().filter(ds.measures()::containsKey).toList();
        } else {
            return List.of();
        }
        if (measures.isEmpty()) {
            return List.of();
        }
        try {
            BiAggregate.Totals t = BiAggregate.totals(ds, levels, measures, query, props.totalsMaxRows() * levels.size(), q -> service.query(loaded, q, who, false));
            List<Map<String, Object>> out = new ArrayList<>();
            for (BiAggregate.Level l : t.levels()) {
                Map<String, Object> level = new TreeMap<>();
                level.put("by", l.by());
                level.put("measures", l.measures());
                level.put("masked", l.masked() == null ? List.of() : l.masked().stream().sorted().toList());
                level.put("rows", sorted(norm(l.rows())));
                out.add(level);
            }
            if (!t.notTotalled().isEmpty()) {
                out.add(Map.of("notTotalled", List.copyOf(new TreeMap<>(t.notTotalled()).keySet())));
            }
            return out;
        } catch (DrishtiException e) {
            return List.of(Map.of("refused", e.errorCode().code()));
        }
    }

    /**
     * Per page, the state with its first list or dropdown slicer set to the first value that slicer's own query gives the administrator;
     * a page with no such slicer, or whose slicer has no value, is left out.
     */
    static Map<String, ReportState> slicerStates(QueryService service, Report report, Dataset ds, QueryService.Loaded loaded, QueryService.Caller admin) {
        Map<String, ReportState> out = new TreeMap<>();
        for (Report.Page page : report.pages()) {
            for (Report.Visual s : page.visuals()) {
                Object mode = s.format() == null ? null : s.format().get("mode");
                if (!"slicer".equals(s.type()) || mode != null && !List.of("list", "dropdown").contains(String.valueOf(mode))) {
                    continue;
                }
                Object first = null;
                try {
                    VisualQueries.Built b = VisualQueries.build(report, page, s, ReportState.NONE, ds, BiReportProperties.defaults(), (f, n, by, d, c, from, to) -> List.of());
                    if (b.query() != null) {
                        List<List<Object>> rows = sorted(norm(MqService.rows(service.query(loaded, b.query(), admin, false))));
                        first = rows.stream().map(r -> r.isEmpty() ? null : r.get(0)).filter(x -> x instanceof String t && !t.isBlank()).findFirst().orElse(null);
                    }
                } catch (RuntimeException e) {
                    first = null;
                }
                if (first == null) {
                    continue;
                }
                Map<String, JsonNode> set = new TreeMap<>(Map.of(s.id(), JsonNodeFactory.instance.arrayNode().add(String.valueOf(first))));
                out.put(page.id(), new ReportState(set, List.of(), List.of(), List.of(), null, null, null, null, null));
                break;
            }
        }
        return out;
    }

    /** Every report of config/bi (on a model, on a dataset, and the boards: reports whose pages are walls of tiles), parsed. */
    static Map<String, Report> reports() throws IOException {
        Map<String, Report> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(BI.resolve("reports"), 2)) {                  // and its domain folders
            for (Path f : s.filter(p -> p.toString().endsWith(".report.yaml")).sorted().toList()) {
                ReportParser.Result r = ReportParser.parse(Files.readString(f));
                if (r.report() != null) {
                    out.put(r.report().name(), r.report());
                }
            }
        }
        return out;
    }

    /** One MQ answered as a person: the rows (sorted), the totals, the masked columns and the no-access list; or the refusal's code. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> answer(QueryService service, ModelView v, String mqText, MqHarness.Reader who, MqService mq) {
        MqHarness h = new MqHarness(service, SampleLake.DAY2);
        Map<String, Object> out = new TreeMap<>();
        try {
            Map<String, Object> body = mq.ask(h.bind(v, who, "foundation:" + v.def().name()), MqParser.parse(mqText), false).body();
            out.put("rows", sorted(norm((List<List<Object>>) body.get("rows"))));
            Object totals = body.get("totals");
            if (totals instanceof List<?> l && !l.isEmpty()) {
                List<Object> ts = new ArrayList<>();
                for (Object t : l) {
                    Map<String, Object> m = (Map<String, Object>) t;
                    Map<String, Object> level = new TreeMap<>();
                    level.put("by", m.get("by"));
                    level.put("rows", sorted(norm((List<List<Object>>) m.get("rows"))));
                    ts.add(level);
                }
                out.put("totals", ts);
            }
            Map<String, Object> meta = (Map<String, Object>) body.get("meta");
            if (meta != null) {
                out.put("masked", meta.get("masked") instanceof List<?> ml ? ml.stream().map(String::valueOf).sorted().toList() : List.of());
                out.put("noAccess", meta.get("noAccess") == null ? List.of() : meta.get("noAccess"));
            }
        } catch (DrishtiException e) {
            out.put("refused", e.errorCode().code());
        }
        return out;
    }

    /** A rows extract kept small: its count, the hash of its sorted rows, the masked columns. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> extract(Map<String, Object> answer) {
        if (answer.containsKey("refused")) {
            return answer;
        }
        List<Object> rows = (List<Object>) answer.get("rows");
        Map<String, Object> out = new TreeMap<>();
        out.put("count", rows.size());
        out.put("sha256", sha(String.valueOf(rows)));
        out.put("masked", answer.get("masked"));
        return out;
    }

    /** Values as they compare (see the class comment). */
    static List<List<Object>> norm(List<List<Object>> rows) {
        List<List<Object>> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (List<Object> r : rows) {
            List<Object> row = new ArrayList<>();
            for (Object x : r) {
                row.add(value(x));
            }
            out.add(row);
        }
        return out;
    }

    static Object value(Object x) {
        return switch (x) {
            case null -> null;
            case BigDecimal d -> "dec:" + d.stripTrailingZeros().toPlainString();
            case Long l -> l;
            case Integer i -> (long) i;
            case Short s -> (long) s;
            case Double d -> d.isNaN() || d.isInfinite() ? "double:" + d : (Object) d;
            case Float f -> (double) f;
            case Boolean b -> b;
            case LocalDate d -> "date:" + d;
            default -> String.valueOf(x);
        };
    }

    private static List<List<Object>> sorted(List<List<Object>> rows) {
        List<List<Object>> out = new ArrayList<>(rows);
        out.sort(Comparator.comparing(String::valueOf));
        return out;
    }

    static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------------------------

    static ObjectMapper json() {
        return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    static void write(String file, Object content) throws IOException {
        Files.createDirectories(FIXTURES);
        Files.writeString(FIXTURES.resolve(file), json().writeValueAsString(content) + "\n");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(String file) throws IOException {
        return json().readValue(FIXTURES.resolve(file).toFile(), Map.class);
    }

    /**
     * The differences between a recorded value and a new one, each naming where ({@code path}) and both values: floating point to 1e-9
     * relative, everything else exactly.
     */
    static void compare(String path, Object want, Object got, List<String> problems) {
        if (want instanceof Map<?, ?> a && got instanceof Map<?, ?> b) {
            for (Object k : a.keySet()) {
                compare(path + "/" + k, a.get(k), b.get(k), problems);
            }
            b.keySet().stream().filter(k -> !a.containsKey(k)).forEach(k -> problems.add(path + "/" + k + ": new, not recorded"));
            return;
        }
        if (want instanceof List<?> a && got instanceof List<?> b) {
            if (a.size() != b.size()) {
                problems.add(path + ": " + b.size() + " now, " + a.size() + " recorded");
                return;
            }
            for (int i = 0; i < a.size(); i++) {
                compare(path + "[" + i + "]", a.get(i), b.get(i), problems);
            }
            return;
        }
        if (want instanceof Double x && got instanceof Number n) {
            double y = n.doubleValue();
            if (x.doubleValue() != y && Math.abs(x - y) > 1e-9 * Math.max(Math.abs(x), Math.abs(y))) {
                problems.add(path + ": " + got + " now, " + want + " recorded");
            }
            return;
        }
        if (want instanceof Number x && got instanceof Number y && !(want instanceof Double)) {
            if (x.longValue() != y.longValue() || got instanceof Double) {
                problems.add(path + ": " + got + " now, " + want + " recorded (exact)");
            }
            return;
        }
        if (want == null ? got != null : !want.equals(got)) {
            problems.add(path + ": " + got + " now, " + want + " recorded");
        }
    }

    /** Names as a YAML list of quoted texts. */
    static String list(List<String> names) {
        return "[" + String.join(", ", names.stream().map(n -> "\"" + n + "\"").toList()) + "]";
    }
}
