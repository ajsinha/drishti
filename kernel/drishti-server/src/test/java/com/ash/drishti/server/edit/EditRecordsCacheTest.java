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

import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cache of a record's overlays and owned values never outlives the transaction that writes them: the moment an apply commits (its
 * state "applied" is visible), every read shows the new value, before anyone calls {@link EditRecords#forget} (which the apply does
 * later, after it closed the draft and told the consoles); a read while the transaction is open is never kept.
 */
class EditRecordsCacheTest {

    @TempDir
    Path dir;
    private HikariDataSource ds;

    @AfterEach
    void close() {
        if (ds != null) {
            ds.close();
        }
    }

    private static EditRecords.Overlay overlay(String change, Object value) {
        return new EditRecords.Overlay(change, "$.NOTIONAL", "trade", "MX-1", value, new BigDecimal("28000000"), LocalDate.of(2026, 11, 6), null,
                EditRecords.ACTIVE, "tia", Instant.now());
    }

    @Test
    void aCommittedOverlayIsReadAtOnceNotTheCachedRecordFromBefore() throws Exception {
        ds = EditTestDb.sqlite(dir);
        EditLog log = new EditLog(ds);
        EditRecords records = new EditRecords(ds);
        try (Connection c = ds.getConnection()) {
            records.overlay(c, new EditRecords.Overlay("chg-0", "$.NOTIONAL", "trade", "MX-2", 1, 0, LocalDate.of(2026, 11, 6), null,
                    EditRecords.ACTIVE, "tia", Instant.now()));                            // the site has an overlay: reads use the cache
        }
        assertThat(records.overlays("trade", "MX-1")).as("read before: cached empty").isEmpty();
        assertThat(records.owned("trade", "MX-1")).isEmpty();

        log.tx(c -> {
            records.overlay(c, overlay("chg-1", new BigDecimal("28001250")));
            assertThat(records.overlays("trade", "MX-1")).as("not committed yet: another connection reads the record as it was").isEmpty();
            records.owned(c, new EditRecords.Owned("trade", "MX-1", "$.BOOK", "RATES-2", "chg-1", "tia", Instant.now()));
            return null;
        }, "apply chg-1");

        assertThat(records.overlays("trade", "MX-1")).as("committed: read at once, no forget needed").extracting(EditRecords.Overlay::value)
                .singleElement().satisfies(v -> assertThat(new BigDecimal(v.toString())).isEqualByComparingTo("28001250"));
        assertThat(records.owned("trade", "MX-1")).extracting(EditRecords.Owned::value).containsExactly("RATES-2");
    }

    @Test
    void aRolledBackWriteLeavesTheRecordAsItWasAndCachedAgain() throws Exception {
        ds = EditTestDb.sqlite(dir);
        EditLog log = new EditLog(ds);
        EditRecords records = new EditRecords(ds);
        log.tx(c -> {
            records.overlay(c, overlay("chg-1", 28_001_250));
            return null;
        }, "apply chg-1");
        assertThat(records.overlays("trade", "MX-1")).hasSize(1);
        assertThatThrownBy(() -> log.tx(c -> {
            records.overlay(c, new EditRecords.Overlay("chg-2", "$.NOTIONAL", "trade", "MX-1", 1, 28_001_250, LocalDate.of(2026, 11, 6), null,
                    EditRecords.ACTIVE, "tia", Instant.now()));
            throw new IllegalStateException("fenced");
        }, "apply chg-2")).hasMessageContaining("fenced");
        assertThat(records.overlays("trade", "MX-1")).extracting(EditRecords.Overlay::changeId).containsExactly("chg-1");
    }

    @Test
    void aWithdrawalIsReadAtOnce() throws Exception {
        ds = EditTestDb.sqlite(dir);
        EditLog log = new EditLog(ds);
        EditRecords records = new EditRecords(ds);
        log.tx(c -> {
            records.overlay(c, overlay("chg-1", 28_001_250));
            return null;
        }, "apply chg-1");
        assertThat(records.overlays("trade", "MX-1")).hasSize(1);
        log.tx(c -> {
            records.withdraw(c, "chg-1");
            return null;
        }, "withdraw chg-1");
        assertThat(records.overlays("trade", "MX-1")).as("the source's value shows again at once").isEmpty();
    }
}
