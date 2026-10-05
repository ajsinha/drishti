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

    /**
     * Whether this dated source holds the kind's data for {@code asOf}'s business date (its latest when the date is
     * null). One that does is authoritative for the date: an entity it does not list then is not held, and the router
     * asks no later source for it. Must be cheap (no remote call per read). The default, {@link DateCoverage#UNKNOWN},
     * keeps the router asking the next source when this one does not hold an entity.
     */
    default DateCoverage coverage(String kind, AsOf asOf) {
        return DateCoverage.UNKNOWN;
    }

    /**
     * True when this source answers {@link AsOf#knownAt()} (it keeps earlier versions: Delta Lake, Iceberg). A dated
     * source that does not is never asked for a read with {@code knownAt} for a date it may hold: the read fails with
     * {@code DRS-1007} naming it, instead of showing today's data as if it were what was known then.
     */
    default boolean timeTravel() {
        return false;
    }

    /**
     * Pushes each new generation of the entity to {@code listener} until the subscription is closed. A source that
     * learns the entity was deleted (a Kafka tombstone, a queue's delete message) pushes
     * {@link EntityDocument#deleted(EntityRef, Provenance)} through the same listener, so a delete and a later
     * re-creation arrive in order; open views then say the entity was deleted. Sources that never delete need do
     * nothing, and listeners that do not care about deletes skip documents whose {@link EntityDocument#deleted()} is
     * true.
     */
    default Subscription subscribe(EntityRef ref, Consumer<EntityDocument> listener) {
        throw new UnsupportedOperationException(manifest().name() + " is not live");
    }

    /**
     * True when this source pushes updates for the entity even though another source answers its reads: a stream that
     * keeps no state (a Kafka connector in {@code ticks} mode) driving views a lake or database serves. A live view of
     * the entity then subscribes here. The default is false: a live source normally pushes what it also serves.
     */
    default boolean pushes(EntityRef ref) {
        return false;
    }

    /**
     * When this source last received new data (a message, a refresh that changed something, a new table version), or
     * null when it cannot tell (a database read on demand). Views say how old their data is from it, and a connector's
     * {@code stale-after} setting turns it into a warning.
     */
    default java.time.Instant lastUpdate() {
        return null;
    }

    /**
     * The document paths this source keeps as columns for the kind (a Delta table's promoted columns), so searches and
     * aggregates can read them without documents; empty when it keeps none.
     */
    default java.util.Set<String> columnar(String kind) {
        return java.util.Set.of();
    }

    /**
     * Every entity of the kind on the business date with these paths, column by column; empty when the source cannot
     * answer them as columns (then documents are read). Only called with paths {@link #columnar} lists.
     */
    default Optional<ColumnSet> columns(String kind, java.util.Collection<String> paths, AsOf asOf) throws Exception {
        return Optional.empty();
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

    /**
     * Why this source's {@link #search} of the kind is incomplete right now, or empty when it is complete: its index of
     * the kind could not be rebuilt (a table it cannot read), so it lists an older set of entities or none. A search that
     * lists the kind is then reported partial, with this reason, instead of looking exact. Shown to whoever searched,
     * so it names what cannot be read without secrets. The default is complete.
     */
    default Optional<String> listingProblem(String kind) {
        return Optional.empty();
    }

    /**
     * What this source caches, for the admin's cache page: entry counts, sizes, hits. Empty when it caches nothing.
     */
    default java.util.Map<String, Object> cacheStats() {
        return java.util.Map.of();
    }

    /**
     * Drops everything this source caches (memory and disk); the next reads refill from the source of truth.
     * Called by an admin at any time; must be safe while reads are in flight.
     */
    default void purgeCaches() {
    }

    /**
     * What the plugin's own definition says a field of one of its kinds means, for About this page's glossary: a derived kind
     * says "sum of mtm over the trades" and the formula. The default knows nothing.
     *
     * @param means one sentence of meaning
     * @param formula the formula as configured
     * @param origin where the definition is, for people to find it
     */
    record FieldNote(String means, String formula, String origin) {}

    default Optional<FieldNote> describeField(String kind, String field) {
        return Optional.empty();
    }

    /** Human-readable health; {@code "UP"} when healthy. */
    default String health() {
        return "UP";
    }

    @Override
    default void close() {}
}
