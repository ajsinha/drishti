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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Iceberg connector's document disk cache never serves stale data (2.9.7, as {@code DeltaDocStalenessTest}): a position of the same
 * date committed again is read new right after a data-load signal; without a signal it may be the old one within {@code refresh-seconds}
 * and is always the new one after that; an as-of read at the old snapshot reads the old one. Documents are keyed by the day's
 * fingerprint (its data and delete files). Also: the id-map TTL's minimum is enforced at start.
 */
class IcebergDocStalenessTest {

    static final LocalDate D1 = LocalDate.of(2026, 10, 21);
    static final int REFRESH_SECONDS = 2;

    @TempDir
    Path tmp;

    /** Commits {@code n} positions as the whole of {@link #D1}, the first one's quantity {@code quantity}. */
    private void commit(Path root, double quantity) throws Exception {
        List<VectorLake.Pos> ps = new java.util.ArrayList<>(VectorLake.positions(4, 1));
        VectorLake.Pos p0 = ps.get(0);
        ps.set(0, new VectorLake.Pos(p0.id(), p0.desk(), p0.book(), quantity, p0.pnl()));
        VectorLake.loadIceberg(tmp, root, "native", Map.of(D1, ps));
    }

    private IcebergSourcePlugin plugin(Path root, Map<String, String> extra) throws Exception {
        Map<String, String> s = new HashMap<>(VectorLake.settings(root, "native", tmp.resolve("cache")));
        s.put("refresh-seconds", String.valueOf(REFRESH_SECONDS));
        s.put("doc-cache-mb", "0");
        s.putAll(extra);
        IcebergSourcePlugin p = new IcebergSourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    private static double quantity(IcebergSourcePlugin p, AsOf asOf) throws Exception {
        return p.fetch(EntityRef.of(VectorLake.KIND, "POS-00000"), asOf).orElseThrow().data().get("QUANTITY").asDouble();
    }

    @Test
    void aPositionCommittedAgainIsReadNewAfterTheSignalOrTheRefreshAndOldOnlyAsOfTheOldSnapshot() throws Exception {
        Path root = tmp.resolve("lake");
        commit(root, 1);
        IcebergSourcePlugin p = plugin(root, Map.of());
        try {
            assertThat(quantity(p, AsOf.of(D1))).isEqualTo(1);
            assertThat(quantity(p, AsOf.of(D1))).isEqualTo(1);
            assertThat(p.cacheStats()).as("the disk cache is open").containsKey("docsOnDiskMB");
            Thread.sleep(50);
            Instant atFirst = Instant.now();
            Thread.sleep(50);

            // (i) a data-load signal: the next read is the new document
            commit(root, 2);
            p.refreshVersions();
            assertThat(quantity(p, AsOf.of(D1))).isEqualTo(2);
            Thread.sleep(50);
            Instant atSecond = Instant.now();
            Thread.sleep(50);

            // (ii) no signal: within refresh-seconds the old one may still answer; after it, always the new one
            commit(root, 3);
            assertThat(quantity(p, AsOf.of(D1))).isIn(2.0, 3.0);
            Thread.sleep(REFRESH_SECONDS * 2000L + 500);                   // the refresh runs every refresh-seconds, then on a virtual thread
            for (int i = 0; i < 5; i++) {
                assertThat(quantity(p, AsOf.of(D1))).as("after refresh-seconds, never the cached old document").isEqualTo(3);
            }

            // (iii) as of the older snapshots: their own documents, never the newest
            assertThat(quantity(p, new AsOf(D1, atFirst))).isEqualTo(1);
            assertThat(quantity(p, new AsOf(D1, atSecond))).isEqualTo(2);
            assertThat(quantity(p, AsOf.of(D1))).isEqualTo(3);
            assertThat(((Number) p.cacheStats().get("idMapsReplaced")).longValue()).as("earlier maps of the date: replaced").isGreaterThan(0);
        } finally {
            p.close();
        }
    }

    @Test
    void anIdMapTtlUnderADayIsRefusedAtStartNamingTheSettingAndTheMinimum() throws Exception {
        Path root = tmp.resolve("lake");
        commit(root, 1);
        assertThatThrownBy(() -> plugin(root, Map.of("id-map-disk.ttl", "23h"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("risk-store").hasMessageContaining("id-map-disk.ttl").hasMessageContaining("minimum is 24h");
        IcebergSourcePlugin p = plugin(root, Map.of("id-map-disk.ttl", "7d"));
        try {
            assertThat(p.cacheStats()).containsEntry("idMapTtl", "PT168H");
        } finally {
            p.close();
        }
    }
}
