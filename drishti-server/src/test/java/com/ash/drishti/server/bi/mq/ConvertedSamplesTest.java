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

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.engine.BiLiveProperties;
import com.ash.drishti.server.bi.engine.DuckDbEngine;
import com.ash.drishti.server.bi.engine.Layers;
import com.ash.drishti.server.bi.engine.QueryProperties;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.migrate.ModelConverter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The dataset-to-model converter against the shipped samples (docs/architecture/BI_MODEL_SPEC.md sections 8.2, 8.3 and 8.6): every 2.1 sample dataset in
 * {@code config/bi} is converted, every output passes the model layer's parsers and Check, and every converted model answers <b>as the 2.1 dataset does</b>,
 * row for row, as {@code rita} (risk), {@code hana} (a desk-head) and {@code ann} (an analyst without the raw power, so masked): each measure alone and by
 * the first level of each hierarchy, sorted, as of a date, with a measure filter and a field filter. The composed sample is compared with the join the 2.1
 * engine made of its two sources (a left join on the counterparty) against the host model with the report filter the converter adds. The allow-list is
 * section 8.5's: a desk-head's question about a dataset its rule never reached is answered in full (as risk sees it) instead of refused; the derived sample
 * becomes a Python dataset, which only the console runs (its Transform's tests cover it). The committed samples under {@code config/bi/models} are the
 * converter's output, so what ships is what is proven here.
 */
class ConvertedSamplesTest {

    /** The 2.1 samples as shipped before the conversion (DEL v2 expressions): the originals the converted models are compared with. */
    static final Path ORIGINALS = Path.of("src", "test", "resources", "bi", "samples-2.1");
    static final Path BI = Path.of("..", "config", "bi");

    @TempDir Path store;
    QueryService service;
    SampleLake lake;
    final Map<String, Dataset> old = new LinkedHashMap<>();
    ModelConverter.Result result;
    final MqService mq = new MqService(MqProperties.defaults());

    static List<ModelConverter.Source> sources() throws Exception {
        List<ModelConverter.Source> out = new ArrayList<>();
        for (String dir : List.of("datasets", "reports", "boards", "notebooks", "python")) {
            try (Stream<Path> files = Files.list(ORIGINALS.resolve(dir))) {
                for (Path f : files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList()) {
                    String kind = f.getFileName().toString().replaceAll("^.*\\.([a-z]+)\\.yaml$", "$1");
                    out.add(new ModelConverter.Source(dir + "/" + f.getFileName(), kind, Files.readString(f)));
                }
            }
        }
        return out;
    }

    @BeforeEach
    void setUp() throws Exception {
        List<ModelConverter.Source> sources = sources();
        for (ModelConverter.Source s : sources) {
            if ("dataset".equals(s.kind()) && !com.ash.drishti.server.bi.layer.DatasetDefParser.isVersion2(s.yaml())) {
                DatasetParser.Result r = DatasetParser.parse(s.yaml());
                old.put(r.dataset().name(), r.dataset());
            }
        }
        result = ModelConverter.convert(sources, ModelConverter.Exposure.NOTE);
        lake = new SampleLake(old.values().stream().filter(d -> !d.framed()).toList());
        QueryProperties props = QueryProperties.defaults(store.toString());
        BiLiveProperties live = new BiLiveProperties(false, null, null, null, null, null, null, null, null, null, null, null);
        service = new QueryService(props, live, new DuckDbEngine(props), lake, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    @Test
    void everyOutputPassesCheckAndEverySampleIsConverted() {
        assertThat(result.count("problem")).as(result.report()).isZero();
        assertThat(result.items()).filteredOn(i -> "dataset".equals(i.kind()) && !"removed".equals(i.state())).allMatch(i -> "converted".equals(i.state()));
        assertThat(result.items()).filteredOn(i -> "report".equals(i.kind()) && !i.key().contains("live")).allMatch(i -> "converted".equals(i.state()));
        assertThat(result.items()).filteredOn(i -> "report".equals(i.kind()) && i.key().contains("live")).allMatch(i -> "manual".equals(i.state()));   // live: wave W3
        assertThat(result.removes()).hasSize(8).doesNotContain("datasets/live-trading.dataset.yaml", "datasets/live-exposure.dataset.yaml");
        String report = result.writes().get("reports/pnl-vs-exposure.report.yaml");
        assertThat(report).contains("model: trading-risk@1").doesNotContain("dataset: counterparty-exposure").doesNotContain("links:").doesNotContainPattern("(?m)^[^#]*joined\\.")
                .contains("{ field: Trades, op: gt, value: 0 }").contains("title: Desk P&L").contains("values: [P&L 1d]");
        assertThat(result.writes().get("models/trading-risk.model.yaml")).contains("P&L per exposure").contains("may-see-all")
                .contains("{ from: trades.nettingSet, to: netting_sets.id, active: false }");
    }

    @Test
    void theConverterIsIdempotent() throws Exception {
        List<ModelConverter.Source> again = new ArrayList<>();
        Map<String, String> writes = result.writes();
        for (ModelConverter.Source s : sources()) {
            if (!result.removes().contains(s.key())) {
                again.add(new ModelConverter.Source(s.key(), s.kind(), writes.getOrDefault(s.key(), s.yaml())));
            }
        }
        writes.forEach((k, v) -> {
            if (!again.stream().anyMatch(s -> s.key().equals(k))) {
                again.add(new ModelConverter.Source(k, k.startsWith("models/") ? "model" : "dataset", v));
            }
        });
        ModelConverter.Result second = ModelConverter.convert(again, ModelConverter.Exposure.NOTE);
        second.writes().forEach((k, v) -> assertThat(v).as("a second run changes nothing: " + k).isEqualTo(writes.get(k)));   // the kept live datasets convert to the same
        assertThat(second.removes()).isEmpty();
    }

    /** Section 8.6 for every sample dataset with tables: every measure, by every hierarchy's first level, plus sort, as-of, measure filter and field filter. */
    @Test
    void everyConvertedModelAnswersAsTheOldDataset() {
        int compared = 0;
        int allowed = 0;
        for (Dataset ds : old.values()) {
            if (ds.framed()) {
                continue;
            }
            ModelView v = converted(ds.name());
            Set<String> restricted = new HashSet<>();
            ds.security().rows().stream().filter(r -> !com.ash.drishti.server.bi.layer.RuleText.everyRow(r.where()))
                    .forEach(r -> ds.tables().keySet().stream().filter(t -> r.where().contains(t + ".")).forEach(restricted::add));
            List<List<String>> groupings = new ArrayList<>();
            groupings.add(List.of());
            ds.hierarchies().values().forEach(levels -> groupings.add(List.of(levels.get(0))));
            for (String m : ds.measures().keySet()) {
                if (ds.measures().get(m).python() != null) {
                    continue;
                }
                for (List<String> by : groupings) {
                    for (MqHarness.Reader who : readers()) {
                        Object a = oldAnswer(ds, new QueryRequest(by, List.of(m), List.of(), null, null, List.of(), 500, "auto"), who);
                        String q = "{ mq: 1, model: x, select: { fields: " + list(by) + ", measures: " + list(List.of(m)) + " } }";
                        Object b = newAnswer(v, q, who);
                        boolean otherFact = !restricted.isEmpty() && Collections.disjoint(ModelView.tablesOf(ds, m), restricted);
                        if ("hana".equals(who.user()) && otherFact && "refused".equals(a)) {
                            assertThat(b).as("8.5 allow-list: " + ds.name() + " " + m + " by " + by + " for the desk-head, as risk sees it")
                                    .isEqualTo(newAnswer(v, q, MqHarness.RITA));
                            allowed++;
                            continue;
                        }
                        assertThat(b).as(ds.name() + " " + m + " by " + by + " as " + who.user()).isEqualTo(a);
                        compared++;
                    }
                }
            }
            String m = ds.measures().keySet().stream().filter(x -> ds.measures().get(x).python() == null).findFirst().orElseThrow();
            String f = ds.hierarchies().values().stream().map(l -> l.get(0)).filter(x -> !x.matches(".*\\.(year|quarter|month|day|week)$")).findFirst().orElse(null);
            if (f == null) {
                continue;
            }
            for (MqHarness.Reader who : readers()) {
                String sel = "{ mq: 1, model: x, select: { fields: [" + f + "], measures: [\"" + m + "\"] }";
                assertThat(values(newAnswer(v, sel + ", sort: [{ by: \"" + m + "\", desc: true }], limit: 3 }", who))).as(ds.name() + " sorted (ties in any order)")
                        .isEqualTo(values(oldAnswer(ds, new QueryRequest(List.of(f), List.of(m), List.of(), null, null, List.of(new QueryRequest.Sort(m, true)), 3, "auto"), who)));
                assertThat(newAnswer(v, sel + ", time: { as-of: 2026-10-08 } }", who)).as(ds.name() + " as of")
                        .isEqualTo(oldAnswer(ds, new QueryRequest(List.of(f), List.of(m), List.of(), SampleLake.DAY1, SampleLake.DAY1, List.of(), 500, "auto"), who));
                assertThat(newAnswer(v, sel + ", having: [{ measure: \"" + m + "\", op: gt, value: 0 }] }", who)).as(ds.name() + " having")
                        .isEqualTo(oldAnswer(ds, new QueryRequest(List.of(f), List.of(m), List.of(new QueryRequest.Filter(m, "gt", List.of(0.0))), null, null, List.of(), 500, "auto"), who));
                assertThat(newAnswer(v, sel + ", where: [{ field: " + f + ", op: not-null }] }", who)).as(ds.name() + " filtered")
                        .isEqualTo(oldAnswer(ds, new QueryRequest(List.of(f), List.of(m), List.of(new QueryRequest.Filter(f, "not-null", List.of())), null, null, List.of(), 500, "auto"), who));
                compared += 4;
            }
        }
        assertThat(compared).isGreaterThan(300);
        assertThat(allowed).isGreaterThan(0);
    }

    /**
     * The composed sample: the 2.1 engine asked each source as the reader and left-joined the answers on the counterparty. The converted report asks the host
     * model by counterparty with the filter Trades > 0; the rows must be the same (P&L, trades, EE and PFE peaks per counterparty).
     */
    @Test
    @SuppressWarnings("unchecked")
    void theComposedSampleAnswersAsItsTwoSourcesJoined() {
        Dataset pnl = old.get("trading-risk");
        Dataset exposure = old.get("counterparty-exposure");
        ModelView host = converted("trading-risk");
        int rows = 0;
        for (MqHarness.Reader who : readers()) {
            Object left = oldAnswer(pnl, new QueryRequest(List.of("counterparties.id"), List.of("P&L 1d", "Trades"), List.of(), null, null, List.of(), 500, "auto"), who);
            Object right = oldAnswer(exposure, new QueryRequest(List.of("counterparties.id"), List.of("Exposure (EE peak)", "Exposure (PFE peak)"), List.of(), null, null,
                    List.of(), 500, "auto"), who);
            assertThat(left).as("pnl as " + who.user()).isInstanceOf(List.class);
            assertThat(right).as("exposure as " + who.user()).isInstanceOf(List.class);
            Map<Object, List<Object>> ee = new LinkedHashMap<>();
            for (List<Object> row : (List<List<Object>>) right) {
                ee.put(row.get(0), row.subList(1, 3));
            }
            List<List<Object>> joined = new ArrayList<>();
            for (List<Object> row : (List<List<Object>>) left) {
                List<Object> out = new ArrayList<>(row);
                List<Object> e = ee.get(row.get(0));
                out.add(e == null ? null : e.get(0));
                out.add(e == null ? null : e.get(1));
                joined.add(out);
            }
            Object asked = newAnswer(host, "{ mq: 1, model: x, select: { fields: [counterparties.id], measures: [\"P&L 1d\", Trades, \"Exposure (EE peak)\", "
                    + "\"Exposure (PFE peak)\"] }, having: [{ measure: Trades, op: gt, value: 0 }] }", who);
            assertThat(asked).as("asked as " + who.user()).isInstanceOf(List.class);
            Comparator<List<Object>> byId = Comparator.comparing(x -> String.valueOf(x.get(0)));
            List<List<Object>> got = new ArrayList<>((List<List<Object>>) asked);
            got.sort(byId);
            joined.sort(byId);
            assertThat(got).as("as " + who.user()).isEqualTo(joined);
            rows += got.size();
        }
        assertThat(rows).isGreaterThan(3);
    }

    /** The committed samples are the converter's output: the models, the datasets and the reports under config/bi are what it writes. */
    @Test
    void theCommittedSamplesAreTheConvertersOutput() throws Exception {
        for (Map.Entry<String, String> w : result.writes().entrySet()) {
            Path f = BI.resolve(w.getKey());
            assertThat(f).as(w.getKey() + " is committed (drishti.py bi migrate --models)").exists();
            assertThat(Files.readString(f)).as(w.getKey()).isEqualTo(w.getValue());
        }
    }

    List<MqHarness.Reader> readers() {
        return List.of(MqHarness.RITA, MqHarness.deskHead(lake.first("desk")), MqHarness.ANN);
    }

    Object oldAnswer(Dataset ds, QueryRequest q, MqHarness.Reader who) {
        MqHarness h = new MqHarness(service, SampleLake.DAY2);
        String key = "old:" + ds.name();
        try {
            return MqService.rows(service.query(new QueryService.Loaded(key, Layers.key(key), ds, "h|" + ds.measures().keySet()), q, h.caller(ds, who), false));
        } catch (DrishtiException e) {
            return "refused";
        }
    }

    @SuppressWarnings("unchecked")
    Object newAnswer(ModelView v, String yaml, MqHarness.Reader who) {
        MqHarness h = new MqHarness(service, SampleLake.DAY2);
        try {
            return (List<List<Object>>) mq.ask(h.bind(v, who, "new:" + v.def().name()), MqParser.parse(yaml), false).body().get("rows");
        } catch (DrishtiException e) {
            return "refused";
        }
    }

    ModelView converted(String name) {
        ModelConverter.Item item = result.items().stream().filter(i -> name.equals(i.name()) && "dataset".equals(i.kind())).findFirst().orElseThrow();
        assertThat(item.state()).as(name + ": " + item.problem()).isEqualTo("converted");
        String model = item.outputs().stream().filter(o -> "model".equals(o.kind())).findFirst().orElseThrow().yaml();
        String[] datasets = item.outputs().stream().filter(o -> "dataset".equals(o.kind())).map(ModelConverter.Output::yaml).toArray(String[]::new);
        return MqHarness.model(model, datasets);
    }

    /** The last column of an answer (the measure), in order: what a sort decides when its keys tie. */
    @SuppressWarnings("unchecked")
    static Object values(Object answer) {
        return answer instanceof List<?> l ? ((List<List<Object>>) l).stream().map(r -> r.get(r.size() - 1)).toList() : answer;
    }

    static String list(List<String> names) {
        return "[" + String.join(", ", names.stream().map(n -> "\"" + n + "\"").toList()) + "]";
    }
}
