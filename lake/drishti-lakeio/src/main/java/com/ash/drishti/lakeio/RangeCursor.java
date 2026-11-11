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

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;

/** A position over a {@link RangeReader}: what every seekable stream of the engines reads through. Used by one thread. */
public final class RangeCursor {

    private final RangeReader reader;
    private long pos;

    public RangeCursor(RangeReader reader) {
        this.reader = reader;
    }

    public long position() {
        return pos;
    }

    public void seek(long newPos) {
        pos = newPos;
    }

    /** One byte, or -1 at the end of the file. */
    public int read() throws IOException {
        ByteBuffer one = ByteBuffer.allocate(1);
        int n = read(one);
        return n <= 0 ? -1 : one.get(0) & 0xff;
    }

    /** Up to {@code dst.remaining()} bytes; -1 at the end of the file. */
    public int read(ByteBuffer dst) throws IOException {
        if (!dst.hasRemaining()) {
            return 0;
        }
        int n = reader.read(pos, dst);
        if (n > 0) {
            pos += n;
        }
        return n;
    }

    /** Fills {@code dst}, or throws {@link EOFException} (having moved past what was read). */
    public void readFully(ByteBuffer dst) throws IOException {
        int want = dst.remaining();
        try {
            reader.readFully(pos, dst);
        } catch (EOFException e) {
            pos += want - dst.remaining();
            throw e;
        }
        pos += want;
    }

    public void close() throws IOException {
        reader.close();
    }
}
