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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Which kinds conform to which contracts (engine specification 10.1 rule 1): each running pack's {@code kinds.<kind>.conforms: [owner/name@major]}.
 * Binding rule 2 asks it for the kinds that meet an input's contract; the pack that declares each is kept for the words.
 */
public final class KindContracts {

    /** Nobody conforms to anything: every input must be named in its engine file. */
    public static final KindContracts NONE = new KindContracts(Map.of());

    private final Map<String, Map<String, String>> byContract;   // contract (owner/name@major) -> kind -> pack

    private KindContracts(Map<String, Map<String, String>> byContract) {
        this.byContract = byContract;
    }

    /** From the running packs' manifests, by pack name ({@code Pack.manifest()}: the pack.yaml as read). */
    @SuppressWarnings("unchecked")
    public static KindContracts of(Map<String, Map<String, Object>> manifests) {
        Map<String, Map<String, String>> out = new TreeMap<>();
        new TreeMap<>(manifests).forEach((pack, m) -> {
            if (!(m.get("kinds") instanceof Map<?, ?> kinds)) {
                return;
            }
            ((Map<String, Object>) kinds).forEach((kind, decl) -> {
                if (decl instanceof Map<?, ?> d && d.get("conforms") instanceof List<?> list) {
                    for (Object c : list) {
                        out.computeIfAbsent(normal(String.valueOf(c)), x -> new LinkedHashMap<>()).put(kind, pack);
                    }
                }
            });
        });
        return new KindContracts(out);
    }

    /** The kinds that conform to {@code contract} ({@code sample/item@1}; a {@code @1.2} minor is read as its major), each with its pack. */
    public Map<String, String> conforming(String contract) {
        return byContract.getOrDefault(normal(contract), Map.of());
    }

    /** Every contract some kind conforms to. */
    public List<String> contracts() {
        return new ArrayList<>(byContract.keySet());
    }

    /** {@code owner/name@major}: a minor or patch after the major is dropped (contracts bind by major, ENGINE_INTEGRATION_DESIGN 0.6). */
    static String normal(String contract) {
        String c = contract == null ? "" : contract.strip();
        int at = c.indexOf('@');
        if (at < 0) {
            return c;
        }
        String v = c.substring(at + 1);
        int dot = v.indexOf('.');
        return c.substring(0, at + 1) + (dot < 0 ? v : v.substring(0, dot));
    }
}
