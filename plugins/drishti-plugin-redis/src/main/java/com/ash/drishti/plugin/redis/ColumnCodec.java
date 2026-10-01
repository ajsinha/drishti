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
package com.ash.drishti.plugin.redis;

import com.github.luben.zstd.Zstd;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A day's ids and promoted fields, stored column-wise in chunks of {@code chunk-rows} (10,000) values, each chunk one
 * field of the day's column hash ({@link RedisLayout#columns}). A day of a million trades is then about two thousand
 * fields read with a few pipelined {@code HMGET}s, never a million keys. Each chunk is a type byte followed by a zstd
 * frame of its values:
 *
 * <ul>
 *   <li>{@code D} numbers: the count, then the doubles' bytes transposed (every value's first byte, then every value's
 *       second byte …), which lets zstd find the runs amounts share (NaN for null);</li>
 *   <li>{@code T} texts with few distinct values (books, desks, currencies, dates): the count, a dictionary of the
 *       chunk's distinct values, then each row's dictionary index plus one as a varint (0 for null);</li>
 *   <li>{@code P} other texts (ids, names): the count, then each value's length plus one as a varint (0 for null) and
 *       its UTF-8 bytes.</li>
 * </ul>
 *
 * <p>The hash's {@code meta} field describes the day ({@link Meta}): the version (when it was written), rows, rows per
 * chunk, chunks, and each column with its type.
 */
public final class ColumnCodec {

    private static final byte NUMBERS = 'D';
    private static final byte DICTIONARY = 'T';
    private static final byte PLAIN = 'P';
    private static final int LEVEL = 3;

    private ColumnCodec() {
    }

    /** What a day's column hash holds; {@code columns} maps each promoted path to true when it is numeric. */
    public record Meta(long version, int rows, int chunkRows, int chunks, Map<String, Boolean> columns) {

        public byte[] encode() {
            StringBuilder s = new StringBuilder();
            s.append("version ").append(version).append('\n').append("rows ").append(rows).append('\n')
                    .append("chunk-rows ").append(chunkRows).append('\n').append("chunks ").append(chunks).append('\n');
            columns.forEach((path, numeric) -> s.append(numeric ? "number " : "text ").append(path).append('\n'));
            return s.toString().getBytes(StandardCharsets.UTF_8);
        }

