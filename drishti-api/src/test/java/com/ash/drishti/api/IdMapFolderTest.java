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
package com.ash.drishti.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The id-map folder's safeguards (2.9.7, RESOURCES_AND_SCALE.md section 14.25): the cap never evicts a map in use, warns once about a
 * map alone over it and trims the others; the TTL counts from the last use, never drops a mapped map nobody can vouch for, an open date,
 * the latest dates or today, is jittered within 10%, is at least 24 hours, and a delete that fails is tried again.
 */
class IdMapFolderTest {

    static final LocalDate D1 = LocalDate.of(2026, 9, 1);

    @TempDir
    Path tmp;

    private final List<IdMapFolder> made = new java.util.ArrayList<>();
    private Runnable engine;

    @AfterEach
    void close() {
        made.forEach(IdMapFolder::close);
        if (engine != null) {
            engine.run();
        }
    }

    /** A clock the test moves. */
    static final class FakeClock extends Clock {
        volatile Instant now = Instant.now();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private IdMapFolder folder(long maxBytes, Duration ttl, Clock clock) {
        IdMapFolder f = new IdMapFolder("lake", tmp.resolve("idmaps"), maxBytes, ttl, clock);
        made.add(f);
        return f;
    }

    private Path map(String kind, String date, int n, int pad) throws Exception {
        Path f = tmp.resolve("idmaps").resolve(kind).resolve("7@1").resolve(date + ".idmap");
        String p = "x".repeat(pad);
        MappedIdFile.write(n, i -> "T-" + i + p, id -> 0, f);
        return f;
    }

    /** Written, then not mapped and last used {@code ago} before the real now. */
    private Path idle(String date, Duration ago) throws Exception {
        Path f = map("trade", date, 200, 0);
        MappedIdFile.release(f, false);
        Files.setLastModifiedTime(f, FileTime.from(Instant.now().minus(ago)));
        return f;
    }

    // ------------------------------------------------------------------------------------------------------------------ the cap

    @Test
    void aTinyCapKeepsALargeMapInUseWarnsOnceAndTrimsTheSmallOnes() throws Exception {
        IdMapFolder f = folder(1L << 20, null, Clock.systemUTC());
        Path large = map("trade", "2026-09-30", 50_000, 30);                 // about 2 MB, mapped: in use
        assertThat(Files.size(large)).isGreaterThan(1L << 20);
        List<Path> small = List.of(idle("2026-09-01", Duration.ofHours(3)), idle("2026-09-02", Duration.ofHours(2)),
                idle("2026-09-03", Duration.ofHours(1)));
        int rebuilt = 0;
        for (int round = 0; round < 5; round++) {                             // a connector reading the large date again and again
            MappedIdFile m = MappedIdFile.open(large);
            if (m == null) {
                rebuilt++;
                map("trade", "2026-09-30", 50_000, 30);
            }
            f.trim(null);
        }
        assertThat(rebuilt).as("no rebuild loop").isZero();
        assertThat(large).exists();
        assertThat(MappedIdFile.isMapped(large)).isTrue();
        small.forEach(p -> assertThat(p).doesNotExist());
        assertThat(f.warnedCount()).as("warned once per date and version").isEqualTo(1);
        assertThat(f.warnings()).hasSize(1);
        assertThat(f.warnings().values().iterator().next()).contains("trade 2026-09-30").contains("id-map-disk.max-gb").contains("lake");
        Map<String, Object> row = f.dates().stream().filter(r -> "2026-09-30".equals(r.get("date"))).findFirst().orElseThrow();
        assertThat(row).containsEntry("warning", true).containsEntry("state", "mapped");
        assertThat(f.dates()).filteredOn(r -> "evicted (max-gb)".equals(r.get("reason"))).hasSize(3);
        assertThat(f.stats()).containsEntry("idMapsEvicted", 3L).containsEntry("idMapWarnings", 1).containsEntry("idMapsInUseOverCap", 1);
        MappedIdFile.release(large, true);
    }

