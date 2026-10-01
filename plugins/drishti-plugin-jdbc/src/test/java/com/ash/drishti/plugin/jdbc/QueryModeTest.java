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
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourceContext;
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
 * Query mode with several queries per kind, over your own schema (H2 in memory): an entity built from three tables,
 * type-ahead from an ids query, a day's promoted fields from a columns query, and reverse lookups by query and from
 * columns.
 */
class QueryModeTest {

    private static final String URL = "jdbc:h2:mem:querymode;DB_CLOSE_DELAY=-1";
    private static final LocalDate D1 = LocalDate.of(2026, 9, 29);
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
            s.execute("create table trades(trade_id varchar, business_date date, notional decimal(18,2), mtm decimal(18,2), book varchar, "
                    + "netting_set varchar, counterparty_id varchar, primary key (trade_id, business_date))");
            s.execute("insert into trades values ('MX-1', DATE '2026-09-29', 100000000, 1500000, 'BOOK-RATES-3', 'NS-A', 'CP-1')");
            s.execute("insert into trades values ('MX-1', DATE '2026-09-30', 100000000, 1600000, 'BOOK-RATES-3', 'NS-A', 'CP-1')");
            s.execute("insert into trades values ('MX-2', DATE '2026-09-30', 50000000, -250000, 'BOOK-RATES-3', 'NS-A', 'CP-2')");
            s.execute("insert into trades values ('CLY-7', DATE '2026-09-30', 25000000, 90000, 'BOOK-CREDIT-1', 'NS-B', 'CP-2')");
            s.execute("create table legs(trade_id varchar, leg_no int, pay_receive varchar, rate decimal(9,4))");
            s.execute("insert into legs values ('MX-1', 1, 'PAY', 3.25), ('MX-1', 2, 'RECEIVE', 3.10)");
            s.execute("create table counterparties(id varchar primary key, legal_name varchar, rating varchar)");
            s.execute("insert into counterparties values ('CP-1', 'Meridian Reinsurance Ltd', 'A+'), ('CP-2', 'Northbridge Capital', 'BBB')");
        }
        Map<String, String> settings = new HashMap<>();
        settings.put("url", URL);
        settings.put("pool-size", "4");
        settings.put("source-name", "risk-db");
        settings.put("query.trade", "select trade_id, notional, mtm, book, netting_set, business_date from trades "
                + "where trade_id = :id and business_date = (select max(business_date) from trades where trade_id = :id and business_date <= :asOf)");
        settings.put("query.trade.legs", "select leg_no, pay_receive, rate from legs where trade_id = :id order by leg_no");
        settings.put("query.trade.counterparty", "select c.id, c.legal_name, c.rating from counterparties c join trades t on t.counterparty_id = c.id "
                + "where t.trade_id = :id and t.business_date <= :asOf order by t.business_date desc");
        settings.put("part-shape.trade.counterparty", "object");
        settings.put("ids.trade", "select distinct trade_id from trades");
        settings.put("columns.trade", "select trade_id, mtm, notional, book, netting_set, counterparty_id from trades where business_date = :asOf");
        settings.put("layout.trade.columns", "mtm,notional,book,nettingSet,counterparty.id");
        settings.put("query.counterparty", "select id, legal_name, rating from counterparties where id = :id");
        settings.put("reverse.counterparty", "select id from counterparties where id = :target");
        plugin = new JdbcSourcePlugin();
        plugin.start(context(settings));
        long until = System.nanoTime() + 5_000_000_000L;
        while (plugin.search("trade", "MX", 10).isEmpty() && System.nanoTime() < until) {
            Thread.sleep(50);                                     // the ids are read in the background at start
        }
    }

    @AfterAll
    static void close() {
        plugin.close();
    }

    @Test
    void anEntityIsBuiltFromSeveralQueriesAtOnce() throws Exception {
        DataNode t = plugin.fetch(EntityRef.of("trade", "MX-1"), AsOf.of(D2)).orElseThrow().data();
        assertThat(t.get("mtm").asDouble()).isEqualTo(1_600_000);
        assertThat(t.get("nettingSet").asText()).isEqualTo("NS-A");
        assertThat(t.get("legs").size()).isEqualTo(2);                   // a list part
        assertThat(t.at("legs[1].payReceive").asText()).isEqualTo("RECEIVE");
        assertThat(t.at("legs[0].rate").asDouble()).isEqualTo(3.25);
        assertThat(t.at("counterparty.legalName").asText()).isEqualTo("Meridian Reinsurance Ltd");   // an object part
        assertThat(plugin.fetch(EntityRef.of("trade", "MX-1"), AsOf.of(D1)).orElseThrow().data().get("mtm").asDouble()).isEqualTo(1_500_000);
        assertThat(plugin.fetch(EntityRef.of("trade", "MX-404"), AsOf.of(D2))).isEmpty();
        assertThat(plugin.fetch(EntityRef.of("trade", "MX-2"), AsOf.of(D2)).orElseThrow().data().get("legs").size()).isZero();
    }

    @Test
    void typeAheadComesFromTheIdsQuery() {
        assertThat(plugin.search("trade", "MX", 10)).extracting(h -> h.ref().id()).containsExactly("MX-1", "MX-2");
        assertThat(plugin.manifest().capabilities().search()).isTrue();
        assertThat(plugin.manifest().capabilities().reverseLookup()).isTrue();
        assertThat(plugin.manifest().capabilities().dated()).isTrue();
    }

    @Test
    void aDaysPromotedFieldsComeFromTheColumnsQuery() {
        assertThat(plugin.columnar("trade")).containsExactlyInAnyOrder("mtm", "notional", "book", "nettingSet", "counterparty.id");
        ColumnSet c = plugin.columns("trade", List.of("mtm", "nettingSet", "counterparty.id"), AsOf.of(D2)).orElseThrow();
        assertThat(c.size()).isEqualTo(3);
        int mx2 = List.of(c.ids()).indexOf("MX-2");
        assertThat((Double) c.value("mtm", mx2)).isEqualTo(-250_000);
        assertThat(c.value("counterparty.id", mx2)).isEqualTo("CP-2");
        assertThat(plugin.columns("trade", List.of("mtm"), AsOf.of(D1)).orElseThrow().size()).isEqualTo(1);
        assertThat(plugin.columns("trade", List.of("mtm"), AsOf.of(LocalDate.of(2019, 12, 5)))).as("a day it does not hold").isEmpty();
    }

    @Test
    void reverseLookupsComeFromAQueryOrFromTheColumns() {
        assertThat(plugin.reverse(EntityRef.of("netting-set", "NS-A"), "trade", AsOf.of(D2)))
                .containsExactlyInAnyOrder(EntityRef.of("trade", "MX-1"), EntityRef.of("trade", "MX-2"));     // from the columns
        assertThat(plugin.reverse(EntityRef.of("counterparty", "CP-2"), "counterparty", AsOf.of(D2))).containsExactly(EntityRef.of("counterparty", "CP-2"));
    }

    @Test
    void columnNamesBecomeFieldNames() {
        assertThat(QueryMode.camel("TRADE_ID")).isEqualTo("tradeId");
        assertThat(QueryMode.camel("counterparty__id")).isEqualTo("counterparty__id");
        assertThat(QueryMode.camel("mtm")).isEqualTo("mtm");
        assertThat(QueryMode.NamedSql.of("select * from t where id = :id and d <= :asOf and x = :target").names()).containsExactly("id", "asOf", "target");
        assertThat(QueryMode.NamedSql.of("select * from t where id = ?").names()).containsExactly("id");
        assertThat(QueryMode.NamedSql.of("select id from t", "asOf").names()).isEmpty();
        assertThat(QueryMode.NamedSql.of("select id from t where ref = ?", "target").names()).containsExactly("target");
    }
}
