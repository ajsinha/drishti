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
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The board check: the examples of UI_BOARD.md pass, and one failing board per rule. */
class BoardCheckTest {

    /** A clean two-chip board: a trade table and a ticket that follows its selection. */
    private static final String BASE = """
            rachana: 1
            board: t
            chips:
              trades: { use: filterable-table@2, supply: { connector: trading/trade-search }, map: { rows.id: TRADE_ID } }
              ticket: { use: entity-card@1, supply: { connector: trading/trade } }
            traces:
              - { from: trades.selected, to: ticket.id }
            layout:
              desktop: { columns: 12, areas: [ { chip: trades, at: [1, 1], span: [6, 4] }, { chip: ticket, at: [7, 1], span: [6, 4] } ] }
            """;

    private static BoardCheckContext ctx() {
        return BoardCheckContext.personal(UiFixtures.chips());
    }

    private static List<UiProblem> check(String board) {
        return BoardCheck.check(board, ctx());
    }

    private static List<UiProblem> check(String board, BoardCheckContext ctx) {
        return BoardCheck.check(board, ctx);
    }

    private static void fails(List<UiProblem> problems, String rule, String path) {
        assertThat(problems).as(problems.toString()).anySatisfy(p -> {
            assertThat(p.rule()).isEqualTo(rule);
            assertThat(p.error()).isTrue();
            assertThat(p.path()).isEqualTo(path);
        });
    }

    private static void warns(List<UiProblem> problems, String rule, String path) {
        assertThat(problems).as(problems.toString()).anySatisfy(p -> {
            assertThat(p.rule()).isEqualTo(rule);
            assertThat(p.error()).isFalse();
            assertThat(p.path()).isEqualTo(path);
        });
        assertThat(BoardCheck.failed(problems)).as(problems.toString()).isFalse();
    }

    // ---------------------------------------------------------------------------------------------- the examples

    @Test
    void theBaseBoardIsClean() {
        assertThat(check(BASE)).isEmpty();
        assertThat(check(BASE, ctx().withSuppliers(UiFixtures.suppliers()))).isEmpty();
    }

    @Test
    void theBookTradesBoardOfSection31Passes() {
        for (String file : List.of("book-trades.board.yaml", "book-trades.board.json")) {
            assertThat(check(UiFixtures.text(file))).as(file).isEmpty();
            List<UiProblem> withSuppliers = check(UiFixtures.text(file), ctx().withSuppliers(UiFixtures.suppliers()).asShared(false));
            assertThat(withSuppliers).as(file).singleElement().satisfies(p -> {
                assertThat(p.rule()).isEqualTo("BC-W5");                      // MTM_USD is masked for this author
                assertThat(p.path()).isEqualTo("chips.trades.map.rows.measures");
            });
        }
    }

    // ---------------------------------------------------------------------------------------------- one failing board per rule

    @Test
    void bc01TheDocumentReadsAndIsWellFormed() {
        fails(check(BASE.replace("rachana: 1", "rachana: 2")), "BC-01", "rachana");
        fails(check(BASE.replace("ticket: {", "Ticket: {")), "BC-01", "chips.Ticket");
        fails(check("rachana: 1\nboard: t\n"), "BC-01", "chips");
        fails(check("chips: [unclosed"), "BC-01", "line 1, column 17");
    }

    @Test
    void bc02EveryUseExistsAtAFixedVersionAndIsNotWithdrawn() {
        fails(check(BASE.replace("entity-card@1", "entity-card")), "BC-02", "chips.ticket.use");
        fails(check(BASE.replace("entity-card@1", "entity-card@9")), "BC-02", "chips.ticket.use");
        ChipCatalog withdrawn = ChipCatalog.of(Map.of(new ChipRef("entity-card", 1), new ChipCatalog.Entry(UiFixtures.card(), ChipState.WITHDRAWN, null)))
                .then(UiFixtures.chips());
        fails(check(BASE, BoardCheckContext.personal(withdrawn)), "BC-02", "chips.ticket.use");
    }

    @Test
    void bc03EveryRequiredPinIsConnected() {
        fails(check(BASE.replace("  - { from: trades.selected, to: ticket.id }\n", "")), "BC-03", "chips.ticket.id");
        fails(check(BASE.replace(", map: { rows.id: TRADE_ID }", ", map: { rows.date: TRADE_DATE }")), "BC-03", "chips.trades.map.rows.id");
        fails(check(BASE.replace("{ use: filterable-table@2, supply: { connector: trading/trade-search }, map: { rows.id: TRADE_ID } }",
                "{ use: filterable-table@2 }")), "BC-03", "chips.trades.rows");
    }

