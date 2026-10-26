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
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.write.WriteCommand;
import com.ash.drishti.api.write.WriteResult;
import com.ash.drishti.common.JsonCodec;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;

/**
 * A database connector's {@code writes:} command (WRITE_BACK.md section 5.4): a command applied twice by a retry changes the source once
 * (its idempotency clause finds the change id), a stale {@code basedOn} comes back conflicted (the guard matches no row), and a command
 * without its guard or idempotency clause, or naming an unknown parameter, is refused when it is checked.
 */
class JdbcWritesTest {

    static final String URL = "jdbc:h2:mem:drishti-writes;DB_CLOSE_DELAY=-1";
    static final Map<String, Object> AMEND = Map.of(
            "sql", "UPDATE MX_TRADE_LEG SET NOTIONAL = :value, VERSION = VERSION + 1, DRISHTI_CHANGE_ID = :changeId "
                    + "WHERE TRADE_ID = :entity AND LEG_NO = :leg AND VERSION = :basedOn",
            "expect-rows", 1,
            "already-applied", "SELECT 1 FROM MX_TRADE_LEG WHERE TRADE_ID = :entity AND LEG_NO = :leg AND DRISHTI_CHANGE_ID = :changeId");

    private static JdbcSourcePlugin plugin() throws Exception {
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS MX_TRADE_LEG");
            s.execute("CREATE TABLE MX_TRADE_LEG (TRADE_ID VARCHAR(32), LEG_NO INT, NOTIONAL DECIMAL(18,2), VERSION BIGINT, DRISHTI_CHANGE_ID VARCHAR(64))");
            s.execute("INSERT INTO MX_TRADE_LEG VALUES ('MX-1', 1, 1000000, 7, NULL)");
        }
        JsonCodec codec = new JsonCodec();
        JdbcSourcePlugin p = new JdbcSourcePlugin();
        p.start(new SourceContext() {
            public Map<String, String> settings() {
                return Map.of("url", URL, "pool-size", "2", "query.trade", "select * from MX_TRADE_LEG where TRADE_ID = ?");
            }

            public DataNode parseJson(InputStream in) throws IOException {
                return codec.read(in);
            }

            public ScheduledExecutorService scheduler() {
                return Executors.newSingleThreadScheduledExecutor();
            }
        });
        return p;
    }

    private static WriteCommand cmd(String changeId, long basedOn, Object value) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("entity", "MX-1");
        params.put("leg", 1);
        params.put("value", value);
        params.put("basedOn", basedOn);
        params.put("changeId", changeId);
        return new WriteCommand("amend-notional", AMEND, params);
    }

    private static Object[] row() throws Exception {
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT NOTIONAL, VERSION, DRISHTI_CHANGE_ID FROM MX_TRADE_LEG WHERE TRADE_ID = 'MX-1'")) {
            r.next();
            return new Object[] {r.getBigDecimal(1), r.getLong(2), r.getString(3)};
        }
    }

    @Test
    void aRetryChangesTheSourceOnceAndAStaleVersionIsConflicted() throws Exception {
        JdbcSourcePlugin p = plugin();
        assertThat(p.writeKey()).contains("jdbc");
        assertThat(p.write(cmd("chg-1", 7, new BigDecimal("2500000"))).status()).isEqualTo(WriteResult.Status.APPLIED);
        assertThat(row()).containsExactly(new BigDecimal("2500000.00"), 8L, "chg-1");
        assertThat(p.write(cmd("chg-1", 7, new BigDecimal("2500000"))).status()).as("the resend").isEqualTo(WriteResult.Status.ALREADY);
        assertThat(row()[1]).as("changed once").isEqualTo(8L);
        WriteResult stale = p.write(cmd("chg-2", 7, new BigDecimal("3000000")));
        assertThat(stale.status()).isEqualTo(WriteResult.Status.CONFLICTED);
        assertThat(row()).containsExactly(new BigDecimal("2500000.00"), 8L, "chg-1");
        p.close();
    }

    @Test
    void aCommandWithoutItsGuardOrIdempotencyOrWithAnUnknownParameterIsRefused() {
        Set<String> known = new java.util.TreeSet<>(WriteCommand.SUPPLIED);
        known.add("leg");
        assertThat(JdbcWrites.check("amend-notional", AMEND, known)).isEmpty();
        Map<String, Object> noGuard = new LinkedHashMap<>(AMEND);
        noGuard.remove("expect-rows");
        assertThat(JdbcWrites.check("x", noGuard, known)).anyMatch(m -> m.contains("expect-rows is required"));
        Map<String, Object> noIdem = new LinkedHashMap<>(AMEND);
        noIdem.remove("already-applied");
        assertThat(JdbcWrites.check("x", noIdem, known)).anyMatch(m -> m.contains("already-applied is required"));
        assertThat(JdbcWrites.check("x", AMEND, Set.copyOf(WriteCommand.SUPPLIED))).anyMatch(m -> m.contains(":leg is not a parameter"));
    }

    @Test
    void namedParametersAreBoundNeverPasted() {
        JdbcWrites.Bound b = JdbcWrites.bind("UPDATE t SET a = :value, b = 'x:y', c = d::text WHERE id = :entity");
        assertThat(b.sql()).isEqualTo("UPDATE t SET a = ?, b = 'x:y', c = d::text WHERE id = ?");
        assertThat(b.names()).isEqualTo(List.of("value", "entity"));
    }
}
