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
package com.ash.drishti.server.edit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.compute.ComputeEvent;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An applied change triggers the reactive connectors (WRITE_BACK.md section 18.3): a {@code write-back} event with the change id as its
 * id, the fields changed and never their values, and the watermark {@code L<lane> -> seq}; how far the triggers were told is kept per
 * lane, so a lane gained after a restart replays only what they missed, in order. Paths lay values over documents, including a page's
 * row picked by its key.
 */
class EditTriggersTest {

    @TempDir
    Path dir;
    HikariDataSource ds;

    @AfterEach
    void close() {
        if (ds != null) {
            ds.close();
        }
    }

    @Test
    void anAppliedChangeIsAWriteBackEventStampedWithItsLaneAndSeqAndReplayedOnlyWhenMissed() throws Exception {
        ds = EditTestDb.sqlite(dir);
        EditLog log = new EditLog(ds);
        log.ensureLanes(4);
        long epoch = log.claim(0, "n1", 10_000).orElseThrow();
        Change a = EditLogTest.change("MX-1", 5);
        Change b = EditLogTest.change("MX-2", 6);
        log.append(a, 0, "n1", epoch, 100);
        log.append(b, 0, "n1", epoch, 100);
        for (EditLog.Row r : log.unapplied(0, 10)) {
            log.finish(r, "n1", epoch, EditLog.APPLIED, null, null, null);
        }
        List<ComputeEvent> sent = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> told = new CopyOnWriteArrayList<>();
        try (EditTriggers t = new EditTriggers(log, e -> {
            sent.add(e);
            return Map.of("var-engine", "2 scopes");
        }, told::add)) {
            EditLog.Row first = log.get(a.id()).orElseThrow();
            t.applied(first);
            waitFor(() -> sent.size() == 1);
            ComputeEvent e = sent.get(0);
            assertThat(e.id()).isEqualTo(a.id());
            assertThat(e.type()).isEqualTo(ComputeEvent.WRITE_BACK);
            assertThat(e.kind()).isEqualTo("trade");
            assertThat(e.entity()).isEqualTo("MX-1");
            assertThat(e.paths()).containsExactly("$.NOTIONAL");
            assertThat(e.watermark()).containsEntry("L0", first.seq());
            assertThat(e.payload()).containsEntry("when", "applied").doesNotContainKey("value");
            waitFor(() -> told.size() == 1);
            assertThat(told.get(0)).containsEntry("type", "recompute").containsEntry("watermark", "L0:" + first.seq());
            t.replay(0);                                                // the lane gained again: only the change not yet told
            waitFor(() -> sent.size() == 2);
            Thread.sleep(200);
            assertThat(sent).extracting(ComputeEvent::id).containsExactly(a.id(), b.id());
            assertThat(log.cursor(EditTriggers.CONSUMER, 0)).isEqualTo(log.get(b.id()).orElseThrow().seq());
        }
    }

    @Test
    void valuesAreLaidAtAFieldAnIndexOrAPageRowPickedByItsKey() {
        DataNode doc = DataNode.of(Map.of("TRADES", List.of(Map.of("TRADE_ID", "MX-1", "NOTIONAL", 1), Map.of("TRADE_ID", "MX-2", "NOTIONAL", 2)),
                "LEGS", List.of(Map.of("NOTIONAL", 9))));
        DataNode out = EditPaths.set(doc, EditPaths.ofRow("$.TRADES", "TRADE_ID", "MX-2", "NOTIONAL"), 5);
        assertThat(EditPaths.get(out, "$.TRADES[TRADE_ID=MX-2].NOTIONAL").asDouble()).isEqualTo(5);
        assertThat(EditPaths.get(out, "$.TRADES[TRADE_ID=MX-1].NOTIONAL").asDouble()).isEqualTo(1);
        assertThat(EditPaths.get(EditPaths.set(doc, "$.LEGS[0].NOTIONAL", 3), "$.LEGS[0].NOTIONAL").asDouble()).isEqualTo(3);
        assertThat(EditPaths.set(doc, "$.TRADES[TRADE_ID=NOPE].NOTIONAL", 3)).as("a row that is not there is not invented").isEqualTo(doc);
        assertThat(EditPaths.get(EditPaths.set(doc, "$.COMMENT", "fixed"), "$.COMMENT").asText()).isEqualTo("fixed");
        assertThat(EditPaths.valid("$.A; DROP")).isFalse();
        assertThat(EditPaths.field("$.TRADES[TRADE_ID=MX-2].NOTIONAL")).isEqualTo("NOTIONAL");
    }

    static void waitFor(java.util.function.BooleanSupplier ok) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (!ok.getAsBoolean() && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        assertThat(ok.getAsBoolean()).isTrue();
    }
}
