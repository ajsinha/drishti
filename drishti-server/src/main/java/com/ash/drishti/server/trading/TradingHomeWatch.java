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
package com.ash.drishti.server.trading;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * What makes the trader home tick (docs/architecture/SHARED_SCREENS.md section 4): the data versions of the kinds it is built from (the
 * trades, their valuations, the VaR limits and P&amp;L vectors, the curves and FX a trader watches), read from the connectors that serve
 * them. A state is the version of each kind and one generation over all of them; two states differ when any kind has a new version. A
 * tick carries no figure and no row: the console asks the figures again as the person, so their books, row rules and masks apply to every
 * update, and nothing a person may not see travels on the stream.
 */
public final class TradingHomeWatch {

    /** One reading: each kind's data version ({@code none} when no connector serves it yet) and the generation over them. */
    public record State(Map<String, String> versions, String generation) {

        /** The kinds whose version differs from {@code before} (all of them when there was no state before). */
        public List<String> changedSince(State before) {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, String> e : versions.entrySet()) {
                if (before == null || !Objects.equals(before.versions().get(e.getKey()), e.getValue())) {
                    out.add(e.getKey());
                }
            }
            return out;
        }

        /** The stream's words: the generation, the kinds watched and the ones that changed. */
        public Map<String, Object> describe(List<String> changed, long at) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("generation", generation);
            m.put("at", at);
            m.put("kinds", List.copyOf(versions.keySet()));
            m.put("changed", changed);
            return m;
        }
    }

    private final List<String> kinds;
    private final Function<String, Optional<String>> version;

    /**
     * @param kinds the kinds watched ({@link TradingHomeProperties#watch()})
     * @param version a kind's current data version (the source router's, as of the latest date); empty or a failure: {@code none}
     */
    public TradingHomeWatch(List<String> kinds, Function<String, Optional<String>> version) {
        this.kinds = List.copyOf(kinds);
        this.version = version;
    }

    /** The kinds watched. */
    public List<String> kinds() {
        return kinds;
    }

    /** The current state: each kind's version, read now. */
    public State read() {
        Map<String, String> v = new LinkedHashMap<>();
        for (String k : kinds) {
            String s;
            try {
                s = version.apply(k).orElse("none");
            } catch (RuntimeException e) {
                s = "none";                      // a source that cannot say: no tick from it until it can
            }
            v.put(k, s);
        }
        return new State(Map.copyOf(v), generation(v));
    }

    /** One short, stable name for a set of versions (the same versions, the same generation, on every node). */
    static String generation(Map<String, String> versions) {
        StringBuilder all = new StringBuilder();
        versions.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> all.append(e.getKey()).append('=').append(e.getValue()).append('\n'));
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(all.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
