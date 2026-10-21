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
package com.ash.drishti.engine.source;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.Subscription;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A named connector: one configured instance of a plugin ({@code drishti.sources.connectors.<name>}), so a pack
 * or a site can run the same plugin several times (a Delta Lake per domain, two databases) under distinct names.
 * The manifest carries the connector's name, and {@code kinds}, when set, limits what it serves.
 */
final class ConnectorInstance implements SourcePlugin {

    private final String name;
    private final Set<String> kinds;
    private final SourcePlugin delegate;

    /** The plugin instance this connector runs. */
    SourcePlugin delegate() {
        return delegate;
    }

    ConnectorInstance(String name, Set<String> kinds, SourcePlugin delegate) {
        this.name = name;
        this.kinds = Set.copyOf(kinds);
        this.delegate = delegate;
    }

    @Override
    public PluginManifest manifest() {
        PluginManifest m = delegate.manifest();
        Set<String> served = kinds.isEmpty() ? m.kinds() : kinds;
        return new PluginManifest(name, m.version(), served, m.capabilities());
    }

    private boolean serves(String kind) {
        return kinds.isEmpty() || kind == null || kinds.contains(kind);
    }

    @Override
    public java.util.Map<java.time.LocalDate, Long> inventory(String kind, int maxDates) {
        return serves(kind) ? delegate.inventory(kind, maxDates) : java.util.Map.of();
    }

    @Override
    public void start(SourceContext context) throws Exception {
        delegate.start(context);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws Exception {
        return serves(ref.kind()) ? delegate.fetch(ref) : Optional.empty();
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) throws Exception {
        return serves(ref.kind()) ? delegate.fetch(ref, asOf) : Optional.empty();
    }

    @Override
    public com.ash.drishti.api.HistoryRead history(com.ash.drishti.api.HistoryRequest request) throws Exception {
        return serves(request.ref().kind()) ? delegate.history(request) : com.ash.drishti.api.HistoryRead.NONE;
    }

    @Override
    public Optional<java.util.NavigableSet<java.time.LocalDate>> days(EntityRef ref, java.time.LocalDate from, java.time.LocalDate to) {
        return serves(ref.kind()) ? delegate.days(ref, from, to) : Optional.of(new java.util.TreeSet<>());
    }

    @Override
    public Optional<java.time.LocalDate> latestDate(String kind) {
        return serves(kind) ? delegate.latestDate(kind) : Optional.empty();
    }

    @Override
    public Optional<java.time.LocalDate> earliestDate(String kind) {
        return serves(kind) ? delegate.earliestDate(kind) : Optional.empty();
    }

    @Override
    public Optional<java.util.NavigableSet<java.time.LocalDate>> heldDates(String kind) {
        return serves(kind) ? delegate.heldDates(kind) : Optional.empty();
    }

    @Override
    public com.ash.drishti.api.DateCoverage coverage(String kind, AsOf asOf) {
        return serves(kind) ? delegate.coverage(kind, asOf) : com.ash.drishti.api.DateCoverage.NOT_HELD;
    }

    @Override
    public boolean timeTravel() {
        return delegate.timeTravel();
    }

    @Override
    public Subscription subscribe(EntityRef ref, Consumer<EntityDocument> listener) {
        return serves(ref.kind()) ? delegate.subscribe(ref, listener) : Subscription.NONE;
    }

    @Override
    public java.time.Instant lastUpdate() {
        return delegate.lastUpdate();
    }

    @Override
    public java.util.Set<String> columnar(String kind) {
        return delegate.columnar(kind);
    }

    @Override
    public Optional<com.ash.drishti.api.ColumnSet> columns(String kind, java.util.Collection<String> paths, AsOf asOf) throws Exception {
        return delegate.columns(kind, paths, asOf);
    }

    @Override
    public boolean documents(String kind, AsOf asOf, java.util.function.Consumer<EntityDocument> sink) throws Exception {
        return serves(kind) ? delegate.documents(kind, asOf, sink) : true;     // a kind it does not serve: it holds none
    }

    @Override
    public Optional<com.ash.drishti.api.ArrowScan> scan(com.ash.drishti.api.ScanRequest request) throws Exception {
        return serves(request.kind()) ? delegate.scan(request) : Optional.empty();
    }

    @Override
    public Optional<com.ash.drishti.api.SqlTarget> sqlTarget() {
        return delegate.sqlTarget();
    }

    @Override
    public boolean pushes(EntityRef ref) {
        return serves(ref.kind()) && delegate.pushes(ref);
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind) {
        return serves(kind) ? delegate.reverse(target, kind) : List.of();
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind, AsOf asOf) {
        return serves(kind) ? delegate.reverse(target, kind, asOf) : List.of();
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return serves(kind) ? delegate.search(kind, text, limit).stream().filter(h -> serves(h.ref().kind())).toList() : List.of();
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit, AsOf asOf) {
        return serves(kind) ? delegate.search(kind, text, limit, asOf).stream().filter(h -> serves(h.ref().kind())).toList() : List.of();
    }

    @Override
    public java.util.Optional<String> listingProblem(String kind) {
        return serves(kind) ? delegate.listingProblem(kind) : java.util.Optional.empty();
    }

    @Override
    public Optional<FieldNote> describeField(String kind, String field) {
        return serves(kind) ? delegate.describeField(kind, field) : Optional.empty();
    }

    @Override
    public java.util.Map<String, Object> cacheStats() {
        return delegate.cacheStats();
    }

    @Override
    public java.util.Optional<com.ash.drishti.api.DatedHitIndex> typeAheadIndex() {
        return delegate.typeAheadIndex();
    }

    @Override
    public java.util.Optional<com.ash.drishti.api.pool.PoolStats> poolStats() {
        return delegate.poolStats();
    }

    @Override
    public boolean dependsOn(String kind) {
        return delegate.dependsOn(kind);
    }

    @Override
    public void purgeCaches() {
        delegate.purgeCaches();
    }

    @Override
    public void refreshVersions() {
        delegate.refreshVersions();
    }

    @Override
    public java.util.Optional<String> tableIdentity(String kind) {
        return delegate.tableIdentity(kind);
    }

    @Override
    public boolean holdsNone(String kind) {
        return delegate.holdsNone(kind);
    }

    @Override
    public java.util.Optional<String> dataVersion(String kind) {
        return delegate.dataVersion(kind);
    }

    @Override
    public java.util.Optional<String> dataVersion(String kind, java.time.LocalDate date) {
        return delegate.dataVersion(kind, date);
    }

    @Override
    public java.util.Optional<com.ash.drishti.api.RowEstimate> rowEstimate(String kind, java.time.LocalDate date) {
        return delegate.rowEstimate(kind, date);
    }

    @Override
    public String health() {
        return delegate.health();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
