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
package com.ash.drishti.server.bi.mq;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Standing query ids. On a cluster {@code sq-<node>-<16 hex>}: the node that holds the subscription is in the id, as a BI run's is ({@code r-<node>-...}), so
 * any node can relay the subscription's stream from its holder without a lookup. Off a cluster {@code sq_<16 hex>}, held where it was made. A node name may
 * itself hold dashes; the 16 hex characters are always last.
 */
public final class StandingId {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern NODE = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern ID = Pattern.compile("sq-([A-Za-z0-9._-]+)-([0-9a-f]{16})");

    private StandingId() {}

    /** A new id; it names {@code node} when that is a node name (letters, digits, dot, dash, underscore). */
    public static String next(String node) {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        String hex = HexFormat.of().formatHex(b);
        return node != null && NODE.matcher(node).matches() ? "sq-" + node + "-" + hex : "sq_" + hex;
    }

    /** The node that holds the subscription, when its id names one. */
    public static Optional<String> node(String id) {
        var m = ID.matcher(id == null ? "" : id);
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
