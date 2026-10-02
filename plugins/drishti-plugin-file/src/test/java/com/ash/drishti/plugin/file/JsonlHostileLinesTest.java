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
package com.ash.drishti.plugin.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JSON-lines days that are not clean (the QA pass of 2026-10-01, docs/qa/2026-10-01): an unreadable line, NaN, a
 * document beyond Jackson's default limits, {@code doc} as an object without {@code columns}, duplicate ids, and a
 * file replaced while it is read.
 */
class JsonlHostileLinesTest {

    private static final java.time.LocalDate DAY = java.time.LocalDate.of(2026, 9, 1);

    @TempDir
    Path root;

    /** An envelope row; {@code doc} as a string, or as an object; with or without {@code columns}. */
    private static String row(String id, double mtm, String desk, boolean docAsObject, boolean columns) {
        String doc = "{\"tradeId\":\"" + id + "\",\"mtm\":" + mtm + ",\"desk\":\"" + desk + "\"}";
        StringBuilder s = new StringBuilder("{\"kind\":\"trade\",\"id\":\"").append(id).append("\",\"date\":\"2026-09-01\",\"doc\":");
        s.append(docAsObject ? doc : "\"" + doc.replace("\"", "\\\"") + "\"");
        if (columns) {
            s.append(",\"columns\":{\"mtm\":").append(mtm).append(",\"desk\":\"").append(desk).append("\"}");
        }
        return s.append('}').toString();
    }

    private Path day(List<String> lines) throws Exception {
        Path f = root.resolve("2026-09-01/trade.jsonl");
        Files.createDirectories(f.getParent());
        Files.write(f, lines, StandardCharsets.UTF_8);
        return f;
    }

