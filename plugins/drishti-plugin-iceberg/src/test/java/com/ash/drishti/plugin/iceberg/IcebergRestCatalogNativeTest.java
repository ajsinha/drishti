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

import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.iceberg.catalog.Catalogs;
import com.ash.drishti.iceberg.write.Operations;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link IcebergRestCatalogTest} with drishti-iceberg's own REST client (the JDK's HTTP client: config, loadTable with
 * ETag, the table's files in S3 through the AWS SDK): the same contract against the same catalog; and commits through
 * the catalog: a table created and written by the native engine, two writers committing at once (the catalog answers
 * 409 to the loser of each round, which re-reads and commits again), read back by the Iceberg library. Skipped where
 * Docker is not reachable.
 */
@Testcontainers(disabledWithoutDocker = true)
class IcebergRestCatalogNativeTest extends IcebergRestCatalogTest {

    @Override
    protected String engine() {
        return "native";
    }

    @Test
    void writersCommittingThroughTheCatalogAtOnceLoseNothingAndTheLibraryReadsTheResult() throws Exception {
        plugin();                                                   // the warehouse bucket exists
        Map<String, String> s = settings();
        s.put("domain", "nat");
        try (Catalog c = Catalogs.open(s)) {
            NativeLayout.open(c, "trade", Map.of("mtm", true), IcebergLayout.DEFAULT_ROW_GROUP_BYTES, 100);
        }
        int writers = 2;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
            List<Future<?>> done = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int me = w;
                done.add(pool.submit(() -> {
                    try (Catalog c = Catalogs.open(s)) {
                        Table t = c.load("trade").orElseThrow();
                        start.await();
                        for (int i = 0; i < 3; i++) {
                            LocalDate day = LocalDate.of(2026, 9, 1 + me * 3 + i);
                            Operations.append(t, NativeLayout.writeDay(t, day, NativeCommitTest.rows("W" + me, 4).iterator(), 100), Map.of());
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get();
            }
        }
        try (IcebergLake lake = IcebergLake.of(s)) {
            org.apache.iceberg.Table t = lake.load("trade").orElseThrow();
            assertThat(IcebergLayoutTest.rows(t)).isEqualTo(writers * 3 * 4L);
            assertThat(t.snapshots()).hasSize(writers * 3);
        }
    }
}
