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
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Delta Lake connector over the fixture lake (written by delta-rs with the contract's rows), plus what only a
 * versioned store can do: read data as known before a later correction.
 */
class DeltaSourcePluginTest extends DatedSourceContract {

    static final Instant T0 = Instant.parse("2026-09-30T20:00:00Z");
    private static DeltaSourcePlugin plugin;

    /**
     * Delta time travel for these tables resolves instants against the commit files' modification times, which a
     * checkout or copy rewrites. The test works on a copy with pinned times: version 0 at T0, version 1 ten
     * seconds later.
     */
    @Override
    protected synchronized SourcePlugin plugin() throws Exception {
        if (plugin != null) {
            return plugin;
        }
        Path root = Files.createTempDirectory("drishti-lake");
        Path src = Path.of("src/test/resources/lake");
        try (var files = Files.walk(src)) {
            for (Path f : files.toList()) {
                Path to = root.resolve(src.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(f, to);
                }
            }
        }
        for (String table : new String[] {"trade", "counterparty"}) {
            try (var logs = Files.list(root.resolve("desk").resolve(table).resolve("_delta_log"))) {
                for (Path log : logs.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                    long version = Long.parseLong(log.getFileName().toString().replace(".json", ""));
                    Files.setLastModifiedTime(log, FileTime.from(T0.plusSeconds(10 * version)));
                }
            }
        }
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(context(Map.of("root", root.toString(), "domain", "desk", "mode.counterparty", "effective", "source-name", "lake")));
        plugin = p;
        return p;
    }

    @Test
    void knownAtReadsTheTableBeforeALaterCorrection() throws Exception {
        EntityDocument before = plugin().fetch(EntityRef.of("trade", "T-1"), new AsOf(LocalDate.of(2026, 9, 30), T0.plusSeconds(5))).orElseThrow();
        assertThat(before.data().get("mtm").asDouble()).isEqualTo(120);
        assertThat(before.data().get("restated").isMissing()).isTrue();
        assertThat(before.provenance().generation()).isZero();
    }

    @Test
    void knownAtBeforeTheTableExistedFindsNothingAndAfterTheLastCommitReadsTheLatest() throws Exception {
        EntityRef t1 = EntityRef.of("trade", "T-1");
        LocalDate d = LocalDate.of(2026, 9, 30);
        assertThat(plugin().fetch(t1, new AsOf(d, T0.minusSeconds(86_400)))).isEmpty();          // nothing was known yet
        EntityDocument later = plugin().fetch(t1, new AsOf(d, java.time.Instant.parse("2099-01-01T00:00:00Z"))).orElseThrow();
        assertThat(later.data().get("mtm").asDouble()).isEqualTo(125);                            // as known now
    }

    private static void copy(Path from, Path to) throws java.io.IOException {
        try (var files = Files.walk(from)) {
            for (Path f : files.toList()) {
                Path t = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(t);
                } else {
                    Files.copy(f, t);
                }
            }
        }
    }

    @Test
    void aTableAddedToTheLakeIsServedFromTheNextReindexWithoutARestart() throws Exception {
        Path root = Files.createTempDirectory("drishti-lake-grow");
        Path src = Path.of("src/test/resources/lake/desk");
        copy(src.resolve("trade"), root.resolve("desk").resolve("trade"));
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(context(Map.of("root", root.toString(), "domain", "desk", "source-name", "growing")));
        try {
            assertThat(p.manifest().kinds()).containsExactly("trade");
            copy(src.resolve("counterparty"), root.resolve("desk").resolve("counterparty"));     // loaded while running
            p.reindex();
            assertThat(p.manifest().kinds()).containsExactlyInAnyOrder("trade", "counterparty");
            assertThat(p.search("counterparty", "", 10)).isNotEmpty();
        } finally {
            p.close();
        }
    }
}
