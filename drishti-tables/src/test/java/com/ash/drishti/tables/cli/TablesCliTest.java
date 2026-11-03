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
package com.ash.drishti.tables.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code tables list|check|preview|lineage|migrate}: exit codes, words and JSON, all in a temporary folder. */
class TablesCliTest {

    @TempDir Path cwd;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    int run(String... args) {
        out.reset();
        err.reset();
        return new TablesCli(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8), cwd).run(List.of(args));
    }

    String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    void tables() throws Exception {
        Files.createDirectories(cwd.resolve("config/tables/trading"));
        Files.writeString(cwd.resolve("config/tables/trading/trades.table.yaml"), """
                rachana: 3
                table: trading.trades
                from: { kind: trade }
                keys: [TRADE_ID]
                columns:
                  TRADE_ID: $.TRADE_ID
                  MTM:      { path: $.MTM, type: decimal }
                """);
        Files.writeString(cwd.resolve("trades.jsonl"), "{\"TRADE_ID\":\"T1\",\"MTM\":5}\n{\"TRADE_ID\":\"T1\",\"MTM\":6}\n");
    }

    @Test
    void usageAndUnknownCommands() {
        assertThat(run()).isEqualTo(TablesCli.USAGE);
        assertThat(run("--help")).isEqualTo(TablesCli.OK);
        assertThat(out()).contains("tables list|check|preview|lineage|migrate");
        assertThat(run("nope")).isEqualTo(TablesCli.USAGE);
        assertThat(run("check", "--bogus")).isEqualTo(TablesCli.USAGE);
        assertThat(run("migrate", "--dry-run", "--check")).isEqualTo(TablesCli.USAGE);
    }

    @Test
    void listCheckPreviewAndLineage() throws Exception {
        tables();
        assertThat(run("list")).isEqualTo(TablesCli.OK);
        assertThat(out()).contains("table  trading.trades@1.0.0  from kind, 2 columns, materialize none");
        assertThat(run("check")).isEqualTo(TablesCli.OK);
        assertThat(out()).contains("1 table(s), 0 model(s): 0 error(s)");
        assertThat(run("check", "--sample", "trading.trades=trades.jsonl")).as("TRADE_ID twice in the sample").isEqualTo(TablesCli.PROBLEMS);
        assertThat(out()).contains("DRS-9613").contains("not unique");
        assertThat(run("preview", "trading.trades", "--sample", "trades.jsonl", "--limit", "1")).isEqualTo(TablesCli.OK);
        assertThat(out()).contains("id\tbusiness_date\tTRADE_ID\tMTM").contains("1 row(s) from 2 document(s)");
        assertThat(run("preview", "trading.trades", "--sample", "trades.jsonl", "--json")).isEqualTo(TablesCli.OK);
        assertThat(out()).contains("\"columns\"");
        assertThat(run("lineage", "trading.trades")).isEqualTo(TablesCli.OK);
        assertThat(out()).contains("nothing in the catalogue uses trading.trades");
    }

    @Test
    void migrateDryRunCheckAndWrite() throws Exception {
        Files.createDirectories(cwd.resolve("config/bi/datasets"));
        Files.writeString(cwd.resolve("config/bi/datasets/t.dataset.yaml"), "rachana: 2\ndataset: t\nsource: { kind: trade }\ncolumns: { X: { type: string } }\n");
        assertThat(run("migrate", "--check")).isEqualTo(TablesCli.PROBLEMS);
        assertThat(out()).contains("1 file(s) would change");
        assertThat(run("migrate", "--dry-run")).isEqualTo(TablesCli.OK);
        assertThat(out()).contains("+++ b/bi/t.table.yaml").contains("+table: bi.t");
        assertThat(Files.exists(cwd.resolve("config/tables/bi/t.table.yaml"))).isFalse();
        assertThat(run("migrate", "--audit", "audit.jsonl", "--who", "ajsinha")).isEqualTo(TablesCli.OK);
        assertThat(Files.readString(cwd.resolve("config/tables/bi/t.table.yaml"))).contains("X: { path: $.X, type: string }".replace("$.X", "\"$.X\""));
        assertThat(Files.readString(cwd.resolve("audit.jsonl"))).contains("\"who\":\"ajsinha\"");
        assertThat(run("migrate", "--check")).isEqualTo(TablesCli.OK);
        assertThat(out()).contains("nothing to migrate");
    }
}