    @Test
    void theCapLeavesMapsWrittenOrMappedInTheLastMinutesAndOpenDates() throws Exception {
        IdMapFolder f = folder(1, null, Clock.systemUTC());
        Path fresh = map("trade", "2026-09-05", 100, 0);
        MappedIdFile.release(fresh, false);                                 // not mapped, but written just now
        Path open = idle("2026-09-06", Duration.ofHours(5));
        Path old = idle("2026-09-07", Duration.ofHours(5));
        engine = DatesInUse.register(this, t -> Set.of(LocalDate.parse("2026-09-06")));
        f.trim(null);
        assertThat(fresh).exists();
        assertThat(open).exists();
        assertThat(old).doesNotExist();
    }

    // ------------------------------------------------------------------------------------------------------------------ the TTL

    @Test
    void theTtlCountsFromTheLastUse() throws Exception {
        FakeClock clock = new FakeClock();
        IdMapFolder f = folder(0, Duration.ofHours(24), clock);
        Path used = idle("2026-09-01", Duration.ofDays(10));                 // written long ago...
        MappedIdFile.open(used).file("T-1");                                  // ...used now (mapped and read)
        MappedIdFile.release(used, false);
        Path unused = idle("2026-09-02", Duration.ZERO);
        clock.now = Instant.now().plus(Duration.ofHours(20));
        assertThat(f.expire()).isZero();                                     // under 24h - 10% for both
        clock.now = Instant.now().plus(Duration.ofHours(27));
        assertThat(f.expire()).isEqualTo(2);                                 // over 24h + 10%
        assertThat(used).doesNotExist();
        assertThat(unused).doesNotExist();
        assertThat(f.dates()).filteredOn(r -> "expired (ttl)".equals(r.get("reason"))).hasSize(2);
        assertThat(f.stats()).containsEntry("idMapsExpired", 2L).containsEntry("idMapTtl", "PT24H");
    }

    @Test
    void neverExpiredWhileMappedUnlessAnEngineVouchesAndNeverAnOpenDate() throws Exception {
        FakeClock clock = new FakeClock();
        IdMapFolder f = folder(0, Duration.ofHours(24), clock);
        Path mapped = map("trade", "2026-09-01", 100, 0);
        Path openDate = map("trade", "2026-09-02", 100, 0);
        clock.now = Instant.now().plus(Duration.ofDays(30));
        assertThat(f.expire()).as("no engine says which dates are open: a mapped map stays").isZero();
        engine = DatesInUse.register(this, t -> Set.of(LocalDate.parse("2026-09-02")));
        assertThat(f.expire()).as("an engine says: the idle mapped map of a closed date goes").isEqualTo(1);
        assertThat(mapped).doesNotExist();
        assertThat(MappedIdFile.isMapped(mapped)).isFalse();                 // unmapped, then deleted
        assertThat(openDate).as("open in an engine").exists();
        MappedIdFile.release(openDate, true);
    }

    @Test
    void neverTodayNorTheConnectorsLatestAndPreviousDates() throws Exception {
        FakeClock clock = new FakeClock();
        clock.now = Instant.now().plus(Duration.ofDays(30));
        IdMapFolder f = folder(0, Duration.ofHours(24), clock);
        Path today = idle(LocalDate.now(clock).toString(), Duration.ZERO);
        Path latest = idle("2026-09-10", Duration.ZERO);
        Path previous = idle("2026-09-09", Duration.ZERO);
        Path older = idle("2026-09-08", Duration.ZERO);
        f.latestDates(() -> List.of(LocalDate.parse("2026-09-10"), LocalDate.parse("2026-09-09")));
        assertThat(f.expire()).isEqualTo(1);
        assertThat(today).exists();
        assertThat(latest).exists();
        assertThat(previous).exists();
        assertThat(older).doesNotExist();
        f.latestDates(() -> {
            throw new IllegalStateException("the lake is not reachable");
        });
        assertThat(f.expire()).as("unsure of its latest dates: nothing goes").isZero();
    }

