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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.UnreadableData;
import com.ash.drishti.common.JsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * DATA-19: in query mode one failing part query made every view of the kind fail with a generic {@code DRS-1003} (the
 * SQL error only in the log), and a {@code columns.<kind>} query that joined a child table repeated ids without a word
 * (searches scanned 11,866 rows for 8,000 trades, impact totals changed). The error now names the failing query (no SQL
 * text, no connection details), and repeated ids are counted once and reported in the log and in health.
 */
class QueryModeProblemsTest {

    private static final String URL = "jdbc:h2:mem:querymodeproblems;DB_CLOSE_DELAY=-1";
    private static final LocalDate D2 = LocalDate.of(2026, 9, 30);
    private static JdbcSourcePlugin plugin;

    private static SourceContext context(Map<String, String> settings) {
        JsonCodec codec = new JsonCodec();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        return new SourceContext() {
            @Override
            public Map<String, String> settings() {
                return settings;
            }

            @Override
            public DataNode parseJson(InputStream in) throws IOException {
                return codec.read(in);
            }

            @Override
            public ScheduledExecutorService scheduler() {
                return scheduler;
            }
        };
    }

    @BeforeAll
    static void schema() throws Exception {
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("create table trades(trade_id varchar, business_date date, mtm decimal(18,2), book varchar, primary key (trade_id, business_date))");
            s.execute("insert into trades values ('MX-1', DATE '2026-09-30', 1600000, 'BOOK-RATES-3')");
            s.execute("insert into trades values ('MX-2', DATE '2026-09-30', -250000, 'BOOK-RATES-3')");
            s.execute("insert into trades values ('MX-3', DATE '2026-09-30', 90000, 'BOOK-CREDIT-1')");
            s.execute("create table legs(trade_id varchar, leg_no int, rate decimal(9,4))");
            s.execute("insert into legs values ('MX-1', 1, 3.25), ('MX-1', 2, 3.10), ('MX-2', 1, 2.0), ('MX-2', 2, 2.1), ('MX-2', 3, 2.2)");
            s.execute("create table books(id varchar primary key, desk varchar)");
            s.execute("insert into books values ('BOOK-RATES-3', 'Rates')");
        }
        Map<String, String> settings = new HashMap<>();
        settings.put("url", URL);
        settings.put("pool-size", "4");
        settings.put("source-name", "qa-db");
        settings.put("query.trade", "select trade_id, mtm, book from trades where trade_id = :id and business_date = :asOf");
        settings.put("query.trade.legs", "select leg_no, rate from legs where trade_id = :id order by leg_no");
        settings.put("query.trade.broken", "select no_such_column from legs where trade_id = :id");
        settings.put("query.book", "select id, desk from books where id = :id");
        // a join with the legs: one row per leg, so MX-1 twice and MX-2 three times (and MX-3, without legs, not at all)
        settings.put("columns.trade", "select t.trade_id, t.mtm, t.book from trades t join legs l on l.trade_id = t.trade_id where t.business_date = :asOf");
        settings.put("layout.trade.columns", "mtm,book");
        plugin = new JdbcSourcePlugin();
        plugin.start(context(settings));
    }

    @AfterAll
    static void close() {
        plugin.close();
    }

    @Test
    void aFailingPartQueryIsNamedInTheErrorWithoutItsSql() {
        assertThatThrownBy(() -> plugin.fetch(EntityRef.of("trade", "MX-1"), AsOf.of(D2))).isInstanceOf(UnreadableData.class)
                .hasMessageContaining("query.trade.broken").hasMessageContaining("MX-1").hasMessageNotContainingAny("select", "no_such_column", "jdbc:h2", URL);
        assertThat(plugin.health()).startsWith("DEGRADED").contains("query.trade.broken");
    }

    @Test
    void anotherKindStillReads() throws Exception {
        assertThat(plugin.fetch(EntityRef.of("book", "BOOK-RATES-3"), AsOf.of(D2)).orElseThrow().data().get("desk").asText()).isEqualTo("Rates");
    }

    @Test
    void aColumnsQueryThatRepeatsIdsCountsEachOnceAndSaysSo() {
        ColumnSet c = plugin.columns("trade", List.of("mtm", "book"), AsOf.of(D2)).orElseThrow();
        assertThat(c.ids()).containsExactly("MX-1", "MX-2");
        assertThat(c.numbers().get("mtm")).containsExactly(1_600_000, -250_000);
        assertThat(plugin.health()).contains("columns.trade").contains("5 rows for 2 entities").contains("MX-2");
    }
}
