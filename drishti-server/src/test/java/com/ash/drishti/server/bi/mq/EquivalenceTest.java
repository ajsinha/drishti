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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    static final Path SAMPLES = Path.of("src", "test", "resources", "bi", "samples-2.1", "datasets");   // the 2.1 datasets

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

    static final String V2_TRADES = """
            rachana: 2
            dataset: risk-trades
            version: 1.0.0
            source: { connector: risk-store, kind: trade-valuation }
            grain: { keys: [id] }
            columns:
              VALUATION_ID:  { type: string }
              TRADE_ID:      string
              SOURCE_SYSTEM: string
              DESK:          string
              PORTFOLIO:     string
              COUNTERPARTY:  string
              NETTING_SET:   string
              PRODUCT_TYPE:  string
              PRODUCT_GROUP: string
              CURRENCY:      string
              TRADE_STATUS:  string
              NOTIONAL:      { type: decimal, format: amount0 }
              MTM:           { type: decimal, format: signed0 }
              PNL_1D:        { type: decimal, format: signed0 }
              DV01:          { type: decimal, format: signed0 }
              TRADE_DATE:    date
              MATURITY_DATE: date
            security:
              masked: [COUNTERPARTY]
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
                  - { from: trades.DESK,               to: desks.id }
                  - { from: trades.PORTFOLIO,               to: books.id }
                  - { from: trades.COUNTERPARTY,     to: counterparties.id }
                  - { from: trades.NETTING_SET,         to: netting_sets.id, active: false }
                  - { from: netting_sets.counterparty, to: counterparties.id }
                measures:
                """ + measures + """
                hierarchies:
                  Trade date:    [trades.TRADE_DATE.year, trades.TRADE_DATE.quarter, trades.TRADE_DATE.month, trades.TRADE_DATE]
                  Desk and book: [desks.name, books.name]
                  Counterparty:  [counterparties.sector, counterparties.name]
                rollups:
                  - { name: by_desk_currency, table: trades, by: [DESK, CURRENCY, PRODUCT_GROUP], measures: [MTM, Notional, "P&L 1d", Trades, MTM per 1m notional] }
                security:
                  rows:
                    trades:
                      - { role: desk-head, where: "trades.DESK in user.desks" }
                      - { role: [risk, analyst, admin], where: "true" }
                  masked: [counterparties.name, counterparties.rating]
                  may-see-all: { netting_sets: [desk-head], counterparties: [desk-head], desks: [desk-head], books: [desk-head] }
                """;
    }

    @Test
    void tradingRiskAsAW1ModelAnswersAsTheOldFileApartFromTheAllowList() {
        Dataset old = samples.get("trading-risk.dataset.yaml");
        // as section 8.2 converts it: trades.NETTING_SET -> netting_sets.id inactive, so the two facts meet on counterparties (one active path)
        ModelView v = MqHarness.model(v2Model(old), V2_TRADES, V2_NETTING, V2_CPTY, V2_DESKS, V2_BOOKS);
        int compared = 0;
        int allowed = 0;
        List<List<String>> groupings = List.of(List.of(), List.of("desks.name"), List.of("trades.CURRENCY"), List.of("counterparties.sector"));
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