    @Test
    void bc04EveryConnectionIsTypeCompatible() {
        fails(check(BASE.replace("map: { rows.id: TRADE_ID } }", "map: { rows.id: TRADE_ID }, controls: { page-size: 500 } }")), "BC-04",
                "chips.trades.controls.page-size");
        fails(check(BASE.replace("from: trades.selected", "from: trades.filter-out")), "BC-04", "traces[0]");
        fails(check(BASE.replace("map: { rows.id: TRADE_ID }", "map: { rows.id: TRADE_ID, rows.date: COUNTERPARTY_NAME }"),
                ctx().withSuppliers(UiFixtures.suppliers())), "BC-04", "chips.trades.map.rows.date");
    }

    @Test
    void bc05EveryMapKeyAndAttributeExists() {
        fails(check(BASE.replace("rows.id: TRADE_ID", "rows.id: TRADE_ID, rows.colour: X")), "BC-05", "chips.trades.map.rows.colour");
        fails(check(BASE.replace("rows.id: TRADE_ID", "rows.id: TRADE_IDX"), ctx().withSuppliers(UiFixtures.suppliers())), "BC-05",
                "chips.trades.map.rows.id");
        fails(check(BASE.replace("map: { rows.id: TRADE_ID } }", "map: { rows.id: TRADE_ID }, controls: { rows: 1 } }")), "BC-05",
                "chips.trades.controls.rows");
        fails(check(BASE.replace("rows.id: TRADE_ID", "rows.id: TRADE_ID, rows.measures: [A, B, C, D, E, F, G]")), "BC-05",
                "chips.trades.map.rows.measures");
    }

    @Test
    void bc06EveryTraceNamesRealPinsAndNoInPinHasTwoSources() {
        fails(check(BASE.replace("to: ticket.id", "to: ticket.nope")), "BC-06", "traces[0].to");
        fails(check(BASE.replace("from: trades.selected", "from: nobody.selected")), "BC-06", "traces[0].from");
        fails(check(BASE.replace("from: trades.selected", "from: board.params.book")), "BC-06", "traces[0].from");
        fails(check(BASE.replace("  - { from: trades.selected, to: ticket.id }\n",
                        "  - { from: trades.selected, to: ticket.id }\n  - { from: trades.selected, to: ticket.id }\n")), "BC-06", "traces[1].to");
    }

    @Test
    void bc07NoCycleAmongDataPins() {
        String data = """
                rachana: 1
                board: t
                chips:
                  a: { use: filterable-table@2 }
                  b: { use: filterable-table@2 }
                traces:
                  - { from: a.visible, to: b.rows }
                  - { from: b.visible, to: a.rows }
                layout: { desktop: { areas: [ { chip: a, at: [1, 1], span: [6, 4] }, { chip: b, at: [7, 1], span: [6, 4] } ] } }
                """;
        fails(check(data), "BC-07", "traces[0]");
        String control = """
                rachana: 1
                board: t
                chips:
                  a: { use: entity-card@1, supply: { connector: trading/trade } }
                  b: { use: entity-card@1, supply: { connector: trading/trade } }
                traces:
                  - { from: a.open, to: b.id }
                  - { from: b.open, to: a.id }
                layout: { desktop: { areas: [ { chip: a, at: [1, 1], span: [6, 4] }, { chip: b, at: [7, 1], span: [6, 4] } ] } }
                """;
        fails(check(control), "BC-07", "traces[0]");
    }

    @Test
    void bc08EverySupplierIsOneTheAuthorMayRead() {
        fails(check(BASE.replace("connector: trading/trade }", "connector: secret/vault }"), ctx().withSuppliers(UiFixtures.suppliers())), "BC-08",
                "chips.ticket.supply");
    }

    @Test
    void bc09TheBoardIsWithinItsLimits() {
        BoardCheckContext tight = new BoardCheckContext(UiFixtures.chips(), null, new BoardCheckContext.Limits(64 * 1024, 1, 120, 3), false, false, null, false);
        fails(check(BASE, tight), "BC-09", "chips");
        BoardCheckContext small = new BoardCheckContext(UiFixtures.chips(), null, new BoardCheckContext.Limits(100, 40, 120, 3), false, false, null, false);
        fails(check(BASE, small), "BC-09", "");
    }

