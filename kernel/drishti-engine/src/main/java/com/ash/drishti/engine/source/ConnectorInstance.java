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
 * The manifest carries the connector's name, and {@code kinds}, when set, limits what it serves. Its {@link SourceShape} ({@code where},
 * {@code set}, {@code map}) shapes every record it serves, the same for every plugin; a connector of a trading system that is switched off
 * ({@link SourceSystems}) serves nothing, and no connector serves a row of a system that is off.
 */
final class ConnectorInstance implements SourcePlugin {

    private final String name;
    private final Set<String> kinds;
    private final SourcePlugin delegate;
    private final SourceShape shape;
    private final SourceSystems systems;

    /** The plugin instance this connector runs. */
    SourcePlugin delegate() {
        return delegate;
    }

    ConnectorInstance(String name, Set<String> kinds, SourcePlugin delegate) {
        this(name, kinds, delegate, SourceShape.NONE, null);
    }

    ConnectorInstance(String name, Set<String> kinds, SourcePlugin delegate, SourceShape shape, SourceSystems systems) {
        this.name = name;
        this.kinds = Set.copyOf(kinds);
        this.delegate = delegate;
        this.shape = shape == null ? SourceShape.NONE : shape;
        this.systems = systems;
    }

    /** How this connector shapes what it reads. */
    SourceShape shape() {
        return shape;
    }

    /** True when the trading system this connector feeds is switched off: it serves nothing until it is on again. */
    boolean switchedOff() {
        return systems != null && shape.system().map(systems::off).orElse(false);
    }

    /** Nothing to shape and no system off: every call goes to the plugin as it is. */
    private boolean plain() {
        return shape.none() && (systems == null || systems.off().isEmpty());
    }

    /** The record as this connector serves it; null when its where leaves it out or its system is off. */
    private EntityDocument shaped(EntityDocument d) {
        if (d == null || plain()) {
            return d;
        }
        if (switchedOff()) {
            return null;
        }
        EntityDocument out = shape.apply(d);
        if (out != null && !out.deleted() && systems != null && !systems.off().isEmpty() && out.data() != null
                && systems.off(out.data().get(SourceShape.SOURCE_SYSTEM).asText())) {
            return null;
        }
        return out;
    }

    /**
     * The record as the store holds it, before {@code map} and {@code set} (Raw JSON shows the source's native record); empty when the
     * connector would not serve it.
     */
    Optional<EntityDocument> fetchNative(EntityRef ref, AsOf asOf) throws Exception {
        if (!serves(ref.kind()) || switchedOff()) {
            return Optional.empty();
        }
        Optional<EntityDocument> d = asOf == null ? delegate.fetch(ref) : delegate.fetch(ref, asOf);
        return d.filter(x -> shaped(x) != null);
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
        return serves(ref.kind()) ? delegate.fetch(ref).map(this::shaped) : Optional.empty();
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) throws Exception {
        return serves(ref.kind()) ? delegate.fetch(ref, asOf).map(this::shaped) : Optional.empty();
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
        if (!serves(ref.kind())) {
            return Subscription.NONE;
        }
        if (shape.none() && systems == null) {
            return delegate.subscribe(ref, listener);
        }
        return delegate.subscribe(ref, d -> {                  // each message filtered and mapped; a system switched off passes none
            EntityDocument s = shaped(d);
            if (s != null) {
                listener.accept(s);
            }
        });
    }

    @Override
    public Subscription subscribeKind(String kind, Consumer<EntityDocument> listener) {
        if (!serves(kind)) {
            return Subscription.NONE;
        }
        if (shape.none() && systems == null) {
            return delegate.subscribeKind(kind, listener);
        }
        return delegate.subscribeKind(kind, d -> {             // several live connectors of a kind: each passes its own system's records
            EntityDocument s = shaped(d);
            if (s != null) {
                listener.accept(s);
            }
        });
    }

