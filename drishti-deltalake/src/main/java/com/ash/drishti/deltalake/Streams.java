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

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * The streams Kernel and Parquet read through, over a {@link RangeReader}: Kernel's
 * {@link io.delta.kernel.defaults.engine.fileio.SeekableInputStream} (commit JSON, deletion vectors) and Parquet's
 * {@link org.apache.parquet.io.SeekableInputStream} (data and checkpoint files). Each stream is used by one thread.
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

    /** Parquet's view of a file. */
    static final class ParquetFile implements org.apache.parquet.io.InputFile {
        private final Storage storage;
        private final String path;
        private final long knownLength;

        ParquetFile(Storage storage, String path, long knownLength) {
            this.storage = storage;
            this.path = path;
            this.knownLength = knownLength;
        }

        @Override
        public long getLength() throws IOException {
            if (knownLength >= 0) {
                return knownLength;
            }
            try (RangeReader r = storage.open(path, -1)) {
                return r.length();
            }
        }

        @Override
        public org.apache.parquet.io.SeekableInputStream newStream() throws IOException {
            return new ParquetStream(storage.open(path, knownLength));
        }

        @Override
        public String toString() {
            return path;
        }
    }

    /** Kernel's seekable stream. */
    static final class KernelStream extends io.delta.kernel.defaults.engine.fileio.SeekableInputStream {
        private final Cursor cursor;

        KernelStream(RangeReader reader) {
            this.cursor = new Cursor(reader);
        }

        @Override
        public long getPos() {
            return cursor.pos;
        }

        @Override
        public void seek(long newPos) {
            cursor.pos = newPos;
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
            cursor.reader.close();
        }
    }

    /** Parquet's seekable stream. */
    static final class ParquetStream extends org.apache.parquet.io.SeekableInputStream {
        private final Cursor cursor;

        ParquetStream(RangeReader reader) {
            this.cursor = new Cursor(reader);
        }

        @Override
        public long getPos() {
            return cursor.pos;
        }

        @Override
        public void seek(long newPos) {
            cursor.pos = newPos;
        }

        @Override
        public void readFully(byte[] bytes) throws IOException {
            cursor.readFully(ByteBuffer.wrap(bytes));
        }

        @Override
        public void readFully(byte[] bytes, int start, int len) throws IOException {
            cursor.readFully(ByteBuffer.wrap(bytes, start, len));
        }

        @Override
        public int read(ByteBuffer buf) throws IOException {
            return cursor.read(buf);
        }

        @Override
        public void readFully(ByteBuffer buf) throws IOException {
            cursor.readFully(buf);
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
            cursor.reader.close();
        }
    }

    /** A position over a range reader. */
    private static final class Cursor {
        final RangeReader reader;
        long pos;

        Cursor(RangeReader reader) {
            this.reader = reader;
        }

        int read() throws IOException {
            ByteBuffer one = ByteBuffer.allocate(1);
            int n = read(one);
            return n <= 0 ? -1 : one.get(0) & 0xff;
        }

        int read(ByteBuffer dst) throws IOException {
            if (!dst.hasRemaining()) {
                return 0;
            }
            int n = reader.read(pos, dst);
            if (n > 0) {
                pos += n;
            }
            return n;
        }

        void readFully(ByteBuffer dst) throws IOException {
            int want = dst.remaining();
            try {
                reader.readFully(pos, dst);
            } catch (EOFException e) {
                pos += want - dst.remaining();
                throw e;
            }
            pos += want;
        }
    }
}
