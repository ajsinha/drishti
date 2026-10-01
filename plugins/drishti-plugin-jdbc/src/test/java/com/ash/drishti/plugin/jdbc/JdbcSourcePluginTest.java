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

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.common.JsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;

class JdbcSourcePluginTest {

    @Test
    void readsRowsAndJsonColumns() throws Exception {
        String url = "jdbc:h2:mem:drishti;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            s.execute("create table trades(trade_id varchar primary key, notional decimal(18,2), maturity date, generation bigint)");
            s.execute("insert into trades values ('IRS-1', 50000000, DATE '2031-10-02', 9)");
            s.execute("create table docs(id varchar primary key, json varchar)");
            s.execute("insert into docs values ('C-1', '{\"curveId\":\"C-1\",\"points\":[{\"tenor\":\"1Y\",\"rate\":3.7}]}')");
        }
        JsonCodec codec = new JsonCodec();
        JdbcSourcePlugin p = new JdbcSourcePlugin();
        p.start(new SourceContext() {
            public Map<String, String> settings() {
                return Map.of("url", url, "source-name", "risk-db", "pool-size", "2",
                        "query.trade", "select * from trades where trade_id = ?", "query.curve", "select json from docs where id = ?");
            }

            public DataNode parseJson(InputStream in) throws IOException {
                return codec.read(in);
            }

            public ScheduledExecutorService scheduler() {
                return Executors.newSingleThreadScheduledExecutor();
            }
        });
        var t = p.fetch(EntityRef.of("trade", "IRS-1")).orElseThrow();
        assertThat(t.data().get("tradeId").asText()).isEqualTo("IRS-1");
        assertThat(t.data().get("notional").asDouble()).isEqualTo(5e7);
        assertThat(t.data().get("maturity").asText()).isEqualTo("2031-10-02");
        assertThat(t.provenance().generation()).isEqualTo(9);
        assertThat(p.fetch(EntityRef.of("curve", "C-1")).orElseThrow().data().at("points[0].rate").asDouble()).isEqualTo(3.7);
        assertThat(p.fetch(EntityRef.of("trade", "NOPE"))).isEmpty();
        assertThat(p.fetch(EntityRef.of("book", "X"))).isEmpty();
        assertThat(p.manifest().kinds()).containsExactlyInAnyOrder("trade", "curve");
        p.close();
    }

    @Test
    void jsonColumnsBecomeNestedDataInQueryMode() throws Exception {
        String url = "jdbc:h2:mem:drishti-json;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            s.execute("create table trades(trade_id varchar primary key, legs json, extras varchar, note varchar)");
            s.execute("insert into trades values ('T-1', JSON '[{\"leg\":1,\"rate\":3.5},{\"leg\":2,\"index\":\"SOFR\"}]', "
                    + "'{\"desk\":\"Rates\",\"tags\":[\"a\",\"b\"]}', '{not json}')");
        }
        JsonCodec codec = new JsonCodec();
        JdbcSourcePlugin p = new JdbcSourcePlugin();
        p.start(new SourceContext() {
            public Map<String, String> settings() {
                return Map.of("url", url, "pool-size", "1", "json-columns", "extras, note", "query.trade", "select * from trades where trade_id = ?");
            }

            public DataNode parseJson(InputStream in) throws IOException {
                return codec.read(in);
            }

            public ScheduledExecutorService scheduler() {
                return Executors.newSingleThreadScheduledExecutor();
            }
        });
        try {
            DataNode t = p.fetch(EntityRef.of("trade", "T-1")).orElseThrow().data();
            assertThat(t.at("legs[0].rate").asDouble()).isEqualTo(3.5);               // a JSON column: nested
            assertThat(t.at("legs[1].index").asText()).isEqualTo("SOFR");
            assertThat(t.at("extras.tags[1]").asText()).isEqualTo("b");               // JSON in a text column named in json-columns
            assertThat(t.get("note").asText()).isEqualTo("{not json}");                // not JSON: kept as text
        } finally {
            p.close();
        }
    }
}
