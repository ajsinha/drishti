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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * One row record ({@link RowPlan}) read in place: the offsets of its fields are found once, the values are copied out only as a writer
 * asks for them. Reused row after row by one writer thread.
 */
final class RowView {

    private final RowPlan plan;
    private byte[] a;
    private int keyOff;
    private int keyLen;
    private int idOff;
    private int idLen;
    private int docOff;
    private int docLen;
    private final int[] prom;
    private final int[] part;
    private final int[] vec;

    RowView(RowPlan plan) {
        this.plan = plan;
        prom = new int[plan.promoted.size()];
        part = new int[plan.partition.size()];
        vec = new int[plan.vectors.size()];
    }

    /** Reads the record at {@code a[off]}. */
    RowView at(byte[] buf, int off) {
        this.a = buf;
        int p = off;
        keyLen = Bytes.getInt(a, p);
        keyOff = p + 4;
        p = keyOff + keyLen;
        idLen = Bytes.getInt(a, p);
        idOff = p + 4;
        p = idOff + idLen;
        docLen = Bytes.getInt(a, p);
        docOff = p + 4;
        p = docOff + docLen;
        for (int i = 0; i < prom.length; i++) {
            prom[i] = p;
            p = skipValue(p);
        }
        for (int i = 0; i < part.length; i++) {
            part[i] = p;
            p = skipValue(p);
        }
        for (int i = 0; i < vec.length; i++) {
            vec[i] = p;
            int n = Bytes.getInt(a, p);
            p += 4 + Math.max(0, n) * 8;
        }
        return this;
    }

    private int skipValue(int p) {
        byte tag = a[p];
        return switch (tag) {
            case RowPlan.NULL -> p + 1;
            case RowPlan.NUM -> p + 1 + 8 + 4 + Bytes.getInt(a, p + 9);
            default -> p + 1 + 4 + Bytes.getInt(a, p + 1);
        };
    }

    byte[] buf() {
        return a;
    }

    int keyOff() {
        return keyOff;
    }

    int keyLen() {
        return keyLen;
    }

    byte[] id() {
        return Arrays.copyOfRange(a, idOff, idOff + idLen);
    }

    String idText() {
        return new String(a, idOff, idLen, StandardCharsets.UTF_8);
    }

    byte[] doc() {
        return Arrays.copyOfRange(a, docOff, docOff + docLen);
    }

    byte tag(int i) {
        return a[prom[i]];
    }

    double num(int i) {
        return Bytes.getDouble(a, prom[i] + 1);
    }

    /** A number's text as Python writes it (lake build's text for a number in a text column). */
    byte[] numText(int i) {
        int len = Bytes.getInt(a, prom[i] + 9);
        return Arrays.copyOfRange(a, prom[i] + 13, prom[i] + 13 + len);
    }

    /** A text or JSON value's UTF-8 bytes. */
    byte[] text(int i) {
        int len = Bytes.getInt(a, prom[i] + 1);
        return Arrays.copyOfRange(a, prom[i] + 5, prom[i] + 5 + len);
    }

    /** Partition value {@code j}, or null. */
    String partition(int j) {
        int p = part[j];
        if (a[p] == RowPlan.NULL) {
            return null;
        }
        return new String(a, p + 5, Bytes.getInt(a, p + 1), StandardCharsets.UTF_8);
    }

    /** Vector {@code k} (a null element is NaN), or null when the row has none. */
    double[] vector(int k) {
        int p = vec[k];
        int n = Bytes.getInt(a, p);
        if (n < 0) {
            return null;
        }
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = Bytes.getDouble(a, p + 4 + i * 8);
        }
        return out;
    }

    int partitions() {
        return part.length;
    }

    RowPlan plan() {
        return plan;
    }
}
