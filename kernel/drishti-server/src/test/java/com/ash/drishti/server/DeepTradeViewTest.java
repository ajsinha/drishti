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
import com.ash.drishti.engine.live.Patch;
import com.ash.drishti.engine.live.PatchDiffer;
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.SutraParser;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A deep trade (trade, legs, each leg's cashflows, each floating cashflow's fixings) through the real pipeline: one tab per leg
 * with the leg's terms and its cashflows as a tree table that opens to the fixings; a live change of a fixing repaints the panel;
 * and inference, without a Sutra, lays the same document out as tabs holding a table.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=finance"})
class DeepTradeViewTest {

    @Autowired ViewPipeline pipeline;
    @Autowired JsonCodec codec;

    static final Sutra SUTRA = new SutraParser().parse("""
            rachana: 1
            sutra: deep-trade
            version: 1
            match: { kind: trade }
            title: { pill: Trade, id: $.tradeId }
            panels:
              - id: legs
                kind: tabs
                title: Legs
                each: $.legs
                tabTitle: "'Leg ' + @.leg + ' · ' + @.label"
                panels:
                  - id: leg-terms
                    kind: kv
                    title: Leg terms
                    columns:
                      - { label: Pay/receive, bind: "@.payReceive" }
                      - { label: Index, bind: "@.index" }
                      - { label: Leg PV, bind: "@.pv", fmt: signed0, tone: sign }
                  - id: leg-cashflows
                    kind: table
                    title: Cashflows and fixings
                    rows: "@.cashflows"
                    children: "@.fixings"
                    expand: 1
                    where: "@.status != 'Settled' || true"
                    columns:
                      - { label: Period, bind: "coalesce(@.periodStart, @.fixingDate)", fmt: date }
                      - { label: Rate, bind: "@.rate", fmt: pct4 }
                      - { label: Amount, bind: "@.amount", fmt: signed0, tone: sign }
                      - { label: Source, bind: "@.source" }
              - { id: flat, kind: kv, title: Flat, columns: [ { label: Id, bind: $.tradeId } ] }
            """, "deep-trade.sutra.yaml", "test");

    static final String DOC = """
            {"tradeId": "CMD-1", "notional": 1000000,
             "legs": [
               {"leg": 1, "label": "Pay fixed", "payReceive": "Pay", "index": null, "pv": -100.0,
                "cashflows": [{"periodStart": "2026-10-01", "payDate": "2026-11-05", "rate": 71.0, "amount": -710.0, "status": "Projected"},
                              {"periodStart": "2026-11-01", "payDate": "2026-12-05", "rate": 71.0, "amount": -710.0, "status": "Projected"}]},
               {"leg": 2, "label": "Receive WTI average", "payReceive": "Receive", "index": "WTI", "pv": 120.0,
                "cashflows": [{"periodStart": "2026-10-01", "payDate": "2026-11-05", "rate": 72.5, "amount": 725.0, "status": "Projected",
                               "fixings": [{"fixingDate": "2026-10-01", "rate": 72.0, "source": "NYMEX"}, {"fixingDate": "2026-10-02", "rate": 73.0, "source": "NYMEX"}]},
                              {"periodStart": "2026-11-01", "payDate": "2026-12-05", "rate": 72.0, "amount": 720.0, "status": "Projected",
                               "fixings": [{"fixingDate": "2026-11-02", "rate": 72.0, "source": "Forward curve"}]}]},
               {"leg": 3, "label": "Pay fee", "payReceive": "Pay", "index": null, "pv": -5.0,
                "cashflows": [{"periodStart": "2026-10-01", "payDate": "2026-11-05", "rate": 0.1, "amount": -5.0, "status": "Projected"}]}
             ]}
            """;

    ViewModel view(String json, Optional<Sutra> sutra) {
        EntityDocument doc = new EntityDocument(EntityRef.of("trade", "CMD-1"), codec.read(json), new Provenance("test", 1, Instant.now(), false));
        return pipeline.preview(sutra, doc);
    }

