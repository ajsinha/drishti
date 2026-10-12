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
import com.ash.drishti.server.bi.layer.CompatReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Equivalence (docs/architecture/BI_MODEL_SPEC.md section 8.6): for every shipped sample dataset, the old dataset query and the equivalent model query
 * through the compatibility reader answer identically, row for row, as {@code rita} (risk), {@code hana} (desk-head) and {@code ann} (analyst without raw):
 * every measure alone and by the first level of each hierarchy, plus sort, limit, filters, a measure filter and an as-of date. Then the {@code trading-risk}
 * sample written as a W1 model (datasets with their own security, the desk-head rule a model rule) answers as the old file does, except the allow-listed
 * change of section 8.5: a desk-head's question about another fact (exposure) is answered instead of cut by the trades rule.
 */
class EquivalenceTest {

    static final Path SAMPLES = Path.of("..", "config", "bi", "datasets");

    @TempDir Path store;
    QueryService service;
    SampleLake lake;
    Map<String, Dataset> samples = new LinkedHashMap<>();
    final MqService mq = new MqService(MqProperties.defaults());

    @BeforeEach
    void setUp() throws Exception {
        try (Stream<Path> files = Files.list(SAMPLES)) {
            for (Path f : files.sorted().toList()) {
                DatasetParser.Result r = DatasetParser.parse(Files.readString(f));
                if (r.dataset() != null && !r.dataset().framed()) {
                    samples.put(f.getFileName().toString(), r.dataset());
                }
            }
        }
        lake = new SampleLake(samples.values());
        QueryProperties props = QueryProperties.defaults(store.toString());
        BiLiveProperties live = new BiLiveProperties(false, null, null, null, null, null, null, null, null, null, null, null);
        service = new QueryService(props, live, new DuckDbEngine(props), lake, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    List<MqHarness.Reader> readers() {
        return List.of(MqHarness.RITA, MqHarness.deskHead(lake.first("desk")), MqHarness.ANN);
    }

    /** The answer, or the refusal's code. */
    Object old(Dataset ds, String key, QueryRequest q, MqHarness.Reader who) {
        MqHarness h = new MqHarness(service, SampleLake.DAY2);
        try {
            QueryService.Result r = service.query(new QueryService.Loaded(key, Layers.key(key), ds, "h|" + ds.measures().keySet()), q, h.caller(ds, who), false);
            return MqService.rows(r);
        } catch (DrishtiException e) {
            return "refused";
        }
    }

    @SuppressWarnings("unchecked")
    Object viaMq(ModelView v, String key, String yaml, MqHarness.Reader who) {
        MqHarness h = new MqHarness(service, SampleLake.DAY2);
        try {
            return (List<List<Object>>) mq.ask(h.bind(v, who, key), MqParser.parse(yaml), false).body().get("rows");
        } catch (DrishtiException e) {
            return "refused";
        }
    }

    static String list(List<String> names) {
        return "[" + String.join(", ", names.stream().map(n -> "\"" + n + "\"").toList()) + "]";
    }

    @Test
    void everyShippedSampleAnswersTheSameThroughTheCompatibilityReader() {
        assertThat(samples.keySet()).contains("trading-risk.dataset.yaml", "trades-basic.dataset.yaml", "trading-cashflows.dataset.yaml", "live-trading.dataset.yaml",
                "world-economics.dataset.yaml", "counterparty-exposure.dataset.yaml");
        int compared = 0;
        int answered = 0;
        for (Map.Entry<String, Dataset> e : samples.entrySet()) {
            Dataset ds = e.getValue();
            String key = "file:dataset:" + ds.name();
            ModelView v = new ModelView(key, Layers.key(key), "", CompatReader.read(ds, key), ds, Map.of(), Set.of(), true);
            List<List<String>> groupings = new ArrayList<>();
            groupings.add(List.of());
            ds.hierarchies().values().forEach(levels -> groupings.add(List.of(levels.get(0))));
            for (String m : ds.measures().keySet()) {
                if (ds.measures().get(m).python() != null) {
                    continue;
                }
                for (List<String> by : groupings) {
                    for (MqHarness.Reader who : readers()) {
                        Object a = old(ds, key, new QueryRequest(by, List.of(m), List.of(), null, null, List.of(), 500, "auto"), who);
                        Object b = viaMq(v, key, "{ mq: 1, model: x, select: { fields: " + list(by) + ", measures: " + list(List.of(m)) + " } }", who);
                        assertThat(b).as(e.getKey() + " " + m + " by " + by + " as " + who.user()).isEqualTo(a);
                        compared++;
                        answered += a instanceof List ? 1 : 0;
                    }
                }
            }
            String m = ds.measures().keySet().stream().filter(x -> ds.measures().get(x).python() == null).findFirst().orElseThrow();
            String f = ds.hierarchies().values().stream().map(l -> l.get(0)).filter(x -> !x.matches(".*\\.(year|quarter|month|day|week)$")).findFirst().orElse(null);
            if (f == null) {
                continue;
            }
            for (MqHarness.Reader who : readers()) {
                QueryRequest sorted = new QueryRequest(List.of(f), List.of(m), List.of(), null, null, List.of(new QueryRequest.Sort(m, true)), 3, "auto");
                assertThat(viaMq(v, key, "{ mq: 1, model: x, select: { fields: [" + f + "], measures: [\"" + m + "\"] }, sort: [{ by: \"" + m + "\", desc: true }], limit: 3 }", who))
                        .as(e.getKey() + " sorted").isEqualTo(old(ds, key, sorted, who));
                QueryRequest dated = new QueryRequest(List.of(f), List.of(m), List.of(), SampleLake.DAY1, SampleLake.DAY1, List.of(), 500, "auto");
                assertThat(viaMq(v, key, "{ mq: 1, model: x, select: { fields: [" + f + "], measures: [\"" + m + "\"] }, time: { as-of: 2026-10-08 } }", who))
                        .as(e.getKey() + " as of").isEqualTo(old(ds, key, dated, who));
                QueryRequest having = new QueryRequest(List.of(f), List.of(m), List.of(new QueryRequest.Filter(m, "gt", List.of(0.0))), null, null, List.of(), 500, "auto");
                assertThat(viaMq(v, key, "{ mq: 1, model: x, select: { fields: [" + f + "], measures: [\"" + m + "\"] }, having: [{ measure: \"" + m + "\", op: gt, value: 0 }] }", who))
                        .as(e.getKey() + " having").isEqualTo(old(ds, key, having, who));
                QueryRequest filtered = new QueryRequest(List.of(f), List.of(m), List.of(new QueryRequest.Filter(f, "not-null", List.of())), null, null, List.of(), 500, "auto");
                assertThat(viaMq(v, key, "{ mq: 1, model: x, select: { fields: [" + f + "], measures: [\"" + m + "\"] }, where: [{ field: " + f + ", op: not-null }] }", who))
                        .as(e.getKey() + " filtered").isEqualTo(old(ds, key, filtered, who));
            }
        }
        assertThat(compared).isGreaterThan(200);
        assertThat(answered).isGreaterThan(compared / 2);
    }

    static final String V2_TRADES = """
            rachana: 2
            dataset: risk-trades
            version: 1.0.0
            source: { connector: trading-store, kind: trade }
            grain: { keys: [id] }
            columns:
              tradeId:          { type: string }
              desk:             string
              book:             string
              nettingSet:       string
              counterpartyId:   { path: counterparty.id, type: string }
              counterpartyName: { path: counterparty.name, type: string }
              productType:      string
              assetClass:       string
              currency:         string
              status:           string
              notional:         { type: decimal, format: amount0 }
              mtm:              { type: decimal, format: signed0 }
              pnl1d:            { type: decimal, format: signed0 }
              dv01:             { path: risk.dv01, type: decimal, format: signed0 }
              tradeDate:        date
              maturityDate:     date
            security:
              masked: [counterpartyName]
            """;
    static final String V2_NETTING = """
            rachana: 2
            dataset: risk-netting-sets
            version: 1.0.0
            source: { connector: credit-store, kind: netting-set }
            columns: { counterparty: string, tradeCount: integer, netMtm: decimal, collateral: decimal, eePeak: decimal, pfePeak: decimal, limit: decimal, utilisation: decimal }
            """;
    static final String V2_CPTY = """
            rachana: 2
            dataset: risk-counterparties
            version: 1.0.0
            source: { connector: reference-store, kind: counterparty }
            columns: { name: string, rating: string, sector: string, country: string, type: string }
            """;
    static final String V2_DESKS = """
            rachana: 2
            dataset: risk-desks
            version: 1.0.0
            source: { connector: reference-store, kind: desk }
            columns: { name: string, head: string }
            """;
    static final String V2_BOOKS = """
            rachana: 2
            dataset: risk-books
            version: 1.0.0
            source: { connector: reference-store, kind: book }
            columns: { name: string, deskName: string }
            """;

    /** The trading-risk sample as a W1 model: its measures, hierarchies and rollup as written, the desk-head rule a model rule on trades (section 2.10). */
    String v2Model(Dataset old) {
        StringBuilder measures = new StringBuilder();
        old.measures().values().forEach(m -> measures.append("  \"").append(m.name()).append("\": { expr: \"").append(m.expr().replace("\"", "\\\"")).append("\" }\n"));
        return """
                rachana: 2
                model: trading-risk
                version: 1.0.0
                datasets:
                  trades:         { dataset: risk-trades, role: fact }
                  netting_sets:   { dataset: risk-netting-sets, role: fact }
                  counterparties: { dataset: risk-counterparties, role: dimension }
                  desks:          { dataset: risk-desks, role: dimension }
                  books:          { dataset: risk-books, role: dimension }
                relationships:
                  - { from: trades.desk,               to: desks.id }
                  - { from: trades.book,               to: books.id }
                  - { from: trades.counterpartyId,     to: counterparties.id }
                  - { from: trades.nettingSet,         to: netting_sets.id, active: false }
                  - { from: netting_sets.counterparty, to: counterparties.id }
                measures:
                """ + measures + """
                hierarchies:
                  Trade date:    [trades.tradeDate.year, trades.tradeDate.quarter, trades.tradeDate.month, trades.tradeDate]
                  Desk and book: [desks.name, books.name]
                  Counterparty:  [counterparties.sector, counterparties.name]
                rollups:
                  - { name: by_desk_currency, table: trades, by: [desk, currency, assetClass], measures: [MTM, Notional, "P&L 1d", Trades, MTM per 1m notional] }
                security:
                  rows:
                    trades:
                      - { role: desk-head, where: "trades.desk in user.desks" }
                      - { role: [risk, analyst, admin], where: "true" }
                  masked: [counterparties.name, counterparties.rating]
                  may-see-all: { netting_sets: [desk-head], counterparties: [desk-head], desks: [desk-head], books: [desk-head] }
                """;
    }

    @Test
    void tradingRiskAsAW1ModelAnswersAsTheOldFileApartFromTheAllowList() {
        Dataset old = samples.get("trading-risk.dataset.yaml");
        // as section 8.2 converts it: trades.nettingSet -> netting_sets.id inactive, so the two facts meet on counterparties (one active path)
        ModelView v = MqHarness.model(v2Model(old), V2_TRADES, V2_NETTING, V2_CPTY, V2_DESKS, V2_BOOKS);
        int compared = 0;
        int allowed = 0;
        List<List<String>> groupings = List.of(List.of(), List.of("desks.name"), List.of("trades.currency"), List.of("counterparties.sector"));
        for (String m : old.measures().keySet()) {
            for (List<String> by : groupings) {
                for (MqHarness.Reader who : readers()) {
                    Object a = old(old, "old-trading-risk", new QueryRequest(by, List.of(m), List.of(), null, null, List.of(), 500, "auto"), who);
                    Object b = viaMq(v, "new-trading-risk", "{ mq: 1, model: x, select: { fields: " + list(by) + ", measures: " + list(List.of(m)) + " } }", who);
                    boolean otherFact = !ModelView.tablesOf(old, m).contains("trades");
                    if ("hana".equals(who.user()) && otherFact) {
                        // section 8.5: security binds to the dataset, so the desk-head's question about netting sets is answered in full: as risk sees it
                        Object risk = viaMq(v, "new-trading-risk", "{ mq: 1, model: x, select: { fields: " + list(by) + ", measures: " + list(List.of(m)) + " } }", MqHarness.RITA);
                        assertThat(b).as(m + " by " + by + " for the desk-head (allow-listed)").isEqualTo(risk);
                        allowed++;
                        continue;
                    }
                    assertThat(b).as(m + " by " + by + " as " + who.user()).isEqualTo(a);
                    compared++;
                }
            }
        }
        assertThat(compared).isGreaterThan(100);
        assertThat(allowed).isGreaterThan(0);
    }
}
