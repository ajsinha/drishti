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

import org.junit.jupiter.api.Test;

/**
 * QA3B-04: {@code relocate} written as the docs write it (plain folders) reads the table as the {@code file:} form does; Windows folders and
 * shares, quotes and S3's Hadoop scheme names are the same folders; a pair that cannot be read is refused naming it; the pair Health offers
 * is the one to copy.
 */
class RelocationTest {

    private static final String META = "file:/data/ice1/credit/credit-limit/metadata/snap-1.avro";

    @Test
    void plainFoldersAndFileUrisAreOneForm() {
        for (String spec : new String[] {"/data/ice1 -> /data/ice2", "file:/data/ice1 -> file:/data/ice2", "file:///data/ice1/ -> /data/ice2/",
            "'/data/ice1 -> /data/ice2'", "\"/data/ice1\" -> \"/data/ice2\"", "file://localhost/data/ice1 -> /data//ice2"}) {
            assertThat(Relocation.parse(spec).apply(META)).as(spec).isEqualTo("file:/data/ice2/credit/credit-limit/metadata/snap-1.avro");
        }
        // a metadata path written as file:/// by another writer matches too
        assertThat(Relocation.parse("/data/ice1 -> /data/ice2").apply("file:///data/ice1/t/data/f.parquet")).isEqualTo("file:/data/ice2/t/data/f.parquet");
    }

    @Test
    void aFolderMatchesWholeFoldersOnly() {
        Relocation r = Relocation.parse("/data/ice1 -> /data/ice2");
        assertThat(r.apply("file:/data/ice10/t/metadata/v1.metadata.json")).isEqualTo("file:/data/ice10/t/metadata/v1.metadata.json");
        assertThat(r.matches("file:/data/ice1/t")).isTrue();
        assertThat(r.matches("file:/data/ice10/t")).isFalse();
    }

    @Test
    void windowsFoldersAndSharesAreTheMetadatasForm() {
        String meta = "file:/C:/lake/credit/t/metadata/snap-1.avro";
        for (String spec : new String[] {"C:\\lake -> D:\\lake", "c:/lake/ -> D:/lake", "file:///C:/lake -> file:/D:/lake", "'C:\\lake -> D:\\lake'"}) {
            assertThat(Relocation.parse(spec).apply(meta)).as(spec).isEqualTo("file:/D:/lake/credit/t/metadata/snap-1.avro");
        }
        assertThat(Relocation.parse("\\\\fs\\share\\lake -> E:\\copy").apply("file://fs/share/lake/t/data/f.parquet"))
                .isEqualTo("file:/E:/copy/t/data/f.parquet");
        assertThat(Relocation.parse("C:\\lake -> D:\\lake").toString()).isEqualTo("C:/lake -> D:/lake");
    }

    @Test
    void s3UnderAnyHadoopNameIsOneBucket() {
        Relocation r = Relocation.parse("s3://prod/wh -> /copy/wh");
        assertThat(r.apply("s3a://prod/wh/desk/trade/data/f.parquet")).isEqualTo("file:/copy/wh/desk/trade/data/f.parquet");
        assertThat(r.apply("s3://prod/wh/desk/trade/data/f.parquet")).isEqualTo("file:/copy/wh/desk/trade/data/f.parquet");
        assertThat(r.apply("s3://other/wh/x")).isEqualTo("s3://other/wh/x");
    }

    @Test
    void aPairThatCannotBeReadIsRefusedNamingIt() {
        assertThatThrownBy(() -> Relocation.parse("/data/ice1 /data/ice2")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'/data/ice1 /data/ice2'").hasMessageContaining("<old folder> -> <new folder>");
        assertThatThrownBy(() -> Relocation.parse(" -> /data/ice2")).hasMessageContaining("the old folder is missing");
        assertThatThrownBy(() -> Relocation.parse("/a -> ")).hasMessageContaining("the new folder is missing");
        assertThatThrownBy(() -> Relocation.parse("ice1 -> /data/ice2")).hasMessageContaining("'ice1' is not an absolute folder");
        assertThatThrownBy(() -> Relocation.parse("/a -> /b -> /c")).hasMessageContaining("one ' -> '");
        assertThat(Relocation.parse("  ").isEmpty()).isTrue();
        assertThat(Relocation.parse("/a -> /b; ; /c -> /d").pairs()).hasSize(2);
    }

    @Test
    void theSuggestedPairIsTheFoldersThatDifferInThePlainForm() {
        assertThat(Relocation.suggest("file:/data/ice1/credit/credit-limit", "/data/ice2/credit/credit-limit")).hasValue("/data/ice1 -> /data/ice2");
        assertThat(Relocation.suggest("file:/C:/lake/credit/t", "D:\\lake\\credit\\t")).hasValue("C:/lake -> D:/lake");
        assertThat(Relocation.suggest("file:/a/t", "/a/t")).isEmpty();
        assertThat(Relocation.suggest("s3a://prod/wh/desk/t", "/copy/desk/t")).hasValue("s3://prod/wh -> /copy");
        // what it suggests reads the table
        String pair = Relocation.suggest("file:/data/ice1/credit/credit-limit", "/data/ice2/credit/credit-limit").orElseThrow();
        assertThat(Relocation.parse("'" + pair + "'").apply(META)).startsWith("file:/data/ice2/credit/credit-limit/");
    }
}
