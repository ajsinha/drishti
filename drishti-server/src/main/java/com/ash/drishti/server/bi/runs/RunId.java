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
package com.ash.drishti.server.bi.runs;

import java.time.Instant;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * Run ids, {@code r-<node>-<ulid>} (docs/architecture/BI_RUNS.md sections 3.4 and 13): the owning node is in the id, so any node can forward a
 * {@code get}, {@code result} or {@code cancel} without a lookup. The ULID (26 Crockford base-32 characters, 48 bits of milliseconds then 80 random
 * bits) sorts by submission time. A node name may itself hold dashes ({@code java-2}); the ULID is always the last 26 characters.
 */
public final class RunId {

    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final Pattern ID = Pattern.compile("r-([A-Za-z0-9._-]+)-([0-9A-HJKMNP-TV-Z]{26})");

    private RunId() {}

    public static String next(String node, Instant now, RandomGenerator random) {
        if (node == null || node.isBlank() || !node.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("a node name of letters, digits, dot, dash or underscore, not '" + node + "'");
        }
        return "r-" + node + "-" + ulid(now.toEpochMilli(), random);
    }

    static String ulid(long millis, RandomGenerator random) {
        char[] out = new char[26];
        long t = millis;
        for (int i = 9; i >= 0; i--) {
            out[i] = CROCKFORD[(int) (t & 31)];
            t >>>= 5;
        }
        for (int i = 10; i < 26; i++) {
            out[i] = CROCKFORD[random.nextInt(32)];
        }
        return new String(out);
    }

    public static boolean valid(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** The node that owns the run. */
    public static String node(String id) {
        var m = ID.matcher(id == null ? "" : id);
        if (!m.matches()) {
            throw new IllegalArgumentException("not a run id: " + id);
        }
        return m.group(1);
    }
}
