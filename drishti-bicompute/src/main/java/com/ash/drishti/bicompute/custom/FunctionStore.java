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
package com.ash.drishti.bicompute.custom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where custom functions' versions are kept (PIVOT_ENGINE.md section 8.7): the host's store (the server keeps them in the content store,
 * so a function follows its author across a cluster); every version is kept.
 */
public interface FunctionStore {

    /** Every version of {@code owner/name}, oldest first (empty: no such function). */
    List<FunctionDef> versions(String owner, String name);

    /** Saves a version (a new one, or the same version with new sharing). */
    void put(FunctionDef def);

    /** The latest version of every function. */
    List<FunctionDef> latest();

    /** One version, when there is one. */
    default Optional<FunctionDef> get(String owner, String name, int version) {
        return versions(owner, name).stream().filter(d -> d.version() == version).findFirst();
    }

    /** A store in memory (tests, the playground). */
    static FunctionStore inMemory() {
        return new FunctionStore() {
            private final Map<String, List<FunctionDef>> all = new ConcurrentHashMap<>();

            @Override
            public List<FunctionDef> versions(String owner, String name) {
                return List.copyOf(all.getOrDefault(owner + "/" + name, List.of()));
            }

            @Override
            public synchronized void put(FunctionDef def) {
                List<FunctionDef> l = new ArrayList<>(all.getOrDefault(def.id(), List.of()));
                l.removeIf(d -> d.version() == def.version());
                l.add(def);
                l.sort(Comparator.comparingInt(FunctionDef::version));
                all.put(def.id(), List.copyOf(l));
            }

            @Override
            public List<FunctionDef> latest() {
                List<FunctionDef> out = new ArrayList<>();
                all.values().forEach(l -> out.add(l.get(l.size() - 1)));
                out.sort(Comparator.comparing(FunctionDef::id));
                return out;
            }
        };
    }
}
