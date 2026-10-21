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
package com.ash.drishti.rachana.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The publish checks: pure, every out pin driven, deterministic, the manual complete, its examples pass. */
class ChipPublishCheckTest {

    private static final String TABLE = UiFixtures.text("filterable-table.v2.chip.yaml");

    private static List<UiProblem> check(String chip) {
        return ChipPublishCheck.check(chip, UiFixtures.chips());
    }

    private static void fails(List<UiProblem> problems, String rule, String path) {
        assertThat(problems).as(problems.toString()).anySatisfy(p -> {
            assertThat(p.rule()).isEqualTo(rule);
            assertThat(p.path()).isEqualTo(path);
        });
    }

    @Test
    void theFilterableTableOfSection21AndTheTestChipsPass() {
        assertThat(check(TABLE)).isEmpty();
        assertThat(check(UiFixtures.text("entity-card.v1.chip.yaml"))).isEmpty();
        assertThat(check(UiFixtures.text("cashflow-ladder.v1.chip.yaml"))).isEmpty();
    }

    @Test
    void cp01TheManualIsComplete() {
        fails(check(TABLE.replace("  accessibility: \"Arrow keys move the row cursor, Enter selects; the selection is announced.\"\n", "")), "CP-01",
                "manual.accessibility");
        fails(check(TABLE.replace("    page-size: \"Rows per page, 10 to 200. Default 50.\"\n", "")), "CP-01", "manual.pins.page-size");
        fails(check(TABLE.substring(0, TABLE.indexOf("manual:")) + TABLE.substring(TABLE.indexOf("pins:\n  in:"))), "CP-01", "manual");
    }

    @Test
    void cp02TheBodyReadsOnlyItsPins() {
        fails(check(TABLE.replace("rows: \"@rows\"", "rows: \"@trades\"")), "CP-02", "body.panels[0].rows");
        fails(check(TABLE.replace("      rows: \"@rows\"\n", "      source: trading/trade-search\n      rows: \"@rows\"\n")), "CP-02", "body.panels[0].source");
        fails(check(TABLE.replace("bind: \"@.label\"", "bind: \"COUNTERPARTY_NAME\"")), "CP-02", "body.panels[0].columns[2].bind");
        fails(check(TABLE.replace("%{page-size}", "%{pagesize}")), "CP-02", "body.panels[0].page-size");
    }

    @Test
    void cp03EveryOutPinIsDrivenAndEveryEmitIsDeclared() {
        fails(check(TABLE.replace("      exposes: { visible: \"@filtered\" }\n", "")), "CP-03", "pins.out.visible");
        fails(check(TABLE.replace("emits: filter-out", "emits: typed")), "CP-03", "body");
    }

    @Test
    void cp04TheBodyIsDeterministic() {
        fails(check(TABLE.replace("%{date-to|+inf}", "today()")), "CP-04", "body.panels[0].where[1]");
        fails(check(TABLE.replace("title: \"%{title|Rows}\"", "title: \"%{viewer}\"")), "CP-04", "body.panels[0].title");
    }

    @Test
    void cp05EveryExampleBoardPasses() {
        fails(check(TABLE.replace("to: ticket.id", "to: ticket.nope")), "CP-05", "manual.examples[0].traces[0].to");
        // the examples use entity-card@1: without it in the store they cannot pass
        assertThat(ChipPublishCheck.check(TABLE, ChipCatalog.empty())).anySatisfy(p -> assertThat(p.message()).startsWith("BC-02"));
    }
}
