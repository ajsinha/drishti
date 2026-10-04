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

import io.delta.kernel.data.FilteredColumnarBatch;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.engine.ExpressionHandler;
import io.delta.kernel.engine.FileReadResult;
import io.delta.kernel.engine.FileSystemClient;
import io.delta.kernel.engine.JsonHandler;
import io.delta.kernel.engine.ParquetHandler;
import io.delta.kernel.expressions.Column;
import io.delta.kernel.expressions.Predicate;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.DataFileStatus;
import io.delta.kernel.utils.FileStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * The Hadoop (Kernel default) engine with one difference: Parquet files whose pages use the deprecated {@code LZ4} codec
 * are read by the native engine's decoder. Hadoop's LZ4 codec reads only Hadoop-framed pages, while Arrow, pyarrow and
 * parquet-cpp wrote raw LZ4 blocks under the same codec name, and parquet-java gives no way to plug another decoder into the
 * reader Kernel builds. Every other file (and every other call) goes to the Hadoop engine. Files the native engine cannot
 * reach (abfs://, gs://, hdfs://) are not routed.
 *
 * <p>Stateless apart from the native engine's per-file footer memory; safe for any number of threads.
 */
public final class Lz4RoutingEngine implements Engine, AutoCloseable {

    private final Engine hadoop;
    private final NativeEngine nativeEngine;
    private final ParquetHandler parquet;

    /**
     * @param hadoop Kernel's default engine over Hadoop's file systems
     * @param nativeEngine the native engine for the same lake's settings
     */
    public Lz4RoutingEngine(Engine hadoop, NativeEngine nativeEngine) {
        this.hadoop = hadoop;
        this.nativeEngine = nativeEngine;
        this.parquet = new Routed();
    }

    @Override
    public ExpressionHandler getExpressionHandler() {
        return hadoop.getExpressionHandler();
    }

    @Override
    public JsonHandler getJsonHandler() {
        return hadoop.getJsonHandler();
    }

    @Override
    public FileSystemClient getFileSystemClient() {
        return hadoop.getFileSystemClient();
    }

    @Override
    public ParquetHandler getParquetHandler() {
        return parquet;
    }

    @Override
    public void close() {
        nativeEngine.close();
    }

    @Override
    public String toString() {
        return "Lz4RoutingEngine[" + hadoop + "]";
    }

    /** Per file: the native decoder for LZ4 files, the Hadoop reader for the rest. */
    private final class Routed implements ParquetHandler {

        @Override
        public CloseableIterator<FileReadResult> readParquetFiles(CloseableIterator<FileStatus> files, StructType physicalSchema,
                Optional<Predicate> predicate) {
            return new CloseableIterator<>() {
                private CloseableIterator<FileReadResult> current;

                @Override
                public boolean hasNext() {
                    while (current == null || !current.hasNext()) {
                        Utils.closeCloseables(current);
                        current = null;
                        if (!files.hasNext()) {
                            return false;
                        }
                        FileStatus f = files.next();
                        Engine reader = NativeEngine.supports(f.getPath()) && nativeEngine.usesLz4(f) ? nativeEngine : hadoop;
                        try {
                            current = reader.getParquetHandler().readParquetFiles(Utils.singletonCloseableIterator(f), physicalSchema,
                                    predicate);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                    return true;
                }

                @Override
                public FileReadResult next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    return current.next();
                }

                @Override
                public void close() throws IOException {
                    Utils.closeCloseables(current, files);
                }
            };
        }

        @Override
        public CloseableIterator<DataFileStatus> writeParquetFiles(String directoryPath, CloseableIterator<FilteredColumnarBatch> dataIter,
                java.util.List<Column> statsColumns) throws IOException {
            return hadoop.getParquetHandler().writeParquetFiles(directoryPath, dataIter, statsColumns);
        }

        @Override
        public void writeParquetFileAtomically(String filePath, CloseableIterator<FilteredColumnarBatch> data) throws IOException {
            hadoop.getParquetHandler().writeParquetFileAtomically(filePath, data);
        }
    }
}
