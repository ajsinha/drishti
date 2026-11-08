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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Iceberg file paths are literal strings (docs/guides/DATA_LAKE_GUIDE.md, "Iceberg spec compliance"): a partition folder the Apache
 * library or pyiceberg names {@code g=a%2Fb} (the value {@code a/b}) or {@code g=100%25} ({@code 100%}) is that folder, in a
 * {@code file:///} path, a {@code file:/} path and through {@code relocate}; never percent-decoded into another one.
 */
class LiteralPathsTest {

    @Test
    void aFileUriWithAPercentIsThePathItSpells() {
        assertThat(FileAccess.literal("file:///lake/t/data/g=a%2Fb/f.parquet")).isEqualTo("/lake/t/data/g=a%2Fb/f.parquet");
        assertThat(FileAccess.literal("file:/lake/t/data/g=100%25/f.parquet")).isEqualTo("/lake/t/data/g=100%25/f.parquet");
        assertThat(FileAccess.literal("file:/lake/t/data/business_date=2026-11-06/f.parquet"))
                .isEqualTo("file:/lake/t/data/business_date=2026-11-06/f.parquet");
        assertThat(FileAccess.literal("s3://bucket/t/data/g=a%2Fb/f.parquet")).isEqualTo("s3://bucket/t/data/g=a%2Fb/f.parquet");
    }

    @Test
    void theLibrarysEncodedFoldersAreRead(@TempDir Path dir) throws Exception {
        FileAccess io = FileAccess.local();
        for (String folder : new String[] {"g=a%2Fb", "g=100%25", "g=a%252Fb", "g=%C3%A9%E6%BC%A2", "g=a+b", "g=..", "g=x%01y"}) {
            Path f = Files.createDirectories(dir.resolve(folder)).resolve("f.parquet");
            Files.writeString(f, folder);
            for (String path : new String[] {"file://" + f, "file:" + f, f.toString()}) {
                assertThat(new String(io.readAll(path), StandardCharsets.UTF_8)).as(path).isEqualTo(folder);
            }
        }
    }

    @Test
    void relocateComparesTheMetadatasPathsAsWritten() {
        Relocation r = Relocation.parse("/data/ice1 -> /data/ice2");
        assertThat(r.apply("file:///data/ice1/t/data/g=a%2Fb/f.parquet")).isEqualTo("file:/data/ice2/t/data/g=a%2Fb/f.parquet");
        assertThat(Relocation.parse("/data/100%25 -> /data/copy").apply("file:/data/100%25/t/f.parquet")).isEqualTo("file:/data/copy/t/f.parquet");
        assertThat(Relocation.parse("/data/100%25 -> /data/copy").apply("file:/data/100%/t/f.parquet")).isEqualTo("file:/data/100%/t/f.parquet");
    }
}
