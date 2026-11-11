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
package com.ash.drishti.engine.history;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.HistoryDay;
import com.ash.drishti.api.Provenance;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SOAK3-02: the history cache is one byte budget ({@code drishti.history.cache-mb}) for every key, whatever the caller's signature. Since
 * SOAK2-01 days read through a peer are kept per caller signature, so a cluster with many roles keeps more keys for one entity; the budget
 * is still global, and the least recently used entries are dropped to stay under it.
 */
class HistoryCacheBoundTest {

    /** Ten days of a trade-like document: 40 fields and two legs of ten fields each (the names shared, as a parser shares them). */
    static List<HistoryDay> days(String id) {
        List<HistoryDay> out = new ArrayList<>();
        for (int d = 1; d <= 10; d++) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("TRADE_ID", id);
            for (int f = 0; f < 39; f++) {
                doc.put(("FIELD_" + f).intern(), f % 2 == 0 ? "value-" + f + "-" + id : (Object) (f * 1.5 + d));
            }
            List<Object> legs = new ArrayList<>();
            for (int l = 0; l < 2; l++) {
                Map<String, Object> leg = new LinkedHashMap<>();
                for (int f = 0; f < 10; f++) {
                    leg.put(("LEG_" + f).intern(), "leg-" + l + "-" + f);
                }
                legs.add(leg);
            }
            doc.put("LEGS", legs);
            LocalDate date = LocalDate.of(2026, 10, d);
            EntityRef ref = EntityRef.of("trade", id);
            out.add(new HistoryDay(date, new EntityDocument(ref, DataNode.of(doc), new Provenance("lake", d, Instant.EPOCH, false, date)), 1));
        }
        return out;
    }

    @Test
    void manySignaturesShareOneByteBoundAndTheLeastRecentlyUsedGoFirst() {
        long max = 2L * 1024 * 1024;
        HistoryCache cache = new HistoryCache();
        long one = HistoryCache.estimate(days("T-0"));
        String shared = HistoryService.cacheKey("trade", "T-0", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10), 0, java.util.Set.of(), null, "v1");
        cache.put(shared, days("T-0"), max);
        int puts = 1;
        for (int s = 0; s < 40; s++) {                     // forty role sets, each reading 100 peer-held trades
            String signature = "role-" + s + "|";
            for (int e = 1; e <= 100; e++) {
                String key = HistoryService.signedKey(HistoryService.cacheKey("trade", "T-" + e, LocalDate.of(2026, 10, 1),
                        LocalDate.of(2026, 10, 10), 0, java.util.Set.of(), null, "v1"), signature);
                cache.put(key, days("T-" + e), max);
                puts++;
                if (s == 0) {                              // used all through the first signature's 100 (over the budget's ~66)
                    assertThat(cache.get(shared)).as("an entry in use is kept").isNotNull();
                }
                assertThat(((Number) cache.stats().get("bytes")).longValue()).isLessThanOrEqualTo(max);
                assertThat(cache.get(key)).as("the newest entry is kept").isNotNull();
            }
        }
        Map<String, Object> stats = cache.stats();
        long entries = ((Number) stats.get("entries")).longValue();
        assertThat(entries).as("entries are bounded by the bytes, not by the signatures").isLessThanOrEqualTo(max / one).isLessThan(puts);
        assertThat(((Number) stats.get("bytes")).longValue()).isGreaterThan(max - one).isLessThanOrEqualTo(max);
        assertThat(cache.get(shared)).as("the shared entry was last used in the first round: it went later").isNull();
        String first = HistoryService.signedKey(HistoryService.cacheKey("trade", "T-1", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10), 0,
                java.util.Set.of(), null, "v1"), "role-0|");
        assertThat(cache.get(first)).as("the first signature's entries went first").isNull();
    }

    @Test
    void anAnswerOverAQuarterOfTheBudgetIsNeverKeptAndZeroKeepsNothing() {
        HistoryCache cache = new HistoryCache();
        long one = HistoryCache.estimate(days("T-1"));
        cache.put("k", days("T-1"), one * 4 - 1);
        assertThat(cache.get("k")).isNull();
        cache.put("k", days("T-1"), 0);
        assertThat(cache.stats()).containsEntry("entries", 0).containsEntry("bytes", 0L);
        cache.put("k", days("T-1"), one * 4);
        assertThat(cache.get("k")).isNotNull();
    }
}
