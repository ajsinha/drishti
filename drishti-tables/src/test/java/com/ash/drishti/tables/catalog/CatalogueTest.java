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
package com.ash.drishti.tables.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.tables.Problem;
import com.ash.drishti.tables.TableCodes;
import com.ash.drishti.tables.def.TableName;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The catalogue: files by domain folder, stored items (Q16), was: names (Q18), models checked against their tables, cycles, lineage. */
class CatalogueTest {

    @TempDir Path root;

    static final String TRADES = """
            rachana: 3
            table: trading.trades
            was: trading-risk-trades
            from: { kind: trade }
            keys: [TRADE_ID]
            columns:
              TRADE_ID: { path: $.TRADE_ID, type: string }
              DESK:     { path: $.DESK, type: string }
              STATUS:   { path: $.TRADE_STATUS, type: string }
              MTM:      { path: $.MTM, type: decimal }
              DESK_NAME: { expr: "related(core.desks.NAME, on = $.DESK)", type: string }
            """;
    static final String DESKS = """
            rachana: 3
            table: core.desks
            from: { kind: desk }
            columns:
              NAME:   { path: $.NAME, type: string }
              REGION: { path: $.REGION, type: string }
            """;
    static final String OPEN = """
            rachana: 3
            table: trading.open_trades
            from: { table: trading.trades }
            filter: "$.STATUS != 'MATURED' && $.STATUS != null"
            columns:
              MTM_M: { expr: "$.MTM / 1000000", type: decimal }
            """;
    static final String MODEL = """
            rachana: 3
            model: trading-risk
            version: 1.0.0
            tables:
              trades: { table: trading-risk-trades@^1, role: fact }
              open:   { table: trading.open_trades, role: fact }
              desks:  { table: core.desks, role: dimension }
            relationships:
              - { from: trades.DESK, to: desks.id }
              - { from: open.DESK, to: desks.id }
            measures:
              MTM:      { expr: "sum(trades.MTM)", format: signed0 }
              Open MTM: { expr: "sum(open.MTM)" }
              Open M:   { expr: "sum(open.MTM_M)" }
              Share:    { expr: "share_of(measure('MTM'), over = ALL)" }
              Trades:   { expr: "count(trades)" }
            hierarchies:
              Desk: [desks.REGION, desks.NAME]
            security:
              rows:
                desks:
                  - { role: desk-head, where: "desks.NAME in user.desks" }
            """;

    void write(String rel, String text) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    Catalogue sample() throws Exception {
        write("trading/trades.table.yaml", TRADES);
        write("core/desks.table.yaml", DESKS);
        write("trading/open_trades.table.yaml", OPEN);
        write("trading/trading-risk.model.yaml", MODEL);
        return Catalogue.load(root, ItemStore.NONE);
    }

    static List<String> errors(List<Problem> ps) {
        return ps.stream().filter(Problem::isError).map(Problem::line).toList();
    }

    @Test
    void readsFilesByDomainAndChecksEverything() throws Exception {
        Catalogue c = sample();
        assertThat(c.tables()).extracting(t -> t.name()).containsExactly("core.desks", "trading.open_trades", "trading.trades");
        assertThat(c.models()).extracting(m -> m.name()).containsExactly("trading-risk");
        assertThat(errors(c.check())).isEmpty();
    }

    @Test
    void aWasNameResolvesForOneReleaseWithAWarning() throws Exception {
        Catalogue c = sample();
        assertThat(c.table("trading-risk-trades")).map(t -> t.name()).contains("trading.trades");
        assertThat(c.table(TableName.Ref.parse("trading.trades@^2"))).isEmpty();
        assertThat(c.check()).anyMatch(p -> p.code().equals(TableCodes.REFERENCE) && p.level().equals(Problem.WARNING) && p.message().contains("now trading.trades"));
    }