    static ViewModel.PanelView panel(ViewModel v, String id) {
        return v.panels().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void oneTabPerLegWhateverTheCount() {
        var legs = (PanelData.Tabs) panel(view(DOC, Optional.of(SUTRA)), "legs").data();
        assertThat(legs.tabs()).extracting(PanelData.Tab::title).containsExactly("Leg 1 · Pay fixed", "Leg 2 · Receive WTI average", "Leg 3 · Pay fee");
        assertThat(legs.tabs()).allSatisfy(t -> assertThat(t.panels()).extracting(ViewModel.PanelView::id).containsExactly("leg-terms", "leg-cashflows"));
        String head = DOC.substring(0, DOC.indexOf("{\"leg\": 3")).stripTrailing();
        String fewer = head.substring(0, head.length() - 1) + "]}";
        var two = (PanelData.Tabs) panel(view(fewer, Optional.of(SUTRA)), "legs").data();
        assertThat(two.tabs()).hasSize(2);
    }

    @Test
    void eachTabHoldsItsLegsTermsAndItsOwnCashflows() {
        var legs = (PanelData.Tabs) panel(view(DOC, Optional.of(SUTRA)), "legs").data();
        var terms = (PanelData.Fields) legs.tabs().get(1).panels().get(0).data();
        assertThat(terms.fields()).extracting(ViewModel.Cell::text).containsExactly("Receive", "WTI", "+120");
        var fee = (PanelData.Table) legs.tabs().get(2).panels().get(1).data();
        assertThat(fee.rows()).hasSize(1);
        assertThat(fee.rows().get(0).cells()).extracting(ViewModel.Cell::text).contains("2026-10-01", "−5");
    }

    @Test
    void aFloatingCashflowOpensToItsFixingsAndAFixedOneHasNone() {
        var legs = (PanelData.Tabs) panel(view(DOC, Optional.of(SUTRA)), "legs").data();
        var index = (PanelData.Table) legs.tabs().get(1).panels().get(1).data();
        assertThat(index.expand()).isEqualTo(1);
        assertThat(index.rows()).hasSize(2);
        assertThat(index.rows().get(0).children()).hasSize(2);
        var fixing = index.rows().get(0).children().get(1);
        assertThat(fixing.cells()).extracting(ViewModel.Cell::text).containsExactly("2026-10-02", "7,300.0000%", "", "NYMEX");
        var fixed = (PanelData.Table) legs.tabs().get(0).panels().get(1).data();
        assertThat(fixed.rows()).allSatisfy(r -> assertThat(r.children()).isNullOrEmpty());
    }

    @Test
    void aLiveChangeOfAFixingRepaintsTheTabsPanelOnly() {
        ViewModel before = view(DOC, Optional.of(SUTRA));
        ViewModel after = view(DOC.replace("\"rate\": 73.0", "\"rate\": 74.0"), Optional.of(SUTRA));
        List<Patch> patches = new PatchDiffer().diff(before, after);
        assertThat(patches).extracting(Patch::op).containsExactly("panel");
        assertThat(patches.get(0).panel().id()).isEqualTo("legs");
        var index = (PanelData.Table) ((PanelData.Tabs) patches.get(0).panel().data()).tabs().get(1).panels().get(1).data();
        assertThat(index.rows().get(0).children().get(1).cells().get(1).text()).isEqualTo("7,400.0000%");
    }

    @Test
    void withoutASutraInferenceShowsATabPerLegHoldingATableThatOpensToTheFixings() {
        ViewModel v = view(DOC, Optional.empty());
        var tabs = v.panels().stream().filter(p -> p.kind().equals("tabs")).findFirst().orElseThrow();
        var data = (PanelData.Tabs) tabs.data();
        assertThat(data.tabs()).hasSize(3);
        var table = data.tabs().get(1).panels().stream().filter(p -> p.kind().equals("table")).findFirst().orElseThrow();
        var rows = (PanelData.Table) table.data();
        assertThat(rows.rows().get(0).children()).hasSize(2);
        assertThat(rows.expand()).isEqualTo(1);
        assertThat(v.panels().stream().filter(p -> p.kind().equals("table") && p.id().contains("cashflows"))).isEmpty();   // not also shown beside the tabs
    }
}