    private FileSourcePlugin started(Map<String, String> extra) {
        Map<String, String> s = new HashMap<>(Map.of("root", root.toString(), "layout.trade.columns", "mtm,desk", "rescan-seconds", "3600"));
        s.putAll(extra);
        FileSourcePlugin p = new FileSourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    private static List<String> goodRows(int n) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            lines.add(row("T-" + (1000 + i), i % 2 == 0 ? -i : i, "DESK-" + i % 4, false, true));
        }
        return lines;
    }

    @Test
    void oneTruncatedLineIsSkippedAndCountedNotTheWholeDay() throws Exception {
        List<String> lines = goodRows(400);
        lines.add(200, lines.get(200).substring(0, 40));                  // line 201: truncated
        day(lines);
        FileSourcePlugin p = started(Map.of());
        assertThat(p.fetch(EntityRef.of("trade", "T-1000"), AsOf.of(DAY))).isPresent();
        assertThat(p.fetch(EntityRef.of("trade", "T-1399"), AsOf.of(DAY))).isPresent();
        ColumnSet c = p.columns("trade", List.of("mtm"), AsOf.of(DAY)).orElseThrow();
        assertThat(c.size()).isEqualTo(400);
        assertThat(c.incomplete()).contains("1 unreadable line").contains("2026-09-01/trade.jsonl");
        assertThat(p.cacheStats()).containsEntry("unreadableLines", 1L);
        assertThat(p.health()).startsWith("UP").contains("1 unreadable line in 2026-09-01/trade.jsonl").contains("line 201");
    }

    @Test
    void nanAndInfinityAsPythonWritesThemAreReadAsNoValue() throws Exception {
        List<String> lines = goodRows(10);
        lines.add("{\"kind\":\"trade\",\"id\":\"T-NAN\",\"doc\":\"{\\\"tradeId\\\":\\\"T-NAN\\\",\\\"mtm\\\":NaN}\",\"columns\":{\"mtm\":NaN,\"desk\":\"D\"}}");
        lines.add("{\"kind\":\"trade\",\"id\":\"T-INF\",\"doc\":{\"tradeId\":\"T-INF\",\"mtm\":-Infinity,\"desk\":\"D\"}}");
        day(lines);
        FileSourcePlugin p = started(Map.of());
        ColumnSet c = p.columns("trade", List.of("mtm", "desk"), AsOf.of(DAY)).orElseThrow();
        assertThat(c.size()).isEqualTo(12);
        assertThat(c.incomplete()).isNull();
        int nan = java.util.Arrays.asList(c.ids()).indexOf("T-NAN");
        int inf = java.util.Arrays.asList(c.ids()).indexOf("T-INF");
        assertThat(c.value("mtm", nan)).isNull();
        assertThat(c.value("mtm", inf)).isNull();
        assertThat(c.value("desk", inf)).isEqualTo("D");
        var doc = p.fetch(EntityRef.of("trade", "T-NAN"), AsOf.of(DAY)).orElseThrow().data();
        assertThat(doc.get("mtm").isNull()).isTrue();
        assertThat(doc.get("tradeId").asText()).isEqualTo("T-NAN");
        assertThat(p.fetch(EntityRef.of("trade", "T-INF"), AsOf.of(DAY)).orElseThrow().data().get("mtm").isNull()).isTrue();
    }

    @Test
    void aDocumentBeyondJacksonsDefaultStringLimitIsReadAndOneBeyondMaxDocumentMbIsSkipped() throws Exception {
        List<String> lines = goodRows(5);
        String big = "x".repeat(21 << 20);                               // above Jackson's 20 MB default string limit
        lines.add("{\"kind\":\"trade\",\"id\":\"T-BIG\",\"doc\":{\"tradeId\":\"T-BIG\",\"mtm\":7,\"desk\":\"D\",\"blob\":\"" + big + "\"}}");
        day(lines);
        FileSourcePlugin p = started(Map.of());
        var doc = p.fetch(EntityRef.of("trade", "T-BIG"), AsOf.of(DAY)).orElseThrow().data();
        assertThat(doc.get("blob").asText()).hasSize(21 << 20);
        assertThat(p.columns("trade", List.of("mtm"), AsOf.of(DAY)).orElseThrow().size()).isEqualTo(6);

        FileSourcePlugin small = started(Map.of("max-document-mb", "1"));
        assertThat(small.fetch(EntityRef.of("trade", "T-BIG"), AsOf.of(DAY))).isEmpty();
        assertThat(small.fetch(EntityRef.of("trade", "T-1004"), AsOf.of(DAY))).isPresent();
        ColumnSet c = small.columns("trade", List.of("mtm"), AsOf.of(DAY)).orElseThrow();
        assertThat(c.size()).isEqualTo(5);
        assertThat(small.health()).contains("line 6").contains("max-document-mb");
    }

    @Test
    void anUnreadableFileIsIndexedOnceUntilItChanges() throws Exception {
        Path f = day(goodRows(3));
        FileSourcePlugin p = started(Map.of());
        assertThat(p.fetch(EntityRef.of("trade", "T-1000"), AsOf.of(DAY))).isPresent();
        Object builds = p.cacheStats().get("indexBuilds");
        Files.write(f, List.of(row("T-1000", 1, "D", false, true)), StandardCharsets.UTF_8);
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        org.junit.jupiter.api.Assumptions.assumeTrue(f.toFile().setReadable(false), "cannot make a file unreadable here");
        try {
            for (int i = 0; i < 5; i++) {
                assertThat(p.fetch(EntityRef.of("trade", "T-1000"), AsOf.of(DAY))).isEmpty();
            }
            assertThat((Long) p.cacheStats().get("indexBuilds")).isEqualTo((Long) builds + 1);
            assertThat(p.health()).startsWith("UP").contains("unreadable file 2026-09-01/trade.jsonl");
        } finally {
            f.toFile().setReadable(true);
        }
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 10_000));
        assertThat(p.fetch(EntityRef.of("trade", "T-1000"), AsOf.of(DAY))).isPresent();
        assertThat(p.health()).isEqualTo("UP");
    }

    @Test
    void docAsAnObjectWithoutColumnsIsPromotedLikeDocAsAString() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            lines.add(row("T-" + i, -10 - i, "DESK-" + i % 3, i % 2 == 0, false));
        }
        day(lines);
        FileSourcePlugin p = started(Map.of());
        ColumnSet c = p.columns("trade", List.of("mtm", "desk"), AsOf.of(DAY)).orElseThrow();
        assertThat(c.size()).isEqualTo(20);
        for (int i = 0; i < c.size(); i++) {
            int n = Integer.parseInt(c.ids()[i].substring(2));
            assertThat(c.value("mtm", i)).as(c.ids()[i]).isEqualTo(-10.0 - n);
            assertThat(c.value("desk", i)).as(c.ids()[i]).isEqualTo("DESK-" + n % 3);
        }
    }

    @Test
    void aDuplicateIdKeepsItsLastLineEverywhere() throws Exception {
        List<String> lines = goodRows(10);
        for (int i = 0; i < 3; i++) {
            lines.add(row("T-" + (1000 + i), 1_000_000_000 + i, "DESK-DUP", false, true));   // the later copy wins
        }
        day(lines);
        FileSourcePlugin p = started(Map.of());
        ColumnSet c = p.columns("trade", List.of("mtm", "desk"), AsOf.of(DAY)).orElseThrow();
        assertThat(c.size()).isEqualTo(10);
        assertThat(c.ids()).doesNotHaveDuplicates();
        int at = java.util.Arrays.asList(c.ids()).indexOf("T-1001");
        assertThat(c.value("mtm", at)).isEqualTo(1_000_000_001.0);
        assertThat(c.value("desk", at)).isEqualTo("DESK-DUP");
        assertThat(p.fetch(EntityRef.of("trade", "T-1001"), AsOf.of(DAY)).orElseThrow().data().get("mtm").asDouble()).isEqualTo(1_000_000_001.0);
        assertThat(p.reverse(EntityRef.of("desk", "DESK-DUP"), "trade", AsOf.of(DAY))).hasSize(3);
        assertThat(p.cacheStats()).containsEntry("duplicateIds", 3L);
        assertThat(p.health()).contains("3 duplicate ids in 2026-09-01/trade.jsonl");
        assertThat(c.incomplete()).isNull();                                 // every entity is there: the last line of each
    }

    @Test
    void readsRacingAnAtomicReplaceNeverFailOrReturnAnotherEntity() throws Exception {
        List<String> lines = goodRows(300);
        Path f = day(lines);
        FileSourcePlugin p = started(Map.of());
        assertThat(p.fetch(EntityRef.of("trade", "T-1000"), AsOf.of(DAY))).isPresent();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Future<?>> running = new ArrayList<>();
            running.add(pool.submit(() -> {                                  // flip.py: shuffled copies, tmp + rename
                Random rnd = new Random(7);
                List<String> shuffled = new ArrayList<>(lines);
                for (int k = 0; k < 150 && !stop.get(); k++) {
                    Collections.shuffle(shuffled, rnd);
                    Path tmp = f.resolveSibling("trade.jsonl.tmp");
                    Files.write(tmp, shuffled, StandardCharsets.UTF_8);
                    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
                return null;
            }));
            for (int t = 0; t < 4; t++) {
                int seed = t;
                running.add(pool.submit(() -> {
                    Random rnd = new Random(seed);
                    while (!stop.get()) {
                        String id = "T-" + (1000 + rnd.nextInt(300));
                        try {
                            var doc = p.fetch(EntityRef.of("trade", id), AsOf.of(DAY));
                            if (doc.isEmpty() || !doc.get().data().get("tradeId").asText().equals(id)) {
                                failures.incrementAndGet();
                                errors.add(id + " -> " + doc.map(d -> d.data().get("tradeId").asText()).orElse("empty"));
                            }
                        } catch (Exception e) {
                            failures.incrementAndGet();
                            errors.add(id + " -> " + e);
                        }
                        reads.incrementAndGet();
                    }
                    return null;
                }));
            }
            running.get(0).get();
            stop.set(true);
            for (Future<?> r : running) {
                r.get();
            }
        } finally {
            stop.set(true);
            pool.shutdownNow();
        }
        assertThat(reads.get()).isPositive();
        assertThat(errors).as("%d of %d reads", failures.get(), reads.get()).isEmpty();
    }

    @Test
    void anIndexOverAFileRewrittenInPlaceNeverReturnsAnotherEntitysLine() throws Exception {
        List<String> lines = goodRows(50);
        Path f = day(lines);
        FileSourcePlugin p = started(Map.of());
        assertThat(p.fetch(EntityRef.of("trade", "T-1010"), AsOf.of(DAY))).isPresent();
        FileTime t = Files.getLastModifiedTime(f);
        List<String> shuffled = new ArrayList<>(lines);
        Collections.shuffle(shuffled, new Random(3));
        Files.write(f, shuffled, StandardCharsets.UTF_8);                    // same inode, same size, same time
        Files.setLastModifiedTime(f, t);
        for (int i = 0; i < 50; i++) {
            String id = "T-" + (1000 + i);
            assertThat(p.fetch(EntityRef.of("trade", id), AsOf.of(DAY)).orElseThrow().data().get("tradeId").asText()).isEqualTo(id);
        }
    }
}
