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
package com.ash.drishti.identity.collab;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * Time-ordered, random 26-character ids (Crockford base32 ULID) with a prefix: {@code sh_}, {@code th_}, {@code cm_}. They are made on
 * the server, sort by time, need no database sequence and are unguessable in URLs (a guess still needs the right to read).
 */
public final class Ulid {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {}

    public static String next(String prefix) {
        return next(prefix, System.currentTimeMillis());
    }

    public static String next(String prefix, long millis) {
        char[] out = new char[26];
        long t = millis;
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (t & 31)];
            t >>>= 5;
        }
        byte[] r = new byte[10];
        RANDOM.nextBytes(r);
        // 80 random bits as 16 characters of 5 bits
        long hi = 0;
        for (int i = 0; i < 5; i++) {
            hi = (hi << 8) | (r[i] & 0xff);
        }
        long lo = 0;
        for (int i = 5; i < 10; i++) {
            lo = (lo << 8) | (r[i] & 0xff);
        }
        for (int i = 17; i >= 10; i--) {
            out[i] = ALPHABET[(int) (hi & 31)];
            hi >>>= 5;
        }
        for (int i = 25; i >= 18; i--) {
            out[i] = ALPHABET[(int) (lo & 31)];
            lo >>>= 5;
        }
        return prefix + new String(out);
    }

    /** True when the text looks like an id made with {@code prefix}. */
    public static boolean valid(String id, String prefix) {
        if (id == null || id.length() != prefix.length() + 26 || !id.startsWith(prefix)) {
            return false;
        }
        for (int i = prefix.length(); i < id.length(); i++) {
            if (new String(ALPHABET).indexOf(id.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    /** The moment an id was made, decoded from its first ten characters. */
    public static Instant timeOf(String id, String prefix) {
        long t = 0;
        String alpha = new String(ALPHABET);
        for (int i = prefix.length(); i < prefix.length() + 10; i++) {
            t = (t << 5) | alpha.indexOf(id.charAt(i));
        }
        return Instant.ofEpochMilli(t);
    }
}
