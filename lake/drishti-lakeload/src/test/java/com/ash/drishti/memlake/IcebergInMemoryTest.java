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
package com.ash.drishti.memlake;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.iceberg.expr.Expr;
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.manifest.DataFile;
import com.ash.drishti.iceberg.read.ParquetRowReader;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Schema;
import com.ash.drishti.iceberg.types.Type;
import com.ash.drishti.iceberg.write.ParquetDataWriter;
import com.ash.drishti.lakeio.ParquetInput;
import com.ash.drishti.memlake.store.MemoryStorage;
import com.ash.drishti.memlake.store.MemoryStores;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** An Iceberg data file in a memory warehouse (docs/architecture/DATA_ARCHITECTURE.md section 7): written and read through the same Storage. */
class IcebergInMemoryTest {

    private static final Schema SCHEMA = new Schema(0, List.of(NestedField.required(1, "id", Type.Primitive.STRING),
            NestedField.optional(2, "doc", Type.Primitive.STRING), NestedField.required(3, "business_date", Type.Primitive.DATE),
            NestedField.optional(4, "mtm", Type.Primitive.DOUBLE)));

    @AfterEach
    void drop() {
        MemoryStores.remove("ice");
    }

    @Test
    void aDataFileIsWrittenAndReadInMemory() throws Exception {
        MemoryStorage store = MemoryStores.define("ice", 32L << 20, 64 * 1024, "");
        FileAccess io = FileAccess.local();
        assertThat(FileAccess.supports("mem://ice/wh/t")).isTrue();
        String path = "mem://ice/wh/news/data/f-1.parquet";
        DataFile f;
        try (ParquetDataWriter w = new ParquetDataWriter(io, path, SCHEMA, Map.of("write.parquet.compression-codec", "zstd"), 0,
                new Object[] {20_000}, 1)) {
            for (int i = 0; i < 300; i++) {
                w.write(new Object[] {String.format("N-%04d", i), "{\"t\":\"headline " + i + "\"}", 20_000, i * 1.0});
            }
            f = w.finish();
        }
        assertThat(f.recordCount()).isEqualTo(300);
        assertThat(store.status(path).size()).isEqualTo(f.fileSizeInBytes());
        int found = 0;
        try (ParquetRowReader r = new ParquetRowReader(ParquetInput.of(io.storage(path), path, f.fileSizeInBytes()), path, f.fileSizeInBytes(),
                SCHEMA.columns(), null, Map.of(), Expr.eq(1, "N-0123"), io.codecs(), false)) {
            while (r.hasNext()) {
                Object[] row = r.next().values();
                if ("N-0123".equals(row[0])) {
                    assertThat(row[1]).isEqualTo("{\"t\":\"headline 123\"}");
                    found++;
                }
            }
        }
        assertThat(found).isEqualTo(1);
    }
}
