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
package com.ash.drishti.server.datamap;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.source.SourceFailures;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.packs.PackRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Builds the data map ({@link DataMap}) over the server's sources, in the background, once per business date and set of loaded packs, and
 * again after {@code drishti.datamap.ttl} (a load may have changed the data). A request gets what is known at once: the shape from the
 * pack files, then the counts, then each join's coverage as it is checked ({@link DataMap.Result#state}). The data is read as the server,
 * on {@code drishti.datamap.threads} threads, at most {@code drishti.datamap.max-rows} rows a kind; the controller leaves out what the
 * caller may not open.
 */
@Service
public class DataMapService {

    private static final Logger log = LoggerFactory.getLogger(DataMapService.class);

    private record Entry(DataMap.Result result, Instant started) {}

    private final PackRegistry registry;
    private final SourceRouter router;
    private final int maxRows;
    private final Duration ttl;
    private final Duration budget;
    private final ExecutorService pool;
    /** Runs each build's phases: a build waits on its tasks from here, never from a thread of {@link #pool}, so the pool never waits on itself. */
    private final ExecutorService driver = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("datamap-build").factory());
    private final Map<String, Entry> built = new ConcurrentHashMap<>();

    public DataMapService(PackRegistry registry, SourceRouter router, @Value("${drishti.datamap.max-rows:20000}") int maxRows,
            @Value("${drishti.datamap.ttl:PT15M}") Duration ttl, @Value("${drishti.datamap.read-budget:PT30S}") Duration budget,
            @Value("${drishti.datamap.threads:4}") int threads) {
        this.registry = registry;
        this.router = router;
        this.maxRows = Math.max(1, maxRows);
        this.ttl = ttl;
        this.budget = budget;
        this.pool = Executors.newFixedThreadPool(Math.max(1, threads), Thread.ofPlatform().daemon().name("datamap-", 0).factory());
    }

    /** The map for this date as far as it is built; starts building it when there is none, or when the last is older than the ttl. */
    public DataMap.Result current(AsOf asOf) {
        String date = asOf.businessDate() == null ? "latest" : asOf.businessDate().toString();
        String key = date + "@" + registry.version();
        Entry e = built.compute(key, (k, old) -> {
            if (old != null && (!"ready".equals(old.result().state) || old.started().plus(ttl).isAfter(Instant.now()))) {
                return old;
            }
            DataMap.Result fresh = new DataMap.Result(DataMap.read(registry.packs()), date);
            if (old != null) {                     // a rebuild: show the last counts and coverage until the new ones come
                fresh.counts.putAll(old.result().counts);
                fresh.coverage.putAll(old.result().coverage);
            }
            driver.execute(() -> build(fresh, asOf));
            return new Entry(fresh, Instant.now());
        });
        built.keySet().removeIf(k -> !k.equals(key) && built.size() > 4);
        return e.result();
    }

    private void build(DataMap.Result out, AsOf asOf) {
        long t0 = System.nanoTime();
        try {
            DataMap.build(lake(asOf), maxRows, out, tasks -> {
                List<Future<?>> running = new ArrayList<>();
                tasks.forEach(t -> running.add(pool.submit(t)));
                for (Future<?> f : running) {
                    try {
                        f.get();
                    } catch (Exception ex) {
                        log.debug("data map: a task failed", ex);
                    }
                }
            });
        } catch (RuntimeException ex) {
            log.warn("The data map for {} could not be built: {}", out.date, ex.toString());
            out.state = "ready";
        }
        log.info("Data map for {} built in {} ms: {} kinds, {} joins", out.date, (System.nanoTime() - t0) / 1_000_000,
                out.structure.kinds().size(), out.structure.joins().size());
    }

    /** The server's sources on the date, read as the server (the controller filters what a caller sees). */
    private DataMap.Lake lake(AsOf asOf) {
        return new DataMap.Lake() {
            @Override
            public DataMap.Ids ids(String kind, int limit) {
                SourceFailures failures = new SourceFailures();
                List<EntityHit> hits = router.list(kind, "", limit + 1, budget, asOf, failures).hits();
                Set<String> ids = new LinkedHashSet<>();
                for (int i = 0; i < Math.min(limit, hits.size()); i++) {
                    ids.add(hits.get(i).ref().id());
                }
                String failed = failures.asMap().entrySet().stream().map(f -> f.getKey() + ": " + f.getValue()).reduce((a, b) -> a + "; " + b).orElse(null);
                return new DataMap.Ids(ids, hits.size() > limit, hits.isEmpty() ? null : hits.get(0).ref().id(), failed);
            }

            @Override
            public Set<String> columnar(String kind) {
                return router.columnar(kind);
            }

            @Override
            public Optional<List<Object[]>> columns(String kind, List<String> fields, int limit) {
                Optional<ColumnSet> got = router.columns(kind, fields, asOf, budget, new SourceFailures());
                if (got.isEmpty()) {
                    return Optional.empty();
                }
                ColumnSet c = got.get();
                List<Object[]> rows = new ArrayList<>(Math.min(limit, c.size()));
                for (int r = 0; r < Math.min(limit, c.size()); r++) {
                    Object[] row = new Object[fields.size()];
                    for (int i = 0; i < fields.size(); i++) {
                        row[i] = c.value(fields.get(i), r);
                    }
                    rows.add(row);
                }
                return Optional.of(rows);
            }

            @Override
            public Set<String> exists(String kind, List<String> ids) {
                List<EntityRef> refs = ids.stream().map(id -> EntityRef.of(kind, id)).toList();
                Set<String> out = new LinkedHashSet<>();
                router.fetchAll(refs, budget, asOf).forEach((r, d) -> {
                    if (d != null && !d.deleted()) {
                        out.add(r.id());
                    }
                });
                return out;
            }

            @Override
            public boolean fetch(String kind, List<String> ids, Predicate<DataNode> sink) {
                List<EntityRef> refs = ids.stream().map(id -> EntityRef.of(kind, id)).toList();
                Map<EntityRef, EntityDocument> docs = router.fetchAll(refs, budget, asOf);
                boolean any = false;
                for (EntityRef r : refs) {
                    EntityDocument d = docs.get(r);
                    if (d == null || d.deleted()) {
                        continue;
                    }
                    any = true;
                    if (!sink.test(d.data())) {
                        break;
                    }
                }
                return any;
            }

            @Override
            public boolean documents(String kind, Predicate<DataNode> sink) {
                final class Enough extends RuntimeException {
                    Enough() {
                        super(null, null, false, false);
                    }
                }
                boolean[] stopped = {false};
                try {
                    return router.documents(kind, asOf, d -> {
                        if (!d.deleted() && !sink.test(d.data())) {
                            stopped[0] = true;
                            throw new Enough();
                        }
                    });
                } catch (RuntimeException e) {
                    if (stopped[0]) {
                        return true;                    // stopped at max-rows: what was read is the sample
                    }
                    throw e;
                }
            }
        };
    }
}
