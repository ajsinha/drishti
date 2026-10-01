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

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Positional reads of one file: a local file channel or ranged GETs of an object. One reader serves one stream of
 * one read (not shared between threads); opening one is cheap.
 */
public interface RangeReader extends Closeable {

    /** The file's length in bytes. */
    long length() throws IOException;

    /**
     * Reads up to {@code dst.remaining()} bytes starting at {@code position} into {@code dst}.
     *
     * @return the bytes read, at least one, or -1 at the end of the file
     */
    int read(long position, ByteBuffer dst) throws IOException;

    /** Fills {@code dst} from {@code position}, or throws {@link EOFException} if the file ends first. */
    default void readFully(long position, ByteBuffer dst) throws IOException {
        long at = position;
        while (dst.hasRemaining()) {
            int n = read(at, dst);
            if (n < 0) {
                throw new EOFException("end of file at " + at + " with " + dst.remaining() + " bytes still to read");
            }
            at += n;
        }
    }
}
