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
package com.ash.drishti.plugin.delta;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.HitIndex;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.engine.Engine;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.hadoop.conf.Configuration;

/**
 * Serves entities from Delta Lake tables, one table per kind, partitioned by business date:
 * {@code <root>/<domain>/<kind>/} with {@code business_date=yyyy-MM-dd/} partitions of {@code (id, doc)} rows.
 *
 * <p>A read for a business date takes the entity from the latest partition on or before that date: within
 * {@code lookback-days} for {@code snapshot} tables (a full copy every business date, the default), or without
 * limit for {@code effective} tables (a row only when the entity changes, e.g. reference data). {@code knownAt}
 * reads the table as it was at that instant (Delta time travel), before later corrections.
 *
 * <p>Settings: {@code root} (default {@code ./data/delta}), {@code domain} (a sub-folder, e.g. {@code finance}),
 * {@code kinds} (comma list; default: every table found), {@code mode.<kind>} ({@code snapshot}|{@code effective}),
 * {@code lookback-days} (10), {@code id-column} ({@code id}), {@code doc-column} ({@code doc}), {@code date-column}
 * ({@code business_date}), {@code refresh-seconds} (10: how often a table's latest version is checked),
 * {@code cache-partitions} (256), {@code source-name} ({@code delta}).
 */
public final class DeltaSourcePlugin implements SourcePlugin {

    private record PartKey(String kind, long version, LocalDate date) {}

    /** One partition in memory: documents by id, and a reverse index (referenced id to referring ids). */
    private record Part(Map<String, String> docs, Map<String, Set<String>> refs) {}

    private final Map<String, DeltaTable> tables = new LinkedHashMap<>();
    private final Map<String, String> modes = new HashMap<>();
    private final HitIndex index = new HitIndex();
    private Cache<String, DeltaTable.Layout> latest;
    private Cache<String, DeltaTable.Layout> travelled;
    private Cache<PartKey, Part> parts;
    private SourceContext context;
    private String sourceName;
    private int lookbackDays;
    private Path base;

    @Override
    public PluginManifest manifest() {
        return new PluginManifest(sourceName == null ? "delta" : sourceName, "1.0", tables.keySet(), new SourceCapabilities(false, true, true, true));
    }

