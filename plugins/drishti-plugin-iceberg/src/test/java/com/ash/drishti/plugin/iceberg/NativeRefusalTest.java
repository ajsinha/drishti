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

import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.iceberg.catalog.Catalogs;
import com.ash.drishti.iceberg.expr.Expr;
import com.ash.drishti.iceberg.types.UnsupportedFeature;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.Table;
import org.junit.jupiter.api.Test;

/**
 * What drishti-iceberg does not read it refuses by name, never reads wrongly: an ORC or Avro data file (naming the
 * file), an encrypted data file, a root it cannot reach on the native engine (naming the library engine).
 */
class NativeRefusalTest {

    static Table table(Path root, String kind) {
        IcebergLake lake = IcebergLake.of(Map.of("root", root.toString(), "domain", "desk"));
        return IcebergLayout.open(lake, kind, Map.of("mtm", true), IcebergLayout.DEFAULT_ROW_GROUP_BYTES, 1000);
    }

    @Test
    void orcAndEncryptedDataFilesAreRefusedNamingTheFile() throws Exception {
        Path root = Files.createTempDirectory("drishti-iceberg-refusal");
        Table orc = table(root, "orcs");
        orc.newAppend().appendFile(DataFiles.builder(orc.spec()).withPath(root + "/desk/orcs/data/part-0.orc").withFormat(FileFormat.ORC)
                .withPartition(IcebergLayout.partition(orc.spec(), java.time.LocalDate.of(2026, 9, 30))).withRecordCount(1).withFileSizeInBytes(10)
                .build()).commit();
        Table enc = table(root, "secret");
        enc.newAppend().appendFile(DataFiles.builder(enc.spec()).withPath(root + "/desk/secret/data/part-0.parquet").withFormat(FileFormat.PARQUET)
                .withPartition(IcebergLayout.partition(enc.spec(), java.time.LocalDate.of(2026, 9, 30))).withRecordCount(1).withFileSizeInBytes(10)
                .withEncryptionKeyMetadata(ByteBuffer.wrap(new byte[] {1, 2, 3})).build()).commit();
        try (Catalog c = Catalogs.open(Map.of("root", root.toString(), "domain", "desk")); ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var t = c.load("orcs").orElseThrow();
            assertThatThrownBy(() -> t.plan(t.currentSnapshot().orElseThrow(), Expr.TRUE, pool)).isInstanceOf(UnsupportedFeature.class)
                    .hasMessageContaining("ORC data files").hasMessageContaining("part-0.orc").hasMessageContaining("engine: library");
            var s = c.load("secret").orElseThrow();
            assertThatThrownBy(() -> s.plan(s.currentSnapshot().orElseThrow(), Expr.TRUE, pool)).isInstanceOf(UnsupportedFeature.class)
                    .hasMessageContaining("encrypted data files").hasMessageContaining("part-0.parquet");
        }
    }

    @Test
    void aStoreTheNativeEngineCannotReachNamesTheLibraryEngine() {
        assertThatThrownBy(() -> Catalogs.open(Map.of("root", "abfs://c@acct.dfs.core.windows.net/lake"))).hasMessageContaining("engine: library");
        assertThatThrownBy(() -> IcebergEngine.choose("native", null, "gs://bucket/lake", false)).hasMessageContaining("engine: library");
        assertThat(IcebergEngine.choose(null, null, "gs://bucket/lake", true)).isEqualTo(IcebergEngine.LIBRARY);   // the default, a root it cannot read
        assertThat(IcebergEngine.choose(null, null, "C:\\lake", true)).isEqualTo(IcebergEngine.NATIVE);
        assertThat(IcebergEngine.choose(null, null, "/srv/lake", false)).isEqualTo(IcebergEngine.NATIVE);         // the default on Linux too
        assertThat(IcebergEngine.choose("auto", null, "/srv/lake", false)).isEqualTo(IcebergEngine.LIBRARY);
        assertThat(IcebergEngine.choose("auto", null, "C:\\lake", true)).isEqualTo(IcebergEngine.NATIVE);
        assertThat(IcebergEngine.choose(null, "native", "/srv/lake", false)).isEqualTo(IcebergEngine.NATIVE);         // DRISHTI_ICEBERG_ENGINE
        assertThat(IcebergEngine.choose("library", "native", "/srv/lake", true)).isEqualTo(IcebergEngine.LIBRARY);    // the setting wins
        assertThatThrownBy(() -> IcebergEngine.choose("hadoop", null, "/srv/lake", false)).hasMessageContaining("native, library or auto");
    }
}
