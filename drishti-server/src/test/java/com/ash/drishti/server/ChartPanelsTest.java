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
package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.SutraParser;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The seven chart and aggregate kinds bound through the real pipeline, on whole and on broken documents. */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=finance"})
class ChartPanelsTest {

    @Autowired ViewPipeline pipeline;
    @Autowired JsonCodec codec;

    static final Sutra SUTRA = new SutraParser().parse("""
            rachana: 1
            sutra: chart-kinds
            version: 1
            match: { kind: trade }
            title: { pill: Trade, id: $.tradeId }
            panels:
              - { id: explain, kind: waterfall, rows: $.explain, label: step, value: pnl, sum: Closing, fmt: signed0 }
              - id: dist
                kind: histogram
                rows: $.scenarios
                bins: 4
                markers:
                  - { label: VaR 99%, value: "-$.var99", tone: neg }
                  - { label: Missing, value: $.nothing }
              - { id: rr, kind: scatter, rows: $.books, x: var, y: "@.pnl / 1000", size: trades, label: book, group: desk, fmt: amount0 }
              - { id: px, kind: candlestick, rows: $.ohlc, volume: volume, fmt: price2 }
              - { id: tree, kind: graph, nodes: $.tree.nodes, edges: $.tree.edges }
              - { id: life, kind: timeline, rows: $.events, label: event, detail: note }
              - { id: grid, kind: pivot, rows: $.positions, by: book, across: ccy, value: mtm, agg: sum, heat: true, fmt: amount0 }
              - { id: counts, kind: pivot, rows: $.positions, by: ccy, across: book, totals: false }
            """, "chart-kinds.sutra.yaml", "test");

    static final String DOC = """
            {"tradeId": "IRS-1001",
             "explain": [{"step": "Opening", "pnl": 1000, "total": true}, {"step": "Carry", "pnl": 200}, {"step": "Rates", "pnl": -500},
                         {"step": "Broken", "pnl": "n/a"}],
             "scenarios": [-4, -3, -1, 0, 0, 1, 2, 4, "x", null], "var99": 3.5,
             "books": [{"book": "IRS-2001", "desk": "Rates", "var": 10, "pnl": 5000, "trades": 4}, {"book": "B2", "desk": "FX", "var": 20, "pnl": -1000},
                       {"book": "B3", "var": "?", "pnl": 1}],
             "ohlc": [{"date": "2026-09-28", "open": 10, "high": 11, "low": 9, "close": 10.5, "volume": 100},
                      {"date": "2026-09-29", "open": 10.5, "high": 10.6, "low": 9.5, "close": 9.8},
                      {"date": "2026-09-30", "open": 9.8, "high": 9.9, "low": 9.0}],
             "tree": {"nodes": [{"id": "CP-PARENT", "label": "Parent", "type": "Group"}, {"id": "IRS-1001", "label": "This"}, {"id": "X", "label": "x"},
                                {"label": "no id"}],
                      "edges": [{"from": "CP-PARENT", "to": "IRS-1001", "label": "owns"}, {"from": "CP-PARENT", "to": "NOWHERE"}]},
             "events": [{"date": "2026-02-01", "event": "Confirmed", "status": "Matched"}, {"date": "2026-01-15", "event": "Booked", "note": "Captured",
                         "status": "Done"}, {"date": "2026-03-01", "event": "Amended", "status": "Pending approval"}],
             "positions": [{"book": "B1", "ccy": "USD", "mtm": 100}, {"book": "B1", "ccy": "EUR", "mtm": -40}, {"book": "B2", "ccy": "USD", "mtm": 60},
                           {"book": "B1", "ccy": "USD", "mtm": 1}, {"book": "B2", "ccy": "GBP", "mtm": "?"}]}
            """;

    ViewModel view(String json) {
        EntityDocument doc = new EntityDocument(EntityRef.of("trade", "IRS-1001"), codec.read(json), new Provenance("test", 1, Instant.now(), false));
        return pipeline.preview(Optional.of(SUTRA), doc);
    }

