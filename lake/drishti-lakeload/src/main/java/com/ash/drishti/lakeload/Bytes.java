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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A growable byte buffer, reused by one thread for one row after another (no allocation per row once it has grown), with the
 * fixed-width little-endian reads and writes the row records use ({@link RowCodec}).
 */
final class Bytes {

    static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    static final VarHandle DOUBLE = MethodHandles.byteArrayViewVarHandle(double[].class, ByteOrder.LITTLE_ENDIAN);

    byte[] a;
    int n;

    Bytes(int capacity) {
        a = new byte[Math.max(16, capacity)];
    }

    void clear() {
        n = 0;
    }

    int length() {
        return n;
    }

    void ensure(int more) {
        if (n + more > a.length) {
            long want = Math.max((long) a.length * 2, (long) n + more);
            if (want > Integer.MAX_VALUE - 16) {
                throw new IllegalStateException("a row of more than 2 GB");
            }
            a = Arrays.copyOf(a, (int) want);
        }
    }

    void put(int b) {
        ensure(1);
        a[n++] = (byte) b;
    }

    void put(byte[] src, int off, int len) {
        ensure(len);
        System.arraycopy(src, off, a, n, len);
        n += len;
    }

    void put(byte[] src) {
        put(src, 0, src.length);
    }

    void ascii(String s) {
        int len = s.length();
        ensure(len);
        for (int i = 0; i < len; i++) {
            a[n++] = (byte) s.charAt(i);
        }
    }

    void utf8(String s) {
        put(s.getBytes(StandardCharsets.UTF_8));
    }

    void putInt(int v) {
        ensure(4);
        INT.set(a, n, v);
        n += 4;
    }

    void putLong(long v) {
        ensure(8);
        LONG.set(a, n, v);
        n += 8;
    }

    void putDouble(double v) {
        ensure(8);
        DOUBLE.set(a, n, v);
        n += 8;
    }

    /** Overwrites four bytes at {@code at} (a length written before its content was known). */
    void setInt(int at, int v) {
        INT.set(a, at, v);
    }

    byte[] copy(int from, int to) {
        return Arrays.copyOfRange(a, from, to);
    }

    static int getInt(byte[] b, int at) {
        return (int) INT.get(b, at);
    }

    static double getDouble(byte[] b, int at) {
        return (double) DOUBLE.get(b, at);
    }
}
