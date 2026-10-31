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
package com.ash.drishti.server.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.caches.MemoryBudget;
import com.ash.drishti.server.disk.DiskBudget;
import com.ash.drishti.server.disk.DiskProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The platform gauges sampled from the disk budget, the memory budget and the process's mappings. */
class PlatformMetricsTest {

    @TempDir Path dir;

    @Test
    void theDiskFoldersAndTheMemoryPoolsBecomeSeriesAndAFolderWithoutACapHasNoLimit() throws Exception {
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        DiskBudget disk = new DiskBudget(DiskProperties.defaults(), dir);
        Files.createDirectories(dir.resolve("hot"));
        Files.write(dir.resolve("hot/a.bin"), new byte[4096]);
        Files.createDirectories(dir.resolve("dumps"));
        disk.register(DiskBudget.measured("var/hot", dir.resolve("hot"), () -> 1L << 20));
        disk.register(DiskBudget.measured("dumps", dir.resolve("dumps"), () -> 0L));
        MemoryBudget memory = new MemoryBudget(0.5, Map.of(), 0.85, 1L << 30);
        PlatformMetrics m = new PlatformMetrics(r, () -> null, () -> memory, () -> disk, null, () -> null, () -> null, Clock.systemUTC());
        m.sampleDisk();
        m.sampleMemory();
        assertThat(r.get("drishti.disk.folder.bytes").tag("folder", "var/hot").gauge().value()).isGreaterThanOrEqualTo(4096);
        assertThat(r.get("drishti.disk.folder.limit.bytes").tag("folder", "var/hot").gauge().value()).isEqualTo(1 << 20);
        assertThat(r.find("drishti.disk.folder.limit.bytes").tag("folder", "dumps").gauge()).isNull();
        assertThat(r.get("drishti.disk.free.bytes").gauge().value()).isPositive();
        assertThat(r.get("drishti.memory.budget.bytes").tag("pool", "all").gauge().value()).isEqualTo(1L << 29);
        assertThat(r.find("drishti.memory.budget.bytes").gauges()).hasSizeGreaterThan(1);           // the whole and each pool
    }

    @Test
    void theMappingsOfFilesAndOfDeletedFilesAreCounted() throws Exception {
        Path maps = dir.resolve("maps");
        Files.writeString(maps, """
                7f00-7f01 r--p 00000000 08:02 1 /usr/lib/x.so
                7f01-7f02 rw-p 00000000 00:00 0
                7f02-7f03 r--s 00000000 08:02 2 /data/var/hot/2026-10-30.vhot (deleted)
                7f03-7f04 rw-p 00000000 00:00 0 [heap]
                """);
        assertThat(PlatformMetrics.maps(maps)).containsExactly(2, 1);
        assertThat(PlatformMetrics.maps(dir.resolve("none"))).isNull();
    }
}
