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
package com.ash.drishti.plugin.delta;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.deltalake.NativeEngine;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A row deleted in place with a deletion vector (as Databricks or Spark delete) is gone for both engines, whose reads
 * of deletion vectors go through Kernel's file-system client; the version before the delete still has it, and a table
 * with deletion vectors switches column reads off.
 */
class DeltaDeletionVectorTest {

    static Path lake(String fixture) throws Exception {
        Path root = Files.createTempDirectory("drishti-dv");
        Path src = Path.of("src/test/resources/" + fixture);
        try (Stream<Path> files = Files.walk(src)) {
            for (Path f : files.toList()) {
                Path to = root.resolve(src.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(f, to);
                }
            }
        }
        return root;
    }

    @ParameterizedTest
    @ValueSource(strings = {"native", "hadoop"})
    void aRowDeletedWithADeletionVectorIsGone(String engine) throws Exception {
        Path root = lake("lake-layout");
        LocalDate day = LocalDate.of(2026, 9, 30);
        Thread.sleep(20);                                         // after the copied commits (their times are the copy's)
        Instant beforeDelete = Instant.now();
        Thread.sleep(20);                                         // the delete's commit is strictly later
        try (NativeEngine writer = NativeEngine.create()) {
            DeletionVectorFixture.delete(writer, root.resolve("desk/trade"), day.toString(), "T-033");
        }
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(DatedSourceContract.context(Map.of("root", root.toString(), "domain", "desk", "engine", engine,
                "layout.trade.columns", "mtm,book")));
        try {
            assertThat(p.fetch(EntityRef.of("trade", "T-033"), AsOf.of(day))).as("deleted on " + day).isEmpty();
            assertThat(p.fetch(EntityRef.of("trade", "T-034"), AsOf.of(day))).isPresent();      // its neighbours stay
            assertThat(p.fetch(EntityRef.of("trade", "T-032"), AsOf.of(day))).isPresent();
            assertThat(p.fetch(EntityRef.of("trade", "T-033"), AsOf.of(day.minusDays(1)))).isPresent();   // another day
            assertThat(p.fetch(EntityRef.of("trade", "T-033"), new AsOf(day, beforeDelete))).as("known before the delete").isPresent();
            assertThat(p.columnar("trade")).isEmpty();                                     // columns would still hold it
            assertThat(p.reverse(EntityRef.of("book", "BOOK-A"), "trade", AsOf.of(day))).extracting(EntityRef::id)
                    .doesNotContain("T-033").isNotEmpty();
        } finally {
            p.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"native", "hadoop"})
    void bothEnginesSeeTheSameRowsAfterADelete(String engine) throws Exception {
        Path root = lake("lake");
        try (NativeEngine writer = NativeEngine.create()) {
            DeletionVectorFixture.delete(writer, root.resolve("desk/trade"), DatedSourceContract.D3.toString(), "T-2");
        }
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(DatedSourceContract.context(Map.of("root", root.toString(), "domain", "desk", "engine", engine)));
        try {
            List<String> left = p.search("trade", "", 50).stream().map(h -> h.ref().id()).toList();
            assertThat(left).contains("T-1");
            assertThat(p.fetch(EntityRef.of("trade", "T-2"), AsOf.of(DatedSourceContract.D3))).isEmpty();
            assertThat(p.fetch(EntityRef.of("trade", "T-1"), AsOf.of(DatedSourceContract.D3))).isPresent();
        } finally {
            p.close();
        }
    }
}
