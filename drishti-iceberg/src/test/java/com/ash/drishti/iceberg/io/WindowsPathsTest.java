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
package com.ash.drishti.iceberg.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.lakeio.LocalPaths;
import com.ash.drishti.lakeio.LocalStorage;
import com.ash.drishti.lakeio.S3Settings;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The paths a Windows laptop gives and an Iceberg table holds (drive letters, backslashes, UNC shares, {@code file:}
 * URIs of each form), on any operating system: every one resolves to the local disk, to the operating-system path
 * Windows opens, and through relocation; schemes the engine does not read are refused naming the library engine.
 */
class WindowsPathsTest {

    final LocalStorage windows = new LocalStorage(true);
    final FileAccess io = new FileAccess(windows, S3Settings.from(Map.of()), Map.of("s3://prod/wh", "file:/C:/copy/wh"), null);

    @Test
    void everyWindowsFormIsLocal() {
        for (String p : new String[] {"C:\\lake\\desk", "C:/lake/desk", "file:/C:/lake/desk", "file:///C:/lake/desk", "\\\\fs\\share\\lake",
            "file://fs/share/lake", "D:\\My Lake\\trade"}) {
            assertThat(FileAccess.supports(p)).as(p).isTrue();
            assertThat(io.storage(p)).as(p).isSameAs(windows);
        }
        assertThat(FileAccess.supports("s3a://bucket/lake")).isTrue();
        assertThat(FileAccess.supports("abfs://c@a.dfs.core.windows.net/lake")).isFalse();
        assertThatThrownBy(() -> io.storage("gs://bucket/lake")).hasMessageContaining("engine: library");
    }

    @Test
    void metadataPathsOpenWhereWindowsKeepsThem() {
        String table = LocalPaths.toUri("C:\\lake\\desk\\trade", true);
        assertThat(table).isEqualTo("file:/C:/lake/desk/trade");
        assertThat(LocalPaths.toLocal(table + "/metadata/v12.metadata.json", true)).isEqualTo("C:\\lake\\desk\\trade\\metadata\\v12.metadata.json");
        assertThat(LocalPaths.toLocal("file:///C:/lake/desk/trade/data/business_date=2026-09-30/f.parquet", true))
                .isEqualTo("C:\\lake\\desk\\trade\\data\\business_date=2026-09-30\\f.parquet");
        assertThat(LocalPaths.toLocal("file://fs/share/lake/t/metadata/v1.metadata.json", true)).isEqualTo("\\\\fs\\share\\lake\\t\\metadata\\v1.metadata.json");
        assertThat(LocalPaths.toUri("\\\\fs\\share\\lake\\t", true)).isEqualTo("file://fs/share/lake/t");
    }

    @Test
    void aTableCopiedFromS3IsReadWhereItWasCopiedTo() {
        String moved = io.resolve("s3://prod/wh/desk/trade/data/f.parquet");
        assertThat(moved).isEqualTo("file:/C:/copy/wh/desk/trade/data/f.parquet");
        assertThat(io.storage(moved)).isSameAs(windows);
        assertThat(LocalPaths.toLocal(moved, true)).isEqualTo("C:\\copy\\wh\\desk\\trade\\data\\f.parquet");
        assertThat(io.resolve("s3://other/x")).isEqualTo("s3://other/x");
    }
}