    static ViewModel.PanelView panel(ViewModel v, String id) {
        return v.panels().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void aWaterfallRunsATotalFromOpeningToClosing() {
        var w = (PanelData.Waterfall) panel(view(DOC), "explain").data();
        assertThat(w.steps()).extracting(PanelData.Step::label).containsExactly("Opening", "Carry", "Rates", "Closing");
        var rates = w.steps().get(2);
        assertThat(rates.from()).isEqualTo(1200);
        assertThat(rates.to()).isEqualTo(700);
        assertThat(rates.tone()).isEqualTo("neg");
        assertThat(rates.text()).isEqualTo("−500");
        assertThat(w.steps().get(3).total()).isTrue();
        assertThat(w.steps().get(3).value()).isEqualTo(700);
    }

    @Test
    void aHistogramBinsTheNumbersAndDrawsTheMarkersItCanRead() {
        var h = (PanelData.Histogram) panel(view(DOC), "dist").data();
        assertThat(h.count()).isEqualTo(8);
        assertThat(h.dropped()).isEqualTo(2);
        assertThat(h.bins()).hasSize(4).extracting(PanelData.Bin::count).containsExactly(2, 1, 3, 2);
        assertThat(h.bins().get(0).from()).isEqualTo(-4);
        assertThat(h.bins().get(3).to()).isEqualTo(4);
        assertThat(h.markers()).singleElement().satisfies(m -> {
            assertThat(m.value()).isEqualTo(-3.5);
            assertThat(m.tone()).isEqualTo("neg");
        });
    }

    @Test
    void aScatterPlotsRowsWithTwoNumbersAndLinksTheEntitiesItNames() {
        var s = (PanelData.Scatter) panel(view(DOC), "rr").data();
        assertThat(s.points()).hasSize(2);
        assertThat(s.groups()).containsExactly("Rates", "FX");
        assertThat(s.points().get(0).y()).isEqualTo(5);
        assertThat(s.points().get(0).size()).isEqualTo(4);
        assertThat(s.points().get(0).link().kind()).isEqualTo("trade");
        assertThat(s.points().get(1).link()).isNull();
        assertThat(s.xLabel()).isEqualTo("var");
    }

    @Test
    void aCandlestickNeedsAllFourPricesAndReportsTheLastMove() {
        var c = (PanelData.Candles) panel(view(DOC), "px").data();
        assertThat(c.candles()).hasSize(2);
        assertThat(c.volume()).isTrue();
        assertThat(c.last()).isEqualTo("9.80");
        assertThat(c.tone()).isEqualTo("neg");
        assertThat(c.change()).startsWith("−0.70");
    }

    @Test
    void aGraphKeepsNodesWithIdsAndEdgesBetweenThem() {
        var g = (PanelData.Graph) panel(view(DOC), "tree").data();
        assertThat(g.nodes()).extracting(PanelData.Node::id).containsExactly("CP-PARENT", "IRS-1001", "X");
        assertThat(g.nodes().get(0).link().kind()).isEqualTo("counterparty");
        assertThat(g.nodes().get(1).focus()).isTrue();
        assertThat(g.edges()).singleElement().satisfies(e -> assertThat(e.label()).isEqualTo("owns"));
        assertThat(g.layout()).isEqualTo("tree");
    }

    @Test
    void aTimelineSortsEventsByDateAndTonesTheirStatus() {
        var t = (PanelData.Timeline) panel(view(DOC), "life").data();
        assertThat(t.events()).extracting(PanelData.Event::label).containsExactly("Booked", "Confirmed", "Amended");
        assertThat(t.events()).extracting(PanelData.Event::tone).containsExactly("ok", "ok", "warn");
        assertThat(t.events().get(0).detail()).isEqualTo("Captured");
    }

    @Test
    void aPivotAggregatesByOneFieldAcrossAnotherWithTotals() {
        var v = view(DOC);
        var p = (PanelData.Pivot) panel(v, "grid").data();
        assertThat(p.columns()).containsExactly("USD", "EUR");
        assertThat(p.rows()).extracting(PanelData.PivotRow::label).containsExactly("B1", "B2");
        assertThat(p.rows().get(0).values()).containsExactly(101.0, -40.0);
        assertThat(p.rows().get(1).values()).containsExactly(60.0, null);
        assertThat(p.rows().get(0).total().text()).isEqualTo("61");
        assertThat(p.totals()).extracting(ViewModel.Cell::text).containsExactly("161", "−40", "121");
        assertThat(p.min()).isEqualTo(-40);
        assertThat(p.max()).isEqualTo(101);
        var counts = (PanelData.Pivot) panel(v, "counts").data();
        assertThat(counts.agg()).isEqualTo("count");
        assertThat(counts.totals()).isNull();
        assertThat(counts.rows().get(0).values()).containsExactly(2.0, 1.0);
    }

    @Test
    void aDocumentWithoutTheListsGivesEmptyPanelsNotErrors() {
        var v = view("{\"tradeId\": \"IRS-1001\", \"explain\": {\"not\": \"a list\"}, \"scenarios\": \"nope\"}");
        for (String id : new String[] {"explain", "dist", "rr", "px", "tree", "life", "grid", "counts"}) {
            var p = panel(v, id);
            assertThat(p.empty()).as(id).isTrue();
            assertThat(p.error()).as(id).isNull();
        }
    }
}
