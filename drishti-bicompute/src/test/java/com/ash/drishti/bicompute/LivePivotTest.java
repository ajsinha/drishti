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
package com.ash.drishti.bicompute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.core.ScaleOptions;
import com.ash.drishti.bicompute.live.AnswerPatch;
import com.ash.drishti.bicompute.live.LivePivots;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.bicompute.sql.DuckDbExecutor;
import com.ash.drishti.bicompute.sql.TabularProvider;
import com.ash.drishti.bicompute.sql.TabularSource;
import com.ash.drishti.common.DrishtiException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Live pivots (ARCHITECTURE.md section 23.10, item 5): after the data of the view's date moves, the patch applied to the last answer equals a
 * fresh query of the view, cell by cell; a new member is a reshape with the whole answer; a signal of another date or source computes
 * nothing; a signal with no change reads nothing again (every set from the partial-state cache) and says {@code status}; the limits are
 * refused in words (DRS-5721); a live recompute is exact unless ≈ was asked; a signal reaches a stream through the scheduler.
 */
class LivePivotTest {

    static final String D1 = "2026-10-29";
    static final String D2 = "2026-10-30";

    /** A small table per business date, restaged by the test; each restage a new data version of that date. */
    static final class Days implements TabularSource.Resolver, AutoCloseable {
        final DuckDbExecutor exec = new DuckDbExecutor(DuckDbExecutor.Settings.small());
        final Map<String, Integer> versions = new ConcurrentHashMap<>();
        final AtomicInteger reads = new AtomicInteger();
        static final List<DuckDbExecutor.Column> COLS = List.of(new DuckDbExecutor.Column("REGION", "text"), new DuckDbExecutor.Column("CCY", "text"),
                new DuckDbExecutor.Column("NOTIONAL", "number"));

        void put(String date, List<Object[]> rows) {
            exec.stage("d_" + date.replace("-", ""), COLS, sink -> {
                for (Object[] r : rows) {
                    sink.row(r);
                }
                return rows.size();
            });
            versions.merge(date, 1, Integer::sum);
        }

        @Override
        public Map<String, String> sources(Viewer v) {
            return Map.of("book", "Book");
        }

        @Override
        public boolean serves(String source) {
            return "book".equals(source);
        }

        @Override
        public TabularSource open(Viewer v, String source, String date) {
            String day = date == null || date.isBlank() ? D2 : date;
            return new TabularSource() {
                @Override
                public String id() {
                    return "book";
                }

                @Override
                public String title() {
                    return "Book";
                }

                @Override
                public List<PivotProvider.Field> fields() {
                    return List.of(new PivotProvider.Field("REGION", "string", "region", ""), new PivotProvider.Field("CCY", "string", "currency", ""),
                            new PivotProvider.Field("NOTIONAL", "number", "notional", ""));
                }

                @Override
                public int masked() {
                    return 0;
                }

                @Override
                public String date() {
                    return day;
                }

                @Override
                public List<String> dates() {
                    return List.of(D1, D2);
                }

                @Override
                public String version() {
                    return day + "@" + versions.getOrDefault(day, 0);
                }

                @Override
                public long rows() {
                    return 100;
                }

                @Override
                public long bytes() {
                    return 6_400;
                }

                @Override
                public Map<String, Object> notes() {
                    return new LinkedHashMap<>();
                }

                @Override
                public Relation relation(DuckDbExecutor e) {
                    reads.incrementAndGet();
                    return new Relation("\"d_" + day.replace("-", "") + "\"", List.of(), null, List.of());
                }
            };
        }

        @Override
        public void close() {
            exec.close();
        }
    }

    final Days days = new Days();
    final PivotCore core = new PivotCore(List.of(new TabularProvider(days.exec, days)), StateCache.lru(8L << 20), PivotCore.Settings.defaults());
    final LivePivots live = new LivePivots(core, LivePivots.Settings.defaults(), Clock.systemUTC());
    final Viewer rita = Viewer.of("rita");

    {
        days.put(D1, rows(1));
        days.put(D2, rows(2));
    }

    @AfterEach
    void close() {
        live.close();
        days.close();
    }

