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
package com.ash.drishti.server.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.rachana.live.LiveTables;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A blotter whose broker is absent (the liveset engine's {@code LiveBlotterBrokerAbsentTest}, ported): the table serves its snapshot and its
 * schema says the live updates are paused, with the binding's reason; never an error.
 */
class LiveBlotterBrokerAbsentTest {

    @Test
    void theSnapshotIsServedAndTheLivePauseIsSaid() throws Exception {
        LiveTablesAdapterTest.Bindings b = new LiveTablesAdapterTest.Bindings() {
            @Override
            public List<String> paused(String kind) {
                return List.of("trade-kafka: live updates paused (the broker localhost:9092 cannot be reached)");
            }
        };
        b.docs.add(LiveTablesAdapterTest.trade("T1", 1, "B1", null));
        b.docs.add(LiveTablesAdapterTest.trade("T2", 1, "B2", null));
        try (LiveTables lt = LiveTablesAdapterTest.engine(b, LiveTablesAdapterTest.TRADE, null, Clock.systemUTC())) {
            Map<String, Object> page = lt.rows("trade", Map.of("sort", List.of(Map.of("col", "TRADE_ID", "dir", "desc"))), null);
            assertThat(LiveTablesAdapterTest.col(page, 0)).containsExactly("T2", "T1");
            LiveTablesAdapterTest.await(() -> String.valueOf(lt.schemaDoc("trade", null).get("live")).contains("paused"));
            assertThat(String.valueOf(lt.schemaDoc("trade", null).get("live"))).contains("paused").contains("live updates paused");
        }
    }
}
