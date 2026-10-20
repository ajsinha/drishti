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
package com.ash.drishti.iceberg.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.iceberg.Table;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * QA2-09: a path-based table a catalog wrote (pyiceberg here: {@code NNNNN-<uuid>.metadata.json}, no {@code version-hint.text}) is found
 * at the folder its name gives, read at the metadata file with the highest sequence number, listed among the domain's tables, and a newer
 * file is picked up by a refresh.
 */
class CatalogNamedMetadataTest {

    /** pyiceberg's own tables (the iceberg plugin's fixtures): fx/v1_basic has 00000, 00001 and 00002. */
    static final Path FIXTURES = Path.of("../plugins/drishti-plugin-iceberg/src/test/resources/iceberg-fixtures").toAbsolutePath().normalize();

    @Test
    void aPyicebergTableIsReadAtItsNewestMetadataFile() throws Exception {
        Path root = Files.createTempDirectory("drishti-catalog-named");
        try {
            Path meta = Files.createDirectories(root.resolve("qa/positions/metadata"));
            try (Stream<Path> s = Files.list(FIXTURES.resolve("fx/v1_basic/metadata"))) {
                for (Path f : s.filter(f -> f.getFileName().toString().endsWith(".metadata.json")).toList()) {
                    Files.copy(f, meta.resolve(f.getFileName()));
                }
            }
            assertThat(meta.resolve("version-hint.text")).doesNotExist();
            try (Catalog c = Catalogs.open(Map.of("root", root.toString(), "domain", "qa"))) {
                Table t = c.load("positions").orElseThrow(() -> new AssertionError("no table qa.positions"));
                assertThat(Path.of(t.metadataLocation()).getFileName().toString()).startsWith("00002-");
                assertThat(c.tables()).isEqualTo(List.of("positions"));
                // a newer commit by the catalog: a refresh moves to it
                String newest;
                try (Stream<Path> s = Files.list(meta)) {
                    newest = s.map(f -> f.getFileName().toString()).filter(n -> n.startsWith("00002-")).findFirst().orElseThrow();
                }
                Files.copy(meta.resolve(newest), meta.resolve("00003-0b1d7e52-6a8c-4c5e-9e0f-1c2b3a4d5e6f.metadata.json"));
                TableOps.Loaded next = t.ops().refresh(t.state()).orElseThrow(() -> new AssertionError("the refresh did not see 00003"));
                assertThat(next.location()).endsWith("00003-0b1d7e52-6a8c-4c5e-9e0f-1c2b3a4d5e6f.metadata.json");
                assertThat(t.ops().refresh(next)).isEmpty();
            }
        } finally {
            try (Stream<Path> all = Files.walk(root)) {
                all.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
    }
}
