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
package com.ash.drishti.server.live;

import com.ash.drishti.api.AsOf;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The sources of the tables Drishti declares in code beyond a kind's own (a FORM's result: {@link FormResults}), by the name a declaration's
 * {@code snapshot:} and {@code stream:} give: the kind they read, the business date (a day other than the live one is static), whether they
 * read whole (a condition on a field no lake column holds), and the other Drishti*net members' rows and changes when the table spans them.
 * A name not here is a kind's own. At most {@link #MAX} are kept, the least recently declared leaving first.
 */
public final class LiveSources {

    /** The most sources kept. */
    public static final int MAX = 2_000;

    /**
     * One source.
     *
     * @param kind the kind read
     * @param at the business date (live: the current one, with its stream)
     * @param whole read whole even when a lake keeps columns
     * @param peers the other members' rows, or null (this node's only)
     * @param peerStream the other members' changes, or null
     */
    public record Source(String kind, AsOf at, boolean whole, KindSnapshot.Peers peers, KindStream.Peers peerStream) {

        /** Whether the table follows the kind's changes (the live business date). */
        public boolean live() {
            return at == null || at.isLatest();
        }
    }

    private final Map<String, Source> sources = new LinkedHashMap<>();

    /** Keeps a source under a name (replacing one of that name); the oldest leaves beyond {@link #MAX}. */
    public synchronized void put(String name, Source s) {
        sources.remove(name);
        sources.put(name, s);
        while (sources.size() > MAX) {
            sources.remove(sources.keySet().iterator().next());
        }
    }

    /** The source of a name, when one is kept. */
    public synchronized Optional<Source> get(String name) {
        return Optional.ofNullable(sources.get(name));
    }

    /** The names kept, oldest first. */
    public synchronized List<String> names() {
        return List.copyOf(sources.keySet());
    }
}
