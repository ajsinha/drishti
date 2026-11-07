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

import com.ash.drishti.server.caches.OpenDates;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The product owner's rule of which dates stay in memory (VAR_GUIDE.md section 26.8), as the VaR and exposure engines apply it through
 * {@link OpenDates}: least recently used out first; the date just used and the newest business date always stay; a pinned date stays,
 * counted; over the byte safety net a currency book of the least recently used date leaves before any date. The retirements' order is
 * asserted.
 */
class VarEvictionLruTest {

    static final LocalDate D1 = LocalDate.of(2026, 9, 28);
    static final LocalDate D2 = LocalDate.of(2026, 9, 29);
    static final LocalDate D3 = LocalDate.of(2026, 9, 30);              // the newest business date

    /** An engine's open dates as the services keep them: a date's base book, its currency books, its stress book (VaR only). */
    static final class Engine implements OpenDates.Books {
        long now = 0;                                                    // the test's clock (milliseconds)
        final OpenDates policy;
        final Map<LocalDate, Long> base = new LinkedHashMap<>();
        final Map<LocalDate, Map<String, Long>> extras = new LinkedHashMap<>();
        final List<String> retired = new ArrayList<>();
        final int hotDates;
        final long net;
        final Set<LocalDate> pinned = new HashSet<>();
        final boolean withExtras;

        Engine(int hotDates, long net, boolean withExtras) {
            this(hotDates, net, withExtras, 0);
        }

        Engine(int hotDates, long net, boolean withExtras, long graceMillis) {
            this.policy = new OpenDates(() -> now, graceMillis);
            this.hotDates = hotDates;
            this.net = net;
            this.withExtras = withExtras;
        }

        /** A request of {@code d}: stamped, opened when not open, then the rule applied (as VarService.load and evictOld do). */
        void ask(LocalDate d) {
            policy.touch(d);
            if (!base.containsKey(d)) {
                base.put(d, 100L);
                if (withExtras) {
                    Map<String, Long> x = new LinkedHashMap<>();
                    x.put("currency EUR", 100L);
                    x.put("stress", 100L);
                    extras.put(d, x);
                }
            }
            Set<LocalDate> protect = new HashSet<>(pinned);
            protect.add(d);
            protect.add(D3);
            for (LocalDate x : policy.overCount(base.keySet(), hotDates, protect)) {
                retire(x);
            }
            policy.underNet(base.keySet(), net, d, protect, this);
        }

        public long bytes(LocalDate d) {
            return base.getOrDefault(d, 0L) + extras.getOrDefault(d, Map.of()).values().stream().mapToLong(Long::longValue).sum();
        }

        public List<OpenDates.Extra> extras(LocalDate d) {
            List<OpenDates.Extra> out = new ArrayList<>();
            for (Map.Entry<String, Long> e : new ArrayList<>(extras.getOrDefault(d, Map.of()).entrySet())) {
                out.add(new OpenDates.Extra(e.getKey(), e.getValue(), () -> extras.get(d).remove(e.getKey())));
            }
            return out;
        }

        public void retire(LocalDate d) {
            base.remove(d);
            extras.remove(d);
            policy.forget(d);
            retired.add(d.toString());
        }
    }

    @Test
    void theLeastRecentlyUsedLeavesFirstTheJustUsedAndTheNewestStay() {
        for (boolean var : new boolean[] {true, false}) {               // VaR (with currency and stress books) and exposure (dates only)
            Engine e = new Engine(2, 0, var);
            e.ask(D1);
            e.ask(D2);
            e.ask(D3);                                                   // three open: D1, used longest ago, leaves
            assertThat(e.base.keySet()).containsExactlyInAnyOrder(D2, D3);
            e.ask(D1);                                                   // D1 again: D2 is now the least recently used (D3 is the newest)
            assertThat(e.base.keySet()).containsExactlyInAnyOrder(D3, D1);
            e.ask(D2);                                                   // D2 again: D1 leaves; D3 (newest) and D2 (just used) stay
            assertThat(e.base.keySet()).containsExactlyInAnyOrder(D3, D2);
            assertThat(e.retired).containsExactly("2026-09-28", "2026-09-29", "2026-09-28");
        }
    }