    static List<Object[]> rows(int seed) {
        List<Object[]> out = new ArrayList<>();
        String[] regions = {"AMERICAS", "APAC", "EMEA"};
        String[] ccys = {"USD", "EUR", "GBP"};
        for (int i = 0; i < 90; i++) {
            out.add(new Object[] {regions[i % 3], ccys[(i / 3) % 3], 1000.0 * seed + i});
        }
        return out;
    }

    static PivotView view(String date) {
        Map<String, Object> m = new LinkedHashMap<>(Map.of("source", "book", "rows", List.of("REGION"), "columns", List.of("CCY"), "values",
                List.of(Map.of("measure", "sum(NOTIONAL)", "as", "N"), Map.of("measure", "count()", "as", "C"))));
        if (date != null) {
            m.put("date", date);
        }
        return PivotView.parse(m);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> answer(Map<String, Object> subscribed) {
        return (Map<String, Object>) subscribed.get("answer");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPatchAppliedToTheLastAnswerEqualsAFreshQueryAndANewMemberIsAReshape() {
        Map<String, Object> s = live.subscribe(rita, view(D1), ScaleOptions.AUTO);
        String id = String.valueOf(s.get("id"));
        Map<String, Object> shown = answer(s);
        assertThat(PivotCoreTest.cells(shown)).isEqualTo(PivotCoreTest.cells(core.query(rita, view(D1))));
        List<LivePivots.Event> events = new CopyOnWriteArrayList<>();
        live.attach("rita", id, events::add);
        assertThat(events).first().satisfies(e -> assertThat(e.name()).isEqualTo("answer"));
        // figures move, members stay: a patch of just the moved cells
        List<Object[]> moved = rows(1);
        moved.set(0, new Object[] {"AMERICAS", "USD", 5_000_000.0});
        days.put(D1, moved);
        assertThat(live.signal(LivePivots.Signal.load("book", D1))).isEqualTo(1);
        LivePivots.Event p = live.recomputeNow("rita", id);
        assertThat(p.name()).isEqualTo("patch");
        Map<String, Object> cells = (Map<String, Object>) p.data().get("cells");
        assertThat(cells.keySet()).as("only AMERICAS×USD, its row and column totals and the grand total moved")
                .containsExactlyInAnyOrder("REGION=AMERICAS|CCY=USD", "REGION=AMERICAS|*", "*|CCY=USD", "*|*");
        shown = AnswerPatch.apply(shown, p.data());
        Map<String, Object> fresh = core.query(rita, view(D1));
        assertThat(PivotCoreTest.cells(shown)).isEqualTo(PivotCoreTest.cells(fresh));
        // a new currency: the columns change, the whole answer is sent
        List<Object[]> grown = new ArrayList<>(moved);
        grown.add(new Object[] {"APAC", "JPY", 7.0});
        days.put(D1, grown);
        LivePivots.Event r = live.recomputeNow("rita", id);
        assertThat(r.name()).isEqualTo("reshape");
        shown = AnswerPatch.apply(shown, r.data());
        assertThat(PivotCoreTest.cells(shown)).isEqualTo(PivotCoreTest.cells(core.query(rita, view(D1))));
        assertThat((long) r.data().get("seq")).isEqualTo(2L);
    }

    @Test
    void aSignalOfAnotherDateOrSourceComputesNothingAndNoChangeReadsNothingAgain() {
        Map<String, Object> s = live.subscribe(rita, view(D1), ScaleOptions.AUTO);
        String id = String.valueOf(s.get("id"));
        assertThat(live.signal(LivePivots.Signal.load("book", D2))).as("another date").isZero();
        assertThat(live.signal(LivePivots.Signal.load("trades", D1))).as("another source").isZero();
        assertThat(live.signal(LivePivots.Signal.load("bookings", D1))).as("a source whose name only starts the same").isZero();
        int before = days.reads.get();
        LivePivots.Event e = live.recomputeNow("rita", id);
        assertThat(e.name()).as("nothing moved").isEqualTo("status");
        assertThat(days.reads.get()).as("every set from the partial-state cache: the table is not read").isEqualTo(before);
        // a view that follows the current date is touched by a load of any date of its source
        live.subscribe(rita, view(null), ScaleOptions.AUTO);
        assertThat(live.signal(LivePivots.Signal.load("book", D1))).isEqualTo(2);
        assertThat(live.signal(LivePivots.Signal.live("book"))).as("a live update touches every date").isEqualTo(2);
    }

    @Test
    void theLimitsAreRefusedInWordsAndALiveRecomputeIsExactUnlessApproximateWasAsked() {
        LivePivots tight = new LivePivots(core, new LivePivots.Settings(true, 1, 10, 0, 12, 0, 60), Clock.systemUTC());
        try {
            Map<String, Object> s = tight.subscribe(rita, PivotView.parse(Map.of("source", "book", "rows", List.of("REGION"), "values",
                    List.of(Map.of("measure", "count()")))), ScaleOptions.AUTO);
            assertThat(s.get("options")).isEqualTo(Map.of("exact", true, "progressive", false, "rollups", true));
            assertThatThrownBy(() -> tight.subscribe(rita, view(D2), ScaleOptions.AUTO)).isInstanceOf(DrishtiException.class)
                    .hasMessageContaining("DRS-5721").hasMessageContaining("max-per-person");
            assertThatThrownBy(() -> tight.subscribe(Viewer.of("tom"), PivotView.parse(Map.of("source", "book", "rows", List.of("REGION", "CCY"),
                    "values", List.of(Map.of("measure", "count()")), "expandTo", Map.of("rows", 2))), ScaleOptions.AUTO))
                    .hasMessageContaining("at most 12 cells").hasMessageContaining("has 13");
            Map<String, Object> pinned = new LinkedHashMap<>(Map.of("source", "book", "rows", List.of("REGION"), "values",
                    List.of(Map.of("measure", "count()")), "asOf", "v1"));
            assertThatThrownBy(() -> tight.subscribe(Viewer.of("ann"), PivotView.parse(pinned), ScaleOptions.AUTO)).hasMessageContaining("never changes");
            Map<String, Object> asked = tight.subscribe(Viewer.of("bob"), PivotView.parse(Map.of("source", "book", "rows", List.of("CCY"),
                    "values", List.of(Map.of("measure", "count()")))), ScaleOptions.parse(Map.of("exact", false)));
            assertThat(asked.get("options")).isEqualTo(Map.of("exact", false, "progressive", false, "rollups", true));
            assertThat(tight.list("rita")).singleElement().satisfies(m -> assertThat(m.get("source")).isEqualTo("book"));
            assertThat(tight.end("bob", String.valueOf(asked.get("id")))).isTrue();
            assertThat(tight.end("rita", String.valueOf(asked.get("id")))).as("only its own person ends it").isFalse();
        } finally {
            tight.close();
        }
        LivePivots off = new LivePivots(core, new LivePivots.Settings(false, 5, 10, 0, 100, 0, 60), Clock.systemUTC());
        assertThatThrownBy(() -> off.subscribe(rita, view(D1), ScaleOptions.AUTO)).hasMessageContaining("switched off");
        off.close();
    }

    @Test
    void aSignalReachesTheStreamThroughTheSchedulerAndTheRollupHookRunsFirst() throws Exception {
        List<String> before = new CopyOnWriteArrayList<>();
        live.before(s -> before.add(s.detail()));
        Map<String, Object> s = live.subscribe(rita, view(D2), ScaleOptions.AUTO);
        String id = String.valueOf(s.get("id"));
        CountDownLatch got = new CountDownLatch(1);
        List<LivePivots.Event> events = new CopyOnWriteArrayList<>();
        live.attach("rita", id, e -> {
            events.add(e);
            if (!"answer".equals(e.name())) {
                got.countDown();
            }
        });
        List<Object[]> moved = rows(2);
        moved.set(4, new Object[] {"APAC", "EUR", -1.0});
        days.put(D2, moved);
        live.signal(LivePivots.Signal.load("book", D2));
        assertThat(got.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(events.get(1).name()).isEqualTo("patch");
        assertThat(before).containsExactly("a load of book for 2026-10-30");
        assertThat(String.valueOf(events.get(1).data().get("causes"))).contains("a load of book for 2026-10-30");
    }
}
