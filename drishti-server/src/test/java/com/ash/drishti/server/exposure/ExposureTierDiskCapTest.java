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
package com.ash.drishti.server.exposure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exposure's hot files bounded in bytes (2.9.7, O5): the cap wins over the retention (PO-3): a retained month-end beyond it is deleted
 * from local disk (read from the lake when asked again).
 */
class ExposureTierDiskCapTest {

    @TempDir
    Path dir;

    static ExposureProperties props(Path hot, Double maxDiskGb) {
        return new ExposureProperties(null, null, null, null, null, null, null, null, null, null, hot.toString(), "mapped", null, true, 0, null,
                null, null, null, null, null, null, null, null, null, maxDiskGb, null);
    }

    @Test
    void theCapWinsOverTheRetention() throws Exception {
        long t = 1_000;
        for (String d : new String[] {"2026-06-30", "2026-07-31", "2026-08-31", "2026-09-30"}) {           // month-ends: all retained
            Path f = Files.createDirectories(dir.resolve(d)).resolve("run-0123456789abcdef.xhot");
            Files.write(f, new byte[100 * 1024]);
            Files.setLastModifiedTime(f, FileTime.fromMillis(t += 1_000));
        }
        ExposureTier tier = new ExposureTier(props(dir, 250.0 / 1024 / 1024)).start();
        assertThat(tier.trimTo(tier.maxDiskBytes(), null)).isEqualTo(200 * 1024);
        assertThat(tier.dates()).extracting(m -> m.get("date")).containsExactly("2026-09-30", "2026-08-31");
        assertThat(tier.purgeDate(LocalDate.of(2026, 8, 31))).isEqualTo(1);
        assertThat(props(dir, null).maxDiskGb()).isZero();
        assertThat(props(dir, null).loadThreads()).isEqualTo(2);
        assertThat(new ExposureTier(props(dir, null)).maxDiskBytes()).as("0: a quarter of the disk")
                .isEqualTo(com.ash.drishti.server.disk.DiskBudget.shareOfDisk(dir, 0.25));
    }
}
