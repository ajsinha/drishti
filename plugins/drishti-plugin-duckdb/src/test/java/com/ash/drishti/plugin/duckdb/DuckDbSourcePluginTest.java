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
package com.ash.drishti.plugin.duckdb;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.testkit.DatedSourceContract;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * The DuckDB connector over a file written by {@link DuckDbLoader} with {@code mtm} and {@code nettingSet} promoted to
 * columns: the same tests as every dated source, plus columns, reverse lookups from columns, health, retention, a
 * day loaded again, and a load that replaces the file while the connector holds it open. DuckDB is embedded: no
 * container is needed.
 */
class DuckDbSourcePluginTest extends DatedSourceContract {

    private static final Path DIR = tempDir();
    private static DuckDbSourcePlugin plugin;

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("duckdb-test");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        if (plugin != null) {
            plugin.close();
        }
        try (var files = Files.walk(DIR)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static String line(String domain, Row r) {
        StringBuilder s = new StringBuilder("{\"domain\":\"").append(domain).append("\",\"kind\":\"").append(r.kind()).append("\",\"id\":\"")
                .append(r.id()).append("\",\"date\":\"").append(r.date()).append("\",\"doc\":\"")
                .append(new String(JsonStringEncoder.getInstance().quoteAsString(r.json()))).append('"');
        if (r.kind().equals("trade")) {                               // the layout below promotes these two
            var doc = new com.ash.drishti.common.JsonCodec().read(r.json());
            s.append(",\"columns\":{\"mtm\":").append(doc.get("mtm").asDouble()).append(",\"nettingSet\":\"").append(doc.get("nettingSet").asText())
                    .append("\"}");
        }
        return s.append('}').toString();
    }

    private static void load(Path database, List<String> lines, String... options) throws Exception {
        Path f = Files.createTempFile(DIR, "rows", ".jsonl");
        Files.write(f, lines, StandardCharsets.UTF_8);
        List<String> args = new ArrayList<>(List.of(f.toString(), database.toString(), "--parsers", "2", "--memory-limit", "256MB"));
        args.addAll(List.of(options));
        DuckDbLoader.main(args.toArray(new String[0]));
        Files.delete(f);
    }

    private static Map<String, String> settings(Path database, String... more) {
        Map<String, String> s = new java.util.HashMap<>(Map.of("path", database.toString(), "table", "desk.entities", "mode.counterparty", "effective",
                "source-name", "desk-duckdb", "scan-threads", "2", "layout.trade.columns", "mtm,nettingSet", "memory-limit", "256MB"));
        for (int i = 0; i < more.length; i += 2) {
            s.put(more[i], more[i + 1]);
        }
        return s;
    }

    @Override
    protected synchronized SourcePlugin plugin() throws Exception {
        if (plugin != null) {
            return plugin;
        }
        // rows in an order that is not the id order: the loader sorts each day
        List<String> lines = new ArrayList<>(ROWS.stream().map(r -> line("desk", r)).toList());
        java.util.Collections.reverse(lines);
        load(DIR.resolve("desk.duckdb"), lines);
        DuckDbSourcePlugin p = new DuckDbSourcePlugin();
        p.start(context(settings(DIR.resolve("desk.duckdb"))));
        plugin = p;
        return p;
    }

    private static String query(Path database, String sql) throws Exception {
        Properties p = new Properties();
        p.setProperty("jdbc_instance_cache", "false");
        // attached under a name no schema has, so that desk.entities means the schema desk
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:", p); Statement st = c.createStatement()) {
            st.execute("ATTACH '" + database + "' AS f (READ_ONLY)");
            st.execute("USE f");
            try (ResultSet rs = st.executeQuery(sql)) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    @Test
    void aDaysPromotedColumnsAnswerSearchesAndReverseLookupsWithoutDocuments() throws Exception {
        SourcePlugin p = plugin();
        assertThat(p.columnar("trade")).containsExactlyInAnyOrder("mtm", "nettingSet");
        assertThat(p.columnar("counterparty")).isEmpty();
        ColumnSet c = p.columns("trade", List.of("mtm", "nettingSet"), AsOf.LATEST).orElseThrow();
        assertThat(c.ids()).containsExactly("T-1", "T-2");            // the newest day holds T-1 and T-2
        for (int i = 0; i < c.size(); i++) {
            var doc = p.fetch(EntityRef.of("trade", c.ids()[i])).orElseThrow().data();
            assertThat((Double) c.value("mtm", i)).isEqualTo(doc.get("mtm").asDouble());
            assertThat(c.value("nettingSet", i)).isEqualTo(doc.get("nettingSet").asText());
        }
        assertThat(p.columns("trade", List.of("mtm"), AsOf.of(D1)).orElseThrow().size()).isEqualTo(3);
        assertThat(p.columns("trade", List.of("notional"), AsOf.LATEST)).isEmpty();
        assertThat(p.columns("trade", List.of("mtm"), AsOf.of(LocalDate.of(2019, 12, 5)))).as("a day it does not hold").isEmpty();
        assertThat(p.reverse(EntityRef.of("netting-set", "NS-B"), "trade", AsOf.of(D1))).containsExactly(EntityRef.of("trade", "T-3"));
        assertThat(p.search("trade", "t-", 10)).hasSize(2);
        assertThat(p.lastUpdate()).isNotNull();
        assertThat(p.cacheStats()).containsEntry("ids", 3).containsEntry("kinds", 2);
    }

    @Test
    void eachDayIsWrittenSortedById() throws Exception {
        plugin();
        assertThat(query(DIR.resolve("desk.duckdb"), "SELECT string_agg(kind || ':' || business_date || ':' || id, ',') FROM desk.entities"))
                .as("the stream came in reverse; the file holds each (kind, day) in id order")
                .startsWith("counterparty:2026-09-28:CP-X,counterparty:2026-09-30:CP-X,trade:2026-09-28:T-1,trade:2026-09-28:T-2,trade:2026-09-28:T-3");
    }

    @Test
    void reverseLookupsWithoutPromotedColumnsReadABoundedNumberOfDocuments() throws Exception {
        plugin();
        DuckDbSourcePlugin p = new DuckDbSourcePlugin();
        p.start(context(settings(DIR.resolve("desk.duckdb"), "layout.trade.columns", "")));
        try {
            assertThat(p.columnar("trade")).isEmpty();
            assertThat(p.reverse(EntityRef.of("netting-set", "NS-A"), "trade", AsOf.of(D2)))
                    .containsExactly(EntityRef.of("trade", "T-1"), EntityRef.of("trade", "T-2"));
            assertThat(p.reverse(EntityRef.of("rating", "A-"), "counterparty", AsOf.of(D3))).containsExactly(EntityRef.of("counterparty", "CP-X"));
            assertThat(p.reverse(EntityRef.of("rating", "A-"), "counterparty", AsOf.of(D2))).isEmpty();
        } finally {
            p.close();
        }
    }

    @Test
    void aFileWithoutTheDeclaredColumnsSaysSo() throws Exception {
        plugin();
        DuckDbSourcePlugin p = new DuckDbSourcePlugin();
        p.start(context(settings(DIR.resolve("desk.duckdb"), "layout.trade.columns", "mtm,nettingSet,notional")));
        try {
            assertThat(p.health()).isEqualTo("UP (not laid out as the pack declares: trade (2 of 3 columns); searches read documents)");
        } finally {
            p.close();
        }
    }

    @Test
    void aMissingFileIsDownUntilALoadWritesIt() throws Exception {
        Path later = DIR.resolve("later.duckdb");
        DuckDbSourcePlugin p = new DuckDbSourcePlugin();
        p.start(context(settings(later, "table", "hist.entities", "refresh-seconds", "1", "layout.trade.columns", "")));
        try {
            assertThat(p.health()).startsWith("DOWN: no DuckDB file at");
            // DATA-03: what the missing file holds is unknown, so a read fails (DRS-1003) rather than passing to another store
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> p.fetch(EntityRef.of("trade", "T-9")))
                    .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("has not read its catalogue yet: no DuckDB file at");
            load(later, List.of("{\"domain\":\"hist\",\"kind\":\"trade\",\"id\":\"T-9\",\"date\":\"2026-09-30\",\"doc\":\"{}\"}"));
            waitFor(() -> {
                try {
                    return p.fetch(EntityRef.of("trade", "T-9")).isPresent();
                } catch (java.sql.SQLException e) {
                    return false;                                  // not read yet
                }
            });
            assertThat(p.fetch(EntityRef.of("trade", "T-8"))).as("not held, once the file is read").isEmpty();
            assertThat(p.health()).isEqualTo("UP");
        } finally {
            p.close();
        }
    }

    @Test
    void aLoadReplacesTheFileUnderARunningConnector() throws Exception {
        Path db = DIR.resolve("swap.duckdb");
        String row = "{\"domain\":\"again\",\"kind\":\"trade\",\"id\":\"%s\",\"date\":\"%s\",\"doc\":\"{\\\"v\\\":%d}\",\"columns\":{\"v\":%d}}";
        load(db, List.of(row.formatted("T-1", "2026-09-29", 1, 1), row.formatted("T-2", "2026-09-30", 1, 1), row.formatted("T-1", "2026-09-30", 1, 1)));
        DuckDbSourcePlugin p = new DuckDbSourcePlugin();
        p.start(context(settings(db, "table", "again.entities", "refresh-seconds", "1", "layout.trade.columns", "v")));
        try {
            assertThat(p.search("trade", "t-", 10)).hasSize(2);
            assertThat(p.columns("trade", List.of("v"), AsOf.LATEST).orElseThrow().size()).isEqualTo(2);
            // the server holds the file read-only; the loader writes a new one and renames it over: 2026-09-30 is replaced
            // whole (T-2 is gone), a row given twice keeps the last, and 2026-09-29 stays
            load(db, List.of(row.formatted("T-3", "2026-09-30", 1, 1), row.formatted("T-1", "2026-09-30", 2, 2), row.formatted("T-1", "2026-09-30", 3, 3)));
            waitFor(() -> p.search("trade", "t-3", 10).size() == 1);
            assertThat(p.fetch(EntityRef.of("trade", "T-1")).orElseThrow().data().get("v").asDouble()).isEqualTo(3.0);
            assertThat(p.fetch(EntityRef.of("trade", "T-2"))).isEmpty();
            assertThat(p.fetch(EntityRef.of("trade", "T-1"), AsOf.of(LocalDate.of(2026, 9, 29))).orElseThrow().data().get("v").asDouble()).isEqualTo(1.0);
            ColumnSet c = p.columns("trade", List.of("v"), AsOf.LATEST).orElseThrow();
            assertThat(c.ids()).containsExactly("T-1", "T-3");
            assertThat(c.value("v", 0)).isEqualTo(3.0);
            assertThat(p.cacheStats()).containsEntry("generation", 2L);
            assertThat(query(db, "SELECT string_agg(business_date || '=' || rows, ',' ORDER BY business_date) FROM again.entity_dates"))
                    .isEqualTo("2026-09-29=1,2026-09-30=2");
            assertThat(Files.exists(db.resolveSibling("swap.duckdb.stage"))).isFalse();
            assertThat(Files.exists(db.resolveSibling("swap.duckdb.loading"))).isFalse();
        } finally {
            p.close();
        }
    }

    @Test
    void daysOlderThanTheRetentionAreDropped() throws Exception {
        Path db = DIR.resolve("hist.duckdb");
        String old = "{\"domain\":\"hist\",\"kind\":\"trade\",\"id\":\"T-9\",\"date\":\"%s\",\"doc\":\"{}\"}";
        load(db, List.of(old.formatted("2026-06-15"), old.formatted("2026-09-03")));
        load(db, List.of(old.formatted("2026-09-30")), "--keep-days", "30");
        assertThat(query(db, "SELECT string_agg(CAST(business_date AS VARCHAR), ',' ORDER BY business_date) FROM hist.entities"))
                .isEqualTo("2026-09-03,2026-09-30");
        assertThat(query(db, "SELECT count(*) FROM hist.entity_dates")).isEqualTo("2");
    }

    @Test
    void recreateDropsTheOldRowsOfTheDomainsItLoads() throws Exception {
        Path db = DIR.resolve("re.duckdb");
        String row = "{\"domain\":\"%s\",\"kind\":\"trade\",\"id\":\"%s\",\"date\":\"2026-09-29\",\"doc\":\"{}\"}";
        load(db, List.of(row.formatted("a", "T-1"), row.formatted("b", "T-2")));
        load(db, List.of(row.formatted("a", "T-3").replace("09-29", "09-30")), "--recreate");
        assertThat(query(db, "SELECT string_agg(id, ',') FROM a.entities")).isEqualTo("T-3");
        assertThat(query(db, "SELECT string_agg(id, ',') FROM b.entities")).as("a domain the stream does not reach stays").isEqualTo("T-2");
    }

    @Test
    void promotedPathsBecomeColumnNamesAsInDeltaLake() {
        assertThat(DuckDbLayout.column("counterparty.id")).isEqualTo("counterparty__id");
        assertThat(DuckDbLayout.quoted("tradeId")).isEqualTo("\"tradeId\"");
        assertThat(DuckDbLayout.schema("market-data")).isEqualTo("market_data");
    }

    @FunctionalInterface
    private interface Check {
        boolean ok() throws Exception;
    }

    private static void waitFor(Check check) throws Exception {
        long until = System.nanoTime() + 15_000_000_000L;
        while (!check.ok()) {
            assertThat(System.nanoTime()).as("waited 15 s").isLessThan(until);
            Thread.sleep(100);
        }
    }
}
