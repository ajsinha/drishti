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

import com.ash.drishti.lakeio.RangeCursor;
import com.ash.drishti.lakeio.RangeReader;
import com.ash.drishti.lakeio.Storage;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * The streams Kernel reads through, over a {@link RangeReader}: Kernel's
 * {@link io.delta.kernel.defaults.engine.fileio.SeekableInputStream} (commit JSON, deletion vectors). Parquet's
 * streams (data and checkpoint files) are the shared module's ({@link com.ash.drishti.lakeio.ParquetInput}). Each
 * stream is used by one thread.
 */
final class Streams {

    private Streams() {}

    /** Kernel's view of a file, opened on demand. */
    static final class KernelFile implements io.delta.kernel.defaults.engine.fileio.InputFile {
        private final Storage storage;
        private final String path;
        private final long knownLength;

        KernelFile(Storage storage, String path, long knownLength) {
            this.storage = storage;
            this.path = path;
            this.knownLength = knownLength;
        }

        @Override
        public long length() throws IOException {
            if (knownLength >= 0) {
                return knownLength;
            }
            try (RangeReader r = storage.open(path, -1)) {
                return r.length();
            }
        }

        @Override
        public String path() {
            return path;
        }

        @Override
        public io.delta.kernel.defaults.engine.fileio.SeekableInputStream newStream() throws IOException {
            return new KernelStream(storage.open(path, knownLength));
        }
    }

    /** Kernel's seekable stream. */
    static final class KernelStream extends io.delta.kernel.defaults.engine.fileio.SeekableInputStream {
        private final RangeCursor cursor;

        KernelStream(RangeReader reader) {
            this.cursor = new RangeCursor(reader);
        }

        @Override
        public long getPos() {
            return cursor.position();
        }

        @Override
        public void seek(long newPos) {
            cursor.seek(newPos);
        }

        @Override
        public void readFully(byte[] b, int off, int len) throws IOException {
            cursor.readFully(ByteBuffer.wrap(b, off, len));
        }

        @Override
        public int read() throws IOException {
            return cursor.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return cursor.read(ByteBuffer.wrap(b, off, len));
        }

        @Override
        public void close() throws IOException {
            cursor.close();
        }
    }
}
