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
        return new TablesCli(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8), cwd,
                (del, scope) -> com.ash.drishti.tables.migrate.DelTranslator.Result.ok(del, List.of())).run(List.of(args));
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
    void previewReadsRelatedTablesAndSpotRates() throws Exception {
        Files.createDirectories(cwd.resolve("config/tables/trading"));
        Files.createDirectories(cwd.resolve("config/tables/core"));
        Files.writeString(cwd.resolve("config/tables/core/books.table.yaml"), """
                rachana: 3
                table: core.books
                from: { kind: book }
                keys: [BOOK_ID]
                columns:
                  BOOK_ID:   { path: $.BOOK_ID, type: string }
                  DESK_NAME: { path: $.DESK_NAME, type: string }
                """);
        Files.writeString(cwd.resolve("config/tables/trading/legs.table.yaml"), """
                rachana: 3
                table: trading.legs
                from: { kind: trade }
                grain: $.LEGS[*]
                keys: [TRADE_ID, LEG_ID]
                columns:
                  TRADE_ID: $.TRADE_ID
                  LEG_ID:   { path: "@.LEG_ID", type: string }
                  USD:      { expr: "@.NOTIONAL * fx(@.CURRENCY, 'USD')", type: decimal }
                  JPY:      { expr: "@.NOTIONAL * fx(@.CURRENCY, 'JPY')", type: decimal }
                  DESK:     { expr: "related(core.books.DESK_NAME, on = $.BOOK)", type: string }
                """);
        Files.writeString(cwd.resolve("trades.jsonl"), "{\"TRADE_ID\":\"T1\",\"BOOK\":\"B1\",\"LEGS\":[{\"LEG_ID\":\"1\",\"CURRENCY\":\"EUR\",\"NOTIONAL\":100},"
                + "{\"LEG_ID\":\"2\",\"CURRENCY\":\"GBP\",\"NOTIONAL\":10}]}\n");
        Files.writeString(cwd.resolve("books.jsonl"), "{\"BOOK_ID\":\"B1\",\"DESK_NAME\":\"Rates\"}\n");
        Files.writeString(cwd.resolve("fx.jsonl"), "{\"PAIR_NAME\":\"EUR/USD\",\"MID\":1.25}\n{\"PAIR_NAME\":\"USD/JPY\",\"MID\":150}\n");
        assertThat(run("preview", "trading.legs", "--sample", "trades.jsonl", "--json")).isEqualTo(TablesCli.OK);
        assertThat(out()).as("no sources: only a currency to itself converts").doesNotContain("Rates").doesNotContain("125");
        assertThat(run("preview", "trading.legs", "--sample", "trades.jsonl", "--related", "core.books=books.jsonl", "--fx", "fx.jsonl", "--json"))
                .isEqualTo(TablesCli.OK);
        assertThat(out()).as("EUR to USD as quoted, EUR to JPY through USD, the book's desk; GBP has no quote: no rate")
                .contains("[ \"row-1\", null, 0, \"T1\", \"1\", 125, 18750, \"Rates\" ]").contains("[ \"row-1\", null, 1, \"T1\", \"2\", null, null, \"Rates\" ]");
        assertThat(run("preview", "trading.legs", "--sample", "trades.jsonl", "--related", "core.nope=books.jsonl")).isEqualTo(TablesCli.USAGE);
    }

    @Test
    void usageAndUnknownCommands() {
        assertThat(run()).isEqualTo(TablesCli.USAGE);
        assertThat(run("--help")).isEqualTo(TablesCli.OK);
        assertThat(out()).contains("tables list|check|preview|lineage|offer|migrate");
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
        tables();                                                   // a table of its own beside the migrated ones: checked in its own folder
        assertThat(run("migrate", "--dry-run")).isEqualTo(TablesCli.OK);
        assertThat(out()).doesNotContain("DRS-9601");
    }

    @Test
    void offerPrintsWritesOnceAndRefusesAStoredName() throws Exception {
        tables();
        Files.writeString(cwd.resolve("new.jsonl"), "{\"ACCOUNT_ID\":\"A1\",\"LIMIT\":5,\"MOVES\":[{\"AMOUNT\":1}]}\n");
        assertThat(run("offer", "bank.accounts", "--sample", "new.jsonl", "--kind", "account")).isEqualTo(TablesCli.OK);
        assertThat(out()).contains("table: bank.accounts").contains("ACCOUNT_ID").contains("$.MOVES[*]");
        assertThat(Files.exists(cwd.resolve("config/tables/bank/accounts.table.yaml"))).isFalse();
        assertThat(run("offer", "bank.moves", "--sample", "new.jsonl", "--lake", "demo", "--table", "bank.accounts", "--grain", "$.MOVES[*]",
                "--write")).isEqualTo(TablesCli.OK);
        assertThat(Files.readString(cwd.resolve("config/tables/bank/moves.table.yaml"))).contains("$.MOVES[*]").contains("@.AMOUNT");
        assertThat(run("offer", "bank.moves", "--sample", "new.jsonl", "--kind", "account", "--write")).isEqualTo(TablesCli.PROBLEMS);
        assertThat(run("offer", "bank.cards", "--sample", "new.jsonl", "--kind", "account", "--stored", "bank.cards", "--write"))
                .isEqualTo(TablesCli.PROBLEMS);
        assertThat(run("check", "--stored", "trading.trades,trade")).isEqualTo(TablesCli.PROBLEMS);
        assertThat(out()).contains("DRS-9616");
    }
}
