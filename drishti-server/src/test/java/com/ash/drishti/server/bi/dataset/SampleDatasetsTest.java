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
package com.ash.drishti.server.bi.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The sample datasets shipped in config/bi/datasets parse and validate with no errors, and say what the tutorial says they say. */
class SampleDatasetsTest {

    private static final Path DIR = Path.of("src/test/resources/bi/samples-2.1/datasets");         // the 2.1 datasets (config/bi has their models)

    private static DatasetParser.Result load(String name) throws IOException {
        return DatasetParser.parse(Files.readString(DIR.resolve(name + ".dataset.yaml")));
    }

    @Test
    void everyShippedDatasetIsValidAndMarkedAsASample() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(DIR)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".dataset.yaml")).sorted().toList();
        }
        assertThat(files).extracting(p -> p.getFileName().toString()).contains("trades-basic.dataset.yaml", "trading-risk.dataset.yaml", "trading-cashflows.dataset.yaml");
        for (Path f : files) {
            DatasetParser.Result r = DatasetParser.parse(Files.readString(f));
            assertThat(r.problems().stream().filter(DatasetParser.Problem::error).filter(x -> !r.dataset().framed() || !"DRS-9190".equals(x.code())).toList())
                    .as(f.getFileName().toString()).isEmpty();                   // a composed or derived one is refused since 2.2, and only that
            assertThat(r.dataset().sample()).as("sample: true in " + f.getFileName()).isTrue();
            assertThat(r.dataset().description()).isNotBlank();
            assertThat(Files.readString(f).lines().filter(l -> l.stripLeading().startsWith("#")).count()).as("comments in " + f.getFileName()).isGreaterThan(8);
        }
    }

    @Test
    void theBasicSampleIsOneTableWithFourMeasuresAPythonMeasureAndADeskBookHierarchy() throws IOException {
        Dataset d = load("trades-basic").dataset();
        assertThat(d.tables()).containsOnlyKeys("trade");
        assertThat(d.measures().keySet()).containsExactly("Notional", "MTM", "Trades", "Average MTM per trade", "Desk P&L volatility");
        assertThat(d.hierarchies().get("Desk and book")).containsExactly("trade.DESK", "trade.PORTFOLIO");
    }

    @Test
    void theRiskSampleCrossesThreePacksAndCarriesARowRuleAndAMask() throws IOException {
        Dataset d = load("trading-risk").dataset();
        assertThat(d.tables().values().stream().map(Dataset.Table::connector).toList()).contains("risk-store", "credit-store", "reference-store");
        assertThat(d.relationships()).hasSize(5);
        assertThat(d.security().rows()).extracting(Dataset.RowRule::role).contains("desk-head", "risk");
        assertThat(d.security().masked()).contains("trades.COUNTERPARTY");
        assertThat(d.hierarchies().get("Trade date")).contains("trades.TRADE_DATE.year", "trades.TRADE_DATE.month");
        assertThat(d.rollups()).hasSize(1);
    }

    @Test
    void theCashflowSampleHasLegsAndCashflowsAsArrayTables() throws IOException {
        Dataset d = load("trading-cashflows").dataset();
        assertThat(d.tables().get("legs").each()).isEqualTo("LEGS");
        assertThat(d.tables().get("cashflows").each()).isEqualTo("LEGS[].CASH_FLOWS");
        assertThat(d.relationships()).extracting(Dataset.Relationship::fromTable).containsExactly("legs", "cashflows");
    }

    @Test
    void theExposureSampleHasNoRowRuleSoEveryoneSeesTheNettingSets() throws IOException {
        Dataset d = load("counterparty-exposure").dataset();
        assertThat(d.tables()).containsOnlyKeys("netting_sets", "counterparties");
        assertThat(d.security().rows()).isEmpty();
        assertThat(d.security().masked()).contains("counterparties.NAME");
        assertThat(d.measures().keySet()).contains("Exposure (EE peak)", "Exposure (PFE peak)");
    }

    /** A 2.1 composed dataset is still read (the converter needs its sources and join) but refused: removed in 2.2 (BI_MODEL_SPEC.md section 8). */
    @Test
    void theComposedSampleIsReadForTheConverterButRefused() throws IOException {
        DatasetParser.Result r = load("desk-pnl-vs-exposure");
        assertThat(r.problems()).anySatisfy(x -> assertThat(x.code() + " " + x.message()).startsWith("DRS-9190").contains("drishti.py bi migrate --models"));
        Dataset d = r.dataset();
        assertThat(d.composed()).isTrue();
        assertThat(d.compose().from().values()).extracting(Dataset.Input::dataset).containsExactly("trading-risk", "counterparty-exposure");
        assertThat(d.compose().joins().get(0).type()).isEqualTo("left");
        assertThat(d.measures().keySet()).containsExactly("P&L per exposure");
    }

    @Test
    void theDerivedSampleIsReadForTheConverterButRefused() throws IOException {
        DatasetParser.Result r = load("desk-pnl-vs-exposure-py");
        assertThat(r.problems()).as("read for the converter, refused to run").anySatisfy(x -> assertThat(x.code()).isEqualTo("DRS-9190"));
        Dataset d = r.dataset();
        assertThat(d.derivedFrame()).isTrue();
        assertThat(d.derived().transform()).isEqualTo("pnl-vs-exposure");
        assertThat(d.derived().inputs().values()).extracting(Dataset.Input::dataset).containsExactly("trading-risk", "counterparty-exposure");
        assertThat(d.tables().get(Dataset.RESULT).columns().keySet()).contains("counterparty", "desk_pnl", "ee_peak", "pnl_per_exposure");
        assertThat(d.measures().keySet()).contains("P&L per exposure");
    }
}
