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
     * An identifier of the kind's data as the source holds it now (a Delta table's version and commit time), or empty when the source
     * cannot tell. When it changes, whatever was built from the old data (the BI store's columns and rollups) is stale and is built again.
     * Must be cheap: it is asked on every BI query that reads a stored layer.
     */
    default Optional<String> dataVersion(String kind) {
        return Optional.empty();
    }

    /**
     * The newest business date this source holds the kind for, or empty when it cannot say (or keeps only current data). Must be
     * cheap (no read of data): a search on a date without data names it, so the person can switch to a date that has some.
     */
    default Optional<java.time.LocalDate> latestDate(String kind) {
        return Optional.empty();
    }

    /**
     * The oldest business date this source holds the kind for, or empty when it cannot say. Must be cheap, as {@link #latestDate}:
     * Drishti*net gossips it, so a node that is down counts only for the dates it may hold (DRISHTI_NET.md section 7).
     */
    default Optional<java.time.LocalDate> earliestDate(String kind) {
        return Optional.empty();
    }

    /**
     * Every business date this source holds the kind for, oldest first, or empty when it cannot say. Must be cheap, as {@link #latestDate}
     * (what the source already keeps: a lake's layout, a folder per date): a statement for a date without data names the nearest dates that
     * have some (STATEMENTS.md section 6).
     */
    default Optional<java.util.NavigableSet<java.time.LocalDate>> heldDates(String kind) {
        return Optional.empty();
    }

    /**
     * The version of one business date's data of the kind (a Delta table's files for that date), or the kind's {@link #dataVersion(String)}
     * when the source cannot be more precise: a later day appended leaves an earlier day's stored layer valid.
     */
    default Optional<String> dataVersion(String kind, java.time.LocalDate date) {
        return dataVersion(kind);
    }

    /**
     * How many rows one business date of the kind holds, from the source's metadata only (a Delta table's add-action statistics, an Iceberg
     * snapshot's manifests), or empty when it cannot say without reading data (the default). Must read no data file: the BI runs planner asks it
     * for every date of a question before anything runs (docs/architecture/BI_RUNS.md section 4.1).
     */
    default Optional<RowEstimate> rowEstimate(String kind, java.time.LocalDate date) {
        return Optional.empty();
    }

    /**
     * Where this source keeps the kind's data, written so that every server reading the same data says the same (a Delta table's id
     * from its log, else its location), or empty when it cannot say. Drishti*net calls two servers whose sources name the same identity
     * <em>replicas</em> of the kind (a search asks one of them) and different identities <em>shards</em> (a search asks all). Must be cheap.
     */
    default Optional<String> tableIdentity(String kind) {
        return Optional.empty();
    }

    /**
     * True when this source surely holds nothing of the kind now: a Delta connector with no table for it, a folder with no file of it.
     * Drishti*net leaves such a source out of the node's capability digest, so a node is never taken for a holder of what it cannot have
     * (never asked for a document, a search or a grouped page of it). False when it cannot say, the default. Must be cheap.
     */
    default boolean holdsNone(String kind) {
        return false;
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

    /**
     * The document paths this source keeps as vector columns for the kind (a Delta table's {@code layout.<kind>.vectors}: a
     * {@code list<double>} column such as a P&amp;L vector), empty when it keeps none.
     */
    default java.util.Set<String> vectorColumns(String kind) {
        return java.util.Set.of();
    }

    /**
     * Every entity of the kind on the business date with its vector {@code column} and the text columns {@code texts} (each one
     * {@link #columnar} lists), streamed to {@code sink} file by file without holding the date in memory. Returns false, having given
     * nothing, when the source cannot read the column so; true when it read the date (possibly nothing).
     */
    default boolean vectors(String kind, String column, java.util.List<String> texts, AsOf asOf, VectorSink sink) throws Exception {
        return false;
    }

    /**
     * Every document of the kind on the business date, in one pass over the source (a whole day read file by file, not
     * entity by entity), for connectors built from other kinds (derived pages). Returns false, having given nothing to
     * {@code sink}, when the source cannot read so (the caller then reads entity by entity); true when it read the date
     * (possibly nothing: the source does not hold it).
     */
    default boolean documents(String kind, AsOf asOf, Consumer<EntityDocument> sink) throws Exception {
        return false;
    }

    /**
     * Bulk read for analytics (RUPAKA.md section 6.1): the chosen paths of a kind over a business-date range, filters applied, as column
     * batches. Empty when the source cannot answer it as columns (the caller then reads records and warns that a large table will be slow).
     * Sources that promote columns implement it with {@link ColumnScans#over}.
     */
    default Optional<ArrowScan> scan(ScanRequest request) throws Exception {
        return Optional.empty();
    }

    /**
     * The entity on each business date of the request that this source holds, oldest first (HISTORY_QUERIES.md section 9). The default
     * reads date by date through {@link #fetch(EntityRef, AsOf)}, {@link HistoryRequest#parallelDays()} at once ({@link HistoryReads#perDay});
     * a connector whose layout can list or read an entity's dates in one go overrides it. A date answered by an earlier date's document is
     * not a day of the entity. {@link HistoryRequest#fields()}, when set, may cut the documents to those top-level fields.
     */
    default HistoryRead history(HistoryRequest request) throws Exception {
        return HistoryReads.perDay(this, request);
    }

    /**
     * The business dates from..to (both included) on which this source holds the entity, or empty when it cannot list them cheaply (then
     * every business date is read). Must read no document.
     */
    default Optional<java.util.NavigableSet<java.time.LocalDate>> days(EntityRef ref, java.time.LocalDate from, java.time.LocalDate to) {
        return Optional.empty();
    }

    /** Where this source's SQL store keeps its kinds, so a compiled query can run in the source (pushdown); empty when it is not a SQL store. */
    default Optional<SqlTarget> sqlTarget() {
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
     * This source's multi-day type-ahead index ({@code index.days} above 1), for the admin's index page and reload; empty when
     * it keeps one day or has none. Reload is by {@link DatedHitIndex#reload}.
     */
    default Optional<DatedHitIndex> typeAheadIndex() {
        return Optional.empty();
    }

    /**
     * The state of this source's database connection pool (open, in use, idle, waiting, longest borrow), for Admin and the
     * health page; empty for a source that holds no pooled database connections (files, queues, REST, and the embedded
     * SQLite and DuckDB, which are not pooled).
     */
    default Optional<com.ash.drishti.api.pool.PoolStats> poolStats() {
        return Optional.empty();
    }

    /**
     * True when what this source serves is computed from the entities of {@code kind} read through other sources (a derived kind:
     * pages per counterparty built from trades), so a load of {@code kind} makes its caches stale. The default is false.
     */
    default boolean dependsOn(String kind) {
        return false;
    }

    /**
     * Drops everything this source caches (memory and disk); the next reads refill from the source of truth.
     * Called by an admin at any time; must be safe while reads are in flight.
     */
    default void purgeCaches() {
    }

    /**
     * Looks for the source's newest data at the next read, dropping no cached data: a data-load signal that arrives while a purge of this
     * source has just run is merged into the next one ({@code drishti.loads.refresh-window}), and only this runs for it, so the load's
     * checks still see the new version. The default does nothing (the source's own refresh interval applies).
     */
    default void refreshVersions() {
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
    /**
     * What the source holds for a kind, newest business date first: the date and how many entities it has (at most
     * {@code maxDates} dates). Empty when the source cannot say. Used by Admin → Packs → Data source → Test connection;
     * it may read data, so it is never called on a request path.
     */
    default java.util.Map<java.time.LocalDate, Long> inventory(String kind, int maxDates) {
        return java.util.Map.of();
    }

    default String health() {
        return "UP";
    }

    @Override
    default void close() {}

    /**
     * The settings this plugin reads, for the connector form and validator (Admin → Connectors). The default declares
     * none, and the server then falls back to its catalogue of the shipped plugins; a plugin that declares them here wins.
     */
    default List<SettingSpec> settingSpecs() {
        return List.of();
    }

    /**
     * The key of a {@code writes:} command's block this plugin performs ({@code jdbc}, {@code rest}), or empty when it is read-only
     * (WRITE_BACK.md section 5.4: {@code write} is implemented once per plugin, never per source system). A connector whose plugin has
     * none cannot have {@code writes:}.
     */
    default java.util.Optional<String> writeKey() {
        return java.util.Optional.empty();
    }

    /**
     * What is wrong with one command's block, in words (empty when it is usable): the plugin checks its template, its required
     * concurrency guard and idempotency clause, and that every parameter it names is one of {@code known}. A command with a problem is
     * refused when the connector loads, which then writes nothing.
     */
    default List<String> checkWrite(String command, java.util.Map<String, Object> block, java.util.Set<String> known) {
        return List.of("plugin " + manifest().name() + " cannot write");
    }

    /** Performs one command in the source's own transaction, guarded and idempotent by the change id (WRITE_BACK.md section 5.3). */
    default com.ash.drishti.api.write.WriteResult write(com.ash.drishti.api.write.WriteCommand command) throws Exception {
        throw new UnsupportedOperationException("plugin " + manifest().name() + " cannot write");
    }
}
