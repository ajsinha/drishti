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
package com.ash.drishti.enginehost;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the running packs use of engines (engine specification 10.1 rule 2): {@code pack.yaml uses.engines: [{engine, version, outputs,
 * optional, as}]}. The host checks each use against the installed descriptor at start (EN-41) and derives the packs' health from the engines
 * they use (specification 7.6 rule 3).
 */
public record PackUses(List<Use> uses) {

    /** One pack's use of one engine. {@code as}: output to {@code {kind, mnemonic}}, the pack's own names for it. */
    public record Use(String pack, String engine, String version, List<String> outputs, boolean optional, Map<String, Map<String, String>> as) {}

    /** No pack uses an engine. */
    public static final PackUses NONE = new PackUses(List.of());

    public PackUses {
        uses = List.copyOf(uses);
    }

    /** From the running packs' manifests, by pack name. An entry without {@code engine} is skipped (the pack's check reports it). */
    @SuppressWarnings("unchecked")
    public static PackUses of(Map<String, Map<String, Object>> manifests) {
        List<Use> out = new ArrayList<>();
        new TreeMap<>(manifests).forEach((pack, m) -> {
            if (!(m.get("uses") instanceof Map<?, ?> u) || !(u.get("engines") instanceof List<?> engines)) {
                return;
            }
            for (Object o : engines) {
                if (!(o instanceof Map<?, ?> e) || e.get("engine") == null) {
                    continue;
                }
                List<String> outputs = e.get("outputs") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
                Map<String, Map<String, String>> as = new TreeMap<>();
                if (e.get("as") instanceof Map<?, ?> a) {
                    ((Map<String, Object>) a).forEach((output, names) -> {
                        if (names instanceof Map<?, ?> n) {
                            Map<String, String> words = new TreeMap<>();
                            ((Map<String, Object>) n).forEach((k, v) -> words.put(k, String.valueOf(v)));
                            as.put(output, words);
                        }
                    });
                }
                out.add(new Use(pack, String.valueOf(e.get("engine")), e.get("version") == null ? "" : String.valueOf(e.get("version")), outputs,
                        Boolean.TRUE.equals(e.get("optional")), as));
            }
        });
        return new PackUses(out);
    }

    /** The uses of one engine. */
    public List<Use> of(String engine) {
        return uses.stream().filter(u -> u.engine().equals(engine)).toList();
    }

    /** Every pack and the engines it uses, for the health board. */
    public Map<String, List<String>> byPack() {
        Map<String, List<String>> out = new TreeMap<>();
        uses.forEach(u -> out.computeIfAbsent(u.pack(), k -> new ArrayList<>()).add(u.engine()));
        return out;
    }
}
