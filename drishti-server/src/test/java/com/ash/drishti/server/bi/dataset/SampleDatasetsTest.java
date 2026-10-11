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

    private static final Path DIR = Path.of("../config/bi/datasets");

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
            assertThat(r.problems().stream().filter(DatasetParser.Problem::error).toList()).as(f.getFileName().toString()).isEmpty();
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
        assertThat(d.hierarchies().get("Desk and book")).containsExactly("trade.desk", "trade.book");
    }

    @Test
    void theRiskSampleCrossesThreePacksAndCarriesARowRuleAndAMask() throws IOException {
        Dataset d = load("trading-risk").dataset();
        assertThat(d.tables().values().stream().map(Dataset.Table::connector).toList()).contains("trading-store", "credit-store", "reference-store");
        assertThat(d.relationships()).hasSize(5);
        assertThat(d.security().rows()).extracting(Dataset.RowRule::role).contains("desk-head", "risk");
        assertThat(d.security().masked()).contains("counterpartyName");
        assertThat(d.hierarchies().get("Trade date")).contains("trades.tradeDate.year", "trades.tradeDate.month");
        assertThat(d.rollups()).hasSize(1);
    }

    @Test
    void theCashflowSampleHasLegsAndCashflowsAsArrayTables() throws IOException {
        Dataset d = load("trading-cashflows").dataset();
        assertThat(d.tables().get("legs").each()).isEqualTo("legs");
        assertThat(d.tables().get("cashflows").each()).isEqualTo("schedule");
        assertThat(d.relationships()).extracting(Dataset.Relationship::fromTable).containsExactly("legs", "cashflows");
    }
}
