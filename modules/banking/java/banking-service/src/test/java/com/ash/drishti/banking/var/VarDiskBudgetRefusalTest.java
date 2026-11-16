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
package com.ash.drishti.banking.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.banking.compute.var.VarBook;
import com.ash.drishti.server.disk.DiskBudget;
import com.ash.drishti.server.disk.DiskProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The disk is the mapped tier's hard limit (VAR_GUIDE.md section 26.8): a first build reserves a floor even with no hot file to measure
 * by ({@code estimate()} is never 0), and while the rows stream in the disk is checked every 64 MB; a date that would leave the disk
 * under the guard is refused with {@code DRS-5204} naming the bytes needed and free, and nothing is left in the tier's folder.
 */
class VarDiskBudgetRefusalTest {

    @TempDir
    Path dir;

    /** A disk whose usable space the test sets (the {@code FileStore} seam). */
    static final class SmallDisk extends DiskBudget {
        volatile long usable = 1L << 40;

        SmallDisk(Path root) {
            super(new DiskProperties(0.0, 0.0, null, null, null), root);
        }

        @Override
        public long usableBytes(Path p) {
            return usable;
        }
    }

    @Test
    void aFirstBuildReservesAFloor() {
        VarTier t = new VarTier(new VarProperties.Tier("mapped", dir.resolve("hot").toString(), 60, false, 0, 0.0)).start();
        assertThat(t.estimate()).as("no hot file yet: a floor, not 0").isEqualTo(VarTier.FIRST_BUILD_BYTES);
        SmallDisk disk = new SmallDisk(dir);
        disk.usable = 100L << 20;                                        // under the floor: refused before anything is written
        t.budget(disk);
        assertThatThrownBy(() -> t.target(LocalDate.of(2026, 9, 30), "0123456789abcdef0123"))
                .isInstanceOfSatisfying(DrishtiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DISK_GUARD));
        t.close();
    }

    @Test
    void theDiskRunningOutWhileTheRowsStreamInRefusesTheDateAndLeavesNothing() throws Exception {
        Path hot = dir.resolve("hot");
        VarTier t = new VarTier(new VarProperties.Tier("mapped", hot.toString(), 60, false, 0, 0.0)).start();
        SmallDisk disk = new SmallDisk(dir);
        t.budget(disk);
        LocalDate d = LocalDate.of(2026, 9, 30);
        VarBook.HotTarget target = t.target(d, "0123456789abcdef0123");
        disk.usable = 50L << 20;                                         // the disk fills while the date loads
        List<LocalDate> days = new ArrayList<>();
        for (int i = 0; i < 2048; i++) {
            days.add(LocalDate.of(2018, 1, 1).plusDays(i));
        }
        VarBook.Builder b = new VarBook.Builder(d, "HS", days, Long.MAX_VALUE, target);
        double[] row = new double[days.size()];
        String[] texts = new String[VarBook.TRADE_TEXTS.size()];
        texts[4] = "BOOK-1";
        double[] nums = new double[VarBook.TRADE_NUMBERS.size()];
        int[] added = {0};
        assertThatThrownBy(() -> {
            for (int i = 0; i < 6000; i++) {                             // 6,000 rows of 2,048 values: 98 MB of float64, checked at 64 MB
                b.trade("T-" + i, row, row.length, texts, nums);
                added[0]++;
            }
        }).isInstanceOfSatisfying(DrishtiException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.VAR_CAPACITY);
            assertThat(e.getMessage()).contains("DRS-5204").contains("needs about").contains("GB free in").contains(hot.toString());
        });
        assertThat(added[0]).as("stopped at the first check").isLessThan(6000);
        b.discard();
        try (Stream<Path> s = Files.walk(hot)) {
            assertThat(s.filter(Files::isRegularFile).toList()).as("nothing left in the tier's folder").isEmpty();
        }
        t.close();
    }
}
