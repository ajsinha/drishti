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
import java.util.List;
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
        assertThat(callnot.path("localChips").toString()).contains("local-deposit");
        assertThat(Files.readString(out.resolve("trading-endur-callnot.board.yaml"))).contains("use: \"deal-ticket@1\"", "board: \"trading-endur-callnot\"");
        Ran text = run(args("trading"));
        System.out.println(text.out());
        assertThat(text.out()).startsWith("pack trading: ");
    }

    @Test
    void everyPackDryRuns() throws Exception {
        for (String pack : List.of("core", "counterparty-risk", "market-data", "market-risk", "news-and-markets", "world-economy")) {
            Ran r = run(args(pack));
            System.out.println(r.out());
            assertThat(r.err()).isEmpty();
            assertThat(r.out()).startsWith("pack " + pack + ": ");
        }
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
