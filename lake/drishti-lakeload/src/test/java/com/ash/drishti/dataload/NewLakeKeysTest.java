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
package com.ash.drishti.dataload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.lakeconfig.Lake;
import com.ash.drishti.lakeconfig.LakeTable;
import com.ash.drishti.lakeconfig.Lakes;
import com.ash.drishti.lakeconfig.PackEntry;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pushing into a lake that does not exist yet (DATA_LAKE_GUIDE.md sections 3.4, 4.4 and 5.6): a table created by its first load takes its
 * kind's key from the pack (its ingest key, else its data contract's) as the lake entry's {@code id:}; a {@code .ready} file's
 * {@code key:} wins; a tar's folder may be a table name ({@code trade_endur}, {@code trade_calypso}: one kind, several tables) or a kind
 * name written either way ({@code var_limit}, {@code var-limit}).
 */
class NewLakeKeysTest {

    @TempDir Path tmp;
    Path lakes;
    Path data;

    @BeforeEach
    void folders() {
        lakes = tmp.resolve("config/lakes");
        data = tmp.resolve("data");
        System.setProperty(Lakes.PROPERTY, lakes.toString());
        Lakes.useProperties(k -> k.equals("drishti.data.dir") ? data.toString() : null);
    }

    @AfterEach
    void reset() {
        System.clearProperty(Lakes.PROPERTY);
        Lakes.useProperties(null);
    }

    /** A pack in the lake form, as the shipped trading pack: one kind with a table per source system, no id: in its entries, no ingest key. */
    static Path bookPack(Path base) throws IOException {
        Path pack = Files.createDirectories(base.resolve("packs/book"));
        Files.writeString(pack.resolve("pack.yaml"), """
                pack: book
                kinds: [trade, var-limit]
                connectors:
                  trade-endur:
                    plugin: delta
                    kinds: [trade]
                    settings: {lake: demo, lake-tables: book.trade_endur}
                  trade-calypso:
                    plugin: delta
                    kinds: [trade]
                    settings: {lake: demo, lake-tables: book.trade_calypso}
                  risk:
                    plugin: delta
                    kinds: [var-limit]
                    settings: {lake: demo, lake-tables: book.var-limit}
                lake:
                  tables:
                    book.trade_endur: {kind: trade, promote: [TRADE_ID, DESK]}
                    book.trade_calypso: {kind: trade, promote: [TRADE_ID, DESK]}
                    book.var-limit: {promote: [NODE_ID]}
                """);
        Files.createDirectories(pack.resolve("contracts"));
        Files.writeString(pack.resolve("contracts/trade.contract.yaml"), "kind: trade\nkey:\n- TRADE_ID\n");
        Files.writeString(pack.resolve("contracts/var-limit.contract.yaml"), "kind: var-limit\nkey:\n- NODE_ID\n");
        return pack;
    }

    static Routes.Packs packs(Path pack) {
        return new Routes.Packs() {
            @Override
            public Routes.PackInfo find(String name) {
                return name.equals("book") ? new Routes.PackInfo(pack, List.of("trade", "var-limit")) : null;
            }

            @Override
            public List<String> names() {
                return List.of("book");
            }
        };
    }

    static String nodes(String... ids) {
        StringBuilder b = new StringBuilder();
        for (String id : ids) {
            b.append("{\"NODE_ID\":\"").append(id).append("\",\"PARENT_ID\":null}\n");
        }
        return b.toString();
    }

    @Test
    void theKindsKeyComesFromTheContractWhenThePackEntryNamesNone() throws IOException {
        Path pack = bookPack(tmp);
        assertThat(PackEntry.contractKey(pack, "trade")).isEqualTo("TRADE_ID");
        assertThat(PackEntry.contractKey(pack, "nothing")).isNull();
        Files.writeString(pack.resolve("contracts/two.contract.yaml"), "key: [A, B]\n");
        assertThat(PackEntry.contractKey(pack, "two")).as("a key of two fields is no row id").isNull();
        LakeTable t = PackEntry.read(pack, "trade", "trade-calypso").toTable("trade");
        assertThat(t.name()).isEqualTo("book.trade_calypso");
        assertThat(t.id()).isEqualTo("TRADE_ID");
        assertThat(PackEntry.read(pack, "var-limit", null).toTable("var-limit").id()).isEqualTo("NODE_ID");
        assertThat(PackEntry.tables(pack)).extracting(PackEntry.PackTable::table)
                .containsExactly("trade_endur", "trade_calypso", "var-limit");
    }

