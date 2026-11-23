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
package com.ash.drishti.server.home;

import com.ash.drishti.kernel.api.home.HomeData;
import com.ash.drishti.kernel.api.home.MeasureSource;
import com.ash.drishti.kernel.api.home.HomeDefinition;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds why a followed measure moved (ROLE_HOME_SUTRAS.md 4.4) by asking the measure's own engine, never by a second implementation: after
 * each load or recompute it reads the measure now, finds the subjects whose move since the reference snapshot is above the measure's
 * thresholds, and for each asks the engine for its drivers (input groups swapped one at a time, in a fixed order). The rows are stored
 * once for the firm; the {@code changes} engine turns them into the WHY words. Cost is bounded: only subjects above their thresholds, only
 * measures that name drivers, once per load, a subject cap per measure.
 */
public final class AttributionRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AttributionRunner.class);
    static final int MOST_SUBJECTS = 50;

    private final HomeCatalogue catalogue;
    private final HomeStore store;
    private final HomeClock clock;
    private final Map<String, MeasureSource> sources;

    public AttributionRunner(HomeCatalogue catalogue, HomeStore store, HomeClock clock, List<MeasureSource> sources) {
        this.catalogue = catalogue;
        this.store = store;
        this.clock = clock;
        Map<String, MeasureSource> m = new java.util.LinkedHashMap<>();
        sources.forEach(s -> m.put(s.engine(), s));
        this.sources = Map.copyOf(m);
    }

    /** Runs after a load or recompute completes (hook: {@code LoadService.onReady}); returns the driver rows stored. */
    public synchronized int run() {
        LocalDate now = clock.today();
        LocalDate then = clock.previous();
        List<HomeData.MeasureValue> before = store.snapshot(now.equals(then) ? now : then);
        if (before.isEmpty()) {
            return 0;                                           // no reference snapshot yet: nothing to explain, the Home says so
        }
        Map<String, Double> was = new java.util.HashMap<>();
        before.forEach(v -> was.put(v.key(), v.value()));
        List<HomeData.Driver> out = new ArrayList<>();
        java.util.Set<String> done = new java.util.HashSet<>();
        for (HomeDefinition d : catalogue.all()) {
            for (HomeDefinition.Follow f : d.measures().follow()) {
                MeasureSource s = sources.get(f.engine());
                if (f.drivers().isEmpty() || s == null || !s.on() || !done.add(f.engine() + "|" + f.measure() + "|" + f.subjects())) {
                    continue;
                }
                List<HomeData.MeasureValue> current = s.values(MeasureSnapshotJob.FIRM, f.measure(), f.subjects(), now);
                int n = 0;
                for (HomeData.MeasureValue v : current) {
                    Double w = was.get(v.key());
                    if (w == null || n >= MOST_SUBJECTS) {
                        continue;
                    }
                    double move = Math.abs(v.value() - w);
                    if (move < f.minAbs() || (w != 0 && move / Math.abs(w) < f.minRel())) {
                        continue;
                    }
                    n++;
                    try {
                        out.addAll(s.drivers(MeasureSnapshotJob.FIRM, f.measure(), f.subjects(), v.subject(), then, now, f.drivers()));
                    } catch (RuntimeException e) {
                        LOG.warn("home attribution: {} {} left unexplained: {}", f.measure(), v.subject(), e.toString());
                    }
                }
            }
        }
        store.putDrivers(now, out);
        return out.size();
    }

}
