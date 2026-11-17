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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The overlay layer reads "today" from the business dates (a fixed today holds), never from the wall clock. */
class EditLayerTodayTest {

    @TempDir
    Path dir;
    private HikariDataSource ds;

    @AfterEach
    void close() {
        if (ds != null) {
            ds.close();
        }
    }

    @Test
    void aLiveReadShowsTheOverlaysOfTheBusinessTodayNotOfTheWallClock() throws Exception {
        ds = EditTestDb.sqlite(dir);
        EditRecords records = new EditRecords(ds);
        try (Connection c = ds.getConnection()) {
            records.overlay(c, new EditRecords.Overlay("chg-1", "$.NOTIONAL", "trade", "MX-1", 2, 1, LocalDate.of(2026, 11, 6), null,
                    EditRecords.ACTIVE, "asha", Instant.now()));
        }
        AtomicReference<LocalDate> today = new AtomicReference<>(LocalDate.of(2026, 11, 5));
        EditLayer layer = new EditLayer(records, true, today::get);
        EntityRef ref = EntityRef.of("trade", "MX-1");
        EntityDocument doc = new EntityDocument(ref, DataNode.of(Map.of("NOTIONAL", 1)), null);

        assertThat(layer.apply(doc, AsOf.LATEST).data().get("NOTIONAL").asDouble()).as("the change of 11-06 is not on the page of a fixed today 11-05").isEqualTo(1.0);
        assertThat(layer.note(ref, null)).isNull();

        today.set(LocalDate.of(2026, 11, 6));
        assertThat(layer.apply(doc, AsOf.LATEST).data().get("NOTIONAL").asDouble()).isEqualTo(2.0);
        assertThat(layer.note(ref, null)).contains("chg-1");
    }
}
