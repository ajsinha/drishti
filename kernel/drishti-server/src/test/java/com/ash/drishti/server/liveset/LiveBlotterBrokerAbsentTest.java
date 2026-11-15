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
package com.ash.drishti.server.liveset;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.server.liveset.LiveQueryTest.Fake;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A live blotter whose stream connector waits for its broker still serves the lake snapshot, and says its live updates are paused. */
class LiveBlotterBrokerAbsentTest {

    static final class Paused extends Fake {
        @Override
        public List<String> livePaused(String kind) {
            return List.of("trade-endur-live: broker not reachable at kafka1:9092 - live updates paused; retrying");
        }
    }

    @Test
    void theSnapshotIsServedAndTheLivePauseIsSaid() throws Exception {
        Paused f = new Paused();
        f.put("T1", "EUR", 5, "ACME");
        f.put("T2", "EUR", 9, "ACME");
        try (LiveSets sets = new LiveSets(f, Clock.systemUTC(), 20, 100)) {
            LiveSet s = sets.query(LiveQueryTest.Q, AsOf.LATEST, null, null, false);
            assertThat(LiveQueryTest.ids(s.page(0, 100, null, false, null, null))).containsExactly("T2", "T1");
            assertThat(sets.livePaused("trade")).singleElement().asString().contains("live updates paused");
        }
    }
}
