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
package com.ash.drishti.server.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.disk.DiskBudget;
import com.ash.drishti.server.disk.DiskProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The VaR hot files bounded in bytes and the free-space guard (2.9.7, RESOURCES_AND_SCALE.md section 7.2, O5): the least recently used
 * files go beyond {@code tier.max-disk-gb} (never an open one), a date's files are purged on request, and a build that would leave the disk
 * under the guard is refused with {@code DRS-5218} before anything is written.
 */
class VarTierDiskCapTest {

    @TempDir
    Path dir;

    private Path file(String folder, int kb, long usedAt) throws Exception {
        Path f = Files.createDirectories(dir.resolve(folder)).resolve("inputs-0123456789abcdef.vhot");
        Files.write(f, new byte[kb * 1024]);
        Files.setLastModifiedTime(f, FileTime.fromMillis(usedAt));
        return f;
    }

    private VarTier tier(double maxGb) {
        return new VarTier(new VarProperties.Tier("mapped", dir.toString(), 60, true, 0, maxGb)).start();
    }

    @Test
    void theLeastRecentlyUsedGoBeyondTheCap() throws Exception {
        Path d1 = file("2026-09-28", 100, 1_000);
        Path d1eur = file("2026-09-28-EUR", 100, 1_500);
        Path d2 = file("2026-09-29", 100, 2_000);
        Path d3 = file("2026-09-30", 100, 3_000);
        VarTier t = tier(250.0 / 1024 / 1024);                          // 250 KB
        assertThat(t.diskBytes()).isEqualTo(400 * 1024);
        assertThat(t.trimTo(t.maxDiskBytes(), null)).isEqualTo(200 * 1024);
        assertThat(d1).doesNotExist();
        assertThat(d1eur).doesNotExist();
        assertThat(d2).exists();
        assertThat(d3).exists();
        assertThat(t.dates()).extracting(m -> m.get("date")).containsExactly("2026-09-30", "2026-09-29");
        assertThat(t.dates().get(0)).containsEntry("state", "disk").containsEntry("sizeBytes", 100L * 1024).containsEntry("digest", "0123456789abcdef");
        assertThat(t.purgeDate(LocalDate.of(2026, 9, 29))).isEqualTo(1);
        assertThat(d2).doesNotExist();
        assertThat(t.estimate()).isEqualTo(3 * 100 * 1024);
        assertThat(tier(0).maxDiskBytes()).as("0: half the disk").isEqualTo(DiskBudget.shareOfDisk(dir, 0.5));
    }

    @Test
    void aBuildThatWouldFillTheDiskIsRefusedWithAReason() throws Exception {
        file("2026-09-30", 10, 1_000);
        DiskBudget tight = new DiskBudget(new DiskProperties(0.0, 1e9, null, null, null), dir);        // a guard of 1,000 PB: never fits
        VarTier t = tier(1).budget(tight);
        assertThatThrownBy(() -> t.target(LocalDate.of(2026, 10, 1), VarDate.USD, "ab".repeat(32))).isInstanceOf(DrishtiException.class)
                .satisfies(e -> assertThat(((DrishtiException) e).errorCode()).isEqualTo(ErrorCode.DISK_GUARD))
                .hasMessageContaining("not enough free disk to build the VaR hot file of 2026-10-01").hasMessageContaining("needed")
                .hasMessageContaining("guard");
        assertThat(dir.resolve("2026-10-01")).as("nothing written").doesNotExist();
        DiskBudget roomy = new DiskBudget(DiskProperties.defaults(), dir);
        VarTier ok = tier(1).budget(roomy);
        if (DiskBudget.usable(dir) > roomy.guardBytes(dir) + (1L << 30)) {    // a workstation with room: the build goes ahead
            assertThat(ok.target(LocalDate.of(2026, 10, 1), VarDate.USD, "ab".repeat(32)).file().getFileName().toString()).startsWith("inputs-");
        }
        assertThat(roomy.describe()).containsKeys("freeBytes", "guardBytes", "folders");
        assertThat(roomy.folders()).extracting(DiskBudget.Folder::name).contains("var/hot");
    }
}
