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
package com.ash.drishti.server.pivot;

import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.sql.DuckDbExecutor;
import com.ash.drishti.bicompute.sql.TabularProvider;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.server.admission.AdmissionGate;
import com.ash.drishti.server.admission.Work;
import com.ash.drishti.server.data.DataViewService;
import com.ash.drishti.server.edition.EditionCommands;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.var.VarPivotProvider;
import com.ash.drishti.server.var.VarService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The pivot engine's beans (PIVOT_GUIDE.md): the node's DuckDB executor, the tabular provider over the Data view's tables, the VaR provider
 * when the VaR engine runs here, the partial-state cache in the one memory budget ({@code pivot.states}), busy protection, and saved views.
 * The engine itself is the {@code drishti-bicompute} module's {@link PivotCore}; nothing here aggregates.
 */
@Configuration
@EnableConfigurationProperties(PivotProperties.class)
public class PivotConfiguration {

    /** The partial-state cache's name in Admin → Caches and the one memory budget. */
    public static final String CACHE = "pivot.states";

    /** The partial-state cache as a Caffeine cache (byte-weighed), so the memory budget can see and trim it. */
    public static final class States implements StateCache {
        final Cache<String, Object[]> cache;

        States(long maxBytes) {
            this.cache = Caffeine.newBuilder().maximumWeight(maxBytes).weigher((String k, Object[] v) -> (int) Math.min(Integer.MAX_VALUE,
                    (long) v[1])).recordStats().build();
        }

        @Override
        public Object get(String key) {
            Object[] v = cache.getIfPresent(key);
            return v == null ? null : v[0];
        }

        @Override
        public void put(String key, Object value, long bytes) {
            cache.put(key, new Object[] {value, bytes + 64L + 2L * key.length()});
        }

        @Override
        public long bytes() {
            return cache.policy().eviction().map(e -> e.weightedSize().orElse(0L)).orElse(0L);
        }

        @Override
        public long entries() {
            return cache.estimatedSize();
        }

        @Override
        public void clear() {
            cache.invalidateAll();
        }

        public Cache<String, Object[]> caffeine() {
            return cache;
        }
    }

    @Bean(destroyMethod = "close")
    public DuckDbExecutor pivotExecutor(PivotProperties props) {
        return new DuckDbExecutor(props.executor());
    }

    @Bean
    public KindSources pivotKindSources(DataViewService data, SourceRouter router, Entitlements entitlements, EditionCommands edition,
            PivotProperties props) {
        return new KindSources(data, router, entitlements, edition, props);
    }

    @Bean
    public States pivotStates(PivotProperties props) {
        return new States((long) props.cacheMb() << 20);
    }

    @Bean
    public PivotCore pivotCore(PivotProperties props, DuckDbExecutor exec, KindSources kinds, States states, ObjectProvider<VarService> var,
            ObjectProvider<AdmissionGate> gate) {
        List<PivotProvider> providers = new ArrayList<>();
        VarService v = var.getIfAvailable();
        if (v != null) {
            providers.add(new VarPivotProvider(v));
        }
        providers.add(new TabularProvider(exec, kinds));
        PivotCore core = new PivotCore(providers, states, props.core());
        AdmissionGate g = gate.getIfAvailable();
        if (g != null) {
            core.admission((viewer, what) -> g.admit(Work.PIVOT_VIEW, viewer.user(), what));
        }
        return core;
    }

    @Bean
    public PivotViews pivotViews(ContentStore store) {
        return new PivotViews(store);
    }
}