    @Override
    public java.time.Instant lastUpdate() {
        return delegate.lastUpdate();
    }

    @Override
    public java.util.Set<String> columnar(String kind) {
        java.util.Set<String> stored = delegate.columnar(kind);
        if (shape.none() || stored.isEmpty()) {
            return stored;
        }
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        stored.forEach(c -> {
            String canonical = shape.canonicalName(c);
            if (canonical != null) {
                out.add(canonical);
            }
        });
        out.addAll(shape.set().keySet());
        return out;
    }

    @Override
    public Optional<com.ash.drishti.api.ColumnSet> columns(String kind, java.util.Collection<String> paths, AsOf asOf) throws Exception {
        if (plain()) {
            return delegate.columns(kind, paths, asOf);
        }
        if (switchedOff()) {
            return Optional.empty();
        }
        return ShapedColumns.read(delegate, shape, systems, kind, paths, asOf);
    }

    @Override
    public java.util.Set<String> vectorColumns(String kind) {
        return delegate.vectorColumns(kind);
    }

    @Override
    public boolean vectors(String kind, String column, java.util.List<String> texts, AsOf asOf, com.ash.drishti.api.VectorSink sink)
            throws Exception {
        return delegate.vectors(kind, column, texts, asOf, sink);
    }

    @Override
    public boolean vectors(String kind, String column, java.util.List<String> texts, AsOf asOf, com.ash.drishti.api.KeyRange range,
            com.ash.drishti.api.VectorSink sink) throws Exception {
        return delegate.vectors(kind, column, texts, asOf, range, sink);
    }

    @Override
    public boolean documents(String kind, AsOf asOf, java.util.function.Consumer<EntityDocument> sink) throws Exception {
        if (!serves(kind)) {
            return true;                                       // a kind it does not serve: it holds none
        }
        if (plain()) {
            return delegate.documents(kind, asOf, sink);
        }
        if (switchedOff()) {
            return true;
        }
        return delegate.documents(kind, asOf, d -> {
            EntityDocument s = shaped(d);
            if (s != null) {
                sink.accept(s);
            }
        });
    }

    @Override
    public Optional<com.ash.drishti.api.ArrowScan> scan(com.ash.drishti.api.ScanRequest request) throws Exception {
        // a shaped connector's rows are read through columns or documents, where its where, map and set apply
        return serves(request.kind()) && plain() ? delegate.scan(request) : Optional.empty();
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
        return serves(kind) ? mine(delegate.search(kind, text, limit).stream().filter(h -> serves(h.ref().kind())).toList(), null) : List.of();
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit, AsOf asOf) {
        return serves(kind) ? mine(delegate.search(kind, text, limit, asOf).stream().filter(h -> serves(h.ref().kind())).toList(), asOf) : List.of();
    }

    /** The hits that are this connector's records (its where; its system on): each checked by a read, the hits being few. */
    private List<EntityHit> mine(List<EntityHit> hits, AsOf asOf) {
        if (plain() || shape.where().all() && (systems == null || systems.off().isEmpty())) {
            return hits;
        }
        if (switchedOff()) {
            return List.of();
        }
        List<EntityHit> out = new java.util.ArrayList<>();
        for (EntityHit h : hits) {
            try {
                if ((asOf == null ? fetch(h.ref()) : fetch(h.ref(), asOf)).isPresent()) {
                    out.add(h);
                }
            } catch (Exception e) {
                out.add(h);                                    // the read failed: the hit stays (the record's own read says why)
            }
        }
        return out;
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
        return switchedOff() || delegate.holdsNone(kind);
    }

    @Override
    public java.util.Optional<String> dataVersion(String kind) {
        // a switch of a trading system moves the version, so a set paged from the store reads it again
        return systems == null || systems.version() == 0 ? delegate.dataVersion(kind)
                : delegate.dataVersion(kind).map(v -> v + "~s" + systems.version());
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
    public boolean memoryOnly() {
        return delegate.memoryOnly();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
