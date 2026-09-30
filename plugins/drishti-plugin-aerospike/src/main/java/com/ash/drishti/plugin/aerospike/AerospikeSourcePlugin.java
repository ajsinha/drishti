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
package com.ash.drishti.plugin.aerospike;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Host;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.ScanPolicy;
import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.HitIndex;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Serves entities from Aerospike: the set of a data domain holds a record per entity with a bin per business date
 * (see {@link DatedRecords}). A read is a single-record get by key, then the date is chosen like every dated
 * source: a {@code snapshot} kind takes the kind's newest date on or before the one asked (within
 * {@code lookback-days}) and an entity without a document for that date is gone; an {@code effective} kind takes the
 * entity's last date on or before it. The dates of each kind, the identifiers (for search) and the references (for
 * reverse lookups) are learned by scanning the set at start and every {@code refresh-seconds}.
 *
 * <p>Settings: {@code hosts} ({@code localhost:3000}), {@code namespace} ({@code test}), {@code set} (the data
 * domain, e.g. {@code trading}), {@code kinds} (default: what the scan finds), {@code mode.<kind>}, {@code reverse-index}
 * (true; the reference index grows with entities × dates × references, so large sets turn it off), {@code lookback-days}
 * (10), {@code refresh-seconds} (60), {@code source-name} ({@code aerospike}), {@code user}/{@code password} (optional).
 */
public final class AerospikeSourcePlugin implements SourcePlugin {

    /** What a scan learns: per kind, the business dates present; per (kind, date), referenced id -> referring ids. */
    private record Catalog(Map<String, NavigableSet<LocalDate>> dates, Map<String, Map<LocalDate, Map<String, Set<String>>>> refs,
            Map<String, NavigableMap<LocalDate, Set<String>>> idsByDate) {}

    private final Map<String, String> modes = new HashMap<>();
    private final HitIndex index = new HitIndex();
    private volatile Catalog catalog = new Catalog(Map.of(), Map.of(), Map.of());
    private AerospikeClient client;
    private SourceContext context;
    private String namespace;
    private String set;
    private String sourceName;
    private int lookbackDays;
    private List<String> configuredKinds = List.of();
    private boolean reverseIndex = true;

    @Override
    public PluginManifest manifest() {
        Set<String> kinds = configuredKinds.isEmpty() ? catalog.dates().keySet() : new HashSet<>(configuredKinds);
        return new PluginManifest(sourceName == null ? "aerospike" : sourceName, "1.0", kinds, new SourceCapabilities(false, reverseIndex, true, true));
    }

