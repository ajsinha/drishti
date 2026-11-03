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

import static com.ash.drishti.testkit.DatedSourceContract.context;
import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A table committed after the connector started, into a domain that had none (the first load into an empty lake): a data-load signal's
 * refresh ({@code purgeCaches}, and the coalesced {@code refreshVersions}) makes the kind readable at once, not at the next reindex
 * ({@link NewTables}). A connector whose tables were there already reads as before.
 */
class NewTablesTest {

    static final Path FIXTURE = Path.of("src/test/resources/lake/desk");
    static final LocalDate D = LocalDate.of(2026, 9, 30);
    static final EntityRef T1 = EntityRef.of("trade", "T-1");

    @TempDir Path root;

    private static void copy(Path from, Path to) throws IOException {
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

    private DeltaSourcePlugin start(String name) throws Exception {
        Files.createDirectories(root.resolve("desk"));
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(context(Map.of("root", root.toString(), "domain", "desk", "source-name", name, "engine", "native")));
        return p;
    }

    @Test
    void aTableCommittedIntoAnEmptyDomainIsReadableRightAfterPurgeCaches() throws Exception {
        DeltaSourcePlugin p = start("empty-then-purged");
        try {
            assertThat(p.manifest().kinds()).isEmpty();
            assertThat(p.health()).startsWith("DOWN: no Delta tables");
            copy(FIXTURE.resolve("trade"), root.resolve("desk").resolve("trade"));          // the load commits the domain's first table
            p.purgeCaches();                                                                  // the data-load signal's refresh
            assertThat(p.manifest().kinds()).containsExactly("trade");
            assertThat(p.fetch(T1, AsOf.of(D))).isPresent();
            assertThat(p.search("trade", "", 10)).as("listed now, not at the next reindex").isNotEmpty();
            assertThat(p.health()).doesNotStartWith("DOWN");
        } finally {
            p.close();
        }
    }

    @Test
    void aTableCommittedIntoAnEmptyDomainIsReadableRightAfterRefreshVersions() throws Exception {
        DeltaSourcePlugin p = start("empty-then-refreshed");
        try {
            copy(FIXTURE.resolve("trade"), root.resolve("desk").resolve("trade"));
            p.refreshVersions();                                                              // a signal merged into a refresh moments ago
            assertThat(p.manifest().kinds()).containsExactly("trade");
            assertThat(p.fetch(T1, AsOf.of(D))).isPresent();
            long until = System.currentTimeMillis() + 10_000;
            while (p.search("trade", "", 10).isEmpty() && System.currentTimeMillis() < until) {
                Thread.sleep(50);                                                             // its listing is made on the connector's loaders
            }
            assertThat(p.search("trade", "", 10)).isNotEmpty();
        } finally {
            p.close();
        }
    }

    @Test
    void aConnectorWhoseTablesWereThereReadsAsBeforeAndFindsALaterOneToo() throws Exception {
        copy(FIXTURE.resolve("trade"), root.resolve("desk").resolve("trade"));
        DeltaSourcePlugin p = start("existing");
        try {
            Optional<EntityDocument> before = p.fetch(T1, AsOf.of(D));
            assertThat(before).isPresent();
            p.purgeCaches();
            p.refreshVersions();
            assertThat(p.manifest().kinds()).containsExactly("trade");
            assertThat(p.fetch(T1, AsOf.of(D)).orElseThrow().data().unwrap()).isEqualTo(before.orElseThrow().data().unwrap());
            copy(FIXTURE.resolve("counterparty"), root.resolve("desk").resolve("counterparty"));
            p.purgeCaches();
            assertThat(p.manifest().kinds()).containsExactlyInAnyOrder("trade", "counterparty");
            assertThat(p.search("counterparty", "", 10)).isNotEmpty();
        } finally {
            p.close();
        }
    }

    @Test
    void aConnectorWithItsKindsConfiguredDoesNotDiscover() throws Exception {
        Files.createDirectories(root.resolve("desk"));
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(context(Map.of("root", root.toString(), "domain", "desk", "source-name", "configured", "engine", "native", "kinds", "trade")));
        try {
            copy(FIXTURE.resolve("counterparty"), root.resolve("desk").resolve("counterparty"));
            p.purgeCaches();
            assertThat(p.manifest().kinds()).containsExactly("trade");
        } finally {
            p.close();
        }
    }
}
