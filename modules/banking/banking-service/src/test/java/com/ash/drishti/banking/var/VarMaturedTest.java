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
package com.ash.drishti.banking.var;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The maturity check of the trades that live supplied positions name: a flag only, and never a zero when it could not be made. */
class VarMaturedTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 9);
    private static final VarMatured NONE = VarMatured.ofIntake(Map.of(), 0);

    private static VarMatured.Feed feed(Map<String, String> maturities) {
        return (wanted, sink) -> {
            int[] found = {0};
            maturities.forEach((t, m) -> {
                if (wanted.contains(t)) {
                    sink.accept(t, m);
                    found[0]++;
                }
            });
            return found[0];
        };
    }

    @Test
    void aTradePastItsMaturityIsFlaggedOnItsPosition() {
        VarMatured m = NONE.checked(Map.of("P1", List.of("T1", "T2"), "P2", List.of("T3"), "P3", List.of("T1")), DAY,
                feed(Map.of("T1", "2026-11-08T00:00:00", "T2", "2026-11-09", "T3", "2030-01-15")));
        assertThat(m.check()).isEqualTo(VarMatured.CHECKED);
        assertThat(m.positions()).isEqualTo(2);
        assertThat(m.trades()).isEqualTo(1);                             // T1 once, though two positions name it
        assertThat(m.listed()).containsOnlyKeys("P1", "P3");
        assertThat(m.listed().get("P1")).containsExactly(Map.entry("T1", "2026-11-08"));
        assertThat(m.unresolved()).isZero();
    }

    @Test
    void theSwitchSaysWhatItAddsAtTheGrainItCameIn() {
        assertThat(VarMaturedSwitch.label(14, 0)).isEqualTo("includes 14 matured positions");
        assertThat(VarMaturedSwitch.label(0, 1)).isEqualTo("includes 1 matured trade");
        assertThat(VarMaturedSwitch.label(1, 3)).isEqualTo("includes 1 matured position and 3 matured trades");
        assertThat(VarMaturedSwitch.label(0, 0)).isEqualTo("includes no matured items");
    }

    @Test
    void maturedItemsKeepTheirVectorAsFloat32AndAreDroppedInWordsBeyondOnePercent() {
        int width = 509;
        double[] v = new double[width];
        for (int i = 0; i < width; i++) {
            v[i] = (i - 254) * 1234.5678;
        }
        VarMaturedItems.Collector c = new VarMaturedItems.Collector(List.of("POSITION_ID", "DESK"));
        assertThat(c.build(1000)).isNull();                                // none matured: nothing kept, nothing to say
        c.add("P1", "POSITION", "2026-01-15", new String[] {"P1", "DESK-FX"}, new double[] {1, 2}, v, width);
        VarMaturedItems kept = c.build(1000);
        assertThat(kept.dropped()).isNull();
        double[] back = new double[width];
        kept.items().get(0).vector(back);
        for (int i = 0; i < width; i++) {
            assertThat(back[i]).isEqualTo((double) (float) v[i]);
        }
        // the memory a kept item costs (VAR_GUIDE.md, "Matured items"): its float32 vector and its texts, about 2.3 KB at 509
        long bytes = kept.bytes();
        System.out.printf("a matured item at %d scenarios keeps about %,d bytes%n", width, bytes);
        assertThat(bytes).isBetween(4L * width, 6L * width);
        VarMaturedItems.Collector many = new VarMaturedItems.Collector(List.of("POSITION_ID"));
        for (int i = 0; i < 200; i++) {
            many.add("P" + i, "TRADE", "2026-01-15", new String[] {"P" + i}, new double[] {0, 0}, v, width);
        }
        assertThat(many.build(50_000).dropped()).isNull();                 // 0.4% of the date's records
        VarMaturedItems over = many.build(10_000);                         // 2%: none kept, said
        assertThat(over.items()).isEmpty();
        assertThat(over.dropped()).contains("200 matured records are more than 1% of the date's records");
    }

    @Test
    void nothingMaturedHasNothingToSay() {
        VarMatured m = NONE.checked(Map.of("P1", List.of("T1")), DAY, feed(Map.of("T1", "2030-01-15")));
        assertThat(m.positions()).isZero();
        assertThat(m.worthSaying()).isFalse();
        assertThat(VarMaturedView.health(m, DAY)).isNull();
        assertThat(NONE.checked(Map.of(), DAY, feed(Map.of())).worthSaying()).isFalse();  // no position-grain records at all
    }

    @Test
    void noTradeIdsOrNoFeedSaysCouldNotBeCheckedNeverZero() {
        VarMatured noIds = VarMatured.ofIntake(Map.of(), 3).checked(Map.of(), DAY, feed(Map.of()));
        assertThat(noIds.check()).isEqualTo(VarMatured.NOT_CHECKED);
        assertThat(noIds.why()).contains("TRADE_IDS");
        VarMatured noFeed = NONE.checked(Map.of("P1", List.of("T1")), DAY, feed(Map.of()));
        assertThat(noFeed.check()).isEqualTo(VarMatured.NOT_CHECKED);
        assertThat(noFeed.worthSaying()).isTrue();
        VarMatured broken = NONE.checked(Map.of("P1", List.of("T1")), DAY, (w, s) -> {
            throw new IllegalStateException("down");
        });
        assertThat(broken.check()).isEqualTo(VarMatured.NOT_CHECKED);
        assertThat(VarMaturedView.health(broken, DAY)).contains("could not be checked");
    }

    @Test
    void aNamedTradeWithoutAMaturityIsCountedAsNotChecked() {
        VarMatured m = NONE.checked(Map.of("P1", List.of("T1", "T2")), DAY, feed(Map.of("T1", "2020-01-01")));
        assertThat(m.positions()).isEqualTo(1);
        assertThat(m.unresolved()).isEqualTo(1);
    }

    @Test
    void theListStopsAtItsCapButTheCountsDoNot() {
        Map<String, List<String>> named = new java.util.HashMap<>();
        Map<String, String> dates = new java.util.HashMap<>();
        for (int i = 0; i < VarMatured.LISTED_TRADES + 5; i++) {
            named.put("P" + i, List.of("T" + i));
            dates.put("T" + i, "2020-01-01");
        }
        VarMatured m = NONE.checked(named, DAY, feed(dates));
        assertThat(m.positions()).isEqualTo(VarMatured.LISTED_TRADES + 5);
        assertThat(m.listed()).hasSize(VarMatured.LISTED_TRADES);
        assertThat(m.capped()).isTrue();
    }

    @Test
    void shardsAddUpAndOneNotCheckedMakesTheWholeNotChecked() {
        VarMatured a = VarMatured.ofIntake(Map.of("ENDUR", Map.of("POSITION", 2)), 0)
                .checked(Map.of("P1", List.of("T1")), DAY, feed(Map.of("T1", "2020-01-01")));
        VarMatured b = VarMatured.ofIntake(Map.of("ENDUR", Map.of("POSITION", 1), "MUREX", Map.of("TRADE", 4)), 0)
                .checked(Map.of("P2", List.of("T2")), DAY, feed(Map.of("T2", "2020-01-01")));
        VarMatured all = VarMatured.merge(List.of(a, b));
        assertThat(all.recordCount()).isEqualTo(7);
        assertThat(all.records().get("ENDUR").get("POSITION")).isEqualTo(3);
        assertThat(all.positions()).isEqualTo(2);
        assertThat(all.listed()).containsOnlyKeys("P1", "P2");
        assertThat(VarMatured.merge(List.of(a, NONE.checked(Map.of("P3", List.of("T3")), DAY, feed(Map.of())))).check())
                .isEqualTo(VarMatured.NOT_CHECKED);
    }
}