    @Override
    public void start(SourceContext ctx) {
        this.context = ctx;
        this.sourceName = ctx.setting("source-name", "aerospike");
        this.namespace = ctx.setting("namespace", "test");
        this.set = ctx.setting("set", ctx.setting("domain", "drishti"));
        this.lookbackDays = Integer.parseInt(ctx.setting("lookback-days", "10"));
        this.reverseIndex = Boolean.parseBoolean(ctx.setting("reverse-index", "true"));
        String kinds = ctx.setting("kinds", "");
        this.configuredKinds = kinds.isBlank() ? List.of() : java.util.Arrays.stream(kinds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        ctx.settings().forEach((k, v) -> {
            if (k.startsWith("mode.")) {
                modes.put(k.substring(5), v);
            }
        });
        ClientPolicy policy = new ClientPolicy();
        policy.user = ctx.setting("user", null);
        policy.password = ctx.setting("password", null);
        policy.timeout = Integer.parseInt(ctx.setting("connect-timeout-ms", "3000"));
        // Start even when the cluster is not reachable or still initialising: the client keeps trying in the background,
        // reads fail (and health says so) until it answers, and the next rescan fills the catalogue.
        policy.failIfNotConnected = false;
        this.client = new AerospikeClient(policy, Host.parseHosts(ctx.setting("hosts", "localhost:3000"), 3000));
        rescan();
        long refresh = Long.parseLong(ctx.setting("refresh-seconds", "60"));
        ctx.scheduler().scheduleWithFixedDelay(this::rescan, refresh, refresh, TimeUnit.SECONDS);
    }

    /** Scans the set once: dates per kind, identifiers for search, references for reverse lookups. */
    void rescan() {
        Map<String, NavigableSet<LocalDate>> dates = new ConcurrentHashMap<>();
        Map<String, Map<LocalDate, Map<String, Set<String>>>> refs = new ConcurrentHashMap<>();
        Map<String, NavigableMap<LocalDate, Set<String>>> ids = new ConcurrentHashMap<>();
        List<EntityHit> hits = java.util.Collections.synchronizedList(new ArrayList<>());
        ScanPolicy sp = new ScanPolicy();
        sp.concurrentNodes = true;
        try {
            client.scanAll(sp, namespace, set, (Key key, Record r) -> {
                String kind = r.getString(DatedRecords.KIND);
                String id = r.getString(DatedRecords.ID);
                if (kind == null || id == null) {
                    return;
                }
                hits.add(new EntityHit(EntityRef.of(kind, id), id, kind + " · " + sourceName));
                DatedRecords.byDate(r).forEach((d, json) -> {
                    synchronized (dates) {
                        dates.computeIfAbsent(kind, k -> new TreeSet<>()).add(d);
                        ids.computeIfAbsent(kind, k -> new java.util.TreeMap<>()).computeIfAbsent(d, k -> new HashSet<>()).add(id);
                        Map<String, Set<String>> byTarget = refs.computeIfAbsent(kind, k -> new HashMap<>()).computeIfAbsent(d, k -> new HashMap<>());
                        for (String target : reverseIndex ? ReferenceScanner.referencedIds(json) : java.util.Set.<String>of()) {
                            byTarget.computeIfAbsent(target, t -> new TreeSet<>()).add(id);
                        }
                    }
                });
            });
        } catch (RuntimeException e) {
            return;   // keep the last catalogue; health reports the connection
        }
        catalog = new Catalog(dates, refs, ids);
        index.replaceAll(hits);
    }

    private boolean effective(String kind) {
        return "effective".equals(modes.get(kind));
    }

    /** The snapshot date for {@code kind} on {@code asked}: the kind's newest date on or before it, within the lookback. */
    private Optional<LocalDate> snapshotDate(String kind, LocalDate asked) {
        NavigableSet<LocalDate> ds = catalog.dates().get(kind);
        if (ds == null || ds.isEmpty()) {
            return Optional.empty();
        }
        LocalDate d = asked == null ? ds.last() : ds.floor(asked);
        if (d == null || asked != null && d.isBefore(asked.minusDays(lookbackDays))) {
            return Optional.empty();
        }
        return Optional.of(d);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws Exception {
        return fetch(ref, AsOf.LATEST);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) throws Exception {
        if (!configuredKinds.isEmpty() && !configuredKinds.contains(ref.kind())) {
            return Optional.empty();
        }
        Record r = client.get(null, new Key(namespace, set, DatedRecords.key(ref.kind(), ref.id())));
        if (r == null) {
            return Optional.empty();
        }
        NavigableMap<LocalDate, String> docs = DatedRecords.byDate(r);
        LocalDate asked = asOf.businessDate();
        Map.Entry<LocalDate, String> hit;
        if (effective(ref.kind())) {
            hit = asked == null ? docs.lastEntry() : docs.floorEntry(asked);
        } else {
            LocalDate d = snapshotDate(ref.kind(), asked).orElse(null);
            if (d == null && catalog.dates().get(ref.kind()) == null) {
                hit = asked == null ? docs.lastEntry() : docs.floorEntry(asked);   // not scanned yet: best effort
            } else {
                hit = d == null || !docs.containsKey(d) ? null : Map.entry(d, docs.get(d));
            }
        }
        if (hit == null) {
            return Optional.empty();
        }
        var data = context.parseJson(new ByteArrayInputStream(hit.getValue().getBytes(StandardCharsets.UTF_8)));
        return Optional.of(new EntityDocument(ref, data, new Provenance(sourceName, r.generation, Instant.now(), false, hit.getKey())));
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind) {
        return reverse(target, kind, AsOf.LATEST);
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind, AsOf asOf) {
        List<EntityRef> out = new ArrayList<>();
        for (String k : kind == null ? catalog.refs().keySet() : Set.of(kind)) {
            Map<LocalDate, Map<String, Set<String>>> byDate = catalog.refs().getOrDefault(k, Map.of());
            Set<String> found = new TreeSet<>();
            if (effective(k)) {
                // each entity's latest document on or before the date decides
                NavigableMap<LocalDate, Set<String>> idsByDate = catalog.idsByDate().getOrDefault(k, new java.util.TreeMap<>());
                Map<String, LocalDate> latest = new HashMap<>();
                (asOf.businessDate() == null ? idsByDate : idsByDate.headMap(asOf.businessDate(), true))
                        .forEach((d, ids) -> ids.forEach(i -> latest.merge(i, d, (a, b) -> a.isAfter(b) ? a : b)));
                latest.forEach((i, d) -> {
                    if (byDate.getOrDefault(d, Map.of()).getOrDefault(target.id(), Set.of()).contains(i)) {
                        found.add(i);
                    }
                });
            } else {
                snapshotDate(k, asOf.businessDate()).ifPresent(d -> found.addAll(byDate.getOrDefault(d, Map.of()).getOrDefault(target.id(), Set.of())));
            }
            found.forEach(i -> out.add(EntityRef.of(k, i)));
        }
        return out;
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return index.search(kind, text, limit);
    }

    @Override
    public Map<String, Object> cacheStats() {
        return Map.of("kinds", catalog.dates().size(), "datesIndexed", catalog.dates().values().stream().mapToInt(Set::size).sum());
    }

    /** Rebuilds the scanned catalogue now. */
    @Override
    public void purgeCaches() {
        rescan();
    }

    @Override
    public String health() {
        return client != null && client.isConnected() ? "UP" : "DOWN: not connected to Aerospike";
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }
}
