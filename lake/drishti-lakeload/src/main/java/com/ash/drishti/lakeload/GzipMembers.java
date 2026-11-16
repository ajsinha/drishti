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
package com.ash.drishti.lakeload;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

/**
 * A gzip file decompressed as a stream, every member of it: a file of several gzip members one after another ({@code cat a.gz b.gz},
 * what {@code pigz} and many exporters write) reads as their contents joined. The JDK's {@link Inflater} with a large input buffer; no
 * temporary copy is made and nothing is read twice. Each member's CRC-32 and length are checked against its trailer.
 *
 * <p>{@code java.util.zip.GZIPInputStream} also reads several members, but decides whether another member follows from
 * {@code available()}, which a network stream may answer with 0 at a member's end: the rest of the file would be silently lost.
 */
final class GzipMembers extends InputStream {

    private static final int FHCRC = 2;
    private static final int FEXTRA = 4;
    private static final int FNAME = 8;
    private static final int FCOMMENT = 16;

    private final InputStream in;
    private final byte[] buf;
    private int pos;
    private int lim;
    private final Inflater inflater = new Inflater(true);
    private final CRC32 crc = new CRC32();
    private long memberOut;
    private boolean inMember;
    private boolean eof;
    private final byte[] one = new byte[1];

    GzipMembers(InputStream in, int bufferBytes) throws IOException {
        this.in = in;
        this.buf = new byte[bufferBytes];
        if (!header(true)) {
            throw new ZipException("not in gzip format (an empty file)");
        }
    }

    @Override
    public int read() throws IOException {
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        while (!eof) {
            if (!inMember) {
                if (!header(false)) {
                    eof = true;
                    return -1;
                }
            }
            try {
                int n = inflater.inflate(b, off, len);
                if (n > 0) {
                    crc.update(b, off, n);
                    memberOut += n;
                    return n;
                }
                if (inflater.finished()) {
                    pos = lim - inflater.getRemaining();
                    trailer();
                    inMember = false;
                    continue;
                }
                if (inflater.needsDictionary()) {
                    throw new ZipException("a gzip member asks for a preset dictionary");
                }
                if (inflater.needsInput()) {
                    if (!fill()) {
                        throw new EOFException("the gzip file ends inside a member (a truncated file)");
                    }
                    inflater.setInput(buf, pos, lim - pos);
                    pos = lim;
                }
            } catch (DataFormatException e) {
                throw new ZipException("corrupt gzip data: " + e.getMessage());
            }
        }
        return -1;
    }

    /** Reads a member's header; false at the end of the file (no further member). {@code first}: the file must start with one. */
    private boolean header(boolean first) throws IOException {
        int id1 = next();
        if (id1 < 0) {
            if (first) {
                return false;
            }
            return false;
        }
        int id2 = next();
        if (id1 != 0x1f || id2 != 0x8b) {
            if (!first && id1 == 0 && allZero(id2)) {
                return false;                                          // zero padding after the last member (tar-style): the end
            }
            throw new ZipException(first ? "not in gzip format" : "data after the last gzip member is not another member");
        }
        if (next() != 8) {
            throw new ZipException("an unsupported gzip compression method");
        }
        int flags = next();
        skip(6);                                                       // mtime, xfl, os
        if ((flags & FEXTRA) != 0) {
            int xlen = next() | next() << 8;
            skip(xlen);
        }
        if ((flags & FNAME) != 0) {
            while (need() != 0) {
                // the file name, up to its zero byte
            }
        }
        if ((flags & FCOMMENT) != 0) {
            while (need() != 0) {
                // the comment
            }
        }
        if ((flags & FHCRC) != 0) {
            skip(2);
        }
        inflater.reset();
        crc.reset();
        memberOut = 0;
        inflater.setInput(buf, pos, lim - pos);
        pos = lim;
        inMember = true;
        return true;
    }

    private boolean allZero(int b) throws IOException {
        if (b != 0) {
            return false;
        }
        int x;
        while ((x = next()) >= 0) {
            if (x != 0) {
                return false;
            }
        }
        return true;
    }

    private void trailer() throws IOException {
        long want = (long) need() | (long) need() << 8 | (long) need() << 16 | (long) need() << 24;
        long size = (long) need() | (long) need() << 8 | (long) need() << 16 | (long) need() << 24;
        if (want != crc.getValue()) {
            throw new ZipException("a gzip member's CRC does not match its data (a corrupt file)");
        }
        if (size != (memberOut & 0xffffffffL)) {
            throw new ZipException("a gzip member's length does not match its data (a corrupt file)");
        }
    }

    private int next() throws IOException {
        if (pos >= lim && !fill()) {
            return -1;
        }
        return buf[pos++] & 0xff;
    }

    private int need() throws IOException {
        int b = next();
        if (b < 0) {
            throw new EOFException("the gzip file ends inside a member's header or trailer");
        }
        return b;
    }

    private void skip(int n) throws IOException {
        for (int i = 0; i < n; i++) {
            need();
        }
    }

    private boolean fill() throws IOException {
        int n = in.read(buf, 0, buf.length);
        while (n == 0) {
            n = in.read(buf, 0, buf.length);
        }
        if (n < 0) {
            pos = lim = 0;
            return false;
        }
        pos = 0;
        lim = n;
        return true;
    }

    @Override
    public void close() throws IOException {
        inflater.end();
        in.close();
    }
}