    @Test
    void aPinnedDateStaysAndIsCounted() {
        Engine e = new Engine(2, 0, true);
        e.pinned.add(D1);
        e.ask(D1);
        e.ask(D2);
        e.ask(D3);                                                       // D1 pinned, D3 newest and just used: D2 is the only one that may go
        assertThat(e.base.keySet()).containsExactlyInAnyOrder(D1, D3);
        e.ask(D2);                                                       // D2 just used, D1 pinned, D3 newest: all protected, the count is exceeded
        assertThat(e.base.keySet()).containsExactlyInAnyOrder(D1, D2, D3);
        assertThat(e.retired).containsExactly("2026-09-29");
    }

    @Test
    void overTheByteNetTheLeastRecentlyUsedDatesCurrencyBookLeavesBeforeAnyDate() {
        // each date: 100 base + 100 EUR + 100 stress = 300; a net of 450 fits a date and a half
        Engine e = new Engine(10, 450, true);
        e.ask(D1);
        e.ask(D2);                                                       // 600 > 450: D1's EUR book, then D1's stress book (D2 is just used)
        assertThat(e.base.keySet()).containsExactlyInAnyOrder(D1, D2);
        assertThat(e.extras.get(D1)).isEmpty();
        assertThat(e.extras.get(D2)).containsOnlyKeys("currency EUR", "stress");
        e.ask(D3);                                                       // 100 + 300 + 300 = 700: D2's extras, then D1 whole
        assertThat(e.base.keySet()).containsExactlyInAnyOrder(D2, D3);
        assertThat(e.extras.get(D2)).isEmpty();
        assertThat(e.retired).containsExactly("2026-09-28");
    }

    @Test
    void theNetNeverClosesAProtectedDate() {
        Engine e = new Engine(10, 50, false);                            // a net smaller than one date
        e.ask(D3);
        assertThat(e.base.keySet()).containsExactly(D3);
        e.ask(D1);                                                       // D1 just used, D3 newest: over the net, nothing may go
        assertThat(e.base.keySet()).containsExactlyInAnyOrder(D1, D3);
        e.ask(D2);                                                       // D1 is neither: it goes
        assertThat(e.base.keySet()).containsExactlyInAnyOrder(D2, D3);
        assertThat(e.retired).containsExactly("2026-09-28");
    }

    @Test
    void aDateInUseStaysOverTheNetAndLeavesOnceIdle() {
        // two dates each over the net alone, asked in turn under load: neither may close the other (a reload storm), until one is idle
        Engine e = new Engine(10, 50, true, 120_000);
        e.ask(D3);
        e.now = 1_000;
        e.ask(D2);                                                       // over the net, but D3 is newest and D2 just used
        e.now = 2_000;
        e.ask(D3);                                                       // D2 read a second ago: in use, it stays with its books
        assertThat(e.base.keySet()).containsExactlyInAnyOrder(D2, D3);
        assertThat(e.extras.get(D2)).containsOnlyKeys("currency EUR", "stress");
        assertThat(e.retired).isEmpty();
        e.now = 200_000;
        e.ask(D3);                                                       // D2 idle past the grace: its books, then the date, leave
        assertThat(e.base.keySet()).containsExactly(D3);
        assertThat(e.retired).containsExactly("2026-09-29");
    }

    @Test
    void theAutomaticNetLeavesRoomForTheRest() {
        long gb = 1L << 30;
        assertThat(OpenDates.automaticNet(16 * gb, 2, 300L << 20)).isEqualTo((long) (0.4 * 16 * gb) - (512L << 20) - 2 * (300L << 20));
        assertThat(OpenDates.automaticNet(2 * gb, 2, 1600L << 20)).as("at least a tenth of the heap").isEqualTo(2 * gb / 10);
    }

    @Test
    void theSettingsDefaultToTenDatesAndFiveMinutes() {
        VarProperties p = new VarProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null);
        assertThat(p.hotDates()).isEqualTo(10);
        assertThat(p.refusalTtlSeconds()).isEqualTo(300);
        assertThat(p.openHeapMb()).isZero();
        assertThat(p.openHeapBytes()).isPositive();
        assertThat(p.pinned().off()).isTrue();
    }
}
