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
package com.ash.drishti.deltalake;

import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.engine.FileReadResult;
import io.delta.kernel.engine.ParquetHandler;
import io.delta.kernel.expressions.Column;
import io.delta.kernel.expressions.Predicate;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.DataFileStatus;
import io.delta.kernel.utils.FileStatus;
import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Kernel's Parquet reads (data files and checkpoints) through {@link ParquetBatches}: files one after another, each
 * batch tagged with its file. Writes are refused: the engine only reads. Stateless and thread-safe.
 */
final class NativeParquetHandler implements ParquetHandler {

    /** Kernel's key for rows per batch, as its default engine reads it. */
    static final String BATCH_SIZE = "delta.kernel.default.parquet.reader.batch-size";

    private final NativeFileIO io;
    private final int batchSize;

    NativeParquetHandler(NativeFileIO io) {
        this.io = io;
        this.batchSize = io.getConf(BATCH_SIZE).map(Integer::valueOf).orElse(1024);
        if (batchSize <= 0) {
            throw new IllegalArgumentException("invalid Parquet reader batch size: " + batchSize);
        }
    }

    @Override
    public CloseableIterator<FileReadResult> readParquetFiles(CloseableIterator<FileStatus> files, StructType physicalSchema,
            Optional<Predicate> predicate) {
        return new CloseableIterator<>() {
            private CloseableIterator<ColumnarBatch> current;
            private String currentPath;

            @Override
            public boolean hasNext() {
                while (current == null || !current.hasNext()) {
                    Utils.closeCloseables(current);
                    current = null;
                    if (!files.hasNext()) {
                        return false;
                    }
                    FileStatus f = files.next();
                    current = new ParquetBatches(io, f, physicalSchema, predicate, batchSize);
                    currentPath = f.getPath();
                }
                return true;
            }

            @Override
            public FileReadResult next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return new FileReadResult(current.next(), currentPath);
            }

            @Override
            public void close() throws IOException {
                Utils.closeCloseables(current, files);
            }
        };
    }

    @Override
    public CloseableIterator<DataFileStatus> writeParquetFiles(String directoryPath, CloseableIterator<FilteredColumnarBatch> dataIter,
            List<Column> statsColumns) {
        throw new UnsupportedOperationException(NativeFileIO.READ_ONLY);
    }

    @Override
    public void writeParquetFileAtomically(String filePath, CloseableIterator<FilteredColumnarBatch> data) {
        throw new UnsupportedOperationException(NativeFileIO.READ_ONLY);
    }
}
