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

import io.delta.kernel.data.ColumnVector;
import io.delta.kernel.data.ColumnarBatch;
import io.delta.kernel.data.Row;
import io.delta.kernel.defaults.engine.DefaultJsonHandler;
import io.delta.kernel.engine.JsonHandler;
import io.delta.kernel.expressions.Predicate;
import io.delta.kernel.types.StructType;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.IOException;
import java.util.Optional;

/**
 * Commit files and JSON strings to Kernel's column batches. The parsing is Kernel's own Jackson-based handler (its
 * classes use no Hadoop), reading through {@link NativeFileIO}; writes are refused. Thread-safe.
 */
final class NativeJsonHandler implements JsonHandler {

    private final DefaultJsonHandler delegate;

    NativeJsonHandler(NativeFileIO io) {
        this.delegate = new DefaultJsonHandler(io);
    }

    @Override
    public ColumnarBatch parseJson(ColumnVector jsonStringVector, StructType outputSchema, Optional<ColumnVector> selectionVector) {
        return delegate.parseJson(jsonStringVector, outputSchema, selectionVector);
    }

    @Override
    public CloseableIterator<ColumnarBatch> readJsonFiles(CloseableIterator<FileStatus> fileIter, StructType physicalSchema,
            Optional<Predicate> predicate) throws IOException {
        return delegate.readJsonFiles(fileIter, physicalSchema, predicate);
    }

    @Override
    public void writeJsonFileAtomically(String filePath, CloseableIterator<Row> data, boolean overwrite) {
        throw new UnsupportedOperationException(NativeFileIO.READ_ONLY);
    }
}