    @Test
    void bc10TheLayoutPlacesEveryInstanceOnceWithoutOverlap() {
        fails(check(BASE.replace(", { chip: ticket, at: [7, 1], span: [6, 4] }", "")), "BC-10", "layout.desktop");
        fails(check(BASE.replace("{ chip: ticket, at: [7, 1]", "{ chip: ticket, at: [5, 1]")), "BC-10", "layout.desktop.areas[1]");
        fails(check(BASE.replace("{ chip: ticket, at: [7, 1]", "{ chip: tikcet, at: [7, 1]")), "BC-10", "layout.desktop.areas[1]");
        fails(check(BASE.replace("{ chip: ticket, at: [7, 1], span: [6, 4] }", "{ chip: ticket, at: [9, 1], span: [6, 4] }")), "BC-10",
                "layout.desktop.areas[1]");
        fails(check(BASE.substring(0, BASE.indexOf("layout:"))), "BC-10", "layout.desktop");
    }

    @Test
    void bc11ASharedBoardUsesReleasedChipsOnly() {
        ChipCatalog drafts = ChipCatalog.of(ChipState.DRAFT, List.of(UiFixtures.table(), UiFixtures.card()));
        assertThat(check(BASE, BoardCheckContext.personal(drafts))).isEmpty();
        fails(check(BASE, BoardCheckContext.personal(drafts).asShared(false)), "BC-11", "chips.trades.use");
        fails(check(BASE, ctx().asShared(true)), "BC-11", "chips.ticket.use");
        ChipCatalog certified = ChipCatalog.of(ChipState.CERTIFIED, List.of(UiFixtures.table(), UiFixtures.card()));
        assertThat(check(BASE, BoardCheckContext.personal(certified).asShared(true))).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------- the warnings

    @Test
    void bcW1AnIdWithoutAKindFeedsAnIdOfAKind() {
        String board = """
                rachana: 1
                board: t
                params:
                  pick: { type: id }
                chips:
                  card: { use: pick-card@1 }
                local-chips:
                  - rachana: 1
                    chip: pick-card
                    version: 1
                    title: Pick card
                    category: test
                    manual: { purpose: p }
                    pins: { in: { id: { role: control, type: id, of: trading/trade, required: true } } }
                    body: { panels: [] }
                traces:
                  - { from: board.params.pick, to: card.id }
                layout: { desktop: { areas: [ { chip: card, at: [1, 1], span: [12, 4] } ] } }
                """;
        warns(check(board), "BC-W1", "traces[0]");
    }

    @Test
    void bcW2ADeprecatedOrUpgradableChip() {
        ChipCatalog old = ChipCatalog.of(Map.of(new ChipRef("entity-card", 1), new ChipCatalog.Entry(UiFixtures.card(), ChipState.DEPRECATED, 2)))
                .then(UiFixtures.chips());
        warns(check(BASE, BoardCheckContext.personal(old)), "BC-W2", "chips.ticket.use");
        ChipCatalog newer = ChipCatalog.of(Map.of(new ChipRef("entity-card", 1), new ChipCatalog.Entry(UiFixtures.card(), ChipState.PUBLISHED, 2)))
                .then(UiFixtures.chips());
        warns(check(BASE, BoardCheckContext.personal(newer)), "BC-W2", "chips.ticket.use");
    }

    @Test
    void bcW3AChipThatDrivesNothingWithAnEmptyOptionalPin() {
        String board = BASE.replace("  - { from: trades.selected, to: ticket.id }\n", "")
                .replace("supply: { connector: trading/trade } }", "supply: { connector: trading/trade }, controls: { id: \"1\" } }");
        warns(check(board), "BC-W3", "chips.trades");
    }

    @Test
    void bcW4ThePhoneLayoutLeavesAnInstanceOut() {
        warns(check(BASE + "  phone: { order: [trades] }\n"), "BC-W4", "layout.phone.order");
    }

    @Test
    void bcW5AMappedAttributeIsMaskedForTheAuthor() {
        warns(check(BASE.replace("rows.id: TRADE_ID", "rows.id: TRADE_ID, rows.measures: [MTM_USD]"), ctx().withSuppliers(UiFixtures.suppliers())),
                "BC-W5", "chips.trades.map.rows.measures");
    }

    @Test
    void aProblemCarriesAFix() {
        UiProblem p = check(BASE.replace("  - { from: trades.selected, to: ticket.id }\n", "")).stream().filter(UiProblem::error).findFirst().orElseThrow();
        assertThat(p.fix()).contains("controls.id").contains("ticket.id");
    }
}