    @Test
    void aTableOverATableHasItsBasesColumnsAndRules() throws Exception {
        Catalogue c = sample();
        assertThat(c.effective(TableName.Ref.parse("trading.open_trades")).orElseThrow().columns().keySet())
                .containsExactly("TRADE_ID", "DESK", "STATUS", "MTM", "DESK_NAME", "MTM_M");
    }

    @Test
    void findsDuplicatesWrongReferencesAndCycles() throws Exception {
        sample();
        write("trading/again.table.yaml", TRADES.replace("was: trading-risk-trades\n", ""));
        write("trading/a.table.yaml", "rachana: 3\ntable: trading.a\nfrom: { table: trading.b }\ncolumns: { X: $.X }\n");
        write("trading/b.table.yaml", "rachana: 3\ntable: trading.b\nfrom: { table: trading.a }\ncolumns: { X: $.X }\n");
        write("trading/bad.model.yaml", MODEL.replace("model: trading-risk", "model: bad").replace("sum(open.MTM_M)", "sum(open.NOPE)")
                .replace("trades.DESK, to: desks.id", "trades.DESK, to: desks.NOPE").replace("sum(trades.MTM)", "sum(trades.LEGS[*].MTM)"));
        write("trading/lookup.table.yaml", "rachana: 3\ntable: trading.lookup\nfrom: { kind: k }\ncolumns:\n  N: { expr: \"related(core.nope.NAME, on = $.K)\" }\n");
        write("misplaced.table.yaml", DESKS.replace("core.desks", "core.other"));
        Catalogue c = Catalogue.load(root, ItemStore.NONE);
        List<Problem> ps = c.check();
        assertThat(ps).anyMatch(p -> p.code().equals(TableCodes.DUPLICATE) && p.message().contains("trading.trades"));
        assertThat(ps).anyMatch(p -> p.code().equals(TableCodes.CYCLE));
        assertThat(ps).anyMatch(p -> p.code().equals(TableCodes.UNKNOWN_NAME) && p.message().contains("open.NOPE"));
        assertThat(ps).anyMatch(p -> p.code().equals(TableCodes.RELATIONSHIP) && p.message().contains("desks.NOPE"));
        assertThat(ps).anyMatch(p -> p.code().equals(TableCodes.PATH_IN_MEASURE));
        assertThat(ps).anyMatch(p -> p.code().equals(TableCodes.REFERENCE) && p.message().contains("core.nope"));
        assertThat(ps).anyMatch(p -> p.code().equals(TableCodes.NAME) && "misplaced.table.yaml".equals(p.source()));
    }

    @Test
    void storedItemsJoinTheFiles() throws Exception {
        sample();
        ItemStore store = () -> List.of(new ItemStore.StoredItem("t-9", ItemStore.StoredItem.TABLE, "risk", 3,
                "rachana: 3\ntable: risk.limits\nfrom: { kind: limit }\ncolumns: { LIMIT: { path: $.LIMIT, type: decimal } }\n"));
        Catalogue c = Catalogue.load(root, store);
        assertThat(c.table("risk.limits")).isPresent();
        assertThat(errors(c.check())).isEmpty();
    }

    @Test
    void lineageSaysWhoUsesATable() throws Exception {
        Catalogue c = sample();
        Lineage l = new Lineage(c);
        l.register(name -> name.equals("trading-risk") ? List.of(new Lineage.Use("report", "desk-wall", "asks MTM by desk")) : List.of());
        assertThat(l.of("trading.trades")).extracting(Lineage.Use::kind, Lineage.Use::name)
                .contains(org.assertj.core.groups.Tuple.tuple("model", "trading-risk"), org.assertj.core.groups.Tuple.tuple("table", "trading.open_trades"));
        assertThat(l.of("trading-risk-trades")).isEqualTo(l.of("trading.trades"));
        assertThat(l.of("core.desks")).anyMatch(u -> u.how().contains("looks up NAME"));
        assertThat(l.of("trading-risk")).extracting(Lineage.Use::kind).containsExactly("report");
        assertThat(c.reads(c.models().get(0))).containsEntry("trades", "trading.trades").containsEntry("desks", "core.desks");
    }
}
