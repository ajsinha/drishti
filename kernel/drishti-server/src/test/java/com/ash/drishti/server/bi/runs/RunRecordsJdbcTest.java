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
package com.ash.drishti.server.bi.runs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** The run rows in the identity database's own schema file (SQLite, the default): written, replaced, read back field for field, and queried. */
class RunRecordsJdbcTest {

    @TempDir
    Path dir;

    @Test
    void aRunRowRoundTripsThroughTheSqliteSchema() throws Exception {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("identity.db"));
        String schema;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("db/schema-sqlite.sql")) {
            schema = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("--[^\\n]*", "");
        }
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String sql : schema.split(";")) {
                if (!sql.isBlank()) {
                    st.execute(sql);
                }
            }
        }
        RunRecords.Jdbc records = new RunRecords.Jdbc(ds);
        Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Run r = Run.planned(RunId.next("java-2", t, new SplittableRandom(4)), "ajsinha", "ajsinha", "mq", "{\"q\":1}", "ab".repeat(32), "sig", Tier.AD_HOC_HEAVY,
                "local", "{\"basis\":\"unknown\"}", "java-2", t).moveTo(RunState.QUEUED, null, null, t);
        Run.Builder b = r.toBuilder();
        b.title = "Desk P&L";
        b.params = "{\"DESK\":[\"RATES-NY\"]}";
        b.resultKey = "ab".repeat(32);
        records.save(b.build());
        Run running = records.get(r.id()).orElseThrow().moveTo(RunState.RUNNING, null, null, t.plusSeconds(1)).progress(0.5, "scanning");
        records.save(running);
        Run back = records.get(r.id()).orElseThrow();
        assertThat(back).isEqualTo(running);
        assertThat(records.onNode("java-2", List.of(RunState.RUNNING))).containsExactly(running);
        assertThat(records.owned("ajsinha", null, 10)).hasSize(1);
        assertThat(records.owned("bob", null, 10)).isEmpty();
        Run done = running.toBuilder().build().moveTo(RunState.STORING, null, null, t.plusSeconds(2)).moveTo(RunState.DONE, null, null, t.plusSeconds(3));
        records.save(done);
        assertThat(records.doneWithKey("ab".repeat(32))).contains(done);
        assertThat(records.inStates(List.of(RunState.RUNNING), 10)).isEmpty();
    }
}
