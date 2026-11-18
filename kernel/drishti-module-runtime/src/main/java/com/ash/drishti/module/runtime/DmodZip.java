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
package com.ash.drishti.module.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * The zip writer of the packager ({@link ModulePack}): every byte of a {@code .dmod} fixed by its input, nothing from the clock or the
 * platform. Each entry has the local header with its real sizes and CRC (no data descriptor), version 2.0, made on Unix (mode 0644), the
 * time 1980-01-01 00:00, a UTF-8 name flag only for a name that is not ASCII, no extra field and no comment; STORED or DEFLATED (raw
 * deflate, level 6, the whole entry in one pass); then the central directory and the end record, without zip64 (a package stays far below
 * 4 GB). java.util.zip's ZipOutputStream is not used: it writes data descriptors and cannot set the file mode.
 */
final class DmodZip implements AutoCloseable {

    private static final int VERSION = 20;
    private static final int UNIX = 3;
    private static final int STORED = 0;
    private static final int DEFLATED = 8;
    private static final int LEVEL = 6;
    private static final int DOS_TIME = 0;                                   // 00:00:00
    private static final int DOS_DATE = (1980 - 1980) << 9 | 1 << 5 | 1;      // 1980-01-01
    private static final long MODE = 0100644L << 16;                         // a regular file, rw-r--r--
    private static final int UTF8_NAME = 0x800;

    private final OutputStream out;
    private final ByteArrayOutputStream central = new ByteArrayOutputStream();
    private long offset;
    private int count;

    DmodZip(Path file) throws IOException {
        this.out = new java.io.BufferedOutputStream(Files.newOutputStream(file), 1 << 16);
    }

    /** One entry: deflated, or stored. */
    void put(String name, byte[] data, boolean deflate) {
        try {
            byte[] body = deflate ? deflate(data) : data;
            CRC32 crc = new CRC32();
            crc.update(data);
            boolean ascii = name.chars().allMatch(c -> c < 0x80);
            byte[] n = name.getBytes(ascii ? StandardCharsets.US_ASCII : StandardCharsets.UTF_8);
            int flags = ascii ? 0 : UTF8_NAME;
            int method = deflate ? DEFLATED : STORED;

            ByteArrayOutputStream h = new ByteArrayOutputStream(30 + n.length);
            int32(h, 0x04034b50);
            h.write(VERSION);
            h.write(0);
            int16(h, flags);
            int16(h, method);
            int16(h, DOS_TIME);
            int16(h, DOS_DATE);
            int32(h, crc.getValue());
            int32(h, body.length);
            int32(h, data.length);
            int16(h, n.length);
            int16(h, 0);
            h.write(n);
            h.writeTo(out);
            out.write(body);

            int32(central, 0x02014b50);
            central.write(VERSION);
            central.write(UNIX);
            central.write(VERSION);
            central.write(0);
            int16(central, flags);
            int16(central, method);
            int16(central, DOS_TIME);
            int16(central, DOS_DATE);
            int32(central, crc.getValue());
            int32(central, body.length);
            int32(central, data.length);
            int16(central, n.length);
            int16(central, 0);                                                  // extra
            int16(central, 0);                                                  // comment
            int16(central, 0);                                                  // disk
            int16(central, 0);                                                  // internal attributes
            int32(central, MODE);
            int32(central, offset);
            central.write(n);

            offset += h.size() + body.length;
            count++;
            if (offset > 0xFFFFFFFFL || count > 0xFFFF) {
                throw new IOException("a package over 4 GB or 65535 entries is not written (no zip64)");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Raw deflate at level 6: the whole input without flushing, then the finish (the bytes zlib gives for compress + flush). */
    private static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(LEVEL, true);
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream(Math.max(64, data.length / 2));
            byte[] buf = new byte[1 << 16];
            d.setInput(data);
            while (!d.needsInput()) {
                int k = d.deflate(buf, 0, buf.length, Deflater.NO_FLUSH);
                o.write(buf, 0, k);
            }
            d.finish();
            while (!d.finished()) {
                int k = d.deflate(buf, 0, buf.length, Deflater.NO_FLUSH);
                o.write(buf, 0, k);
            }
            return o.toByteArray();
        } finally {
            d.end();
        }
    }

    @Override
    public void close() throws IOException {
        try (out) {
            central.writeTo(out);
            ByteArrayOutputStream e = new ByteArrayOutputStream(22);
            int32(e, 0x06054b50);
            int16(e, 0);
            int16(e, 0);
            int16(e, count);
            int16(e, count);
            int32(e, central.size());
            int32(e, offset);
            int16(e, 0);
            e.writeTo(out);
        }
    }

    private static void int16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write(v >>> 8 & 0xFF);
    }

    private static void int32(ByteArrayOutputStream o, long v) {
        o.write((int) (v & 0xFF));
        o.write((int) (v >>> 8 & 0xFF));
        o.write((int) (v >>> 16 & 0xFF));
        o.write((int) (v >>> 24 & 0xFF));
    }
}