    @Test
    void jitterStaysWithinTenPercentAndSpreadsTheMaps() {
        Duration ttl = Duration.ofHours(48);
        Set<Long> seen = new java.util.HashSet<>();
        for (int d = 0; d < 1000; d++) {
            long j = IdMapFolder.jittered(Path.of(D1.plusDays(d) + ".idmap"), ttl);
            assertThat(j).isBetween((long) (ttl.toMillis() * 0.9), (long) (ttl.toMillis() * 1.1));
            seen.add(j / 1000);
        }
        assertThat(seen.size()).as("not all together").isGreaterThan(900);
        assertThat(IdMapFolder.jittered(Path.of("a.idmap"), ttl)).as("the same each sweep").isEqualTo(IdMapFolder.jittered(Path.of("a.idmap"), ttl));
    }

    @Test
    void theTtlIsOffByDefaultAndAtLeastADay() {
        assertThat(IdMapFolder.ttl("lake", "")).isNull();
        assertThat(IdMapFolder.ttl("lake", "0")).isNull();
        assertThat(IdMapFolder.ttl("lake", null)).isNull();
        assertThat(IdMapFolder.ttl("lake", "36h")).hasHours(36);
        assertThat(IdMapFolder.ttl("lake", "7d")).hasDays(7);
        assertThat(IdMapFolder.ttl("lake", "P2D")).hasDays(2);
        assertThatThrownBy(() -> IdMapFolder.ttl("lake", "2h")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lake").hasMessageContaining("id-map-disk.ttl").hasMessageContaining("minimum is 24h");
        assertThatThrownBy(() -> IdMapFolder.ttl("lake", "soon")).hasMessageContaining("id-map-disk.ttl");
    }

    @Test
    void aDeleteThatFailsIsTriedAgainNeverAnError() throws Exception {
        FakeClock clock = new FakeClock();
        IdMapFolder f = folder(0, Duration.ofHours(24), clock);
        Path held = idle("2026-09-01", Duration.ZERO);
        AtomicBoolean windows = new AtomicBoolean(true);
        f.deleter(p -> {
            if (windows.get()) {
                throw new AccessDeniedException(p.toString(), null, "being used by another process");
            }
            Files.deleteIfExists(p);
        });
        clock.now = Instant.now().plus(Duration.ofDays(3));
        assertThat(f.expire()).isEqualTo(1);                                  // no exception
        assertThat(held).exists();
        assertThat(f.pendingDeletes()).containsExactly(held.toAbsolutePath().normalize());
        assertThat(f.stats()).containsEntry("idMapDeletesPending", 1);
        f.sweep();                                                            // still held: still waiting, still no error
        assertThat(held).exists();
        windows.set(false);
        f.sweep();
        assertThat(held).doesNotExist();
        assertThat(f.pendingDeletes()).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------------------------ idle release

    @Test
    void manyDatesReadOnceThenIdleEndUnderTheCapAfterIdleReleaseAndTrimEvenWhenADeleteFailsFirst() throws Exception {
        FakeClock clock = new FakeClock();
        long size = 0;
        List<Path> files = new java.util.ArrayList<>();
        for (int d = 1; d <= 20; d++) {
            Path f = map("trade", D1.plusDays(d).toString(), 2_000, 0);       // read once, still mapped (a heap cache holds it)
            MappedIdFile.open(f).file("T-7");
            size = Files.size(f);
            files.add(f);
        }
        IdMapFolder f = folder(5 * size, null, clock);
        f.trim(null);
        assertThat(MappedIdFile.mapped(tmp.resolve("idmaps"))[0]).as("all in use: none trimmed").isEqualTo(20L);
        AtomicBoolean windows = new AtomicBoolean(true);
        f.deleter(p -> {
            if (windows.get()) {
                throw new AccessDeniedException(p.toString());
            }
            Files.deleteIfExists(p);
        });
        clock.now = Instant.now().plus(Duration.ofHours(1));                 // idle for an hour
        f.sweep();
        assertThat(MappedIdFile.mapped(tmp.resolve("idmaps"))[0]).as("idle: released, files kept").isZero();
        assertThat(f.stats()).containsEntry("idMapsIdleReleased", 20L).containsEntry("idMapsEvicted", 15L);
        assertThat(f.pendingDeletes()).hasSize(15);                          // Windows: still held, tried again
        windows.set(false);
        f.sweep();
        assertThat(MappedIdFile.folderBytes(tmp.resolve("idmaps"))).as("back under the cap").isLessThanOrEqualTo(5 * size);
        assertThat(f.pendingDeletes()).isEmpty();
        assertThat(f.stats()).doesNotContainKey("idMapsInUseOverCap");
        Path kept = files.stream().filter(Files::exists).findFirst().orElseThrow();
        MappedIdFile again = MappedIdFile.open(kept);                        // read again: mapped again, the same answers
        assertThat(again.file("T-7")).isZero();
        assertThat(again.size()).isEqualTo(2_000);
        MappedIdFile.release(kept, false);
    }

    @Test
    void neverReleasedWhileAReadIsInsideNorForAnOpenDate() throws Exception {
        FakeClock clock = new FakeClock();
        IdMapFolder f = folder(1, null, clock);
        Path reading = map("trade", "2026-09-01", 100, 0);
        Path open = map("trade", "2026-09-02", 100, 0);
        engine = DatesInUse.register(this, t -> Set.of(LocalDate.parse("2026-09-02")));
        MappedIdFile m = MappedIdFile.open(reading);
        m.enter();                                                           // a read inside it
        clock.now = Instant.now().plus(Duration.ofHours(2));
        assertThat(f.releaseIdle()).isZero();
        f.trim(null);
        assertThat(reading).exists();
        assertThat(MappedIdFile.isMapped(reading)).isTrue();
        assertThat(m.file("T-1")).isZero();
        m.exit();                                                            // the read is done
        assertThat(f.releaseIdle()).isEqualTo(1);
        assertThat(MappedIdFile.isMapped(open)).as("open in an engine").isTrue();
        f.trim(null);
        assertThat(reading).doesNotExist();
        assertThat(open).exists();
        MappedIdFile.release(open, true);
    }

    @Test
    void aHandleMapsAgainAfterAnIdleReleaseButNotAfterADrop() throws Exception {
        IdMapFolder f = folder(0, null, Clock.systemUTC());
        Path file = map("trade", "2026-09-03", 50, 0);
        MappedIdFile.Handle h = new MappedIdFile.Handle(MappedIdFile.open(file));
        MappedIdFile.releaseThen(file, () -> { });                         // idle release: the file stays
        assertThat(h.closed()).isTrue();
        assertThat(h.file("T-3")).isZero();                                  // mapped again, the read goes on
        assertThat(MappedIdFile.isMapped(file)).isTrue();
        f.deleter(p -> {
            throw new AccessDeniedException(p.toString());
        });
        f.drop(file, IdMapFolder.Drop.EVICTED);                              // a drop: not mapped again while it waits for its delete
        assertThatThrownBy(() -> h.file("T-3")).isInstanceOf(MappedIdFile.Closed.class);
        assertThat(MappedIdFile.open(file)).isNull();
        map("trade", "2026-09-03", 50, 0);                                   // written again meanwhile: that file stays
        f.deleter(Files::deleteIfExists);
        f.retryDeletes();
        f.retryDeletes();
        assertThat(file).exists();
        assertThat(MappedIdFile.open(file).file("T-3")).isZero();
        MappedIdFile.release(file, true);
    }

    @Test
    void idleReleaseIsHalfAnHourByDefaultAndAtLeastFiveMinutes() {
        assertThat(IdMapFolder.idleRelease("lake", "")).hasMinutes(30);
        assertThat(IdMapFolder.idleRelease("lake", "45m")).hasMinutes(45);
        assertThatThrownBy(() -> IdMapFolder.idleRelease("lake", "2m")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id-map-disk.idle-release").hasMessageContaining("minimum is 5m");
    }

    @Test
    void aReplacedMapSaysSo() throws Exception {
        IdMapFolder f = folder(0, null, Clock.systemUTC());
        Path old = map("trade", "2026-09-01", 10, 0);
        f.drop(old, IdMapFolder.Drop.REPLACED);
        assertThat(old).doesNotExist();
        assertThat(f.drops().get(0)).containsEntry("reason", "replaced (new version)").containsEntry("date", "2026-09-01")
                .containsEntry("kind", "trade").containsEntry("version", "7@1");
        assertThat(IdMapFolder.all()).contains(f);
        f.close();
        assertThat(IdMapFolder.all()).doesNotContain(f);
    }
}
