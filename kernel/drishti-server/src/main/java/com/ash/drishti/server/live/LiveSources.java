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
 * A {@code q-} name not here is a search whose table expired ({@link #expired}): never read as a kind. At most {@link #MAX} are kept, the least
 * recently declared leaving first; a source that leaves is handed to the listener ({@link #onEvict}), which releases its declaration.
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

    /** The prefix of a search's table name ({@code FormResults}). */
    public static final String SEARCH = "q-";

    private final Map<String, Source> sources = new LinkedHashMap<>();
    private final int max;
    private volatile java.util.function.Consumer<String> evicted = name -> { };

    public LiveSources() {
        this(MAX);
    }

    /** At most {@code max} sources (a test's small cap). */
    LiveSources(int max) {
        this.max = max;
    }

    /** Told the name of each source that leaves (the live tables release its declaration and close its open table). */
    public void onEvict(java.util.function.Consumer<String> listener) {
        evicted = listener == null ? name -> { } : listener;
    }

    /** Keeps a source under a name (replacing one of that name); the oldest leaves beyond the cap, and the listener is told. */
    public void put(String name, Source s) {
        List<String> gone = new java.util.ArrayList<>();
        synchronized (this) {
            sources.remove(name);
            sources.put(name, s);
            while (sources.size() > max) {
                String oldest = sources.keySet().iterator().next();
                sources.remove(oldest);
                gone.add(oldest);
            }
        }
        java.util.function.Consumer<String> l = evicted;
        for (String g : gone) {
            try {
                l.accept(g);
            } catch (RuntimeException e) {
                // releasing one never stops the others or the put
            }
        }
    }

    /** Whether a name is a search's table whose source is gone (evicted, or never made here): it is read again by running the search. */
    public boolean expired(String name) {
        return name != null && name.startsWith(SEARCH) && get(name).isEmpty();
    }

    /** The refusal of a read of an expired search's table: {@code 404 rdp.table.unknown}, its own words. */
    public static com.ash.rachana.data.ProtocolError expiredError(String name) {
        return com.ash.rachana.data.ProtocolError.of("rdp.table.unknown", 404, "the search's table " + name
                + " has expired: run the search again", "table", name, "expired", true);
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
