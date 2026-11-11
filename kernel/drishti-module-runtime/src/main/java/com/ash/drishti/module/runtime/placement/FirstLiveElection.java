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
package com.ash.drishti.module.runtime.placement;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The minimal, deterministic election: the owner of a part is <b>the first live candidate by node name</b>. Every node that sees the same
 * placement and the same gossip membership picks the same owner, with no vote and no lease; when the owner stops being live, the next
 * candidate by name takes over, and never a node outside the placement.
 */
public final class FirstLiveElection implements SingletonElection {

    private final Supplier<? extends Collection<String>> live;
    private final Map<String, List<String>> candidates = new ConcurrentHashMap<>();
    private final Map<String, String> last = new ConcurrentHashMap<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    /** @param live the ids of the nodes that are up now (this node and the alive members) */
    public FirstLiveElection(Supplier<? extends Collection<String>> live) {
        this.live = live;
    }

    @Override
    public void candidates(String part, List<String> placed) {
        candidates.put(part, placed.stream().sorted().toList());
    }

    @Override
    public Optional<String> owner(String part) {
        Collection<String> up = live.get();
        Optional<String> o = candidates.getOrDefault(part, List.of()).stream().filter(up::contains).findFirst();
        String was = o.isPresent() ? last.put(part, o.get()) : last.remove(part);
        if (!Objects.equals(was, o.orElse(null)) && was != null) {
            listeners.forEach(l -> l.accept(part));
        }
        return o;
    }

    @Override
    public void onChange(Consumer<String> listener) {
        listeners.add(listener);
    }
}
