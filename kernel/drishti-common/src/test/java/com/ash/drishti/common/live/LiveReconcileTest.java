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
package com.ash.drishti.common.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.live.LiveMessage;
import com.ash.drishti.api.live.LivePosition;
import com.ash.drishti.common.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Business dates and the next lake load (LIVE_CONNECTORS_DESIGN.md sections 5 and 6): the live date, rows held for the next one and the
 * roll, closed and future dates; reconcile by order, by cut-off and by reset, orphans, deletes carried, the record, the checkpoints that
 * keep a replay from bringing absorbed rows back.
 */
class LiveReconcileTest {

    @TempDir
    Path tmp;

    private static final JsonCodec CODEC = new JsonCodec();
    static final LocalDate D13 = LocalDate.of(2026, 11, 13);
    static final LocalDate D16 = LocalDate.of(2026, 11, 16);

    /** A kind keyed by ID, ordered by V, its business date in BUSINESS_DATE, absorbed by {@code absorb}. */
    static KindContracts contracts(String absorb) {
        Map<String, Object> live = new LinkedHashMap<>();
        live.put("order", List.of("V"));
        live.put("changed-at", "AT");
        live.put("as-of", "BUSINESS_DATE");
        live.put("absorb", absorb);
        Map<String, Object> c = Map.of("kind", "trade", "class", "fundamental", "key", List.of("ID"), "fields",
                List.of(Map.of("name", "ID"), Map.of("name", "V"), Map.of("name", "AT"), Map.of("name", "BUSINESS_DATE")), "live", live);
        LiveRules rules = LiveRules.parse(c, null).rules();
        return new KindContracts() {
            @Override
            public LiveRules rules(String kind) {
                return rules;
            }

            @Override
            public Map<String, Object> contract(String kind) {
                return Map.of();
            }
        };
    }

    private final AtomicReference<LocalDate> today = new AtomicReference<>(D13);
    private final LiveDates dates = LiveDates.of(today::get, d -> d.getDayOfWeek() == java.time.DayOfWeek.FRIDAY ? d.plusDays(3) : d.plusDays(1));
    private long offset;

    private LiveFeed feed(String absorb, LiveCheckpoints cp) {
        return new LiveFeed("trade-live", Set.of("trade"), Map.of("kind", "trade"), contracts(absorb), null, b -> CODEC.read(new String(b,
                StandardCharsets.UTF_8)), new LiveState(1 << 20, null), null, dates, cp, tmp.resolve("reconcile"));
    }

    private LiveMessage msg(String id, int v, String at, LocalDate date) {
        String d = date == null ? "" : ",\"BUSINESS_DATE\":\"" + date + "\"";
        return new LiveMessage(id, "{\"ID\":\"" + id + "\",\"V\":" + v + ",\"AT\":\"" + at + "\"" + d + "}", new LivePosition("t", 0, offset++),
                Instant.parse(at));
    }

    private static boolean held(LiveFeed f, String id) {
        return f.fetch(EntityRef.of("trade", id)).isPresent();
    }

    @Test
    void theLiveDateIsAppliedTheNextIsHeldForTheRollOthersAreRejected() {
        LiveFeed f = feed("by-order", null);
        f.accept(msg("A", 1, "2026-11-13T10:00:00Z", null));                 // no date: the live date
        f.accept(msg("B", 1, "2026-11-13T10:00:00Z", D13));
        f.accept(msg("C", 1, "2026-11-13T22:00:00Z", D16));                   // Monday, the next business date after Friday 13th
        f.accept(msg("D", 1, "2026-11-13T10:00:00Z", D13.minusDays(1)));
        f.accept(msg("E", 1, "2026-11-13T10:00:00Z", D16.plusDays(1)));
        assertThat(held(f, "A")).isTrue();
        assertThat(held(f, "B")).isTrue();
        assertThat(held(f, "C")).isFalse();
        assertThat(f.held()).isEqualTo(1);
        assertThat(f.counted("rejected.closed-date")).isEqualTo(1);
        assertThat(f.counted("rejected.future-date")).isEqualTo(1);
        assertThat(f.state().entry("trade", "A").orElseThrow().asOf()).isEqualTo("2026-11-13");
        today.set(D16);                                                      // the roll
        f.expire(Instant.now());
        assertThat(held(f, "C")).isTrue();
        assertThat(held(f, "A")).as("carried from the 13th: it stays until a load absorbs it").isTrue();
        assertThat(f.liveDate()).isEqualTo(D16);
    }

