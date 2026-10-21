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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.server.security.Principal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Chip Store and saved boards over one content store backend (UI_BOARD.md phase P2): the lifecycle in order ({@code DRS-2078}), no
 * self-review, where-used after save and delete, compatible-with verification and boards following, sharing, copying, and two nodes
 * saving the same board ({@code DRS-5057}). Each backend runs the same cases.
 */
abstract class ChipStoreContract {

    /** A store; {@link #second()} opens another handle on the same store, as a second node would. */
    protected abstract ContentStore open() throws Exception;

    protected abstract ContentStore second() throws Exception;

    static final Principal ALICE = new Principal("alice", List.of("analyst")), BOB = new Principal("bob", List.of("approver")),
            CAROL = new Principal("carol", List.of("steward")), DAVE = new Principal("dave", List.of("analyst"));

    /** Alice authors, Bob reviews, Carol certifies; everyone saves; Alice and Dave are contributors of 'desk'. */
    static final UiAccess ACCESS = new UiAccess() {
        public boolean mayView(Principal p) { return true; }
        public boolean maySave(Principal p) { return true; }
        public boolean mayAuthor(Principal p) { return p.user().equals("alice") || p.user().equals("bob"); }
        public boolean mayReview(Principal p) { return p.user().equals("bob") || p.user().equals("alice"); }
        public boolean mayCertify(Principal p) { return p.user().equals("carol"); }
        public boolean isAdmin(Principal p) { return false; }
        public String workspaceRole(String ws, Principal p) {
            return ws.equals("desk") && (p.user().equals("alice") || p.user().equals("dave")) ? "contributor"
                    : ws.equals("certified-desk") ? "contributor" : null;
        }
    };

    ContentStore store;
    ChipStore chips;
    BoardStore boards;
    UiSettings settings = new UiSettings(65536, 40, 120, 500, 3, List.of("certified-desk"), Path.of("../config/ui/chips"));

