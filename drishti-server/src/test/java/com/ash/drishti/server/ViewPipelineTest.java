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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.command.CommandParser;
import com.ash.drishti.engine.command.RecentEntities;
import com.ash.drishti.engine.command.SuggestionService;
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.engine.view.ViewModel.Cell;
import com.ash.drishti.engine.view.ViewModel.PanelView;
import com.ash.drishti.api.EntityHit;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Golden ViewModels for the four reference entities, degradation, commands, suggestions and a latency gate. */
@SpringBootTest(properties = {"drishti.rachana.dirs=../sutras", "drishti.rachana.hot-reload=false"})
class ViewPipelineTest {

    @Autowired ViewPipeline pipeline;
    @Autowired CommandParser parser;
    @Autowired SuggestionService suggestions;
    @Autowired RecentEntities recents;

    static PanelView panel(ViewModel v, String id) {
        return v.panels().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void interestRateSwapMatchesTheMockup() {
        ViewModel v = pipeline.view(parser.require("TRD IRS-48213 <GO>"));
        assertThat(v.mnemonic()).isEqualTo("TRD");
        assertThat(v.title().pill()).isEqualTo("Trade · Interest rate swap");
        assertThat(v.title().with().text()).isEqualTo("Northbridge Capital LLP");
        assertThat(v.title().with().link().kind()).isEqualTo("counterparty");
        assertThat(v.strip()).extracting(Cell::text).containsExactly("50,000,000", "Pay fixed", "2026-10-02", "2031-10-02",
                "3.8500%", "−412,580", "+22,310", "RATES-NY-3");
        assertThat(v.strip().get(5).emphasis()).isTrue();
        assertThat(v.strip().get(5).tone()).isEqualTo("neg");
        assertThat(v.strip().get(5).path()).isEqualTo("$.mtm");

        var legs = (PanelData.Tabs) panel(v, "legs").data();
        assertThat(legs.tabs()).extracting(PanelData.Tab::title).containsExactly("Leg 1 · Pay fixed 3.8500%", "Leg 2 · Receive SOFR compounded");
        assertThat(legs.tabs().get(0).fields()).extracting(Cell::text).contains("USD", "50,000,000", "3.8500% fixed", "ACT/360");

        var flows = (PanelData.Table) panel(v, "cashflows").data();
        assertThat(flows.rows()).hasSize(5);
        assertThat(flows.rows().get(0).cells()).extracting(Cell::text).contains("−1,962,430.56", "0.9632", "−1,890,213.11");
        assertThat(flows.total().cells()).extracting(Cell::text).contains("Total", "−9,764,027.78", "−8,746,188.84");

        assertThat(panel(v, "leg2").title()).isEqualTo("Leg 2 · Receive SOFR compounded");
        var curve = (PanelData.Chart) panel(v, "curve").data();
        assertThat(curve.x()).containsExactly("1M", "3M", "6M", "1Y", "2Y", "3Y", "5Y", "7Y", "10Y");
        assertThat(curve.mark()).isEqualTo("5Y");
        assertThat(curve.source().id()).isEqualTo("USD-SOFR");

        var links = (PanelData.Links) panel(v, "refs").data();
        assertThat(links.links()).extracting(PanelData.LinkItem::label).contains("Netting set", "Agreement", "CSA", "Discount curve", "Index");
        assertThat(links.links()).filteredOn(l -> l.text().equals("NS-NORTH-01")).first()
                .satisfies(l -> assertThat(l.badge()).isEqualTo("EE 4.1m"));
        assertThat(links.links()).filteredOn(l -> l.text().equals("CSA-VM-0417")).first()
                .satisfies(l -> assertThat(l.badge()).isEqualTo("threshold 0"));

        var dv01 = (PanelData.Bars) panel(v, "dv01").data();
        assertThat(dv01.bars()).extracting(PanelData.Bar::text).containsExactly("+1,180", "+3,420", "+4,760", "+5,920", "+7,030");

        var built = (PanelData.Fields) panel(v, "built").data();
        assertThat(built.fields()).extracting(Cell::text).contains("Sutra irs-vanilla v3 + inference", "aero-risk, gen 1742");
        assertThat(v.keys()).extracting(k -> k.key() + ":" + k.action()).contains("F2:panel", "F3:panel", "F4:panel", "F7:link", "F9:raw");
        assertThat(v.provenance().source()).isEqualTo("aero-risk");
    }

    @Test
    void otherReferenceViews() {
        ViewModel fx = pipeline.view(EntityRef.of("trade", "FXS-20931"));
        var legs = (PanelData.Tabs) panel(fx, "legs").data();
        assertThat(legs.layout()).isEqualTo("columns");
        assertThat(legs.tabs().get(0).fields()).extracting(Cell::text).contains("EUR 25,000,000.00", "USD 29,350,000.00", "1.17400", "CLS");
        assertThat(((PanelData.Table) panel(fx, "cashflows").data()).total().cells()).extracting(Cell::text).contains("+18,420");

        ViewModel fut = pipeline.view(EntityRef.of("trade", "CFT-77120"));
        var ladder = (PanelData.Table) panel(fut, "settlements").data();
        assertThat(ladder.rows().get(4).highlight()).isTrue();
        assertThat(ladder.rows().get(4).cells().get(0).text()).isEqualTo("2026-09-30 (intraday)");
        assertThat(ladder.total().cells()).extracting(Cell::text).contains("+409,500");
        assertThat(((PanelData.Fields) panel(fut, "contract").data()).fields()).extracting(Cell::label).contains("Exchange", "Tick");

        ViewModel ns = pipeline.view(parser.require("NSET NS-NORTH-01"));
        var trades = (PanelData.Table) panel(ns, "trades").data();
        assertThat(trades.rows()).hasSize(4);
        assertThat(trades.more()).isEqualTo("10 more trades");
        assertThat(trades.rows().get(0).cells().get(0).link().mnemonic()).isEqualTo("TRD");
        var exposure = (PanelData.Chart) panel(ns, "exposure").data();
        assertThat(exposure.series()).hasSize(2);
        assertThat(exposure.limit()).isEqualTo(15_000_000d);
    }

    @Test
    void entitiesWithoutASutraAreInferred() {
        ViewModel v = pipeline.view(EntityRef.of("trade", "IRS-47102"));
        assertThat(v.provenance().layout()).isEqualTo("inference only");
        assertThat(v.strip()).extracting(Cell::text).contains("80,000,000", "+1,106,420");
        ViewModel curve = pipeline.view(parser.require("CRV USD-SOFR"));
        assertThat(curve.panels()).extracting(PanelView::kind).contains("line");
    }

    @Test
    void unknownEntitiesAndCommandsFailWithCodes() {
        assertThatThrownBy(() -> pipeline.view(EntityRef.of("trade", "NOPE-1")))
                .isInstanceOfSatisfying(DrishtiException.class, e -> assertThat(e.errorCode().code()).isEqualTo("DRS-1001"));
        assertThatThrownBy(() -> parser.require("WHAT is this"))
                .isInstanceOfSatisfying(DrishtiException.class, e -> assertThat(e.errorCode().code()).isEqualTo("DRS-4001"));
        assertThat(parser.parse("irs-48213")).contains(EntityRef.of("trade", "IRS-48213"));
    }

    @Test
    void typeAheadSuggestsMnemonicsRecentsAndEntities() {
        assertThat(suggestions.suggest("T", "ash", 10)).extracting(s -> s.mnemonic()).first().isEqualTo("TRD");
        var hits = suggestions.suggest("TRD IRS-4", "ash", 10);
        assertThat(hits).isNotEmpty().allSatisfy(s -> assertThat(s.kind()).isEqualTo("trade"));
        assertThat(hits.get(0).complete()).isEqualTo("TRD IRS-47102");
        assertThat(suggestions.suggest("NS-N", "ash", 10)).extracting(s -> s.complete()).contains("NSET NS-NORTH-01");
        recents.touch("ash", new EntityHit(EntityRef.of("trade", "IRS-48213"), "IRS-48213", "Interest rate swap"));
        assertThat(suggestions.suggest("", "ash", 10)).extracting(s -> s.type() + ":" + s.id()).containsExactly("recent:IRS-48213");
        assertThat(suggestions.suggest("TRD IRS-4", "ash", 10).get(0).id()).isEqualTo("IRS-48213");
    }

    @Test
    void warmViewsStayWellUnderFiftyMillisecondsAtP99() {
        EntityRef ref = EntityRef.of("trade", "IRS-48213");
        for (int i = 0; i < 300; i++) {
            pipeline.view(ref);
        }
        long[] nanos = new long[2000];
        for (int i = 0; i < nanos.length; i++) {
            long t = System.nanoTime();
            pipeline.view(ref);
            nanos[i] = System.nanoTime() - t;
        }
        Arrays.sort(nanos);
        double p99 = nanos[(int) (nanos.length * 0.99)] / 1e6;
        assertThat(p99).as("warm p99 ms").isLessThan(50);
        assertThat(pipeline.layoutHitRate()).isGreaterThan(0.99);
    }
}