    @Test
    void aTableNameResolvesToItsKindAndConnectorAndUnderscoresMatchHyphens() throws Exception {
        Path pack = bookPack(tmp);
        Routes routes = Routes.read(tmp.resolve("none.yaml"), "demo", packs(pack));
        Routes.Route r = routes.route("trade_endur.2026-11-06.jsonl.gz", null);
        assertThat(r.kind()).isEqualTo("trade");
        assertThat(r.connector()).isEqualTo("trade-endur");
        assertThat(routes.route("var_limit.2026-11-06.jsonl", null).kind()).isEqualTo("var-limit");
        List<Routes.Route> tar = routes.routeTar("eod.tar", List.of("trade_endur", "trade_calypso", "var_limit", "var-limit"), ReadyNote.EMPTY);
        assertThat(tar).extracting(Routes.Route::kind).containsExactly("trade", "trade", "var-limit", "var-limit");
        assertThat(tar).extracting(Routes.Route::connector).containsExactly("trade-endur", "trade-calypso", null, null);
        assertThat(tar).extracting(Routes.Route::dataset).containsExactly("trade_endur", "trade_calypso", "var_limit", "var-limit");
        assertThatThrownBy(() -> routes.routeTar("eod.tar", List.of("trade_murex"), ReadyNote.EMPTY))
                .hasMessageContaining("trade_murex: no pack, kind or table").hasMessageContaining("book/trade_endur");
    }

    @Test
    void theReadyFilesKeyWinsInTheNewLakesEntry() throws Exception {
        Path pack = bookPack(tmp);
        Routes routes = Routes.read(tmp.resolve("none.yaml"), "demo", packs(pack));
        Path marker = Files.writeString(tmp.resolve("t.ready"), "lake: keylake\nkey: OTHER_ID\n");
        Routes.Route r = routes.route("trade_endur.2026-11-06.jsonl", ReadyNote.read(marker));
        LoaderProcess.lakeEntry(r);
        Lake lake = Lakes.readFile(lakes.resolve("keylake.yaml"), k -> null);
        assertThat(lake.tables().get("book.trade_endur").id()).isEqualTo("OTHER_ID");
    }

    /** One file with no key: into a lake that does not exist, every row lands; the lake file's new entry carries the kind's key. */
    @Test
    void aFileIntoANewLakeWithoutKeyLoadsEveryRow() throws Exception {
        Fixture f = new Fixture(tmp);
        Path pack = bookPack(tmp);
        f.settings = f.settings.at(f.root.toString(), "demo", tmp.resolve("none.yaml").toString());
        f.drop("trade_endur.2026-11-06.jsonl", Fixture.rows("2026-11-06", "E1", "E2", "E3"), "lake: pushlake\nmode: replace\n");
        DataInbox inbox = new DataInbox(f.settings, "node-a", f.clock, Fixture.ZONE, packs(pack), new LoaderProcess(f.settings), f.signal(), null);
        Cycle.Report r = inbox.cycle("test");
        assertThat(r.files()).as(r.toString()).singleElement().satisfies(o -> {
            assertThat(o.outcome()).as(o.reason()).isEqualTo("loaded");
            assertThat(o.rows()).isEqualTo(3);
        });
        Lake lake = Lakes.readFile(lakes.resolve("pushlake.yaml"), k -> k.equals("drishti.data.dir") ? data.toString() : null);
        assertThat(lake.tables()).containsOnlyKeys("book.trade_endur");
        assertThat(lake.tables().get("book.trade_endur").id()).isEqualTo("TRADE_ID");
        assertThat(data.resolve("delta/pushlake/book/trade_endur/_delta_log")).isDirectory();
    }

