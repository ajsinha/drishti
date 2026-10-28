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
package com.ash.drishti.server.var;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.compute.shard.ShardHandover;
import com.ash.drishti.compute.shard.ShardMap;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.server.shard.ShardPeers;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The handover rule (VAR_SHARDING.md section 7) and the wire format of a partial sum (section 6), on their own: an old owner keeps a shard
 * while its new owner is not ready and for the grace period after, so at every moment of a handover some member is ready for it; an older
 * version is kept while the newer is built; a partial's answer labelled for another shard, map, data version or number of rows is refused;
 * a member's announced state reads back as written.
 */
class VarShardHandoverTest {

    static final long GRACE = 30_000;

    @Test
    void anOwnersCurrentBookIsKeptAndAHandedOverOneOnlyAfterItsSuccessorWasReadyForTheGrace() {
        ShardHandover.Facts mine = new ShardHandover.Facts(true, true, false, false);
        assertThat(ShardHandover.decide(mine, 0, 1_000_000, GRACE)).isEqualTo(ShardHandover.Verdict.KEEP);
        ShardHandover.Facts movedNotReady = new ShardHandover.Facts(false, true, false, false);
        assertThat(ShardHandover.decide(movedNotReady, 0, 1_000_000, GRACE)).as("nobody ready to take it").isEqualTo(ShardHandover.Verdict.KEEP);
        ShardHandover.Facts movedReady = new ShardHandover.Facts(false, true, false, true);
        assertThat(ShardHandover.decide(movedReady, 1_000_000, 1_000_000 + GRACE - 1, GRACE)).isEqualTo(ShardHandover.Verdict.KEEP);
        assertThat(ShardHandover.decide(movedReady, 1_000_000, 1_000_000 + GRACE, GRACE)).isEqualTo(ShardHandover.Verdict.RELEASE);
        ShardHandover.Facts older = new ShardHandover.Facts(true, false, false, true);
        assertThat(ShardHandover.decide(older, 5, 5 + GRACE, GRACE)).as("an older version, the newer ready here").isEqualTo(ShardHandover.Verdict.RELEASE);
        ShardHandover.Facts olderBuilding = new ShardHandover.Facts(true, false, false, false);
        assertThat(ShardHandover.decide(olderBuilding, 0, 1_000_000, GRACE)).as("the newer is still building").isEqualTo(ShardHandover.Verdict.KEEP);
        ShardHandover.Facts staleMap = new ShardHandover.Facts(true, true, true, false);
        assertThat(ShardHandover.decide(staleMap, 10, 10 + GRACE, GRACE)).as("a shard map since replaced").isEqualTo(ShardHandover.Verdict.RELEASE);
    }

    /**
     * A handover played second by second: the new owner takes 7 s to build the shard and announce it; the old owner looks every second. At
     * no second is the shard without a ready holder, and the old owner releases it exactly the grace period after the new one was ready.
     */
    @Test
    void aHandoverNeverLeavesTheShardWithoutAReadyHolder() {
        boolean oldHolds = true;
        boolean newReady = false;
        long readySince = 0;
        long released = -1;
        for (long t = 0; t <= 60_000; t += 1_000) {
            if (t >= 7_000) {
                newReady = true;                                         // built from the lake and announced
            }
            if (oldHolds) {
                ShardHandover.Facts f = new ShardHandover.Facts(false, true, false, newReady);
                readySince = newReady ? (readySince == 0 ? t : readySince) : 0;
                if (ShardHandover.decide(f, readySince, t, GRACE) == ShardHandover.Verdict.RELEASE) {
                    oldHolds = false;
                    released = t;
                }
            }
            assertThat(oldHolds || newReady).as("a ready holder at " + t + " ms").isTrue();
        }
        assertThat(released).isEqualTo(7_000 + GRACE);
    }

    @Test
    void aPartialIsAnsweredOnlyWithItsOwnLabel() {
        ShardMap map = ShardMap.whole(LocalDate.of(2026, 9, 30), "v1", VarSharding.MEASURE.keyOrder(), 1024, "test");
        double[] sum = {1.5, -2.25, 3};
        byte[] ok = json(VarShardRows.answer(0, "S0", map.digest(), "d1", "node-b", 4, sum));
        assertThat(VarShardRows.answer(ok, map, 0, "d1", 4)).containsExactly(sum);
        for (Object[] bad : List.of(new Object[] {1, map.digest(), "d1", 4}, new Object[] {0, "another map", "d1", 4},
                new Object[] {0, map.digest(), "d0-stale", 4}, new Object[] {0, map.digest(), "d1", 3})) {
            byte[] b = json(VarShardRows.answer((int) bad[0], "S" + bad[0], (String) bad[1], (String) bad[2], "node-b", (int) bad[3], sum));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> VarShardRows.answer(b, map, 0, "d1", 4)).hasMessageContaining("refused");
        }
        OrdinalSet rows = OrdinalSet.of(0, 3, 64, 65, 200);
        byte[] req = VarShardRows.request(map.date(), "EUR", map, 0, "d1", rows);
        assertThat(VarShardRows.rowsOf(read(req).path("rows").asText())).isEqualTo(rows);
    }

    @Test
    void aMembersStateReadsBackAsAnnounced() {
        Map<LocalDate, List<ShardPeers.Ready>> dates = new TreeMap<>();
        dates.put(LocalDate.of(2026, 9, 30), List.of(new ShardPeers.Ready("map1", new TreeMap<>(Map.of(0, "abcdef0123456789", 3, "fedcba")))));
        ShardPeers.State s = new ShardPeers.State(true, true, false, dates);
        ShardPeers.State back = ShardPeers.State.parse(s.json());
        assertThat(back).isEqualTo(s);
        assertThat(back.ready(LocalDate.of(2026, 9, 30), "map1", 0, "abcdef0123456789" + "0".repeat(48))).isTrue();
        assertThat(back.ready(LocalDate.of(2026, 9, 30), "map1", 0, "0".repeat(64))).as("another data version").isFalse();
        assertThat(back.ready(LocalDate.of(2026, 9, 30), "map2", 0, null)).isFalse();
        assertThat(ShardPeers.State.parse("not json")).isEqualTo(ShardPeers.State.NONE);
        assertThat(ShardPeers.zone(List.of("region:emea", "az:us-east-1a"), "az")).isEqualTo("us-east-1a");
    }

    private static byte[] json(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode read(byte[] b) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(b);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
