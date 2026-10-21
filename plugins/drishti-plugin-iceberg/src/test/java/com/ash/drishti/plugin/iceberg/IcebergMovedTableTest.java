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
package com.ash.drishti.plugin.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.EntityRef;

import static com.ash.drishti.testkit.DatedSourceContract.ROWS;
import static com.ash.drishti.testkit.DatedSourceContract.context;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * QA2-22: a table moved after it was written (its metadata still names the old folder) is one clear problem in Health, saying what to
 * do, with one short log line, not a WARN and a stack trace at every refresh; the relocate it suggests reads the table with the native
 * engine; and {@code relocate} given to the library engine is said, not silently ignored.
 */
class IcebergMovedTableTest {

    private static Path moved;
    private static final List<LogRecord> RECORDS = new CopyOnWriteArrayList<>();
    private static final Handler HANDLER = new Handler() {
        @Override
        public void publish(LogRecord r) {
            RECORDS.add(r);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private static final Logger CAFFEINE = Logger.getLogger("com.github.benmanes.caffeine");
    private static final Logger PROBLEMS = Logger.getLogger(TableProblems.class.getName());

    @BeforeAll
    static void writeAndMove() throws Exception {
        Path written = Files.createTempDirectory("drishti-iceberg-written");
        Map<String, Boolean> trade = new LinkedHashMap<>();
        trade.put("mtm", true);
        try (IcebergLake lake = IcebergLake.of(Map.of("root", written.toString(), "domain", "desk"))) {
            IcebergSourcePluginTest.write(lake, ROWS, Map.of("trade", trade));
        }
        moved = written.resolveSibling(written.getFileName() + "-moved");
        Files.move(written, moved);
        CAFFEINE.addHandler(HANDLER);
        PROBLEMS.addHandler(HANDLER);
    }

    @AfterAll
    static void detach() {
        CAFFEINE.removeHandler(HANDLER);
        PROBLEMS.removeHandler(HANDLER);
    }

    private IcebergSourcePlugin start(String engine, String relocate) throws Exception {
        Map<String, String> s = new HashMap<>(Map.of("root", moved.toString(), "domain", "desk", "source-name", "moved-" + engine,
                "engine", engine, "refresh-seconds", "1", "kinds", "trade"));
        if (relocate != null) {
            s.put("relocate", relocate);
        }
        IcebergSourcePlugin p = new IcebergSourcePlugin();
        p.start(context(s));
        return p;
    }

    @Test
    void aMovedTableIsOneProblemInHealthWithOneShortLogLine() throws Exception {
        for (String engine : new String[] {"library", "native"}) {
            RECORDS.clear();
            IcebergSourcePlugin p = start(engine, null);
            try {
                Thread.sleep(3_500);                                          // three refreshes
                String health = p.health();
                assertThat(health).as(engine).startsWith("DEGRADED: cannot read trade: its metadata names files under ")
                        .contains("the table was moved or copied after it was written")
                        .contains("relocate: '").contains(" -> " + moved + "'");
                assertThat(RECORDS).as(engine + ": no Caffeine stack trace").noneMatch(r -> r.getLoggerName().startsWith("com.github.benmanes"));
                assertThat(RECORDS.stream().filter(r -> r.getLevel() == java.util.logging.Level.WARNING).count()).as(engine + ": one line").isEqualTo(1);
                assertThat(RECORDS).allMatch(r -> r.getThrown() == null);
            } finally {
                p.close();
            }
        }
    }

    @Test
    void theRelocateItSuggestsReadsTheTableWithTheNativeEngine() throws Exception {
        IcebergSourcePlugin probe = start("native", null);
        String why = probe.problem("trade").orElseThrow();
        probe.close();
        String relocate = why.substring(why.indexOf("relocate: ") + "relocate: ".length());   // as Health gives it, quotes and all
        IcebergSourcePlugin p = start("native", relocate);
        try {
            assertThat(p.fetch(EntityRef.of("trade", "T-1"))).isPresent();
            assertThat(p.health()).startsWith("UP");
            assertThat(p.problem("trade")).isEmpty();
        } finally {
            p.close();
        }
    }

    @Test
    void relocateGivenToTheLibraryEngineIsSaid() throws Exception {
        IcebergSourcePlugin p = start("library", "/nowhere -> /elsewhere");
        try {
            assertThat(p.health()).contains("relocate is read by engine: native only; engine: library ignores it");
        } finally {
            p.close();
        }
    }

    /**
     * QA4-01: on JDK 25 {@code join()} throws a new {@code CancellationException("join")} carrying the load's quiet failure; on JDK 21 the
     * failure itself. Both are found, so the table problem is recorded whichever JDK runs the server; a cancellation of another kind is not.
     */
    @Test
    void aQuietFailureIsFoundAsJoinThrowsItOnEveryJdk() {
        var q = new IcebergSourcePlugin.QuietFailure(new java.io.UncheckedIOException(new java.nio.file.NoSuchFileException("/x/metadata/snap-1.avro")));
        var jdk25 = new java.util.concurrent.CancellationException("join");
        jdk25.initCause(q);
        assertThat(IcebergSourcePlugin.QuietFailure.in(q)).isSameAs(q);
        assertThat(IcebergSourcePlugin.QuietFailure.in(jdk25)).isSameAs(q);
        assertThat(IcebergSourcePlugin.QuietFailure.in(new java.util.concurrent.CompletionException(q))).isSameAs(q);
        assertThat(IcebergSourcePlugin.QuietFailure.in(new java.util.concurrent.CancellationException("cancelled"))).isNull();
        assertThat(IcebergSourcePlugin.QuietFailure.in(new IllegalStateException(q))).isNull();
    }

    /** QA4-01: the moved table lists nothing, and says why (the connector's Test and a search's partial note), without a folder. */
    @Test
    void aMovedTableSaysWhyItListsNothing() throws Exception {
        IcebergSourcePlugin p = start("native", null);
        try {
            assertThat(p.listingProblem("trade")).hasValueSatisfying(why -> assertThat(why)
                    .startsWith("the table trade names files that are not there").doesNotContain(moved.toString()));
        } finally {
            p.close();
        }
    }

    /** QA4-05: a start refused for a relocate it cannot read closes without a NullPointerException (the lake was never opened). */
    @Test
    void aStartRefusedForItsRelocateClosesQuietly() {
        IcebergSourcePlugin p = new IcebergSourcePlugin();
        Map<String, String> s = new HashMap<>(Map.of("root", moved.toString(), "domain", "desk", "engine", "native", "kinds", "trade",
                "relocate", "/no/arrow/here"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> p.start(context(s))).hasMessageContaining("relocate: '/no/arrow/here' cannot be read");
        org.assertj.core.api.Assertions.assertThatCode(p::close).doesNotThrowAnyException();
    }
}