        public static Meta decode(byte[] bytes) {
            long version = 0;
            int rows = 0;
            int chunkRows = 1;
            int chunks = 0;
            Map<String, Boolean> columns = new LinkedHashMap<>();
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
                int sp = line.indexOf(' ');
                if (sp < 0) {
                    continue;
                }
                String key = line.substring(0, sp);
                String value = line.substring(sp + 1);
                switch (key) {
                    case "version" -> version = Long.parseLong(value);
                    case "rows" -> rows = Integer.parseInt(value);
                    case "chunk-rows" -> chunkRows = Integer.parseInt(value);
                    case "chunks" -> chunks = Integer.parseInt(value);
                    case "number" -> columns.put(value, true);
                    case "text" -> columns.put(value, false);
                    default -> {
                        // a newer writer's line: ignored
                    }
                }
            }
            return new Meta(version, rows, chunkRows, chunks, columns);
        }
    }

    /** Values {@code from} (inclusive) to {@code to} (exclusive) of a numeric column, NaN for null. */
    public static byte[] numbers(double[] values, int from, int to) {
        int n = to - from;
        byte[] body = new byte[4 + 8 * n];
        putInt(body, 0, n);
        for (int i = 0; i < n; i++) {
            long bits = Double.doubleToRawLongBits(values[from + i]);
            for (int b = 0; b < 8; b++) {
                body[4 + b * n + i] = (byte) (bits >>> (56 - 8 * b));
            }
        }
        return frame(NUMBERS, body);
    }

    /** Values {@code from} (inclusive) to {@code to} (exclusive) of a text column; a dictionary when values repeat. */
    public static byte[] texts(String[] values, int from, int to) {
        int n = to - from;
        Map<String, Integer> distinct = new LinkedHashMap<>();
        for (int i = from; i < to && distinct.size() <= n / 4; i++) {
            if (values[i] != null) {
                distinct.putIfAbsent(values[i], distinct.size());
            }
        }
        Bytes out = new Bytes(n * 8 + 16);
        out.varint(n);
        if (distinct.size() <= n / 4) {
            out.varint(distinct.size());
            distinct.keySet().forEach(out::text);
            for (int i = from; i < to; i++) {
                out.varint(values[i] == null ? 0 : distinct.get(values[i]) + 1);
            }
            return frame(DICTIONARY, out.toArray());
        }
        for (int i = from; i < to; i++) {
            if (values[i] == null) {
                out.varint(0);
            } else {
                byte[] b = values[i].getBytes(StandardCharsets.UTF_8);
                out.varint(b.length + 1);
                out.write(b);
            }
        }
        return frame(PLAIN, out.toArray());
    }

    /** True when the chunk holds numbers. */
    public static boolean numeric(byte[] chunk) {
        return chunk.length > 0 && chunk[0] == NUMBERS;
    }

    /** Decodes a numeric chunk into {@code into} from {@code offset}; returns the count. */
    public static int readNumbers(byte[] chunk, double[] into, int offset) {
        byte[] body = unframe(chunk);
        int n = getInt(body, 0);
        for (int i = 0; i < n; i++) {
            long bits = 0;
            for (int b = 0; b < 8; b++) {
                bits = bits << 8 | body[4 + b * n + i] & 0xFF;
            }
            into[offset + i] = Double.longBitsToDouble(bits);
        }
        return n;
    }

    /**
     * Decodes a text chunk into {@code into} from {@code offset}, sharing equal values through {@code pool} (books,
     * desks, counterparties: a few hundred distinct strings for a million rows); returns the count.
     */
    public static int readTexts(byte[] chunk, String[] into, int offset, Map<String, String> pool) {
        byte[] body = unframe(chunk);
        int[] pos = {0};
        int n = varint(body, pos);
        if (chunk[0] == DICTIONARY) {
            String[] dict = new String[varint(body, pos)];
            for (int i = 0; i < dict.length; i++) {
                String s = text(body, pos, varint(body, pos));
                dict[i] = pool.size() > 200_000 ? s : pool.computeIfAbsent(s, k -> k);
            }
            for (int i = 0; i < n; i++) {
                int ix = varint(body, pos);
                into[offset + i] = ix == 0 ? null : dict[ix - 1];
            }
        } else {
            for (int i = 0; i < n; i++) {
                int len = varint(body, pos);
                into[offset + i] = len == 0 ? null : text(body, pos, len - 1);
            }
        }
        return n;
    }

    /** Decodes a numeric chunk as texts (a column whose chunks disagree: numbers written as text, as the loader does). */
    public static int readNumbersAsTexts(byte[] chunk, String[] into, int offset) {
        double[] v = new double[getInt(unframe(chunk), 0)];
        readNumbers(chunk, v, 0);
        for (int i = 0; i < v.length; i++) {
            into[offset + i] = Double.isNaN(v[i]) ? null : text(v[i]);
        }
        return v.length;
    }

    /** A number as the text a mixed column stores (integral values without a fraction). */
    public static String text(double d) {
        return d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static byte[] frame(byte type, byte[] body) {
        byte[] z = Zstd.compress(body, LEVEL);
        byte[] out = new byte[z.length + 1];
        out[0] = type;
        System.arraycopy(z, 0, out, 1, z.length);
        return out;
    }

    private static byte[] unframe(byte[] chunk) {
        byte[] z = Arrays.copyOfRange(chunk, 1, chunk.length);
        long size = Zstd.getFrameContentSize(z);
        if (size < 0 || size > Integer.MAX_VALUE - 16) {
            throw new IllegalStateException("a corrupt column chunk");
        }
        return Zstd.decompress(z, (int) size);
    }

    private static String text(byte[] body, int[] pos, int len) {
        String s = new String(body, pos[0], len, StandardCharsets.UTF_8);
        pos[0] += len;
        return s;
    }

    private static int varint(byte[] b, int[] pos) {
        int v = 0;
        int shift = 0;
        while (true) {
            byte x = b[pos[0]++];
            v |= (x & 0x7F) << shift;
            if (x >= 0) {
                return v;
            }
            shift += 7;
        }
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }

    private static int getInt(byte[] b, int at) {
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | b[at + 3] & 0xFF;
    }

    /** A growable byte array with varints. */
    private static final class Bytes {
        private byte[] buf;
        private int size;

        Bytes(int capacity) {
            buf = new byte[Math.max(16, capacity)];
        }

        void ensure(int more) {
            if (size + more > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(buf.length * 2, size + more));
            }
        }

        void varint(int v) {
            ensure(5);
            while ((v & ~0x7F) != 0) {
                buf[size++] = (byte) (v & 0x7F | 0x80);
                v >>>= 7;
            }
            buf[size++] = (byte) v;
        }

        void write(byte[] b) {
            ensure(b.length);
            System.arraycopy(b, 0, buf, size, b.length);
            size += b.length;
        }

        void text(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            varint(b.length);
            write(b);
        }

        byte[] toArray() {
            return Arrays.copyOf(buf, size);
        }
    }

    /** A shared pool per decoded column. */
    public static Map<String, String> pool() {
        return new HashMap<>();
    }
}
