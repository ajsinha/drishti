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
import com.ash.drishti.server.frtb.FrtbService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.var.MrCubeProperties;
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
 * when the VaR engine runs here (the MR cube when engine {@code mr-cube} runs too, MR_CUBE_GUIDE.md), the partial-state cache in the one memory budget ({@code pivot.states}), busy protection, and saved views.
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
            PivotProperties props, ObjectProvider<com.ash.drishti.engine.search.SearchProperties> search,
            ObjectProvider<com.ash.drishti.server.bi.DatasetService> datasets, ObjectProvider<com.ash.drishti.server.bi.engine.QueryService> queries) {
        KindSources k = new KindSources(data, router, entitlements, edition, props, search.getIfAvailable());
        k.layers(new DatasetLayers(datasets, queries));
        return k;
    }

    /** A panel's rows (a table or ladder whose Sutra says pivot:) as a source: the old Pivot tab's place, on the engine. */
    @Bean
    public PanelSources pivotPanelSources(com.ash.drishti.engine.ViewPipeline pipeline, Entitlements entitlements, PivotProperties props) {
        return new PanelSources(pipeline, entitlements, props.stageKeep());
    }

    /** Virtual tables as sources ({@code table:<domain>.<name>}, TABLES_GUIDE.md section 7a). */
    @Bean
    public TableSources pivotTableSources(com.ash.drishti.server.tables.TablesService tables, Entitlements entitlements, PivotProperties props) {
        return new TableSources(tables, entitlements, props.stageKeep());
    }

    /** The Data view's tables, the virtual tables and the panels' rows, one resolver for the tabular provider. */
    static com.ash.drishti.bicompute.sql.TabularSource.Resolver both(KindSources kinds, PanelSources panels, TableSources tables) {
        return new com.ash.drishti.bicompute.sql.TabularSource.Resolver() {
            @Override
            public java.util.Map<String, String> sources(com.ash.drishti.bicompute.spi.Viewer v) {
                java.util.Map<String, String> out = new java.util.LinkedHashMap<>(kinds.sources(v));
                if (tables != null) {
                    out.putAll(tables.sources(v));
                }
                return out;
            }

            @Override
            public boolean serves(String source) {
                return panels.serves(source) || tables != null && tables.serves(source) || kinds.serves(source);
            }

            @Override
            public com.ash.drishti.bicompute.sql.TabularSource open(com.ash.drishti.bicompute.spi.Viewer v, String source, String date) {
                if (panels.serves(source)) {
                    return panels.open(v, source, date);
                }
                return tables != null && tables.serves(source) ? tables.open(v, source, date) : kinds.open(v, source, date);
            }
        };
    }

    @Bean
    public States pivotStates(PivotProperties props) {
        return new States((long) props.cacheMb() << 20);
    }

    @Bean
    public PivotCore pivotCore(PivotProperties props, DuckDbExecutor exec, KindSources kinds, PanelSources panels, ObjectProvider<TableSources> tables,
            States states,
            ObjectProvider<VarService> var, ObjectProvider<MrCubeProperties> mr, ObjectProvider<FrtbService> frtb,
            org.springframework.core.env.Environment env,
            ObjectProvider<AdmissionGate> gate, ObjectProvider<com.ash.drishti.server.engines.EngineRegistry> engines,
            ObjectProvider<com.ash.drishti.server.var.VarParametricProperties> parametric) {
        List<PivotProvider> providers = new ArrayList<>();
        VarService v = var.getIfAvailable();
        if (v != null) {
            boolean mrSwitch = env.getProperty("drishti.engines.mr-cube.enabled", Boolean.class, false);
            providers.add(new VarPivotProvider(v, () -> running(engines, "var", true), () -> running(engines, "mr-cube", mrSwitch),
                    mr.getIfAvailable(MrCubeProperties::defaults), () -> running(engines, "frtb-sa", true)
                            ? com.ash.drishti.server.var.MrCubeExtras.running(frtb.getIfAvailable()) : null)
                    .parametric(parametric.getIfAvailable()));
        }
        providers.add(new TabularProvider(exec, both(kinds, panels, tables.getIfAvailable())));
        PivotCore core = new PivotCore(providers, states, props.core());
        AdmissionGate g = gate.getIfAvailable();
        if (g != null) {
            core.admission((viewer, what) -> g.admit(Work.PIVOT_VIEW, viewer.user(), what));
        }
        return core;
    }

    /** True when an engine runs here ({@code dflt} without the registry: tests and tools that start no engines). */
    static boolean running(ObjectProvider<com.ash.drishti.server.engines.EngineRegistry> engines, String id, boolean dflt) {
        com.ash.drishti.server.engines.EngineRegistry r = engines.getIfAvailable();
        return r == null ? dflt : !r.known(id) || r.running(id);
    }

    /**
     * The pivot engine as a compute engine (docs/architecture/ARCHITECTURE.md section 25, {@code config/engines/pivot.yaml}): off, its API answers
     * 404 DRS-1020, the PIVOT word, the menu entry, Help pages and the CLI's pivot commands are gone, and its partial states and staged
     * tables are released.
     */
    @Bean
    public com.ash.drishti.server.engines.BasicEngine pivotComputeEngine(States states, KindSources kinds, PanelSources panels, DuckDbExecutor exec,
            ObjectProvider<PivotLiveConfiguration.Live> live, ObjectProvider<TableSources> tables) {
        return new com.ash.drishti.server.engines.BasicEngine("pivot").onStop(() -> {
            PivotLiveConfiguration.Live l = live.getIfAvailable();
            if (l != null) {
                l.pivots().clear();                            // every live view ended, its stream told why
            }
            states.clear();
            kinds.release(exec);
            panels.release(exec);
            TableSources t = tables.getIfAvailable();
            if (t != null) {
                t.release(exec);
            }
        }).memory(states::bytes);
    }

    @Bean
    public PivotViews pivotViews(ContentStore store) {
        return new PivotViews(store);
    }

    /** Cell comments, kept with a saved view's versions. */
    @Bean
    public PivotCellComments pivotCellComments(PivotViews views) {
        return new PivotCellComments(views, java.time.Clock.systemUTC());
    }

    /**
     * Rachana's {@code measure(...)} and {@code cell(...)} answered by this server's pivot engine (ONE_GRAMMAR.md part 3); while the
     * pivot engine is switched off they are refused with the reason.
     */
    @Bean
    public RachanaFigures rachanaFigures(PivotCore core, PivotViews views, ObjectProvider<com.ash.drishti.server.engines.EngineRegistry> engines) {
        RachanaFigures figures = new RachanaFigures(core, views, () -> {
            com.ash.drishti.server.engines.EngineRegistry r = engines.getIfAvailable();
            return r == null || !r.known("pivot") || r.running("pivot");
        });
        com.ash.drishti.bicompute.rachana.EngineFunctions.figures(figures);
        return figures;
    }
}
