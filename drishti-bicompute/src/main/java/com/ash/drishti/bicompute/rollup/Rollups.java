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
package com.ash.drishti.bicompute.rollup;

import com.ash.drishti.bicompute.core.AggregationPlan;
import com.ash.drishti.bicompute.core.AggregationPlan.FunctionCall;
import com.ash.drishti.bicompute.core.CellKey;
import com.ash.drishti.bicompute.core.CellSet;
import com.ash.drishti.bicompute.core.Dim;
import com.ash.drishti.bicompute.core.Partials;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.functions.Functions;
import com.ash.drishti.bicompute.spi.Kind;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The rollups of partial states (PIVOT_ENGINE.md section 9.6): their definitions, the rollups built per date, kept in memory and on disk
 * (one file per rollup and date under the folder given, written whole and renamed), and their <b>incremental refresh</b>: a new data
 * version with parts (files) only added merges the new parts' states into their partitions; a partition that lost a part (a restatement,
 * a delete) is rebuilt from its current parts alone; a source that is not parted is rebuilt whole. Refreshes run one at a time, off the
 * request threads; a stale rollup never answers ({@link AggregateAwareness} reads only rollups at the source's current version).
 */
public final class Rollups {

    private static final Logger LOG = LoggerFactory.getLogger(Rollups.class);

    private final Map<String, RollupDef> defs = new ConcurrentHashMap<>();
    private final Map<String, Rollup> built = new ConcurrentHashMap<>();       // id|date -> rollup
    private final Map<String, RollupSource> sources;                            // provider id -> its rollup source
    private final Path dir;
    private final ExecutorService refresher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "drishti-pivot-rollups");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> queued = ConcurrentHashMap.newKeySet();

    /**
     * @param sources the providers' rollup sources (provider id to source)
     * @param dir where rollups are kept on disk (null: memory only)
     */
    public Rollups(Map<String, RollupSource> sources, Path dir) {
        this.sources = Map.copyOf(sources);
        this.dir = dir;
    }

    public Map<String, RollupSource> sources() {
        return sources;
    }

    /** The rollup source that serves a source, or null. */
    public RollupSource sourceOf(String source) {
        for (RollupSource s : sources.values()) {
            if (s.provider().serves(source)) {
                return s;
            }
        }
        return null;
    }

    // ---- definitions --------------------------------------------------------------------------------------------------------------------

    /** Defines (or replaces) a rollup; refused in words when a function is holistic or unknown, or no provider offers rollups of the source. */
    public RollupDef define(RollupDef d) {
        RollupSource src = sourceOf(d.source());
        if (src == null) {
            throw PivotView.bad("no rollups of '" + d.source() + "': its provider does not offer them (VaR's own per-node sums are its rollup)");
        }
        functions(d, src);
        RollupDef old = defs.put(d.id(), d);
        if (old != null && !old.equals(d)) {
            built.keySet().removeIf(k -> k.startsWith(d.id() + "|"));
            delete(d.id());
        }
        load(d);
        return d;
    }

    /** Removes a rollup and its files. */
    public boolean remove(String id) {
        boolean had = defs.remove(id) != null;
        built.keySet().removeIf(k -> k.startsWith(id + "|"));
        delete(id);
        return had;
    }

    public List<RollupDef> definitions() {
        return defs.values().stream().sorted(java.util.Comparator.comparing(RollupDef::id)).toList();
    }

    /** The definition's functions parsed on the source's provider: mergeable or sketch only. */
    static List<FunctionCall> functions(RollupDef d, RollupSource src) {
        List<FunctionCall> out = new ArrayList<>();
        for (String text : d.functions()) {
            FunctionCall f = Functions.parse(text, null, src.provider());
            if (f.fn().kind() == Kind.HOLISTIC || !f.fn().sqlStateMergeable()) {
                throw PivotView.bad("rollup " + d.id() + ": " + f.id() + " is holistic; a rollup keeps only states that merge (sum, count, avg, "
                        + "var, approx_count_distinct, approx_percentile, top)");
            }
            out.add(f);
        }
        return out;
    }

    // ---- built rollups -----------------------------------------------------------------------------------------------------------------

    /** The rollups of a source on a date that are at the source's current version (stale ones are queued for refresh, not returned). */
    public List<Rollup> current(String source, String date) {
        List<Rollup> out = new ArrayList<>();
        RollupSource src = sourceOf(source);
        if (src == null) {
            return out;
        }
        String version = null;
        for (RollupDef d : defs.values()) {
            if (!d.source().equals(source)) {
                continue;
            }
            Rollup r = built.get(d.id() + "|" + date);
            if (version == null) {
                version = src.dataVersion(source, date);
            }
            if (r != null && version.equals(r.version)) {
                out.add(r);
            } else if (date != null && (date.equals(src.latestDate(source)) || "all".equals(d.dates())) || date == null) {
                refreshLater(d.id(), date);
            }
        }
        out.sort(java.util.Comparator.comparingLong(Rollup::rows));
        return out;
    }

    public List<Rollup> all() {
        return new ArrayList<>(built.values());
    }

    /** Queues a refresh of one rollup on one date (once: a refresh queued twice runs once). */
    public void refreshLater(String id, String date) {
        String key = id + "|" + date;
        if (queued.add(key)) {
            refresher.submit(() -> {
                try {
                    refresh(id, date);
                } catch (RuntimeException e) {
                    LOG.warn("pivot rollup {} on {} not refreshed: {}", id, date, e.getMessage());
                } finally {
                    queued.remove(key);
                }
            });
        }
    }

    /** Queues a refresh of every rollup on its source's latest date (and its kept dates): the loads' hook and the periodic check. */
    public void refreshAllLater() {
        for (RollupDef d : defs.values()) {
            RollupSource src = sourceOf(d.source());
            if (src == null) {
                continue;
            }
            refreshLater(d.id(), src.latestDate(d.source()));
        }
    }

    /**
     * Brings one rollup on one date to its source's current version, incrementally where the source is parted: parts added are merged into
     * their partitions; a partition that lost a part is rebuilt; a partition gone is dropped. What it did, in words.
     */
    public synchronized String refresh(String id, String date) {
        RollupDef d = defs.get(id);
        if (d == null) {
            throw new DrishtiException(ErrorCode.PIVOT_NOT_FOUND, "no rollup '" + id + "'");
        }
        RollupSource src = sourceOf(d.source());
        String version = src.dataVersion(d.source(), date);
        Rollup have = built.get(id + "|" + date);
        if (have != null && version.equals(have.version)) {
            return "rollup " + id + " is current (" + version + ")";
        }
        List<FunctionCall> fns = functions(d, src);
        Rollup r = new Rollup(d, date, fns);
        if (have != null) {
            have.partitions.forEach((k, p) -> {
                Rollup.Partition c = new Rollup.Partition();
                c.parts.addAll(p.parts);
                c.rows.putAll(p.rows);
                r.partitions.put(k, c);
            });
        }
        List<RollupSource.Part> parts = src.parts(d.source(), date);
        String words;
        if (parts.isEmpty()) {
            r.partitions.clear();
            Rollup.Partition all = new Rollup.Partition();
            all.rows.putAll(grain(src, d, date, fns, null));
            r.partitions.put(Rollup.WHOLE, all);
            words = "rebuilt whole (the source is not parted)";
        } else {
            Map<String, TreeSet<String>> now = new TreeMap<>();
            for (RollupSource.Part p : parts) {
                now.computeIfAbsent(p.partition() == null ? Rollup.WHOLE : p.partition(), k -> new TreeSet<>()).add(p.id());
            }
            int added = 0;
            int rebuilt = 0;
            int dropped = 0;
            for (String gone : new ArrayList<>(r.partitions.keySet())) {
                if (!now.containsKey(gone)) {
                    r.partitions.remove(gone);
                    dropped++;
                }
            }
            for (Map.Entry<String, TreeSet<String>> e : now.entrySet()) {
                Rollup.Partition p = r.partitions.get(e.getKey());
                if (p != null && e.getValue().containsAll(p.parts)) {
                    List<String> fresh = e.getValue().stream().filter(x -> !p.parts.contains(x)).toList();
                    if (!fresh.isEmpty()) {
                        r.absorb(p, grain(src, d, date, fns, fresh));
                        p.parts.addAll(fresh);
                        added += fresh.size();
                    }
                } else {
                    Rollup.Partition np = new Rollup.Partition();
                    np.rows.putAll(grain(src, d, date, fns, new ArrayList<>(e.getValue())));
                    np.parts.addAll(e.getValue());
                    r.partitions.put(e.getKey(), np);
                    rebuilt++;
                }
            }
            words = added + " new part(s) merged, " + rebuilt + " partition(s) built or rebuilt, " + dropped + " dropped";
        }
        r.changed();
        r.version = version;
        r.refreshedAt = Instant.now().toString();
        r.lastRefresh = words;
        built.put(id + "|" + date, r);
        if (!"all".equals(d.dates())) {
            built.keySet().removeIf(k -> k.startsWith(id + "|") && !k.equals(id + "|" + date));
        }
        save(r);
        return "rollup " + id + " on " + date + ": " + words + "; " + r.rows() + " rows of partial states at " + version;
    }

    /** The grain's states over some parts (null: every row), as the system reads them. */
    static Map<CellKey, Partials.Entry> grain(RollupSource src, RollupDef d, String date, List<FunctionCall> fns, List<String> parts) {
        Map<String, Dim> dims = new LinkedHashMap<>();
        d.grain().forEach(g -> dims.put(g, Dim.parse(g)));
        AggregationPlan plan = new AggregationPlan(d.source(), date, null, Map.of(), dims, fns, List.of(CellSet.of(d.grain(), Map.of())), null, 0);
        Partials p = src.evaluate(plan, parts);
        return p.sets().isEmpty() ? Map.of() : p.sets().get(0).cells();
    }

    // ---- disk --------------------------------------------------------------------------------------------------------------------------

    private Path file(String id, String date) {
        return dir.resolve(id).resolve((date == null ? "undated" : date) + ".rollup");
    }

    private void save(Rollup r) {
        if (dir == null) {
            return;
        }
        try {
            Path f = file(r.def.id(), r.date);
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".part");
            Files.write(tmp, r.encode());
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.warn("pivot rollup {} not saved: {}", r.def.id(), e.getMessage());
        }
    }

    private void load(RollupDef d) {
        if (dir == null || !Files.isDirectory(dir.resolve(d.id()))) {
            return;
        }
        RollupSource src = sourceOf(d.source());
        Map<String, FunctionCall> byId = new LinkedHashMap<>();
        functions(d, src).forEach(f -> byId.put(f.id(), f));
        try (var files = Files.list(dir.resolve(d.id()))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".rollup")).toList()) {
                try {
                    Rollup r = Rollup.decode(d, Files.readAllBytes(f), id -> {
                        FunctionCall c = byId.get(id);
                        if (c == null) {
                            throw new IllegalStateException("the file keeps " + id + ", not in the definition");
                        }
                        return c;
                    });
                    if (r != null && r.functions.size() == byId.size()) {
                        built.put(d.id() + "|" + r.date, r);
                    }
                } catch (RuntimeException e) {
                    LOG.info("pivot rollup file {} not read ({}); it is rebuilt when asked", f, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void delete(String id) {
        if (dir == null || !Files.isDirectory(dir.resolve(id))) {
            return;
        }
        try (var files = Files.list(dir.resolve(id))) {
            for (Path f : files.toList()) {
                Files.deleteIfExists(f);
            }
            Files.deleteIfExists(dir.resolve(id));
        } catch (IOException e) {
            LOG.warn("pivot rollup {} files not deleted: {}", id, e.getMessage());
        }
    }

    /** A rollup's state for Admin and the API. */
    public static Map<String, Object> describe(Rollup r) {
        Map<String, Object> m = new LinkedHashMap<>(r.def.toMap());
        m.put("date", r.date);
        m.put("version", r.version);
        m.put("refreshedAt", r.refreshedAt);
        m.put("lastRefresh", r.lastRefresh);
        m.put("rows", r.rows());
        m.put("bytes", r.bytes());
        m.put("partitions", r.partitions.size());
        return m;
    }

    /** Stops the refresher (the host's shutdown). */
    public void close() {
        refresher.shutdownNow();
    }
}
