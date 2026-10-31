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

import java.util.BitSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The compact id index (2.9.7, D2) answers every id exactly as the hash maps it replaces did: exact ids (the later index of a repeated
 * id), ids in any case (-1 for two ids equal ignoring case), skipped indexes in neither, unknown ids -1.
 */
class IdIndexTest {

    @Test
    void answersAsTheMapsDid() {
        SplittableRandom r = new SplittableRandom(5);
        int n = 20_000;
        String[] ids = new String[n];
        for (int i = 0; i < n; i++) {
            int k = r.nextInt(n * 2);
            ids[i] = switch (i % 5) {
                case 0 -> "T-" + k;
                case 1 -> "t-" + k;                                     // differs from a T- id only in case: ambiguous in any case
                case 2 -> "MX-" + (i % 997);                            // repeated ids
                case 3 -> "Zürich-" + k;
                default -> "CLY-" + Integer.toHexString(k);
            };
        }
        BitSet skip = new BitSet();
        for (int i = 0; i < n; i += 7) {
            skip.set(i);
        }
        Map<String, Integer> exact = new HashMap<>();
        Map<String, Integer> upper = new HashMap<>();
        for (int i = 0; i < n; i++) {
            if (!skip.get(i)) {
                exact.put(ids[i], i);
                upper.merge(ids[i].toUpperCase(Locale.ROOT), i, (a, x) -> -1);
            }
        }
        IdIndex e = IdIndex.exact(ids, skip);
        IdIndex u = IdIndex.upper(ids, skip);
        for (int i = 0; i < n; i++) {
            assertThat(e.get(ids[i])).as(ids[i]).isEqualTo(exact.getOrDefault(ids[i], -1));
            String any = i % 2 == 0 ? ids[i].toLowerCase(Locale.ROOT) : ids[i];
            assertThat(u.get(any)).as(any).isEqualTo(upper.getOrDefault(any.toUpperCase(Locale.ROOT), -1));
        }
        assertThat(e.get("NOPE")).isEqualTo(-1);
        assertThat(u.get("")).isEqualTo(-1);
        assertThat(IdIndex.exact(new String[0], null).get("x")).isEqualTo(-1);
        assertThat(e.bytes()).isLessThan(5L * n);
    }

    /** The lookup cost against the hash map (a 50,000-id basket over 100,000 ids): {@code main}, never in the build. */
    public static void main(String[] args) {
        int n = 100_000;
        String[] ids = new String[n];
        for (int i = 0; i < n; i++) {
            ids[i] = "MX-" + (20_000_000 + i * 7);
        }
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < n; i++) {
            map.put(ids[i], i);
        }
        long t0 = System.nanoTime();
        IdIndex e = IdIndex.exact(ids, null);
        long build = System.nanoTime() - t0;
        SplittableRandom r = new SplittableRandom(3);
        String[] basket = new String[50_000];
        for (int i = 0; i < basket.length; i++) {
            basket[i] = ids[r.nextInt(n)];
        }
        long[] best = {Long.MAX_VALUE, Long.MAX_VALUE};
        long sink = 0;
        for (int rep = 0; rep < 30; rep++) {
            long a = System.nanoTime();
            for (String s : basket) {
                sink += map.get(s);
            }
            long b = System.nanoTime();
            for (String s : basket) {
                sink += e.get(s);
            }
            long c = System.nanoTime();
            best[0] = Math.min(best[0], b - a);
            best[1] = Math.min(best[1], c - b);
        }
        System.out.printf("index built in %.1f ms; 50,000 lookups: hash map %.2f ms, sorted index %.2f ms (%d)%n", build / 1e6, best[0] / 1e6,
                best[1] / 1e6, sink % 7);
    }
}
