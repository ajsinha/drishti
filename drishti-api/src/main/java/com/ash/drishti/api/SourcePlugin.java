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
package com.ash.drishti.api;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A source of entity documents, discovered through {@link java.util.ServiceLoader}. Implementations
 * must be thread-safe: {@link #fetch} is called concurrently from many virtual threads.
 */
public interface SourcePlugin extends AutoCloseable {

    PluginManifest manifest();

    /** Called once before any other method. */
    void start(SourceContext context) throws Exception;

    /** Reads one entity; empty when the source does not hold it. May block (it runs on a virtual thread). */
    Optional<EntityDocument> fetch(EntityRef ref) throws Exception;

    /**
     * Reads one entity as of a business date (and optionally a knowledge time). Dated sources override this;
     * the default ignores {@code asOf}, which is right for sources that only hold current data.
     */
    default Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) throws Exception {
        return fetch(ref);
    }

    /** Pushes each new generation of the entity to {@code listener} until the subscription is closed. */
    default Subscription subscribe(EntityRef ref, Consumer<EntityDocument> listener) {
        throw new UnsupportedOperationException(manifest().name() + " is not live");
    }

    /** Entities of {@code kind} that reference {@code target} (for example trades in a netting set). */
    default List<EntityRef> reverse(EntityRef target, String kind) {
        return List.of();
    }

    /** {@link #reverse(EntityRef, String)} as of a business date; dated sources override it. */
    default List<EntityRef> reverse(EntityRef target, String kind, AsOf asOf) {
        return reverse(target, kind);
    }

    /**
     * Entities of {@code kind} whose identifier or name contains {@code text} (case-insensitive), best
     * matches first, at most {@code limit}. Powers the command-line suggestion dropdown; must be fast
     * (an in-memory index, not a remote scan).
     */
    default List<EntityHit> search(String kind, String text, int limit) {
        return List.of();
    }

    /** {@link #search(String, String, int)} as of a business date; dated sources override it. */
    default List<EntityHit> search(String kind, String text, int limit, AsOf asOf) {
        return search(kind, text, limit);
    }

    /** Human-readable health; {@code "UP"} when healthy. */
    default String health() {
        return "UP";
    }

    @Override
    default void close() {}
}
