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
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The sample datasets shipped in config/bi/datasets parse and validate with no errors, and say what the tutorial says they say. */
class SampleDatasetsTest {

    private static final Path DIR = Path.of("../config/bi/datasets");

    /** The shipped folder as the directory composed and derived datasets find their sources in. */
    private static final Composition.Directory FOLDER = ref -> {
        try (Stream<Path> s = Files.list(DIR)) {
            for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".dataset.yaml")).toList()) {
                String body = Files.readString(f);
                String file = f.getFileName().toString().replace(".dataset.yaml", "");
                if (file.equals(ref) || Composition.declaredName(body).equals(ref)) {
                    return Optional.of(new Composition.Found("file:dataset:" + file, body));
                }
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return Optional.empty();
    };

    @BeforeAll
    static void sources() {
        Composition.install(FOLDER);
    }

    @AfterAll
    static void noSources() {
        Composition.uninstall(FOLDER);
    }

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

    @Test
    void theExposureSampleHasNoRowRuleSoEveryoneSeesTheNettingSets() throws IOException {
        Dataset d = load("counterparty-exposure").dataset();
        assertThat(d.tables()).containsOnlyKeys("netting_sets", "counterparties");
        assertThat(d.security().rows()).isEmpty();
        assertThat(d.security().masked()).contains("counterparties.name");
        assertThat(d.measures().keySet()).contains("Exposure (EE peak)", "Exposure (PFE peak)");
    }

    @Test
    void theComposedSampleJoinsTwoDatasetsOnTheCounterpartyAndAddsAMeasureOfItsOwn() throws IOException {
        DatasetParser.Result r = load("desk-pnl-vs-exposure");
        assertThat(r.problems()).as("no problem at all, not even the fan-out warning").isEmpty();
        Dataset d = r.dataset();
        assertThat(d.composed()).isTrue();
        assertThat(d.compose().from().values()).extracting(Dataset.Input::dataset).containsExactly("trading-risk", "counterparty-exposure");
        assertThat(d.compose().joins()).hasSize(1);
        assertThat(d.compose().joins().get(0).on()).containsExactly("counterparty");
        assertThat(d.compose().joins().get(0).type()).isEqualTo("left");
        assertThat(d.compose().frame()).extracting(Dataset.FrameColumn::name).containsExactly("counterparty", "name", "Desk P&L", "Trades", "sector", "Exposure (EE peak)", "Exposure (PFE peak)");
        assertThat(d.tables().get(Dataset.JOINED).columns().keySet()).containsExactly("counterparty", "name", "Desk_P_L", "Trades", "sector", "Exposure_EE_peak", "Exposure_PFE_peak");
        assertThat(d.measures().keySet()).containsExactly("Desk P&L", "Trades", "Exposure (EE peak)", "Exposure (PFE peak)", "P&L per exposure");
        assertThat(d.measures().get("P&L per exposure").expr()).isEqualTo("measure('Desk P&L') / measure('Exposure (EE peak)')");
    }

    @Test
    void theDerivedSampleDeclaresTheColumnsOfItsTransformAndMeasuresOverThem() throws IOException {
        DatasetParser.Result r = load("desk-pnl-vs-exposure-py");
        assertThat(r.problems()).isEmpty();
        Dataset d = r.dataset();
        assertThat(d.derivedFrame()).isTrue();
        assertThat(d.derived().transform()).isEqualTo("pnl-vs-exposure");
        assertThat(d.derived().inputs().values()).extracting(Dataset.Input::dataset).containsExactly("trading-risk", "counterparty-exposure");
        assertThat(d.tables().get(Dataset.RESULT).columns().keySet()).contains("counterparty", "desk_pnl", "ee_peak", "pnl_per_exposure");
        assertThat(d.measures().keySet()).contains("P&L per exposure");
    }
}