    @Override
    public void start(SourceContext ctx) throws IOException {
        this.context = ctx;
        this.sourceName = ctx.setting("source-name", "delta");
        this.lookbackDays = Integer.parseInt(ctx.setting("lookback-days", "10"));
        Path root = Path.of(ctx.setting("root", "./data/delta")).toAbsolutePath().normalize();
        String domain = ctx.setting("domain", "");
        this.base = domain.isBlank() ? root : root.resolve(domain).normalize();
        if (!base.startsWith(root)) {
            throw new IllegalArgumentException("domain escapes the Delta root: " + domain);
        }
        Engine engine = DefaultEngine.create(new Configuration());
        String id = ctx.setting("id-column", "id");
        String doc = ctx.setting("doc-column", "doc");
        String date = ctx.setting("date-column", "business_date");
        for (String kind : kinds(ctx.setting("kinds", ""))) {
            tables.put(kind, new DeltaTable(engine, base.resolve(kind).toUri().toString(), id, doc, date));
            modes.put(kind, ctx.setting("mode." + kind, "snapshot"));
        }
        long refresh = Long.parseLong(ctx.setting("refresh-seconds", "10"));
        this.latest = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(refresh)).build();
        this.travelled = Caffeine.newBuilder().maximumSize(256).build();
        this.parts = Caffeine.newBuilder().maximumSize(Long.parseLong(ctx.setting("cache-partitions", "256"))).build();
        reindex();
        ctx.scheduler().scheduleWithFixedDelay(this::reindex, refresh * 6, refresh * 6, TimeUnit.SECONDS);
    }

    private List<String> kinds(String configured) throws IOException {
        if (!configured.isBlank()) {
            return java.util.Arrays.stream(configured.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(base)) {
            return s.filter(p -> Files.isDirectory(p.resolve("_delta_log"))).map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private Optional<DeltaTable.Layout> layout(String kind, Instant knownAt) {
        DeltaTable t = tables.get(kind);
        if (t == null) {
            return Optional.empty();
        }
        if (knownAt == null) {
            return Optional.ofNullable(latest.get(kind, k -> t.layout(null).orElse(null)));
        }
        return Optional.ofNullable(travelled.get(kind + "@" + knownAt.toEpochMilli(), k -> t.layout(knownAt).orElse(null)));
    }

    private Part part(String kind, DeltaTable.Layout l, LocalDate date) {
        return parts.get(new PartKey(kind, l.version(), date), k -> {
            Map<String, String> docs = tables.get(kind).read(l, date);
            Map<String, Set<String>> refs = new HashMap<>();
            docs.forEach((id, json) -> ReferenceScanner.referencedIds(json).forEach(r -> refs.computeIfAbsent(r, x -> new HashSet<>()).add(id)));
            return new Part(Collections.unmodifiableMap(docs), refs);
        });
    }

    /** Partitions to look in for {@code date}, newest first. */
    private List<LocalDate> candidates(String kind, DeltaTable.Layout l, LocalDate date) {
        var head = date == null ? l.files().descendingKeySet() : l.files().headMap(date, true).descendingKeySet();
        List<LocalDate> out = new ArrayList<>();
        boolean effective = "effective".equals(modes.get(kind));
        for (LocalDate d : head) {
            if (!effective && date != null && d.isBefore(date.minusDays(lookbackDays))) {
                break;
            }
            out.add(d);
            if (!effective) {
                break;   // a snapshot table: the newest partition on or before the date holds everything
            }
        }
        return out;
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws IOException {
        return fetch(ref, AsOf.LATEST);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) throws IOException {
        Optional<DeltaTable.Layout> l = layout(ref.kind(), asOf.knownAt());
        if (l.isEmpty()) {
            return Optional.empty();
        }
        for (LocalDate d : candidates(ref.kind(), l.get(), asOf.businessDate())) {
            String json = part(ref.kind(), l.get(), d).docs().get(ref.id());
            if (json != null) {
                DataNode doc = context.parseJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
                return Optional.of(new EntityDocument(ref, doc, new Provenance(sourceName, l.get().version(), Instant.now(), false,
                        d == LocalDate.MIN ? null : d)));
            }
        }
        return Optional.empty();
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind) {
        return reverse(target, kind, AsOf.LATEST);
    }

    @Override
    public List<EntityRef> reverse(EntityRef target, String kind, AsOf asOf) {
        List<EntityRef> out = new ArrayList<>();
        for (String k : kind == null ? tables.keySet() : List.of(kind)) {
            layout(k, asOf.knownAt()).ifPresent(l -> {
                Set<String> seen = new HashSet<>();
                for (LocalDate d : candidates(k, l, asOf.businessDate())) {
                    part(k, l, d).refs().getOrDefault(target.id(), Set.of()).stream().filter(seen::add).sorted()
                            .forEach(id -> out.add(EntityRef.of(k, id)));
                }
            });
        }
        return out;
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return index.search(kind, text, limit);
    }

    /** Rebuilds the search index from each table's newest partition (identifiers are stable across dates). */
    void reindex() {
        List<EntityHit> hits = new ArrayList<>();
        for (String kind : tables.keySet()) {
            try {
                layout(kind, null).ifPresent(l -> {
                    if (!l.files().isEmpty()) {
                        part(kind, l, l.files().lastKey()).docs().keySet()
                                .forEach(id -> hits.add(new EntityHit(EntityRef.of(kind, id), id, kind + " · " + sourceName)));
                    }
                });
            } catch (RuntimeException e) {
                // a table being rewritten is indexed next time
            }
        }
        index.replaceAll(hits);
    }

    @Override
    public String health() {
        if (!Files.isDirectory(base)) {
            return "DOWN: no directory " + base;
        }
        return tables.isEmpty() ? "DOWN: no Delta tables under " + base : "UP";
    }
}
