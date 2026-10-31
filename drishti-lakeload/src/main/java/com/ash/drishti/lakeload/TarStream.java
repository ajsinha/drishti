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
import java.nio.charset.StandardCharsets;

/**
 * A tar archive read front to back as a stream, entry after entry (the formats {@code tar} and Python's {@code tarfile} write: ustar
 * with its name prefix, POSIX pax headers for long names and sizes, GNU long names); nothing is written to disk. {@link #next()} moves
 * to the next entry, skipping what is left of the current one; {@link #data()} reads the current entry's bytes.
 */
final class TarStream {

    /** One entry: its name as written, its size, and its type ({@code FILE}, {@code DIR}, {@code LINK} or {@code OTHER}). */
    record Entry(String name, long size, Type type) {}

    enum Type { FILE, DIR, LINK, OTHER }

    private static final int BLOCK = 512;

    private final InputStream in;
    private final byte[] header = new byte[BLOCK];
    private long left;          // bytes of the current entry not read yet
    private long pad;           // the padding after it, up to the next block

    TarStream(InputStream in) {
        this.in = in;
    }

    /** The next entry, or null at the archive's end. */
    Entry next() throws IOException {
        skip(left + pad);
        left = 0;
        pad = 0;
        String longName = null;
        Long paxSize = null;
        while (true) {
            if (!readBlock()) {
                return null;
            }
            if (allZero(header)) {
                return null;                                          // the end-of-archive blocks
            }
            char flag = (char) header[156];
            long size = size(header);
            switch (flag) {
                case 'x', 'g' -> {                                    // pax: the next entry's path and size (global: ignored)
                    byte[] body = readBody(size);
                    if (flag == 'x') {
                        String[] got = pax(body);
                        if (got[0] != null) {
                            longName = got[0];
                        }
                        if (got[1] != null) {
                            paxSize = Long.parseLong(got[1].trim());
                        }
                    }
                    continue;
                }
                case 'L' -> {                                         // GNU: the next entry's long name
                    longName = cString(readBody(size), 0, (int) size);
                    continue;
                }
                case 'K' -> {                                         // GNU: a long link name
                    readBody(size);
                    continue;
                }
                default -> {
                    // an entry
                }
            }
            String name = longName != null ? longName : ustarName(header);
            long real = paxSize != null ? paxSize : size;
            Type type = switch (flag) {
                case '0', '\0', '7' -> Type.FILE;
                case '5' -> Type.DIR;
                case '1', '2' -> Type.LINK;
                default -> Type.OTHER;
            };
            if (type == Type.FILE && name.endsWith("/")) {
                type = Type.DIR;                                      // an old tar's folder
            }
            long data = type == Type.FILE || type == Type.OTHER ? real : 0;
            left = data;
            pad = (BLOCK - data % BLOCK) % BLOCK;
            return new Entry(name, type == Type.FILE ? real : 0, type);
        }
    }

    /** The current entry's bytes (read them before the next {@link #next()}). */
    InputStream data() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (left <= 0) {
                    return -1;
                }
                int n = in.read(b, off, (int) Math.min(len, left));
                if (n < 0) {
                    throw new EOFException("the tar archive ends inside an entry");
                }
                left -= n;
                return n;
            }
        };
    }

    private boolean readBlock() throws IOException {
        int got = 0;
        while (got < BLOCK) {
            int n = in.read(header, got, BLOCK - got);
            if (n < 0) {
                if (got == 0) {
                    return false;
                }
                throw new EOFException("the tar archive ends inside a header");
            }
            got += n;
        }
        return true;
    }

    private byte[] readBody(long size) throws IOException {
        if (size > (16 << 20)) {
            throw new IOException("a tar header entry of " + size + " bytes (more than 16 MB): not a tar archive Drishti reads");
        }
        byte[] b = in.readNBytes((int) size);
        if (b.length < size) {
            throw new EOFException("the tar archive ends inside a header");
        }
        skip((BLOCK - size % BLOCK) % BLOCK);
        return b;
    }

    private void skip(long n) throws IOException {
        byte[] sink = new byte[8192];
        while (n > 0) {
            int r = in.read(sink, 0, (int) Math.min(sink.length, n));
            if (r < 0) {
                throw new EOFException("the tar archive ends inside an entry");
            }
            n -= r;
        }
    }

    private static boolean allZero(byte[] b) {
        for (byte x : b) {
            if (x != 0) {
                return false;
            }
        }
        return true;
    }

    /** The size field: octal text, or base-256 when its first byte has the high bit set (GNU, for sizes of 8 GB and more). */
    static long size(byte[] h) {
        if ((h[124] & 0x80) != 0) {
            long v = 0;
            for (int i = 125; i < 136; i++) {
                v = (v << 8) | (h[i] & 0xff);
            }
            return v;
        }
        long v = 0;
        for (int i = 124; i < 136; i++) {
            byte c = h[i];
            if (c >= '0' && c <= '7') {
                v = v * 8 + (c - '0');
            } else if (c != ' ' && c != 0) {
                break;
            } else if (v > 0) {
                break;
            }
        }
        return v;
    }

    private static String ustarName(byte[] h) {
        String name = cString(h, 0, 100);
        boolean ustar = h[257] == 'u' && h[258] == 's' && h[259] == 't' && h[260] == 'a' && h[261] == 'r';
        String prefix = ustar ? cString(h, 345, 155) : "";
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    private static String cString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    /** A pax header's {@code path} and {@code size} ("LEN key=value\n" records). */
    private static String[] pax(byte[] body) {
        String[] out = new String[2];
        int i = 0;
        while (i < body.length) {
            int sp = i;
            while (sp < body.length && body[sp] != ' ') {
                sp++;
            }
            if (sp >= body.length) {
                break;
            }
            int len;
            try {
                len = Integer.parseInt(new String(body, i, sp - i, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                break;
            }
            if (len <= 0 || i + len > body.length) {
                break;
            }
            String rec = new String(body, sp + 1, i + len - sp - 2, StandardCharsets.UTF_8);
            int eq = rec.indexOf('=');
            if (eq > 0) {
                String k = rec.substring(0, eq);
                if (k.equals("path")) {
                    out[0] = rec.substring(eq + 1);
                } else if (k.equals("size")) {
                    out[1] = rec.substring(eq + 1);
                }
            }
            i += len;
        }
        return out;
    }
}
