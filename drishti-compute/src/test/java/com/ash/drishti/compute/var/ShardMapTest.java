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
package com.ash.drishti.compute.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The shard map (VAR_SHARDING.md section 3): a pure function of the date's row counts per legal entity and desk, the same whatever order
 * the counts were gathered in; contiguous ranges in the hot file's order, a desk never split, each near the target (one desk alone may
 * exceed it); every key, counted or not, in exactly one shard; a shard key that is not the hot file's order refused.
 */
class ShardMapTest {

    static final LocalDate DAY = LocalDate.of(2026, 9, 30);
    static final int WIDTH = 509;
    static final long ROW = WIDTH * 4L;

    static SortedMap<List<String>, Long> counts(long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        List<Map.Entry<List<String>, Long>> all = new ArrayList<>();
        for (int le = 0; le < 6; le++) {
            for (int d = 0; d < 1 + r.nextInt(12); d++) {
                all.add(Map.entry(List.of("LE-" + le, "DESK-" + le + "-" + d), 1L + r.nextInt(5_000)));
            }
        }
        all.add(Map.entry(java.util.Arrays.asList(null, "DESK-NONE"), 17L));   // a trade without a legal entity: sorted first, as the hot file does
        Collections.shuffle(all, new java.util.Random(seed));
        SortedMap<List<String>, Long> m = ShardMap.counts();
        all.forEach(e -> m.put(e.getKey(), e.getValue()));
        return m;
    }

    @Test
    void theSameCountsGiveTheSameMapWhateverTheirOrder() {
        ShardMap a = ShardMap.cut(DAY, "v1", List.of("LEGAL_ENTITY", "DESK"), counts(3), WIDTH, 4_000 * ROW);
        ShardMap b = ShardMap.cut(DAY, "v1", List.of("legal_entity", "desk"), new TreeMap<>(counts(3)), WIDTH, 4_000 * ROW);
        assertThat(a.digest()).isEqualTo(b.digest());
        assertThat(a.shards()).isEqualTo(b.shards());
        assertThat(ShardMap.cut(DAY, "v2", ShardMap.ORDER, counts(3), WIDTH, 4_000 * ROW).digest()).as("another data version, the same cut")
                .isEqualTo(a.digest());
        assertThat(ShardMap.cut(DAY, "v1", ShardMap.ORDER, counts(3), WIDTH, 2_000 * ROW).digest()).as("another cut").isNotEqualTo(a.digest());
    }

    @Test
    void shardsAreContiguousNearTheTargetAndNeverSplitADesk() {
        SortedMap<List<String>, Long> c = counts(11);
        long target = 6_000 * ROW;
        ShardMap m = ShardMap.cut(DAY, "v1", ShardMap.ORDER, c, WIDTH, target);
        assertThat(m.size()).isGreaterThan(3);
        long total = 0;
        List<String> prevLast = null;
        for (ShardMap.Shard s : m.shards()) {
            long rows = c.subMap(s.first(), next(c, s.last())).values().stream().mapToLong(Long::longValue).sum();
            assertThat(s.rows()).isEqualTo(rows);
            assertThat(s.bytes() <= target || c.subMap(s.first(), next(c, s.last())).size() == 1).as(s.range() + " over the target").isTrue();
            if (prevLast != null) {
                assertThat(ShardMap.compare(prevLast, s.first())).isNegative();
                assertThat(next(c, prevLast)).isEqualTo(s.first());            // no key between two shards
            }
            prevLast = s.last();
            total += s.rows();
        }
        assertThat(total).isEqualTo(c.values().stream().mapToLong(Long::longValue).sum());
        for (List<String> k : c.keySet()) {                                    // each desk in exactly the shard whose range holds it
            ShardMap.Shard s = m.shard(m.shardOf(k));
            assertThat(ShardMap.compare(s.first(), k) <= 0 && ShardMap.compare(k, s.last()) <= 0).as(k + " in " + s.range()).isTrue();
        }
        assertThat(m.shardOf(List.of("", ""))).isZero();                        // before every key: the first shard
        assertThat(m.shardOf(List.of("ZZZ", "ZZZ"))).isEqualTo(m.size() - 1);   // after every key: the last
        assertThat(m.shardOf(List.of("LE-2", "DESK-2-99"))).isBetween(0, m.size() - 1);   // a key not counted still has one shard
    }

    @Test
    void aTargetLargerThanTheDateIsOneShardAndAnEmptyDateIsWhole() {
        ShardMap one = ShardMap.cut(DAY, "v1", ShardMap.ORDER, counts(5), WIDTH, Long.MAX_VALUE / 4);
        assertThat(one.size()).isEqualTo(1);
        assertThat(one.shardOf(List.of("LE-3", "DESK-3-0"))).isZero();
        ShardMap none = ShardMap.cut(DAY, "v1", ShardMap.ORDER, ShardMap.counts(), WIDTH, ROW);
        assertThat(none.size()).isEqualTo(1);
        assertThat(none.shard(0).range()).isEqualTo("all");
        assertThat(none.note()).isNotNull();
    }

    @Test
    void theKeyMustBeTheHotFilesOrder() {
        assertThat(ShardMap.checkKey(List.of("LEGAL_ENTITY"))).containsExactly("LEGAL_ENTITY");
        assertThat(ShardMap.checkKey(null)).isEqualTo(ShardMap.ORDER);
        assertThatThrownBy(() -> ShardMap.checkKey(List.of("DESK"))).hasMessageContaining("hot file");
        assertThatThrownBy(() -> ShardMap.checkKey(List.of("LEGAL_ENTITY", "DESK", "TT_BOOK"))).hasMessageContaining("never split");
    }

    private static List<String> next(SortedMap<List<String>, Long> c, List<String> key) {
        for (List<String> k : c.keySet()) {
            if (ShardMap.compare(k, key) > 0) {
                return k;
            }
        }
        return List.of("￿");
    }
}
