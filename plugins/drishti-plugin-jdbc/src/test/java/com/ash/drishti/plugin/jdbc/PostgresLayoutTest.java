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
package com.ash.drishti.plugin.jdbc;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Table mode over the scale layout ({@link PostgresLayout}): the contract's rows loaded by {@link PostgresLoader} into
 * a table partitioned by month with {@code mtm} and {@code nettingSet} promoted to columns. The same tests as every dated
 * source, plus columns, reverse lookups from columns, health and retention. Skipped where Docker is not reachable.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresLayoutTest extends DatedSourceContract {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    private static JdbcSourcePlugin plugin;

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

    private static void load(List<String> lines, String... options) throws Exception {
        Path f = Files.createTempFile("rows", ".jsonl");
        Files.write(f, lines, StandardCharsets.UTF_8);
        List<String> args = new ArrayList<>(List.of(f.toString(), POSTGRES.getJdbcUrl(), "--user", POSTGRES.getUsername(), "--password",
                POSTGRES.getPassword(), "--writers", "2"));
        args.addAll(List.of(options));
        PostgresLoader.main(args.toArray(new String[0]));
        Files.delete(f);
    }

    @Override
    protected synchronized SourcePlugin plugin() throws Exception {
        if (plugin != null) {
            return plugin;
        }
        load(ROWS.stream().map(r -> line("desk", r)).toList(), "--recreate");
        JdbcSourcePlugin p = new JdbcSourcePlugin();
        p.start(context(Map.of("url", POSTGRES.getJdbcUrl(), "user", POSTGRES.getUsername(), "password", POSTGRES.getPassword(),
                "table", "desk.entities", "mode.counterparty", "effective", "source-name", "desk-pg", "pool-size", "4", "scan-threads", "2",
                "layout.trade.columns", "mtm,nettingSet")));
        plugin = p;
        return p;
    }

    @Test
    void aDaysPromotedColumnsAnswerSearchesAndReverseLookupsWithoutDocuments() throws Exception {
        SourcePlugin p = plugin();
        assertThat(p.columnar("trade")).containsExactlyInAnyOrder("mtm", "nettingSet");
        assertThat(p.columnar("counterparty")).isEmpty();
        ColumnSet c = p.columns("trade", List.of("mtm", "nettingSet"), AsOf.LATEST).orElseThrow();
        assertThat(c.size()).isEqualTo(2);                            // the newest day holds T-1 and T-2
        for (int i = 0; i < c.size(); i++) {
            var doc = p.fetch(EntityRef.of("trade", c.ids()[i])).orElseThrow().data();
            assertThat((Double) c.value("mtm", i)).isEqualTo(doc.get("mtm").asDouble());
            assertThat(c.value("nettingSet", i)).isEqualTo(doc.get("nettingSet").asText());
        }
        assertThat(p.columns("trade", List.of("mtm"), AsOf.of(D1)).orElseThrow().size()).isEqualTo(3);
        assertThat(p.columns("trade", List.of("notional"), AsOf.LATEST)).isEmpty();
        assertThat(p.columns("trade", List.of("mtm"), AsOf.of(java.time.LocalDate.of(2019, 12, 5)))).as("a day it does not hold").isEmpty();
        assertThat(p.reverse(EntityRef.of("netting-set", "NS-B"), "trade", AsOf.of(D1))).containsExactly(EntityRef.of("trade", "T-3"));
        assertThat(p.health()).isEqualTo("UP");
        assertThat(p.search("trade", "t-", 10)).hasSize(2);
        assertThat(p.lastUpdate()).isNotNull();
    }

    @Test
    void aTableWithoutTheDeclaredColumnsSaysSo() throws Exception {
        plugin();
        JdbcSourcePlugin p = new JdbcSourcePlugin();
        p.start(context(Map.of("url", POSTGRES.getJdbcUrl(), "user", POSTGRES.getUsername(), "password", POSTGRES.getPassword(),
                "table", "desk.entities", "layout.trade.columns", "mtm,nettingSet,notional")));
        assertThat(p.health()).isEqualTo("UP (not laid out as the pack declares: trade (2 of 3 columns); searches read documents)");
        p.close();
    }

    @Test
    void monthsOlderThanTheRetentionAreDropped() throws Exception {
        String old = "{\"domain\":\"hist\",\"kind\":\"trade\",\"id\":\"T-9\",\"date\":\"%s\",\"doc\":\"{}\"}";
        load(List.of(old.formatted("2026-06-15"), old.formatted("2026-08-03"), old.formatted("2026-09-30")), "--recreate", "--keep-months", "2");
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT string_agg(business_date::text, ',' ORDER BY business_date) FROM hist.entities")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("2026-08-03,2026-09-30");
            assertThat(PostgresLayout.partitionsBefore(c, "hist", java.time.LocalDate.of(2026, 9, 1))).containsExactly("entities_y2026m08");
        }
    }

    @Test
    void loadingADayAgainReplacesIt() throws Exception {
        String row = "{\"domain\":\"again\",\"kind\":\"trade\",\"id\":\"%s\",\"date\":\"2026-09-30\",\"doc\":\"{}\"}";
        load(List.of(row.formatted("T-1"), row.formatted("T-2")), "--recreate");
        load(List.of(row.formatted("T-3")));
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT string_agg(id, ',') || ' ' || (SELECT rows FROM again.entity_dates) FROM again.entities")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("T-3 1");
        }
    }

    @Test
    void promotedPathsBecomeColumnNamesAsInDeltaLake() {
        assertThat(PostgresLayout.column("counterparty.id")).isEqualTo("counterparty__id");
        assertThat(PostgresLayout.quoted("tradeId")).isEqualTo("\"tradeId\"");
        assertThat(PostgresLayout.partition(java.time.LocalDate.of(2026, 9, 30))).isEqualTo("entities_y2026m09");
        assertThat(PostgresLayout.schema("market-data")).isEqualTo("market_data");
    }
}