    /**
     * A tar into a new lake with table-named folders of one kind (trade_endur, trade_calypso) and a kind folder written with an underscore
     * (var_limit), over two dates: every table created with its key, every row lands; the same archive changed and sent again replaces
     * its dates (the rows are the new file's, never doubled).
     */
    @Test
    void aTarOfSeveralTablesOfOneKindLoadsIntoANewLakeAndAgainReplaces() throws Exception {
        Fixture f = new Fixture(tmp);
        Path pack = bookPack(tmp);
        f.settings = f.settings.at(f.root.toString(), "demo", tmp.resolve("none.yaml").toString());
        DataInbox inbox = new DataInbox(f.settings, "node-a", f.clock, Fixture.ZONE, packs(pack), new LoaderProcess(f.settings), f.signal(), null);
        Map<String, String> members = new LinkedHashMap<>();
        members.put("trade_endur/2026-11-05/a.jsonl", Fixture.rows("2026-11-05", "E1", "E2"));
        members.put("trade_endur/2026-11-06/a.jsonl", Fixture.rows("2026-11-06", "E1", "E2", "E3"));
        members.put("trade_calypso/2026-11-05/a.jsonl", Fixture.rows("2026-11-05", "C1"));
        members.put("trade_calypso/2026-11-06/a.jsonl", Fixture.rows("2026-11-06", "C1", "C2"));
        members.put("var_limit/2026-11-05/n.jsonl", nodes("N1", "N2"));
        members.put("var_limit/2026-11-06/n.jsonl", nodes("N1", "N2", "N3"));
        Files.write(f.inbox().resolve("eod.tar"), DataInboxLoaderTest.tar(members));
        Files.writeString(f.inbox().resolve("eod.tar.ready"), "lake: pushlake\nmode: replace\nrows: 13\n");
        f.age(f.inbox().resolve("eod.tar"), f.inbox().resolve("eod.tar.ready"));
        Cycle.Report r = inbox.cycle("test");
        assertThat(r.files()).as(r.toString()).singleElement().satisfies(o -> {
            assertThat(o.outcome()).as(o.reason()).isEqualTo("loaded");
            assertThat(o.rows()).isEqualTo(13);
        });
        Lake lake = Lakes.readFile(lakes.resolve("pushlake.yaml"), k -> k.equals("drishti.data.dir") ? data.toString() : null);
        assertThat(lake.tables()).containsOnlyKeys("book.trade_endur", "book.trade_calypso", "book.var-limit");
        assertThat(lake.tables().values()).extracting(LakeTable::id).containsOnly("TRADE_ID", "NODE_ID");
        for (String t : List.of("book/trade_endur", "book/trade_calypso", "book/var-limit")) {
            assertThat(data.resolve("delta/pushlake/" + t + "/business_date=2026-11-05")).as(t).isDirectory();
            assertThat(data.resolve("delta/pushlake/" + t + "/business_date=2026-11-06")).as(t).isDirectory();
        }

        // the archive again, one more trade on 2026-11-06: replaced, so the dates hold the new archive's rows
        members.put("trade_endur/2026-11-06/a.jsonl", Fixture.rows("2026-11-06", "E1", "E2", "E3", "E4"));
        Files.write(f.inbox().resolve("eod.tar"), DataInboxLoaderTest.tar(members));
        Files.writeString(f.inbox().resolve("eod.tar.ready"), "lake: pushlake\nmode: replace\nrows: 14\n");
        f.age(f.inbox().resolve("eod.tar"), f.inbox().resolve("eod.tar.ready"));
        Cycle.Report again = inbox.cycle("test");
        assertThat(again.files()).as(again.toString()).singleElement().satisfies(o -> {
            assertThat(o.outcome()).as(o.reason()).isEqualTo("loaded");
            assertThat(o.rows()).isEqualTo(14);
        });
        JsonNode rep = report(f, "eod.tar.loaded.json", 2);
        long endur = 0;
        for (JsonNode t : rep.path("tables")) {
            if (t.toString().contains("trade_endur")) {
                endur = t.path("load").path("commit").path("dates").path("2026-11-06").path("rows").asLong(-1);
            }
        }
        assertThat(endur).as(rep.toString()).isEqualTo(4);
    }

    /** The {@code n}-th report of that name in the outbox (oldest first). */
    private static JsonNode report(Fixture f, String name, int n) throws IOException {
        try (Stream<Path> s = Files.walk(f.root.resolve("outbox"))) {
            List<Path> all = s.filter(x -> x.getFileName().toString().equals(name)).sorted(java.util.Comparator.comparing(x -> {
                try {
                    return Files.getLastModifiedTime(x);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            })).toList();
            return Fixture.JSON.readTree(all.get(n - 1).toFile());
        }
    }

    @Test
    void aFileOfTheRemovedRiskHierarchyKindIsRefusedSayingWhatReplacesIt() throws Exception {
        Routes routes = Routes.read(tmp.resolve("none.yaml"), "demo", packs(bookPack(tmp)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> routes.route("risk_hierarchy.2026-11-06.jsonl", null))
                .isInstanceOf(Routes.Unrouted.class).hasMessageContaining("the risk-hierarchy kind is removed")
                .hasMessageContaining("promoted columns").hasMessageContaining("hierarchies:");
    }

    @Test
    void aFileOfTheRemovedPortfolioKindIsRefusedSayingWhatReplacesIt() throws Exception {
        Routes routes = Routes.read(tmp.resolve("none.yaml"), "demo", packs(bookPack(tmp)));
        for (String file : List.of("portfolio.2026-09-30.jsonl.gz", "PORTFOLIO.2026-09-30.jsonl")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> routes.route(file, null))
                    .isInstanceOf(Routes.Unrouted.class).hasMessageContaining("the portfolio kind is removed")
                    .hasMessageContaining("basket store").hasMessageContaining("var:system-baskets").hasMessageContaining("var baskets import");
        }
    }
}
