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

/**
 * A table that drills level by level ({@code levels:}) through the real pipeline, on a four-level document (deal, legs, cash flows,
 * resets): every row names itself for the breadcrumb, carries its rows of the next level bound with that level's columns and their
 * total; a missing level is an empty list, an absent list says the Sutra's words; flatten() reads every leg's flows at once.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=finance"})
class DrillPanelTest {

    @Autowired ViewPipeline pipeline;
    @Autowired JsonCodec codec;

    static final Sutra SUTRA = new SutraParser().parse("""
            rachana: 1
            sutra: deep-deal
            version: 1
            match: { kind: trade }
            title: { pill: Deal, id: $.TRADE_ID }
            panels:
              - id: legs
                kind: table
                rows: $.LEGS
                crumb: "'Leg ' + @.LEG_ID + ' · ' + @.LEG_TYPE"
                root: "'Deal ' + $.TRADE_ID"
                columns:
                  - { label: Leg, bind: "@.LEG_ID" }
                  - { label: Notional, bind: "@.NOTIONAL", fmt: amount0, total: true }
                levels:
                  - rows: "@.CASH_FLOWS"
                    title: Cash flows
                    crumb: "'Flow ' + @.FLOW_ID"
                    columns:
                      - { label: Pay date, bind: "@.PAYMENT_DATE", fmt: date }
                      - { label: Notional, bind: "@.NOTIONAL", fmt: amount0, total: true }
                      - { label: Resets, bind: "size(@.RESETS)", fmt: amount0 }
                  - rows: "@.RESETS"
                    title: Resets
                    columns:
                      - { label: Fixing, bind: "@.RESET_DATE", fmt: date }
                      - { label: Rate, bind: "nonblank(@.RATE, '-')" }
              - id: events
                kind: table
                rows: $.EVENTS
                empty: No events
                columns:
                  - { label: Type, bind: "@.EVENT_TYPE" }
              - id: payments
                kind: timeline
                rows: "flatten($.LEGS[*].CASH_FLOWS)"
                date: PAYMENT_DATE
                label: FLOW_ID
            """, "deep-deal.sutra.yaml", "test");

    static final String DOC = """
            {"TRADE_ID": 100001,
             "LEGS": [
               {"LEG_ID": 1, "LEG_TYPE": "Fixed", "NOTIONAL": 1000,
                "CASH_FLOWS": [{"FLOW_ID": 11, "PAYMENT_DATE": "2026-02-10", "NOTIONAL": 400, "RESETS": []},
                               {"FLOW_ID": 12, "PAYMENT_DATE": "2026-03-10", "NOTIONAL": 600}]},
               {"LEG_ID": 2, "LEG_TYPE": "Float", "NOTIONAL": 1000,
                "CASH_FLOWS": [{"FLOW_ID": 21, "PAYMENT_DATE": "2026-01-10", "NOTIONAL": 1000,
                                "RESETS": [{"RESET_DATE": "2026-01-02", "RATE": "78.5"}, {"RESET_DATE": "2026-01-05", "RATE": ""}, 7]}]}]}
            """;

    ViewModel view(String json) {
        EntityDocument doc = new EntityDocument(EntityRef.of("trade", "100001"), codec.read(json), new Provenance("test", 1, Instant.now(), false));
        return pipeline.preview(Optional.of(SUTRA), doc);
    }

    static ViewModel.PanelView panel(ViewModel v, String id) {
        return v.panels().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void everyLevelCarriesItsRowsColumnsBreadcrumbAndTotals() {
        var t = (PanelData.Table) panel(view(DOC), "legs").data();
        assertThat(t.root()).isEqualTo("Deal 100001");
        assertThat(t.levels()).extracting(PanelData.Level::title).containsExactly("Cash flows", "Resets");
        assertThat(t.levels().get(0).columns()).containsExactly("Pay date", "Notional", "Resets");
        assertThat(t.levels().get(0).numeric()).containsExactly(false, true, true);
        assertThat(t.rows()).extracting(PanelData.Row::crumb).containsExactly("Leg 1 · Fixed", "Leg 2 · Float");
        assertThat(t.total().cells().get(1).text()).isEqualTo("2,000");
        var leg1 = t.rows().get(0);
        assertThat(leg1.children()).extracting(PanelData.Row::crumb).containsExactly("Flow 11", "Flow 12");
        assertThat(leg1.kidsTotal().cells().get(1).text()).isEqualTo("1,000");                  // the flows of leg 1 add up
        assertThat(leg1.children().get(1).children()).isNullOrEmpty();                          // no RESETS key: an empty level, not an error
        var flow21 = t.rows().get(1).children().get(0);
        assertThat(flow21.cells().get(2).text()).isEqualTo("3");                                 // size() counts what the list holds
        assertThat(flow21.children()).hasSize(2);                                                // a number in the list is not a row
        assertThat(flow21.children()).extracting(r -> r.cells().get(1).text()).containsExactly("78.5", "-");
        assertThat(flow21.children().get(0).crumb()).isEqualTo("#1");                           // no crumb: the row's number
        assertThat(flow21.kidsTotal()).isNull();                                                 // no total column at that level
    }

    @Test
    void anAbsentListSaysTheSutrasWordsAndFlattenReadsEveryLeg() {
        var v = view(DOC);
        var events = panel(v, "events");
        assertThat(events.empty()).isTrue();
        assertThat(events.error()).isNull();
        assertThat(((PanelData.Table) events.data()).emptyLabel()).isEqualTo("No events");
        var pay = panel(v, "payments");
        assertThat(pay.error()).isNull();
        assertThat(pay.empty()).isFalse();
    }

    @Test
    void aDocumentWithoutLegsIsAnEmptyDrillNotAnError() {
        var p = panel(view("{\"TRADE_ID\": 9}"), "legs");
        assertThat(p.error()).isNull();
        assertThat(p.empty()).isTrue();
    }
}
