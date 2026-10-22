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
package com.ash.drishti.server.ui;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.identity.content.FileContentStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** P4b: {@code POST /ui/chips/check}, the chip designer's live publish check, its compatibility verdict and where used. */
class UiChipCheckTest {

    MockMvc mvc;
    ChipStore chips;
    BoardStore boards;

    @BeforeEach
    void setUp() throws Exception {
        Path root = Files.createDirectories(Path.of("target", "ui-store-tests", "check-" + UUID.randomUUID()));
        FileContentStore store = new FileContentStore(root);
        UiSettings settings = UiSettings.defaults(Path.of("../config/ui/chips"));
        chips = new ChipStore(store, ChipStoreContract.ACCESS, settings.chipsDir());
        boards = new BoardStore(store, chips, ChipStoreContract.ACCESS, settings);
        mvc = MockMvcBuilders.standaloneSetup(new UiChipToolsController(chips, boards, null, ChipStoreContract.ACCESS))
                .setControllerAdvice(UiControllerTest.advice()).build();
    }

    private org.springframework.test.web.servlet.ResultActions check(String yaml, String user) throws Exception {
        return mvc.perform(UiControllerTest.as(post("/api/v1/ui/chips/check").contentType(MediaType.TEXT_PLAIN).content(yaml), user));
    }

    @Test
    void checksADraftAndRefusesABreakingCompatibilityClaim() throws Exception {
        check(ChipStoreContract.chipYaml(1, "", ""), "alice").andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.previous").doesNotExist());
        check(ChipStoreContract.chipYaml(1, "", "").replace("rows: \"@rows\"", "rows: \"@trades\""), "alice")
                .andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.problems[0].rule").value("CP-02"));
        check(ChipStoreContract.chipYaml(1, "", ""), "dave").andExpect(status().isForbidden());

        chips.saveDraft("pnl-strip", ChipStoreContract.chipYaml(1, "", ""), chips.draftVersion("pnl-strip"), ChipStoreContract.ALICE);
        chips.act("pnl-strip", 1, "submit", null, ChipStoreContract.ALICE);
        chips.act("pnl-strip", 1, "approve", "fine", ChipStoreContract.BOB);
        chips.act("pnl-strip", 1, "publish", null, ChipStoreContract.ALICE);
        boards.save(new BoardStore.Ref("user", "alice", "book-pnl"), ChipStoreContract.board("pnl-strip@1"), null, ChipStoreContract.ALICE);

        check(ChipStoreContract.chipYaml(2, ChipStoreContract.EXTRA_OPTIONAL, "compatible-with: [1]"), "alice")
                .andExpect(jsonPath("$.ok").value(true)).andExpect(jsonPath("$.previous").value(1)).andExpect(jsonPath("$.breaking.length()").value(0))
                .andExpect(jsonPath("$.claimsCompatible").value(true)).andExpect(jsonPath("$.whereUsed[0].boards[0]").value("user:alice:book-pnl"));
        String retyped = ChipStoreContract.chipYaml(2, "", "compatible-with: [1]").replace("selected: { type: id }", "selected: { type: date }");
        check(retyped, "alice").andExpect(jsonPath("$.ok").value(false)).andExpect(jsonPath("$.breaking.length()").value(1))
                .andExpect(jsonPath("$.problems[?(@.rule == 'CP-06')]").exists());
        check(retyped.replace("compatible-with: [1]", ""), "alice").andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.claimsCompatible").value(false));
    }

    static final String COMPOSITE = """
            rachana: 1
            chip: book-search
            version: 1
            title: Book search
            category: modules
            manual:
              purpose: A search box over a filterable table.
              pins: { rows: "The rows. Required.", selected: "Out. The id selected." }
              behaviour: Typing filters the table; clicking a row sends selected.
              defaults: No filter.
              limits: As the table.
              examples:
                - title: Trades
                  board: |
                    chips:
                      m: { use: book-search@1, supply: { connector: trading/trade-search }, map: { rows.id: TRADE_ID } }
              not-for: One record.
              accessibility: As its chips.
            pins:
              in:
                rows: { role: data, type: rowset, required: true, shape: { id: { type: id, required: true }, label: { type: dimension } } }
              out:
                selected: { type: id }
            body:
              board:
                chips:
                  s: { use: search-box@1 }
                  t: { use: filterable-table@2 SUPPLY}
                traces:
                  - { from: s.filter-out, to: t.filter }
              wires: { rows: t.rows }
              exposes: { selected: t.selected }
            """;

    /** CP-07: a composite chip is a board with no connectors; every required inner pin fed; types match; at most three levels. */
    @Test
    void aCompositeChipIsABoardWithNoConnectors() throws Exception {
        check(COMPOSITE.replace(" SUPPLY", ""), "alice").andExpect(jsonPath("$.ok").value(true));
        check(COMPOSITE.replace(" SUPPLY", ", supply: { connector: trading/trade-search }"), "alice").andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.problems[0].rule").value("CP-07")).andExpect(jsonPath("$.problems[0].path").value("body.board.chips.t.supply"));
        check(COMPOSITE.replace(" SUPPLY", "").replace("wires: { rows: t.rows }", "wires: {}"), "alice").andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.problems[?(@.path == 'body.board.chips.t.rows')].rule").value("CP-07"));
        check(COMPOSITE.replace(" SUPPLY", "").replace("selected: { type: id }", "selected: { type: date }"), "alice")
                .andExpect(jsonPath("$.problems[?(@.path == 'pins.out.selected')].rule").value("CP-07"));
    }
}