    @Test
    void byOrderTheLakesRowAsNewOrNewerAbsorbsAndARowNotInTheLoadBeforeTheCutoffIsAnOrphan() throws Exception {
        LiveFeed f = feed("by-order", null);
        f.accept(msg("A", 2, "2026-11-13T10:00:00Z", null));                 // the load has version 2: absorbed
        f.accept(msg("B", 3, "2026-11-13T10:00:00Z", null));                 // the load has version 2: carried
        f.accept(msg("C", 1, "2026-11-13T10:00:00Z", null));                 // not in the load, before the cut-off: an orphan
        f.accept(msg("D", 1, "2026-11-13T19:00:00Z", null));                 // not in the load, after the cut-off: carried
        f.accept(msg("E", 5, "2026-11-13T10:00:00Z", null));
        f.accept(new LiveMessage("E", null, new LivePosition("t", 0, offset++), Instant.parse("2026-11-13T11:00:00Z")));   // deleted after v5
        Map<String, Optional<List<Object>>> lake = new HashMap<>();
        lake.put("A", Optional.of(List.of(2)));
        lake.put("B", Optional.of(List.of(2)));
        lake.put("E", Optional.of(List.of(4)));                              // the load holds E at 4: the delete is newer, still hidden
        LiveState.Reconcile r = f.reconcile("trade", D13, Instant.parse("2026-11-13T18:00:00Z"), k -> lake.getOrDefault(k, Optional.empty()));
        assertThat(r.absorbed()).isEqualTo(1);
        assertThat(r.carried()).isEqualTo(2);
        assertThat(r.orphans()).isEqualTo(1);
        assertThat(r.deletesCarried()).isEqualTo(1);
        assertThat(r.orphanRows()).extracting(LiveState.Entry::key).containsExactly("C");
        assertThat(held(f, "A")).isFalse();
        assertThat(held(f, "B")).isTrue();
        assertThat(held(f, "C")).isFalse();
        assertThat(held(f, "D")).isTrue();
        assertThat(f.deleted(EntityRef.of("trade", "E"))).isTrue();
        List<String> record = Files.readAllLines(tmp.resolve("reconcile").resolve("trade").resolve("2026-11-13.jsonl"));
        assertThat(record.get(0)).contains("\"absorbed\":1").contains("\"orphans\":1").contains("\"rule\":\"by-order\"");
        assertThat(record.get(1)).contains("\"orphan\":\"C\"");
    }

    @Test
    void rowsOfALaterDateAreNotTheLoads() {
        LiveFeed f = feed("reset", null);
        f.accept(msg("A", 1, "2026-11-13T10:00:00Z", null));
        today.set(D16);
        f.accept(msg("B", 1, "2026-11-16T10:00:00Z", null));
        LiveState.Reconcile r = f.reconcile("trade", D13, Instant.parse("2026-11-16T12:00:00Z"), k -> Optional.of(List.of(1)));
        assertThat(r.absorbed()).isEqualTo(1);
        assertThat(held(f, "A")).isFalse();
        assertThat(held(f, "B")).as("the 16th's row: a load of the 13th does not absorb it").isTrue();
    }

    @Test
    void aByCutoffCheckpointKeepsAReplayFromBringingAbsorbedRowsBack() {
        Path file = tmp.resolve("checkpoints").resolve("trade-live.json");
        LiveFeed f = feed("by-cutoff", new LiveCheckpoints(file));
        LiveMessage early = msg("A", 1, "2026-11-13T10:00:00Z", null);
        LiveMessage late = msg("B", 1, "2026-11-13T19:00:00Z", null);
        f.accept(early);
        f.accept(late);
        LiveState.Reconcile r = f.reconcile("trade", D13, Instant.parse("2026-11-13T18:00:00Z"), k -> Optional.of(List.of(1)));
        assertThat(r.absorbed()).isEqualTo(1);
        assertThat(r.carried()).isEqualTo(1);
        LiveFeed restarted = feed("by-cutoff", new LiveCheckpoints(file));   // a restart: the topic replays from the beginning
        restarted.accept(early);
        restarted.accept(late);
        assertThat(held(restarted, "A")).isFalse();
        assertThat(held(restarted, "B")).isTrue();
        assertThat(restarted.counted("absorbed")).isEqualTo(1);
    }

    @Test
    void aResetCheckpointDropsWhatWasAppliedBeforeIt() {
        Path file = tmp.resolve("checkpoints").resolve("reset.json");
        LiveFeed f = feed("reset", new LiveCheckpoints(file));
        LiveMessage a = msg("A", 1, "2026-11-13T10:00:00Z", null);
        f.accept(a);
        f.reconcile("trade", D13, Instant.parse("2026-11-13T18:00:00Z"), k -> Optional.of(List.of(1)));
        assertThat(f.state().size()).isZero();
        LiveFeed restarted = feed("reset", new LiveCheckpoints(file));
        restarted.accept(a);
        LiveMessage after = msg("A", 2, "2026-11-13T20:00:00Z", null);
        restarted.accept(after);
        assertThat(restarted.fetch(EntityRef.of("trade", "A")).map(d -> d.data().get("V").asDouble())).contains(2.0);
        assertThat(restarted.counted("absorbed")).isEqualTo(1);
    }
}
