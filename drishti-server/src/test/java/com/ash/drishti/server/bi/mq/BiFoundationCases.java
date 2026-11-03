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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.engine.BiLiveProperties;
import com.ash.drishti.server.bi.engine.DuckDbEngine;
import com.ash.drishti.server.bi.engine.Layers;
import com.ash.drishti.server.bi.engine.QueryProperties;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.layer.DatasetDefParser;
import com.ash.drishti.server.bi.layer.ModelDef;
import com.ash.drishti.server.bi.layer.ModelDefParser;
import com.ash.drishti.server.bi.report.BiReportProperties;
import com.ash.drishti.server.bi.report.Report;
import com.ash.drishti.server.bi.report.ReportParser;
import com.ash.drishti.server.bi.report.ReportState;
import com.ash.drishti.server.bi.report.VisualQueries;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
import java.util.stream.Stream;

/**
 * The cases of the one parity proof of 2.9.11.e (docs/architecture/BI_FOUNDATION_2911E.md section 5): what BI answers today for every
 * shipped model and report, every person and every date, recorded by {@link BiFoundationRecorder} on the tree before anything moves and
 * compared by {@link BiFoundationParityTest} on every later commit. It extends what {@code BiParityRecorder} (the totals of one test dataset,
 * 2.9.11.b) does, from totals to every answer and every person.
 *
 * <p><b>Compiles and runs on the unchanged tree</b> (develop before 2.9.11.e): it uses only what BI has there and keeps after the switch's
 * rename of its internals is mapped (the model query service {@link MqService#ask}, the model harness {@link MqHarness}, the sample lake
 * {@link SampleLake}, {@link VisualQueries} for a report's visuals, {@link QueryService#query}); nothing of drishti-tables.
 *
 * <ul>
 *   <li><b>Models</b>: every shipped model of {@code config/bi/models} × every person × (the grand total; each hierarchy level alone) × three
 *       times (the later business date, the earlier, the two as a window): the rows of all its measures at once, and the totals.</li>
 *   <li><b>Rows each person sees</b>: model × alias × person: a {@code shape: rows} extract of the alias's columns (the set of rows hashed,
 *       their count, the masked columns).</li>
 *   <li><b>Reports</b>: every shipped report bound to a model × page × visual × person: the rows the visual's query gives (python visuals
 *       are the console's and are left out).</li>
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
    record Shipped(Map<String, ModelView> models, SampleLake lake, Map<String, String> problems) {}

    static Shipped shipped() throws IOException {
        Map<String, String> datasets = new LinkedHashMap<>();
        try (Stream<Path> s = Files.list(BI.resolve("datasets"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".dataset.yaml")).sorted().toList()) {
                String y = Files.readString(f);
                if (DatasetDefParser.isVersion2(y)) {
                    datasets.put(f.getFileName().toString(), y);
                }
            }
        }
        Map<String, ModelView> models = new TreeMap<>();
        Map<String, String> problems = new TreeMap<>();
        try (Stream<Path> s = Files.list(BI.resolve("models"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".model.yaml")).sorted().toList()) {
                String y = Files.readString(f);
                String name = ModelDefParser.parse(y).model() == null ? f.getFileName().toString() : ModelDefParser.parse(y).model().name();
                try {
                    models.put(name, MqHarness.model(y, k -> Optional.empty(), datasets.values().toArray(String[]::new)));
                } catch (RuntimeException e) {
                    problems.put(name, "not loaded: " + e.getMessage());
                }
            }
        }
        List<Dataset> flats = models.values().stream().map(ModelView::flat).filter(d -> !d.framed()).toList();
        return new Shipped(models, new SampleLake(flats), problems);
    }

    /** A query service over the sample lake, snapshots only (the live replay has its own). */
    static QueryService service(Path store, SampleLake lake) {
        QueryProperties props = QueryProperties.defaults(store.toString());
        BiLiveProperties live = new BiLiveProperties(false, null, null, null, null, null, null, null, null, null, null, null);
        return new QueryService(props, live, new DuckDbEngine(props), lake, Clock.systemUTC());
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
        for (Map.Entry<String, MqHarness.Reader> p : persons(lake)) {
            Map<String, Object> mine = new TreeMap<>();
            for (String measure : measures) {
                for (Map.Entry<String, String> t : TIMES.entrySet()) {
                    for (List<String> by : t.getKey().equals("as-of-day2") ? groupings : List.of(List.<String>of())) {
                        String q = "{ mq: 1, model: x, select: { fields: " + ConvertedSamplesTest.list(by) + ", measures: " + ConvertedSamplesTest.list(List.of(measure))
                                + " }, " + t.getValue() + (by.isEmpty() ? "" : ", totals: grand") + " }";
                        mine.put(measure + " | " + t.getKey() + " | by " + (by.isEmpty() ? "(none)" : by.get(0)), answer(service, v, q, p.getValue(), mq));
                    }
                }
            }
            for (String alias : v.def().datasets().keySet()) {
                Dataset.Table table = v.flat().tables().get(alias);
                List<String> cols = table == null ? List.of() : table.columns().keySet().stream().map(c -> alias + "." + c).sorted().toList();
                if (cols.isEmpty()) {
                    continue;
                }
                String q = "{ mq: 1, model: x, select: { fields: " + ConvertedSamplesTest.list(cols) + " }, shape: rows, limit: 100000 }";
                mine.put("rows of " + alias, extract(answer(service, v, q, p.getValue(), mq)));
            }
            out.put(p.getKey(), mine);
        }
        return out;
    }

    /** Every model-bound report's visuals, per person: the rows each visual's own query gives on the later date. */
    static Map<String, Object> report(QueryService service, Report report, ModelView v, SampleLake lake) {
        Map<String, Object> out = new TreeMap<>();
        BiReportProperties props = BiReportProperties.defaults();
        for (Map.Entry<String, MqHarness.Reader> p : persons(lake)) {
            Map<String, Object> mine = new TreeMap<>();
            MqHarness h = new MqHarness(service, SampleLake.DAY2);
            for (Report.Page page : report.pages()) {
                for (Report.Visual visual : page.visuals()) {
                    String key = page.id() + "/" + visual.id();
                    if (visual.type() != null && visual.type().startsWith("python")) {
                        continue;                                           // the console runs a python visual
                    }
                    try {
                        VisualQueries.Built b = VisualQueries.build(report, page, visual, ReportState.NONE, v.flat(), props, (f, n, by, desc, ctx, from, to) -> List.of());
                        if (b.query() == null) {
                            mine.put(key, Map.of("query", "none"));
                            continue;
                        }
                        String k = "report:" + report.name();
                        QueryService.Result r = service.query(new QueryService.Loaded(k, Layers.key(k), v.flat(), "h|" + v.def().name()), b.query(),
                                h.caller(v.flat(), p.getValue()), false);
                        Map<String, Object> a = new TreeMap<>();
                        a.put("rows", sorted(norm(MqService.rows(r))));
                        a.put("masked", r.meta().masked() == null ? List.of() : r.meta().masked().stream().sorted().toList());
                        mine.put(key, a);
                    } catch (DrishtiException e) {
                        mine.put(key, Map.of("refused", e.errorCode().code()));
                    } catch (RuntimeException e) {
                        mine.put(key, Map.of("failed", e.getClass().getSimpleName()));
                    }
                }
            }
            out.put(p.getKey(), mine);
        }
        return out;
    }

    /** The reports of config/bi that bind a model, parsed. */
    static Map<String, Report> reports() throws IOException {
        Map<String, Report> out = new TreeMap<>();
        try (Stream<Path> s = Files.list(BI.resolve("reports"))) {
            for (Path f : s.filter(p -> p.toString().endsWith(".report.yaml")).sorted().toList()) {
                ReportParser.Result r = ReportParser.parse(Files.readString(f));
                if (r.report() != null && r.report().model() != null) {
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
}
