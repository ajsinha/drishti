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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.identity.collab.Ulid;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The edit log on the Drishti database (WRITE_BACK.md › The design and its reasons, sections 7 and 11): appends are ordered by seq and acknowledged once committed, a
 * retry with the same change id is not appended twice, a lane deeper than its limit refuses, ownership is taken by a conditional update
 * and every append and apply is fenced by the epoch (a zombie owner's writes roll back whole), and a new owner replays from the applied seq.
 */
class EditLogTest {

    @TempDir
    Path dir;
    HikariDataSource ds;
    EditLog log;

    @BeforeEach
    void open() {
        ds = EditTestDb.sqlite(dir);
        log = new EditLog(ds);
        log.ensureLanes(4);
    }

    @AfterEach
    void close() {
        ds.close();
    }

    static Change change(String entity, Object to) {
        return new Change(Ulid.next("chg-"), Change.keyOf("trade", entity), "trade", entity, LocalDate.of(2026, 10, 23),
                List.of(new Change.Item("$.NOTIONAL", 1, to)), null, "overlay", null, null, Map.of(), "test", "asha", Instant.now(), 0, Map.of(), false);
    }

    @Test
    void appendsAreOrderedBySeqAndARetryIsNotAppendedTwice() {
        long epoch = log.claim(0, "n1", 10_000).orElseThrow();
        Change a = change("MX-1", 2);
        long s1 = log.append(a, 0, "n1", epoch, 100);
        long s2 = log.append(change("MX-2", 3), 0, "n1", epoch, 100);
        assertThat(s2).isEqualTo(s1 + 1);
        assertThat(log.append(a, 0, "n1", epoch, 100)).as("the same change id").isEqualTo(s1);
        List<EditLog.Row> rows = log.unapplied(0, 10);
        assertThat(rows).extracting(EditLog.Row::seq).containsExactly(s1, s2);
        assertThat(rows.get(0).state()).isEqualTo(EditLog.QUEUED);
        assertThat(EditLog.intact(rows.get(0))).as("the entry's signature").isTrue();
        assertThat(log.lane(0).orElseThrow().depth()).isEqualTo(2);
    }

    @Test
    void aFullLaneRefusesBusy() {
        long epoch = log.claim(1, "n1", 10_000).orElseThrow();
        log.append(change("A", 1), 1, "n1", epoch, 2);
        log.append(change("B", 1), 1, "n1", epoch, 2);
        assertThatThrownBy(() -> log.append(change("C", 1), 1, "n1", epoch, 2)).isInstanceOf(EditLog.Busy.class);
        assertThat(log.unapplied(1, 10)).hasSize(2);
    }

    @Test
    void ownershipIsTakenInTheStoreAndAStaleEpochIsFenced() throws Exception {
        long e1 = log.claim(2, "n1", 50).orElseThrow();
        assertThat(log.claim(2, "n2", 10_000)).as("a live lease is not taken").isEmpty();
        long seq = log.append(change("MX-9", 5), 2, "n1", e1, 100);
        Thread.sleep(120);                                              // n1 is cut off: its lease runs out
        long e2 = log.claim(2, "n2", 10_000).orElseThrow();
        assertThat(e2).isEqualTo(e1 + 1);
        assertThat(log.renew(2, "n1", e1, 10_000)).as("the old owner's renewal").isFalse();
        assertThatThrownBy(() -> log.append(change("MX-9", 6), 2, "n1", e1, 100)).isInstanceOf(EditLog.Fenced.class);
        EditLog.Row row = log.unapplied(2, 1).get(0);
        EditRecords records = new EditRecords(ds);
        assertThatThrownBy(() -> log.finish(row, "n1", e1, EditLog.APPLIED, null, null, c -> records.overlay(c, new EditRecords.Overlay(row.changeId(),
                "$.NOTIONAL", "trade", "MX-9", 5, 1, row.change().businessDate(), null, EditRecords.ACTIVE, "asha", Instant.now()))))
                .isInstanceOf(EditLog.Fenced.class);
        assertThat(new EditRecords(ds).overlays("trade", "MX-9")).as("nothing the zombie attempted is written").isEmpty();
        assertThat(log.lane(2).orElseThrow().appliedSeq()).isZero();
        log.finish(row, "n2", e2, EditLog.APPLIED, null, null, null);   // the new owner replays from the applied seq
        assertThat(log.lane(2).orElseThrow().appliedSeq()).isEqualTo(seq);
        assertThat(log.get(row.changeId()).orElseThrow().state()).isEqualTo(EditLog.APPLIED);
        assertThat(log.unapplied(2, 10)).isEmpty();
    }

    @Test
    void aNodeThatRestartsTakesItsOwnLanesBackAtANewEpoch() {
        long e1 = log.claim(3, "n1", 60_000).orElseThrow();
        long e2 = log.claim(3, "n1", 60_000).orElseThrow();
        assertThat(e2).isEqualTo(e1 + 1);
        assertThatThrownBy(() -> log.append(change("X", 1), 3, "n1", e1, 100)).isInstanceOf(EditLog.Fenced.class);
    }

    @Test
    void cursorsMoveForwardOnly() {
        assertThat(log.cursor("reactive", 0)).isZero();
        log.cursor("reactive", 0, 5);
        log.cursor("reactive", 0, 3);
        assertThat(log.cursor("reactive", 0)).isEqualTo(5);
    }

    @Test
    void approvalsAreFourEyesAndDecidedOnce() {
        ChangeRequests r = new ChangeRequests(ds);
        Change c = change("MX-7", 9);
        r.add(c, 2);
        assertThat(r.approve(c.id(), "ben", "ok", Instant.now()).orElseThrow().state()).isEqualTo(ChangeRequests.AWAITING);
        assertThat(r.approve(c.id(), "kim", "", Instant.now()).orElseThrow().state()).isEqualTo(ChangeRequests.APPROVED);
        assertThat(r.decide(c.id(), ChangeRequests.REJECTED, "kim", "late", Instant.now())).as("no longer awaiting").isFalse();
        assertThat(r.get(c.id()).orElseThrow().approvals()).extracting(ChangeRequests.Approval::by).containsExactly("ben", "kim");
    }
}
