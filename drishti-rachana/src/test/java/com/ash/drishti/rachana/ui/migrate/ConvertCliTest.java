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
package com.ash.drishti.rachana.ui.migrate;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.rachana.ui.ChipState;
import com.ash.drishti.rachana.ui.UiCli;
import com.ash.drishti.rachana.ui.UiDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code ui convert --pack P --dry-run} over the repository's packs, seed chips and BI reports (UI_BOARD.md 11.2, phase P5). */
class ConvertCliTest {

    static final Path ROOT = Path.of("..");

    private record Ran(int code, String out, String err) {}

    private static Ran run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = UiCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Ran(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static String[] args(String pack, String... more) {
        List<String> a = new java.util.ArrayList<>(List.of("ui", "convert", "--pack", pack, "--dry-run", "--packs", ROOT.resolve("config/packs").toString(),
                "--bi", ROOT.resolve("config/bi").toString(), "--chips", ROOT.resolve("config/ui/chips").toString()));
        a.addAll(List.of(more));
        return a.toArray(String[]::new);
    }

    @Test
    void theTradingPackConvertsEveryPageAndReportsItsLocalChips(@TempDir Path out) throws Exception {
        Ran r = run(args("trading", "--json", "--out", out.toString()));
        assertThat(r.err()).isEmpty();
        JsonNode rep = UiDocuments.tree(r.out());
        System.out.println(r.out().length() > 4000 ? r.out().substring(0, 4000) : r.out());
        assertThat(rep.path("entityPages").asInt()).isEqualTo(134);
        assertThat(rep.path("fails").asInt()).as(r.out()).isZero();
        assertThat(r.code()).isEqualTo(UiCli.OK);
        JsonNode callnot = null;
        for (JsonNode i : rep.path("items")) {
            if (i.path("screen").asText().equals("sutra:endur-callnot@1")) {
                callnot = i;
            }
        }
        assertThat(callnot).isNotNull();
        assertThat(callnot.path("chips").toString()).contains("deal-ticket@1", "legs@1", "flow-schedule@1", "events@1", "payments@1", "deal-footer@1");
        assertThat(callnot.path("chips").toString()).contains("kv-summary@1");      // the deposit panel: a generic chip since the record chips
        assertThat(callnot.path("localChips")).isEmpty();
        assertThat(Files.readString(out.resolve("trading-endur-callnot.board.yaml"))).contains("use: \"deal-ticket@1\"", "board: \"trading-endur-callnot\"");
        Ran text = run(args("trading"));
        System.out.println(text.out());
        assertThat(text.out()).startsWith("pack trading: ");
    }

    /**
     * Every pack converts with no failure, its panels on Chip Store chips (the generic record chips, UI_BOARD.md 12.4): the board-local chips left are
     * the true one-offs, each listed with its reason, and no more than the counts the docs give.
     */
    @Test
    void everyPackConvertsOnChipStoreChipsWithOnlyTrueOneOffsLeft() throws Exception {
        Map<String, Integer> oneOffs = new LinkedHashMap<>();
        oneOffs.put("core", 0);
        oneOffs.put("counterparty-risk", 1);              // netting-set trades: it reads four page controls
        oneOffs.put("market-data", 0);
        oneOffs.put("market-risk", 0);
        oneOffs.put("news-and-markets", 8);               // panels reading another record through source: (a chip names no source, CP-02)
        oneOffs.put("world-economy", 0);
        oneOffs.put("trading", 24);                       // computed rows (group, flatten) and the page-control tables
        for (Map.Entry<String, Integer> e : oneOffs.entrySet()) {
            Ran r = run(args(e.getKey(), "--json"));
            assertThat(r.err()).isEmpty();
            JsonNode rep = UiDocuments.tree(r.out());
            System.out.println("pack " + e.getKey() + ": screens " + rep.path("screens") + ", clean " + rep.path("clean") + ", local " + rep.path("local")
                    + ", local chips " + rep.path("localChips") + ", fails " + rep.path("fails"));
            for (JsonNode i : rep.path("items")) {
                i.path("localChips").forEach(l -> System.out.println("  " + i.path("screen").asText() + " " + l.path("chip").asText() + ": "
                        + l.path("reason").asText()));
                if (i.path("status").asText().equals("fails")) {
                    System.out.println("  FAILS " + i);
                }
            }
            assertThat(rep.path("fails").asInt()).as(e.getKey()).isZero();
            assertThat(r.code()).isEqualTo(UiCli.OK);
            assertThat(rep.path("localChips").asInt()).as(e.getKey()).isLessThanOrEqualTo(e.getValue());
        }
    }

    @Test
    void theFormerBoardCheckFailuresConvert() throws Exception {
        Ran r = run(args("market-risk", "--json"));
        JsonNode rep = UiDocuments.tree(r.out());
        Ran w = run(args("world-economy", "--json"));
        String all = r.out() + w.out();
        assertThat(all).contains("report:python-desk-risk/risk", "report:region-economics/regions");
        for (JsonNode i : rep.path("items")) {
            assertThat(i.path("status").asText()).as(i.toString()).isNotEqualTo("fails");
        }
        for (JsonNode i : UiDocuments.tree(w.out()).path("items")) {
            assertThat(i.path("status").asText()).as(i.toString()).isNotEqualTo("fails");
        }
    }

    @Test
    void aRecordPanelBecomesAGenericChipWithTheFieldNamesInTheBoard() throws Exception {
        var chips = UiCli.folder(ROOT.resolve("config/ui/chips"), ChipState.CERTIFIED);
        String text = Files.readString(ROOT.resolve("config/packs/market-data/sutras/market-data/ir-curve.v1.sutra.yaml"));
        Conversion c = new PageConverter(chips).convert("market-data", text);
        assertThat(c.status()).isEqualTo(Conversion.Status.CLEAN);
        JsonNode curve = c.document().path("chips").path("curve");
        assertThat(curve.path("use").asText()).isEqualTo("series-line@1");
        assertThat(curve.path("map").path("rows").asText()).isEqualTo("POINTS");
        assertThat(curve.path("map").path("rows.x").asText()).isEqualTo("TENOR");
        assertThat(c.document().path("chips").path("points").path("use").asText()).isEqualTo("list-table@1");
        String chip = Files.readString(ROOT.resolve("config/ui/chips/series-line.v1.chip.yaml"));
        assertThat(chip.substring(chip.indexOf("body:"))).doesNotContain("TENOR", "POINTS", "ZERO_RATE");
        var board = UiDocuments.readBoard(c.document().toString());
        JsonNode sutra = BoardSutra.toSutra(board, ref -> chips.find(ref).map(e -> e.doc())).orElseThrow();
        assertThat(sutra.path("panels")).isEqualTo(UiDocuments.tree(text).path("panels"));
    }

    @Test
    void usage() {
        assertThat(run("ui", "convert", "--pack", "trading").code()).isEqualTo(UiCli.USAGE);
        assertThat(run("ui", "convert", "--dry-run").code()).isEqualTo(UiCli.USAGE);
        assertThat(run(args("no-such-pack")).code()).isEqualTo(UiCli.USAGE);
    }

    @Test
    void aBoardPaintsBackIntoThePageItCameFrom() throws Exception {
        var chips = UiCli.folder(ROOT.resolve("config/ui/chips"), ChipState.CERTIFIED);
        String text = Files.readString(ROOT.resolve("config/packs/trading/sutras/pages/counterparty-trades.v1.sutra.yaml"));
        Conversion c = new PageConverter(chips).convert("trading", text);
        assertThat(c.status()).isEqualTo(Conversion.Status.LOCAL);
        assertThat(c.traces()).isEqualTo(1);              // the status control into the trades table
        var board = UiDocuments.readBoard(c.document().toString());
        JsonNode sutra = BoardSutra.toSutra(board, ref -> chips.find(ref).map(e -> e.doc())).orElseThrow();
        JsonNode original = UiDocuments.tree(text);
        assertThat(sutra.path("panels")).isEqualTo(original.path("panels"));
        assertThat(sutra.path("strip")).isEqualTo(original.path("strip"));
        assertThat(sutra.path("title")).isEqualTo(original.path("title"));
        assertThat(sutra.path("controls")).isEqualTo(original.path("controls"));
    }
}