    @BeforeEach
    void setUp() throws Exception {
        store = open();
        chips = new ChipStore(store, ACCESS, settings.chipsDir());
        boards = new BoardStore(store, chips, ACCESS, settings);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    static String chipYaml(int version, String extraPins, String compat) {
        return """
                rachana: 1
                chip: pnl-strip
                version: %d
                title: P&L strip
                category: pnl
                %s
                manual:
                  purpose: The day's P&L of the rows as one strip.
                  pins: { rows: "The rows. Required.", selected: "Out. The id clicked."%s }
                  behaviour: Clicking sends selected.
                  defaults: Nothing selected.
                  limits: 1,000 rows.
                  examples:
                    - title: A book's P&L
                      board: |
                        chips:
                          s: { use: pnl-strip@%d, supply: { connector: trading/trade-search }, map: { rows.id: TRADE_ID, rows.value: PNL }%s }
                  not-for: A table (use filterable-table).
                  accessibility: Announced as a list.
                pins:
                  in:
                    rows: { role: data, type: rowset, required: true, shape: { id: { type: id, required: true }, value: { type: measure } } }
                %s
                  out:
                    selected: { type: id }
                body:
                  panels:
                    - { id: strip, kind: hbar, rows: "@rows", label: "@.id", value: "@.value", on-select: { emits: selected, value: "@.id" } }
                """.formatted(version, compat, extraPins.isEmpty() ? "" : ", extra: \"An extra pin.\"", version,
                        extraPins.isEmpty() ? "" : ", controls: { extra: x }", extraPins);
    }

    static final String EXTRA_REQUIRED = "    extra: { role: control, type: text, required: true }";
    static final String EXTRA_OPTIONAL = "    extra: { role: control, type: text }";

    /** Draft, submit, approve (Bob), publish: answers the published state. */
    ChipStore.State release(String yaml, int n) {
        chips.saveDraft("pnl-strip", yaml, chips.draftVersion("pnl-strip"), ALICE);
        chips.act("pnl-strip", n, "submit", null, ALICE);
        chips.act("pnl-strip", n, "approve", "fine", BOB);
        return chips.act("pnl-strip", n, "publish", null, ALICE);
    }

    static String board(String use) {
        return """
                rachana: 1
                board: book-pnl
                title: Book P&L
                chips:
                  strip: { use: %s, supply: { connector: trading/trade-search }, map: { rows.id: TRADE_ID, rows.value: PNL } }
                  ticket: { use: entity-card@1, supply: { connector: trading/trade }, map: { doc.id: TRADE_ID } }
                traces:
                  - { from: strip.selected, to: ticket.id }
                layout:
                  desktop:
                    columns: 12
                    areas:
                      - { chip: strip, at: [1, 1], span: [7, 4] }
                      - { chip: ticket, at: [8, 1], span: [5, 4] }
                """.formatted(use);
    }

    static void code(Runnable r, ErrorCode c) {
        assertThatThrownBy(r::run).isInstanceOf(DrishtiException.class).extracting(e -> ((DrishtiException) e).errorCode()).isEqualTo(c);
    }

    @Test
    void lifecycleRunsInOrderAndOutOfOrderStepsAre2078() {
        chips.saveDraft("pnl-strip", chipYaml(1, "", ""), null, ALICE);
        code(() -> chips.act("pnl-strip", 1, "publish", null, ALICE), ErrorCode.UI_CHIP_NOT_FOUND);
        chips.act("pnl-strip", 1, "submit", null, ALICE);
        code(() -> chips.act("pnl-strip", 1, "publish", null, ALICE), ErrorCode.UI_LIFECYCLE_ORDER);     // publish without approval
        code(() -> chips.act("pnl-strip", 1, "certify", null, CAROL), ErrorCode.UI_LIFECYCLE_ORDER);     // certify before publish
        code(() -> chips.act("pnl-strip", 1, "submit", null, ALICE), ErrorCode.UI_LIFECYCLE_ORDER);      // twice
        assertThat(chips.act("pnl-strip", 1, "approve", null, BOB).state()).isEqualTo(ChipStore.APPROVED);
        assertThat(chips.act("pnl-strip", 1, "publish", null, ALICE).state()).isEqualTo(ChipStore.PUBLISHED);
        code(() -> chips.saveDraft("pnl-strip", chipYaml(1, "", ""), chips.draftVersion("pnl-strip"), ALICE), ErrorCode.UI_LIFECYCLE_ORDER);
        code(() -> chips.act("pnl-strip", 1, "certify", null, ALICE), ErrorCode.UI_FORBIDDEN);
        assertThat(chips.act("pnl-strip", 1, "certify", null, CAROL).state()).isEqualTo(ChipStore.CERTIFIED);
        code(() -> chips.act("pnl-strip", 1, "withdraw", null, BOB), ErrorCode.BAD_REQUEST);             // needs the fault named
        assertThat(chips.act("pnl-strip", 1, "deprecate", null, ALICE).state()).isEqualTo(ChipStore.DEPRECATED);
        assertThat(chips.act("pnl-strip", 1, "withdraw", "wrong totals; use pnl-strip@2", BOB).state()).isEqualTo(ChipStore.WITHDRAWN);
        code(() -> chips.act("pnl-strip", 1, "certify", null, CAROL), ErrorCode.UI_LIFECYCLE_ORDER);
        assertThat(chips.events("pnl-strip", ALICE)).extracting(e -> e.get("action"))
                .containsExactly("draft", "submit", "approve", "publish", "certify", "deprecate", "withdraw");
        code(() -> chips.act("entity-card", 1, "deprecate", null, BOB), ErrorCode.UI_LIFECYCLE_ORDER);    // seed chips are read-only
    }

    @Test
    void aReviewerMayNotApproveTheirOwnChip() {
        chips.saveDraft("pnl-strip", chipYaml(1, "", ""), null, ALICE);
        chips.act("pnl-strip", 1, "submit", null, ALICE);
        code(() -> chips.act("pnl-strip", 1, "approve", null, ALICE), ErrorCode.FOUR_EYES);
        code(() -> chips.act("pnl-strip", 1, "approve", null, DAVE), ErrorCode.UI_FORBIDDEN);
        code(() -> chips.act("pnl-strip", 1, "reject", null, BOB), ErrorCode.BAD_REQUEST);
        assertThat(chips.act("pnl-strip", 1, "reject", "the manual is thin", BOB).state()).isEqualTo(ChipStore.DRAFT);
        assertThat(chips.find("pnl-strip", 1)).isEmpty();                        // back to the draft; the number is free again
        chips.act("pnl-strip", 1, "submit", null, ALICE);
        assertThat(chips.act("pnl-strip", 1, "approve", null, BOB).reviewer()).isEqualTo("bob");
    }

    @Test
    void drafts_areForTheAuthorsPersonalBoardsOnly() {
        chips.saveDraft("pnl-strip", chipYaml(1, "", ""), null, ALICE);
        boards.save(new BoardStore.Ref(BoardStore.USER, "alice", "book-pnl"), board("pnl-strip@1"), null, ALICE);
        code(() -> boards.save(new BoardStore.Ref(BoardStore.USER, "dave", "book-pnl"), board("pnl-strip@1"), null, DAVE), ErrorCode.UI_CHECK_FAILED);
        code(() -> boards.save(new BoardStore.Ref(BoardStore.WORKSPACE, "desk", "book-pnl"), board("pnl-strip@1"), null, ALICE), ErrorCode.UI_CHECK_FAILED);
        code(() -> boards.save(new BoardStore.Ref(BoardStore.USER, "alice", "other"), board("nope@1").replace("book-pnl", "other"), null, ALICE),
                ErrorCode.UI_CHECK_FAILED);
        release(chipYaml(1, "", ""), 1);
        boards.save(new BoardStore.Ref(BoardStore.WORKSPACE, "desk", "book-pnl"), board("pnl-strip@1"), null, DAVE);
        code(() -> boards.save(new BoardStore.Ref(BoardStore.WORKSPACE, "certified-desk", "book-pnl"), board("pnl-strip@1"), null, DAVE),
                ErrorCode.UI_CHECK_FAILED);                                    // a certified-only workspace
        boards.save(new BoardStore.Ref(BoardStore.WORKSPACE, "certified-desk", "seeded"), board("filterable-table@2").replace("book-pnl", "seeded")
                .replace("rows.value: PNL", "rows.label: PNL"), null, DAVE);   // seed chips are certified
    }

    @Test
    void whereUsedFollowsSavesAndDeletes() {
        release(chipYaml(1, "", ""), 1);
        BoardStore.Ref mine = new BoardStore.Ref(BoardStore.USER, "alice", "book-pnl");
        BoardStore.Ref desk = new BoardStore.Ref(BoardStore.WORKSPACE, "desk", "book-pnl");
        BoardStore.Saved a = boards.save(mine, board("pnl-strip@1"), null, ALICE);
        boards.save(desk, board("pnl-strip@1"), null, DAVE);
        List<Map<String, Object>> used = boards.whereUsed("pnl-strip", ALICE);
        assertThat(used).hasSize(1);
        assertThat(used.get(0).get("boards")).isEqualTo(List.of(mine.id(), desk.id()));
        assertThat(boards.whereUsed("pnl-strip", BOB).get(0)).containsEntry("boards", List.of()).containsEntry("others", 2);   // named only when visible
        assertThat(boards.whereUsed("entity-card", ALICE).get(0).get("boards")).isEqualTo(List.of(mine.id(), desk.id()));
        BoardStore.Saved a2 = boards.save(mine, board("filterable-table@2").replace("rows.value: PNL", "rows.label: PNL"), a.version(), ALICE);
        assertThat(boards.whereUsed("pnl-strip", ALICE).get(0).get("boards")).isEqualTo(List.of(desk.id()));
        assertThat(boards.whereUsed("filterable-table", ALICE).get(0).get("boards")).isEqualTo(List.of(mine.id()));
        boards.delete(mine, a2.version(), ALICE);
        assertThat(boards.whereUsed("filterable-table", ALICE)).isEmpty();
        assertThat(boards.whereUsed("entity-card", ALICE).get(0).get("boards")).isEqualTo(List.of(desk.id()));
        code(() -> boards.get(mine, ALICE), ErrorCode.UI_BOARD_NOT_FOUND);
    }

    @Test
    void revisionsAreKeptAndTheStoreSetsOwnerAndVersion() {
        BoardStore.Ref mine = new BoardStore.Ref(BoardStore.USER, "alice", "book-pnl");
        String b = board("filterable-table@2").replace("rows.value: PNL", "rows.label: PNL") + "owner: mallory\n";
        BoardStore.Saved s = boards.save(mine, b, null, ALICE);
        for (int i = 0; i < 4; i++) {
            s = boards.save(mine, b.replace("Book P&L", "Book P&L " + i), s.version(), ALICE);
        }
        Map<String, Object> doc = UiDocs.read(s.text());
        assertThat(doc).containsEntry("owner", "alice").containsEntry("version", 5).containsEntry("title", "Book P&L 3");
        assertThat(boards.revisions(mine, ALICE)).containsExactly(4, 3, 2);    // the last three kept (settings.revisions)
        assertThat(boards.revision(mine, 2, ALICE)).contains("Book P&L 0");
    }

    @Test
    void personalBoardsArePrivateUntilSharedAndCopyToAWorkspace() {
        BoardStore.Ref mine = new BoardStore.Ref(BoardStore.USER, "alice", "book-pnl");
        boards.save(mine, board("filterable-table@2").replace("rows.value: PNL", "rows.label: PNL"), null, ALICE);
        code(() -> boards.get(mine, DAVE), ErrorCode.UI_BOARD_NOT_FOUND);
        assertThat(boards.list(null, DAVE)).isEmpty();
        boards.share(mine, List.of("dave"), ALICE);
        assertThat(boards.get(mine, DAVE).sharedWith()).containsExactly("dave");
        assertThat(boards.list(null, DAVE)).extracting(m -> m.get("access")).containsExactly("shared");
        code(() -> boards.save(mine, board("filterable-table@2"), null, DAVE), ErrorCode.UI_FORBIDDEN);   // shared is read-only
        BoardStore.Saved copy = boards.copyToWorkspace(mine, "desk", "desk-pnl", DAVE);
        assertThat(UiDocs.read(copy.text())).containsEntry("owner", "dave").containsEntry("workspace", "desk").containsEntry("board", "desk-pnl");
        assertThat(boards.list("desk", ALICE)).filteredOn(m -> "workspace".equals(m.get("access"))).extracting(m -> m.get("board")).containsExactly("desk-pnl");
        code(() -> boards.copyToWorkspace(mine, "desk", "x", BOB), ErrorCode.UI_BOARD_NOT_FOUND);
        boards.share(mine, List.of(), ALICE);
        code(() -> boards.get(mine, DAVE), ErrorCode.UI_BOARD_NOT_FOUND);
    }

    @Test
    void compatibleWithIsVerifiedAndBoardsFollow() {
        release(chipYaml(1, "", ""), 1);
        BoardStore.Ref mine = new BoardStore.Ref(BoardStore.USER, "alice", "book-pnl");
        boards.save(mine, board("pnl-strip@1"), null, ALICE);
        // a required pin without a default breaks compatibility
        chips.saveDraft("pnl-strip", chipYaml(2, EXTRA_REQUIRED, "compatible-with: [1]"), chips.draftVersion("pnl-strip"), ALICE);
        chips.act("pnl-strip", 2, "submit", null, ALICE);
        chips.act("pnl-strip", 2, "approve", null, BOB);
        code(() -> chips.act("pnl-strip", 2, "publish", null, ALICE), ErrorCode.UI_LIFECYCLE_ORDER);
        assertThat(UiDocs.read(boards.get(mine, ALICE).text())).extracting(d -> UiDocs.uses(d).get("strip")).isEqualTo("pnl-strip@1");
        // an optional one keeps it: the board moves to version 3 by itself, as a new revision
        release(chipYaml(3, EXTRA_OPTIONAL, "compatible-with: [1]"), 3);
        Map<String, Object> doc = UiDocs.read(boards.get(mine, ALICE).text());
        assertThat(UiDocs.uses(doc).get("strip")).isEqualTo("pnl-strip@3");
        assertThat(doc.get("version")).isEqualTo(2);
        assertThat(boards.whereUsed("pnl-strip", ALICE)).extracting(m -> m.get("version")).containsExactly(3);
        assertThat(chips.find(new com.ash.drishti.rachana.ui.ChipRef("pnl-strip", 1)).orElseThrow().newerCompatible()).isEqualTo(3);
    }

    @Test
    void twoNodesSavingTheSameBoardGiveOneSuccessAndOneConflict() throws Exception {
        try (ContentStore other = second()) {
            ChipStore chips2 = new ChipStore(other, ACCESS, settings.chipsDir());
            BoardStore node2 = new BoardStore(other, chips2, ACCESS, settings);
            BoardStore.Ref desk = new BoardStore.Ref(BoardStore.WORKSPACE, "desk", "book-pnl");
            String b = board("filterable-table@2").replace("rows.value: PNL", "rows.label: PNL");
            String v = boards.save(desk, b, null, ALICE).version();
            assertThat(node2.get(desk, DAVE).version()).isEqualTo(v);
            boards.save(desk, b.replace("Book P&L", "From node one"), v, ALICE);
            code(() -> node2.save(desk, b.replace("Book P&L", "From node two"), v, DAVE), ErrorCode.CONTENT_CONFLICT);
            code(() -> node2.save(desk, b, null, DAVE), ErrorCode.CONTENT_CONFLICT);                    // a create over an existing board
            assertThat(node2.get(desk, DAVE).text()).contains("From node one");
            code(() -> node2.delete(desk, v, ALICE), ErrorCode.CONTENT_CONFLICT);
            // two reviewers approving at once on two nodes: the chip's state is compare-and-set too
            chips.saveDraft("pnl-strip", chipYaml(1, "", ""), null, ALICE);
            chips.act("pnl-strip", 1, "submit", null, ALICE);
            assertThat(chips2.versions("pnl-strip", BOB)).extracting(ChipStore.State::state).containsExactly(ChipStore.IN_REVIEW);
        }
    }

    @Test
    void sizesAreLimited() {
        UiSettings small = new UiSettings(2000, 1, 120, 1, 50, List.of(), settings.chipsDir());
        BoardStore tight = new BoardStore(store, chips, ACCESS, small);
        code(() -> tight.save(new BoardStore.Ref(BoardStore.USER, "alice", "book-pnl"), board("filterable-table@2"), null, ALICE), ErrorCode.UI_TOO_LARGE);
        code(() -> tight.save(new BoardStore.Ref(BoardStore.USER, "alice", "book-pnl"), board("filterable-table@2") + "#" + "x".repeat(3000), null, ALICE),
                ErrorCode.UI_TOO_LARGE);
    }

    @Test
    void theListingOffersReleasedChipsAndTheAuthorsOwn() {
        assertThat(chips.list(null, null, null, null, DAVE)).hasSize(85);       // 86 seed versions, filterable-table has two
        chips.saveDraft("pnl-strip", chipYaml(1, "", ""), null, ALICE);
        assertThat(chips.list(null, null, "strip", null, ALICE)).extracting(m -> m.get("mine")).containsExactly(true);
        assertThat(chips.list(null, null, "strip", null, DAVE)).isEmpty();
        assertThat(chips.list("tables", "master", null, true, DAVE)).extracting(m -> m.get("chip")).containsExactly("filterable-table");
        code(() -> chips.get("pnl-strip", 1, DAVE), ErrorCode.UI_CHIP_NOT_FOUND);
    }
}
