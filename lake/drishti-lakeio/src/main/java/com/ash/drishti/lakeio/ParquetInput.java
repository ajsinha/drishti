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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;
import org.apache.parquet.io.SeekableInputStream;

/**
 * parquet-java's views of a lake's files over a {@link Storage}, without Hadoop's {@code Path} or {@code FileSystem}:
 * an {@link InputFile} with its seekable stream for reading, an {@link OutputFile} for writing.
 */
public final class ParquetInput {

    private ParquetInput() {}

    /** Parquet's view of a file to read; {@code knownLength} negative when the caller does not know it. */
    public static InputFile of(Storage storage, String path, long knownLength) {
        return new File(storage, path, knownLength);
    }

    /** Parquet's view of a new file to write (replacing one of the same name). */
    public static OutputFile output(Storage storage, String path) {
        return new Output(storage, path);
    }

    private static final class File implements InputFile {
        private final Storage storage;
        private final String path;
        private final long knownLength;

        File(Storage storage, String path, long knownLength) {
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
        public SeekableInputStream newStream() throws IOException {
            return new Stream(storage.open(path, knownLength));
        }

        @Override
        public String toString() {
            return path;
        }
    }

    /** Parquet's seekable stream. */
    private static final class Stream extends SeekableInputStream {
        private final RangeCursor cursor;

        Stream(RangeReader reader) {
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
            cursor.close();
        }
    }

    private record Output(Storage storage, String path) implements OutputFile {
        @Override
        public PositionOutputStream create(long blockSizeHint) throws IOException {
            return createOrOverwrite(blockSizeHint);
        }

        @Override
        public PositionOutputStream createOrOverwrite(long blockSizeHint) throws IOException {
            OutputStream out = storage.create(path);
            return new PositionOutputStream() {
                private long pos;

                @Override
                public long getPos() {
                    return pos;
                }

                @Override
                public void write(int b) throws IOException {
                    out.write(b);
                    pos++;
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    out.write(b, off, len);
                    pos += len;
                }

                @Override
                public void flush() throws IOException {
                    out.flush();
                }

                @Override
                public void close() throws IOException {
                    out.close();
                }
            };
        }

        @Override
        public boolean supportsBlockSize() {
            return false;
        }

        @Override
        public long defaultBlockSize() {
            return 0;
        }

        @Override
        public String getPath() {
            return path;
        }
    }
}
