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
package com.ash.drishti.tables.migrate;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.tables.Problem;
import com.ash.drishti.tables.TableCodes;
import com.ash.drishti.tables.catalog.Catalogue;
import com.ash.drishti.tables.catalog.ItemStore;
import com.ash.drishti.tables.def.ModelDef;
import com.ash.drishti.tables.def.TableDef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one migration tool's skeleton (BI_FOUNDATION_2911E.md section 4): datasets into tables, models into layer models, every expression
 * through the translator and the layer's rewrites, the dry run's diff, {@code --check}, the audit, idempotence; and the shipped samples of
 * {@code config/bi}, read only, migrated into a temporary folder with nothing refused but what waits for part A's translator.
 */
class TablesMigratorTest {

    @TempDir Path dir;

    static final String LICENCE = "# Project Drishti · Any data. Any domain. One grammar.\n#\n# PROPRIETARY AND CONFIDENTIAL.\n";

    static final String DATASET = LICENCE + """

            rachana: 2
            expressions: 2
            dataset: trades-basic-trade
            version: 1.0.0
            title: "Trades: one row per trade"
            source: { kind: trade, connector: demo }
            refresh: { on-load: true }
            columns:
              TRADE_ID:        { type: string, role: key }
              DESK:            { type: string }
              STATUS:          { type: string, path: TRADE_STATUS }
              FIRST_CCY:       { type: string, path: LEGS.0.CURRENCY }
              MTM:             { type: decimal, format: signed0 }
              MTM_IN_MILLIONS: { type: decimal, expr: "MTM / 1000000" }
            where: "STATUS != 'CANCELLED'"
            security:
              rows:
                - { role: desk-head, where: "DESK in user.desks" }
                - { role: [risk, analyst], where: "True" }
              masked: [DESK]
            """;
    static final String LEGS = LICENCE + """

            rachana: 2
            dataset: trades-basic-legs
            version: 1.0.0
            source: { kind: trade, each: "LEGS[].CASH_FLOWS" }
            columns:
              LEG_ID:  { type: integer, path: "^.LEG_ID" }
              AMOUNT:  { type: decimal }
            """;
    static final String MODEL = LICENCE + """

            rachana: 2
            expressions: 2
            model: trades-basic
            version: 1.0.0
            datasets:
              trade: { dataset: trades-basic-trade, role: fact }
              legs:  { dataset: trades-basic-legs, role: fact }
            measures:
              MTM:      { expr: "sum(trade.MTM)", format: signed0 }
              Open MTM: { expr: "sum(trade.MTM, where=trade.STATUS != 'MATURED')" }
              Trades:   { expr: "count(trade)" }
              Flows:    { expr: "sum(legs.AMOUNT)", via: r_2 }
            hierarchies:
              Desk: [trade.DESK]
            """;

    Path bi() throws Exception {
        Path bi = dir.resolve("bi");
        Files.createDirectories(bi.resolve("datasets"));
        Files.createDirectories(bi.resolve("models"));
        Files.writeString(bi.resolve("datasets/trades-basic-trade.dataset.yaml"), DATASET);
        Files.writeString(bi.resolve("datasets/trades-basic-legs.dataset.yaml"), LEGS);
        Files.writeString(bi.resolve("models/trades-basic.model.yaml"), MODEL);
        return bi;
    }

