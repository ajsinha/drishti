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
package com.ash.drishti.bicompute.functions;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A HyperLogLog sketch (Flajolet, Fusy, Gandouet and Meunier, 2007; PIVOT_ENGINE.md section 9.5): 2^p registers, each the largest rank
 * (position of the first 1-bit after the first p bits of a 64-bit hash) seen among the values that land on it. Merge is a register-wise
 * maximum: exact for the sketch, associative and commutative, so any split of the rows merged in any order gives the same registers. The
 * estimate's relative standard error is about 1.04 / √m (m = 2^p): 1.6% at p = 12, 0.8% at p = 14.
 *
 * <p>Two hash families: values hashed in Java ({@link #FAMILY_JAVA}, {@link #hash(String)}) and values hashed by DuckDB's {@code hash()}
 * ({@link #FAMILY_SQL}, the tabular provider's SQL state, {@link #sql}). Registers of two families never merge (they would count a value
 * twice); a provider makes all of a plan's states in one family.
 */
public final class Hll {

    /** No value yet: the first value added sets the family. */
    public static final byte FAMILY_NONE = 0;
    /** Values hashed in Java ({@link #hash(String)}). */
    public static final byte FAMILY_JAVA = 1;
    /** Values hashed by DuckDB's {@code hash()} (the tabular provider). */
    public static final byte FAMILY_SQL = 2;

    static final byte TAG = 'H';
    /** The smallest and largest precision: 2,048 to 65,536 registers (the rest of the hash fits a double exactly, so SQL ranks are exact). */
    public static final int MIN_P = 11;
    public static final int MAX_P = 16;

    final int p;
    byte family;
    final byte[] reg;

    public Hll(int p, byte family) {
        if (p < MIN_P || p > MAX_P) {
            throw new IllegalArgumentException("HyperLogLog precision is " + MIN_P + " to " + MAX_P + " (2,048 to 65,536 registers), not " + p);
        }
        this.p = p;
        this.family = family;
        this.reg = new byte[1 << p];
    }

    public int precision() {
        return p;
    }

    public byte family() {
        return family;
    }

    /** The relative standard error of the estimate: 1.04 / √m. */
    public static double relativeError(int p) {
        return 1.04 / Math.sqrt(1 << p);
    }

    /** A 64-bit hash of a value's text (FNV-1a over UTF-8, then MurmurHash3's 64-bit finaliser for avalanche). */
    public static long hash(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xff;
            h *= 0x100000001b3L;
        }
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    /** One value's 64-bit hash added: the first p bits pick the register, the rank of the first 1-bit in the rest is its value. */
    public void addHash(long h) {
        family(FAMILY_JAVA);
        int idx = (int) (h >>> (64 - p));
        long w = h & ((1L << (64 - p)) - 1);
        int rho = Long.numberOfLeadingZeros(w) - p + 1;
        if (rho > reg[idx]) {
            reg[idx] = (byte) rho;
        }
    }

    /** A (register, rank) pair as SQL makes it: {@code register * 64 + rank}. */
    public void addCode(long code) {
        family(FAMILY_SQL);
        int idx = (int) (code >>> 6);
        int rho = (int) (code & 63);
        if (idx < 0 || idx >= reg.length) {
            throw new IllegalArgumentException("register " + idx + " of a sketch of " + reg.length);
        }
        if (rho > reg[idx]) {
            reg[idx] = (byte) rho;
        }
    }

    private void family(byte f) {
        if (family == FAMILY_NONE) {
            family = f;
        } else if (family != f) {
            throw new IllegalStateException("a distinct-count sketch of one hash family cannot take values hashed by the other");
        }
    }

    /** The register-wise maximum of two sketches of the same precision and family (a new sketch). */
    public Hll merge(Hll o) {
        if (o.p == p && o.family != family && (family == FAMILY_NONE || o.family == FAMILY_NONE)) {
            Hll out = new Hll(p, family == FAMILY_NONE ? o.family : family);
            for (int i = 0; i < reg.length; i++) {
                out.reg[i] = (byte) Math.max(reg[i], o.reg[i]);
            }
            return out;
        }
        if (o.p != p || o.family != family) {
            throw new IllegalStateException("two distinct-count sketches of different kinds (precision " + p + " and " + o.p + ", hash family "
                    + family + " and " + o.family + ") cannot be merged: they would count a value twice");
        }
        Hll out = new Hll(p, family);
        for (int i = 0; i < reg.length; i++) {
            out.reg[i] = (byte) Math.max(reg[i], o.reg[i]);
        }
        return out;
    }

    /** The estimate: linear counting while it is at most 2.5 m (small ranges), else the harmonic mean of 2^-register, bias-corrected. */
    public double estimate() {
        int m = reg.length;
        double z = 0;
        int zeros = 0;
        for (byte r : reg) {
            z += Math.scalb(1.0, -r);
            if (r == 0) {
                zeros++;
            }
        }
        // linear counting while it says the range is small (up to 2.5 m): the raw estimate is biased upwards there (a few per cent near
        // 2.5 m), so the choice is made on linear counting's own figure, not on the raw one (HyperLogLog++'s observation, Heule et al.)
        if (zeros > 0) {
            double lc = m * Math.log((double) m / zeros);
            if (lc <= 2.5 * m) {
                return lc;
            }
        }
        double alpha = 0.7213 / (1 + 1.079 / m);
        return alpha * m * (double) m / z;
    }

    /** True when no value was added. */
    /** The state as the playground shows it: the distinct estimate and the precision. */
    @Override
    public String toString() {
        return "hll p=" + p + " ≈" + Math.round(estimate()) + " distinct";
    }

    public boolean empty() {
        for (byte r : reg) {
            if (r != 0) {
                return false;
            }
        }
        return true;
    }

    public byte[] encode() {
        return ByteBuffer.allocate(3 + reg.length).put(TAG).put((byte) p).put(family).put(reg).array();
    }

    public static Hll decode(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b);
        if (bb.get() != TAG) {
            throw new IllegalArgumentException("not a distinct-count sketch");
        }
        Hll h = new Hll(bb.get(), bb.get());
        bb.get(h.reg);
        return h;
    }

    /**
     * The SQL expression of a value's (register, rank) code from DuckDB's 64-bit {@code hash()} of {@code x}: the first p bits pick the
     * register; the rank is the leading zeros of the remaining {@code 64 - p} bits plus one, its bit length found from {@code log2} and
     * corrected by one either way, so it is exact (the remaining bits fit a double exactly for p ≥ 11).
     */
    public static String sqlCode(String x, int p) {
        int rest = 64 - p;
        long mask = (1L << rest) - 1;
        String h = "hash(" + x + ")";
        String w = "(" + h + " & CAST(" + mask + " AS UBIGINT))";
        String l = "CAST(floor(log2(greatest(CAST(" + w + " AS DOUBLE), 1.0))) AS INTEGER)";
        String bitlen = "(" + l + " + 1 - CASE WHEN (CAST(1 AS UBIGINT) << " + l + ") > " + w + " THEN 1 ELSE 0 END"
                + " + CASE WHEN " + l + " < " + (rest - 1) + " AND (CAST(1 AS UBIGINT) << (" + l + " + 1)) <= " + w + " THEN 1 ELSE 0 END)";
        String rho = "CASE WHEN " + w + " = 0 THEN " + (rest + 1) + " ELSE " + rest + " - " + bitlen + " + 1 END";
        return String.format(Locale.ROOT, "CAST(%s >> %d AS BIGINT) * 64 + (%s)", h, rest, rho);
    }

    /** The SQL aggregate of a cell's codes: each (register, rank) pair once, values that are missing left out. */
    public static String sql(String x, int p) {
        return "list(DISTINCT " + sqlCode(x, p) + ") FILTER (WHERE " + x + " IS NOT NULL)";
    }
}
