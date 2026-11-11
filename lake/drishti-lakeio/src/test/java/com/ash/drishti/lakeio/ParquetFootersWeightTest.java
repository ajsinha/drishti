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
package com.ash.drishti.lakeio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.parquet.column.Encoding;
import org.apache.parquet.column.statistics.Statistics;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.metadata.ColumnPath;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.hadoop.metadata.FileMetaData;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;

/**
 * The footer cache is weighed in bytes by column chunks (2.9.9): a footer of many row groups and columns weighs what it holds (about 700
 * bytes a chunk), so the cache's 64 MB bound is a bound in bytes, not in row groups of any width.
 */
class ParquetFootersWeightTest {

    private static ParquetMetadata footer(int groups, int columns) {
        MessageType schema = Types.buildMessage().required(PrimitiveTypeName.INT64).named("c0").named("t");
        List<BlockMetaData> blocks = new ArrayList<>();
        for (int g = 0; g < groups; g++) {
            BlockMetaData b = new BlockMetaData();
            for (int c = 0; c < columns; c++) {
                b.addColumn(ColumnChunkMetaData.get(ColumnPath.get("c" + c), PrimitiveTypeName.INT64, CompressionCodecName.UNCOMPRESSED,
                        Set.of(Encoding.PLAIN), Statistics.createStats(schema.getType(0).asPrimitiveType()), 4, 0, 10, 100, 100));
            }
            blocks.add(b);
        }
        return new ParquetMetadata(new FileMetaData(schema, Map.of(), "test"), blocks);
    }

    @Test
    void aFooterWeighsItsColumnChunks() {
        int narrow = ParquetFooters.weight(footer(10, 2));
        int wide = ParquetFooters.weight(footer(10, 34));
        assertThat(narrow).isEqualTo(ParquetFooters.FOOTER_BYTES + 10 * (64 + 2 * ParquetFooters.CHUNK_BYTES));
        assertThat(wide).as("the same row groups, 17 times the columns").isEqualTo(ParquetFooters.FOOTER_BYTES + 10 * (64 + 34 * ParquetFooters.CHUNK_BYTES));
        // the 1,000,000-position benchmark's lake: 16,487 row groups of 34 columns weighed about 385 MB on the heap
        long bench = (long) ParquetFooters.FOOTER_BYTES + 16_487L * (64 + 34L * ParquetFooters.CHUNK_BYTES);
        assertThat(bench).isBetween(350L << 20, 420L << 20);
        assertThat(ParquetFooters.LIMIT_BYTES).as("the default bound, -Ddrishti.parquet.footer-cache-mb").isEqualTo(64L << 20);
        assertThat(ParquetFooters.weightBytes()).isLessThanOrEqualTo(ParquetFooters.LIMIT_BYTES);
    }
}