    @Test
    void migratesADatasetKeyByKey() throws Exception {
        TablesMigrator.Plan p = new TablesMigrator().plan(bi(), dir.resolve("tables"));
        assertThat(p.count("converted")).isEqualTo(3);
        assertThat(p.writes()).containsOnlyKeys("bi/trades_basic_trade.table.yaml", "bi/trades_basic_legs.table.yaml", "bi/trades-basic.model.yaml");
        String table = p.writes().get("bi/trades_basic_trade.table.yaml");
        assertThat(table).startsWith("# Project Drishti").contains("# Migrated from config/bi/datasets/trades-basic-trade.dataset.yaml")
                .contains("rachana: 3\ntable: bi.trades_basic_trade\nwas: trades-basic-trade\n");
        Catalogue c = Catalogue.of(List.of(), List.of());
        TableDef t = com.ash.drishti.tables.yaml.TableYaml.read(table).table();
        assertThat(t.from().kind()).isEqualTo("trade");
        assertThat(t.from().connector()).isEqualTo("demo");
        assertThat(t.columns().get("STATUS").path()).isEqualTo("$.TRADE_STATUS");
        assertThat(t.columns().get("FIRST_CCY").path()).isEqualTo("$.LEGS[0].CURRENCY");
        assertThat(t.columns().get("MTM_IN_MILLIONS").expr()).isEqualTo("$.MTM / 1000000");
        assertThat(t.filter()).isEqualTo("$.TRADE_STATUS != 'CANCELLED' && $.TRADE_STATUS != null");
        assertThat(t.rules()).extracting(TableDef.Rule::where).containsExactly("$.DESK in user.desks", "true");
        assertThat(t.columns().get("DESK").masked()).isTrue();
        assertThat(t.keys()).containsExactly("TRADE_ID");
        assertThat(t.materialize().mode()).isEqualTo("on-load");
        assertThat(c).isNotNull();
        TableDef legs = com.ash.drishti.tables.yaml.TableYaml.read(p.writes().get("bi/trades_basic_legs.table.yaml")).table();
        assertThat(legs.grain()).isEqualTo("$.LEGS[*].CASH_FLOWS[*]");
        assertThat(legs.columns().get("AMOUNT").path()).isEqualTo("@.AMOUNT");
        assertThat(legs.columns().get("LEG_ID").path()).isEqualTo("^.LEG_ID");
    }

    @Test
    void migratesAModelWithItsAliasesAndTheNullRule() throws Exception {
        TablesMigrator.Plan p = new TablesMigrator().plan(bi(), dir.resolve("tables"));
        ModelDef m = com.ash.drishti.tables.yaml.ModelYaml.read(p.writes().get("bi/trades-basic.model.yaml")).model();
        assertThat(m.tables().get("trade").table().name()).isEqualTo("bi.trades_basic_trade");
        assertThat(m.measures().get("Open MTM").expr()).isEqualTo("sum(trade.MTM, where = trade.STATUS != 'MATURED' && trade.STATUS != null)");
        assertThat(m.measures().get("Flows").expr()).isEqualTo("calc(sum(legs.AMOUNT), via = 'r_2')");
        assertThat(p.problems()).filteredOn(Problem::isError).isEmpty();
        assertThat(TablesMigrator.report(p)).contains("converted  models/trades-basic.model.yaml").contains("(Q1)");
        assertThat(p.diff()).contains("--- /dev/null").contains("+++ b/bi/trades-basic.model.yaml").contains("+rachana: 3");
    }

    @Test
    void writesAuditsAndASecondRunChangesNothing() throws Exception {
        Path bi = bi();
        Path tables = dir.resolve("tables");
        TablesMigrator m = new TablesMigrator();
        TablesMigrator.Plan first = m.plan(bi, tables);
        Path audit = dir.resolve("audit/tables-migrate.jsonl");
        List<String> written = TablesMigrator.write(first, tables, bi, null, MigrationAudit.jsonl(audit, Clock.fixed(Instant.parse("2026-11-03T21:00:00Z"),
                ZoneOffset.UTC)), "ajsinha");
        assertThat(written).hasSize(3);
        assertThat(Files.readAllLines(audit)).hasSize(3).allMatch(l -> l.contains("\"action\":\"tables-migrate\"") && l.contains("\"who\":\"ajsinha\"")
                && l.contains("\"after\":\"sha256:"));
        assertThat(Files.exists(bi.resolve("datasets/trades-basic-trade.dataset.yaml"))).as("BI still reads its files until the switch").isTrue();
        TablesMigrator.Plan second = m.plan(bi, tables);
        assertThat(second.anyWrite()).isFalse();
        assertThat(second.count("unchanged")).isEqualTo(3);
        assertThat(second.diff()).isEmpty();
        assertThat(Catalogue.load(tables, ItemStore.NONE).check()).filteredOn(Problem::isError).isEmpty();
        // the outputs read again as BI input change nothing either: an item already in the layer's form is left as it is
        Files.copy(tables.resolve("bi/trades_basic_trade.table.yaml"), bi.resolve("datasets/already.dataset.yaml"));
        assertThat(m.plan(bi, tables).items()).anyMatch(i -> i.source().equals("datasets/already.dataset.yaml") && i.state().equals("unchanged"));
    }

