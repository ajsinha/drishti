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
package com.ash.drishti.inference;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.rachana.SutraMatcher;
import com.ash.drishti.rachana.RachanaProperties;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PanelKind;
import com.ash.drishti.rachana.model.StripItem;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InferenceEngineTest {

    static final Path FIXTURES = Path.of("..", "examples", "packs", "finance", "samples");
    static final ElCompiler EL = new ElCompiler();
    static final Formats F = Formats.load(null, java.util.List.of("../examples/packs/finance/config/formats.yaml"));
    static final InferenceEngine ENGINE = new InferenceEngine(Semantics.load(null, java.util.List.of("../examples/packs/finance/config/semantics.yaml")), Rules.builtIn());
    static final LayoutMerger MERGER = new LayoutMerger(ENGINE, EL, F);
    static final JsonCodec JSON = new JsonCodec();

    static DataNode fixture(String kind, String id) throws Exception {
        return JSON.read(com.ash.drishti.common.PackSamples.document(FIXTURES, kind, id).orElseThrow());   // samples/<kind>.jsonl
    }

    static Panel panel(List<Panel> ps, String id) {
        return ps.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow(() -> new AssertionError(id + " in " + ps));
    }

    @Test
    void unknownProductRendersSensibly() {
        DataNode doc = JSON.read("""
                {"optionId":"EQO-7","product":"Equity option","counterparty":{"id":"CP-1","name":"Acme"},
                 "underlying":"ACME","strike":0.0125,"expiry":"2027-03-19","mtm":-1200.5,"notional":2000000,
                 "vega":340,"greeks":[{"tenor":"1M","vega":10},{"tenor":"3M","vega":40},{"tenor":"6M","vega":90}],
                 "fills":[{"time":"09:31","qty":100,"price":12.5},{"time":"09:40","qty":50,"price":12.7}],
                 "terms":{"style":"European","settlement":"Cash","exchange":"CBOE"}}""");
        InferredLayout l = ENGINE.infer(doc, "equity-option");
        assertThat(l.title().id()).isEqualTo("$.optionId");
        assertThat(l.title().pill()).isEqualTo("Equity option · Equity option");
        assertThat(l.title().with()).contains("link($.counterparty.id");
        assertThat(l.strip()).extracting(StripItem::bind).contains("$.mtm", "$.notional", "$.strike", "$.expiry");
        assertThat(l.strip()).filteredOn(StripItem::emphasis).extracting(StripItem::bind).containsExactly("$.mtm");
        assertThat(panel(l.panels(), "greeks").kind()).isEqualTo(PanelKind.HBAR);
        assertThat(panel(l.panels(), "fills").kind()).isEqualTo(PanelKind.TABLE);
        assertThat(panel(l.panels(), "terms").kind()).isEqualTo(PanelKind.KV);
        assertThat(l.panels()).extracting(Panel::kind).contains(PanelKind.LINKS, PanelKind.PROVENANCE);
        assertThat(l.explanations()).containsKeys("greeks", "fills", "terms");
    }

    @Test
    void eachReferenceEntityStaysUsableWithoutItsSutra() throws Exception {
        InferredLayout irs = ENGINE.infer(fixture("trade", "IRS-48213"), "trade");
        Panel legs = panel(irs.panels(), "legs");                            // legs that hold cashflows: a tab each, with a table of that leg's cashflows
        assertThat(legs.kind()).isEqualTo(PanelKind.TABS);
        assertThat(legs.parts()).extracting(Panel::id).containsExactly("terms", "cashflows");
        assertThat(legs.parts().get(1).kind()).isEqualTo(PanelKind.TABLE);
        assertThat(legs.parts().get(1).option("rows")).contains("@.cashflows");
        assertThat(legs.parts().get(1).columns()).extracting(c -> c.bind()).contains("@.amount", "@.pv");
        assertThat(irs.panels()).extracting(Panel::id).doesNotContain("legs-0-cashflows");   // and not again beside the tabs, for the first leg only
        assertThat(panel(irs.panels(), "dv01bytenor").kind()).isEqualTo(PanelKind.HBAR);
        assertThat(irs.strip()).hasSizeLessThanOrEqualTo(8).extracting(StripItem::bind).contains("$.mtm", "$.notional");

        InferredLayout fut = ENGINE.infer(fixture("trade", "CFT-77120"), "trade");
        assertThat(panel(fut.panels(), "settlements").kind()).isEqualTo(PanelKind.LADDER);
        assertThat(panel(fut.panels(), "contractterms").kind()).isIn(PanelKind.KV);

        InferredLayout ns = ENGINE.infer(fixture("netting-set", "NS-NORTH-01"), "netting-set");
        assertThat(ns.title().id()).isEqualTo("$.nettingSetId");
        assertThat(panel(ns.panels(), "exposure").kind()).isEqualTo(PanelKind.AREA);
        assertThat(panel(ns.panels(), "membertrades").kind()).isEqualTo(PanelKind.TABLE);

        InferredLayout curve = ENGINE.infer(fixture("curve", "USD-SOFR"), "curve");
        assertThat(panel(curve.panels(), "points").kind()).isEqualTo(PanelKind.LINE);
    }

    @Test
    void legsThatHoldCashflowsThatHoldFixingsAreTabsOfTreeTables() {
        DataNode doc = JSON.read("""
                {"tradeId":"CMD-1","notional":1000000,"mtm":120.5,
                 "legs":[
                  {"leg":1,"payReceive":"Pay","type":"FIXED","currency":"USD","rate":71.0,
                   "cashflows":[{"periodStart":"2026-10-01","payDate":"2026-11-05","rate":71.0,"amount":-710.0,"status":"Projected"},
                                {"periodStart":"2026-11-01","payDate":"2026-12-05","rate":71.0,"amount":-710.0,"status":"Projected"}]},
                  {"leg":2,"payReceive":"Receive","type":"FLOAT","currency":"USD","index":"WTI",
                   "cashflows":[{"periodStart":"2026-10-01","payDate":"2026-11-05","rate":72.5,"amount":725.0,"status":"Projected",
                                 "fixings":[{"fixingDate":"2026-10-01","rate":72.0,"source":"NYMEX"},{"fixingDate":"2026-10-02","rate":73.0,"source":"NYMEX"}]}]},
                  {"leg":3,"payReceive":"Pay","type":"FEE","currency":"USD","rate":0.1,
                   "cashflows":[{"periodStart":"2026-10-01","payDate":"2026-11-05","rate":0.1,"amount":-5.0,"status":"Projected"}]}]}""");
        InferredLayout l = ENGINE.infer(doc, "trade");
        Panel legs = panel(l.panels(), "legs");
        assertThat(legs.kind()).isEqualTo(PanelKind.TABS);
        assertThat(legs.option("each")).contains("$.legs");
        assertThat(legs.parts()).extracting(Panel::id).containsExactly("terms", "cashflows");
        Panel flows = legs.parts().get(1);
        assertThat(flows.option("rows")).contains("@.cashflows");
        assertThat(flows.option("children")).contains("@.fixings");                           // some cashflows hold fixings: a tree
        assertThat(flows.columns()).extracting(c -> c.bind()).contains("@.periodStart", "@.amount", "@.fixingDate", "@.source");
        assertThat(flows.columns()).noneMatch(c -> c.total());                               // the fixing rows are another kind of row: no totals
        assertThat(l.panels()).extracting(Panel::id).noneMatch(id -> id.startsWith("legs-"));
        assertThat(l.explanations().get("legs")).contains("3 records with lists of their own");
    }

    @Test
    void sutraWinsAndInferenceFillsItsGaps() throws Exception {
        try (SutraRegistry reg = new SutraRegistry(new RachanaProperties(List.of("../examples/packs/finance/sutras"), false, null, null, null, null, null, null, null, null), EL)) {
            SutraMatcher matcher = new SutraMatcher(reg, EL, F);
            DataNode fut = fixture("trade", "CFT-77120");
            EffectiveLayout e = MERGER.merge(matcher.match("trade", fut), fut, "trade");
            assertThat(e.label()).isEqualTo("Sutra listed-future v1 + inference");
            assertThat(panel(e.sutra().panels(), "contract").columns()).extracting(c -> c.label())
                    .contains("Exchange", "Delivery month", "Contract size");
            assertThat(e.explanations()).containsKey("contract");

            DataNode irs = fixture("trade", "IRS-48213");
            EffectiveLayout ei = MERGER.merge(matcher.match("trade", irs), irs, "trade");
            assertThat(ei.sutra().panels()).extracting(Panel::id).containsExactly("legs", "cashflows", "leg2", "built", "curve", "refs", "dv01");
            assertThat(ei.label()).isEqualTo("Sutra irs-vanilla v3 + inference");

            DataNode plain = fixture("trade", "IRS-47102");
            EffectiveLayout ep = MERGER.merge(Optional.empty(), plain, "trade");
            assertThat(ep.label()).isEqualTo("inference only");
            assertThat(ep.sutra().strip()).extracting(StripItem::bind).contains("$.notional", "$.mtm");
        }
    }

    @Test
    void aLabelNobodyWroteComesFromTheFieldName() throws Exception {
        Semantics.load(null, java.util.List.of("../examples/packs/finance/config/semantics.yaml"));   // brings UTI, DV01, MTM…
        assertThat(LayoutMerger.labelOf("$.regulatory.uti")).isEqualTo("UTI");
        assertThat(LayoutMerger.labelOf("@.payDate")).isEqualTo("Pay date");
        assertThat(LayoutMerger.labelOf("$.legs[0].cashflows[1].amount")).isEqualTo("Amount");
        assertThat(LayoutMerger.labelOf("link($.nettingSet, 'netting-set')")).isEqualTo("Netting set");
        assertThat(LayoutMerger.labelOf("fmt($.dv01ByTenor, 'x')")).isEqualTo("DV01 by tenor");
        var parsed = new com.ash.drishti.rachana.parse.SutraParser().parse("""
                rachana: 1
                sutra: no-labels
                version: 1
                match: { kind: trade }
                strip:
                  - { bind: $.mtm, fmt: signed0 }
                  - { label: Deal, bind: $.tradeId }
                panels:
                  - { id: legs, kind: table, rows: $.legs, columns: [ { bind: "@.rate" } ] }
                """, "t.yaml", "x");
        var eff = MERGER.merge(java.util.Optional.of(parsed), fixture("trade", "IRS-48213"), "trade").sutra();
        assertThat(eff.strip()).extracting(StripItem::label).containsExactly("MTM", "Deal");
        assertThat(eff.panels().get(0).columns()).extracting(c -> c.label()).containsExactly("Rate");
    }

    @Test
    void semanticsRecogniseRolesAndLabels() {
        Semantics s = Semantics.load(null, java.util.List.of("../examples/packs/finance/config/semantics.yaml"));
        assertThat(s.role("mtm", DataNode.of(-5)).name()).isEqualTo("signed-money");
        assertThat(s.role("fixedRate", DataNode.of(0.0385)).fmt()).isEqualTo("pct4");
        assertThat(s.role("maturityDate", DataNode.of("2031-10-02")).fmt()).isEqualTo("date");
        assertThat(s.role("notional", DataNode.of(5e7)).fmt()).isEqualTo("amount0");
        assertThat(s.isTenor("5Y")).isTrue();
        assertThat(s.isTenor("Z6")).isTrue();
        assertThat(s.isTenor("Hello")).isFalse();
        assertThat(Semantics.humanize("dv01ByTenor")).isEqualTo("DV01 by tenor");   // the finance vocabulary spells DV01
        assertThat(s.idFields("netting-set")).first().isEqualTo("nettingSetId");
    }
}