    @Test
    void movesTheOldFilesWhenAsked() throws Exception {
        Path bi = bi();
        Path tables = dir.resolve("tables");
        TablesMigrator.Plan p = new TablesMigrator().plan(bi, tables);
        TablesMigrator.write(p, tables, bi, dir.resolve("migrated/2.9.11.e"), MigrationAudit.NONE, null);
        assertThat(Files.exists(bi.resolve("models/trades-basic.model.yaml"))).isFalse();
        assertThat(Files.exists(dir.resolve("migrated/2.9.11.e/models/trades-basic.model.yaml"))).isTrue();
    }

    @Test
    void refusesByNameAndNeverWritesHalf() throws Exception {
        Path bi = bi();
        Files.writeString(bi.resolve("models/conditional.model.yaml"), LICENCE + """
                rachana: 2
                model: conditional
                datasets: { trade: { dataset: trades-basic-trade } }
                measures:
                  Signed: { expr: "sum(trade.MTM if trade.MTM > 0 else 0)" }
                """);
        Files.writeString(bi.resolve("datasets/pack.trading.trade.dataset.yaml"), "dataset: pack.trading.trade\ngenerated: true\n");
        TablesMigrator.Plan p = new TablesMigrator().plan(bi, dir.resolve("tables"));
        assertThat(p.items()).filteredOn(i -> i.state().equals("refused")).extracting(TablesMigrator.Item::source)
                .containsExactlyInAnyOrder("models/conditional.model.yaml", "datasets/pack.trading.trade.dataset.yaml");
        assertThat(p.writes()).doesNotContainKey("bi/conditional.model.yaml");
        assertThat(p.problems()).anyMatch(x -> x.code().equals(TableCodes.MIGRATION_REFUSED) && x.message().contains("part A"));
    }

    /** The shipped samples: every dataset and model of config/bi, read only, into a temporary folder. */
    @Test
    void theShippedSamplesMigrateWithOnlyPartAsWorkLeft() throws Exception {
        Path shipped = Path.of("..", "config", "bi");
        Path tables = dir.resolve("tables");
        TablesMigrator m = new TablesMigrator();
        TablesMigrator.Plan p = m.plan(shipped, tables);
        assertThat(p.count("converted")).isGreaterThanOrEqualTo(30);
        // refused: the conditional chains of trading-cashflows (DEL's a if c else b: part A's translator), and the two version-1 live originals
        // the shipped converted forms already stand for (the same table names)
        assertThat(p.items()).filteredOn(i -> i.state().equals("refused")).extracting(TablesMigrator.Item::source)
                .containsExactlyInAnyOrder("models/trading-cashflows.model.yaml", "datasets/live-trading.dataset.yaml", "datasets/live-exposure.dataset.yaml");
        assertThat(p.problems()).filteredOn(Problem::isError).allMatch(x -> x.code().equals(TableCodes.MIGRATION_REFUSED) || x.source().contains("trading-cashflows"),
                "only part A's work is left");
        assertThat(p.changes()).anyMatch(c -> "sum(trades.MTM, where = trades.TRADE_STATUS != 'MATURED' && trades.TRADE_STATUS != null)".equals(c.after()));
        TablesMigrator.write(p, tables, shipped, null, MigrationAudit.NONE, null);
        assertThat(m.plan(shipped, tables).anyWrite()).as("idempotent on the shipped set").isFalse();
        assertThat(Files.list(tables.resolve("bi")).count()).isEqualTo(p.writes().size());
    }
}
