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
package com.ash.drishti.engine;

import com.ash.drishti.common.CommonConfiguration;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.common.ShapeFingerprinter;
import com.ash.drishti.engine.command.CommandParser;
import com.ash.drishti.engine.command.CommandsProperties;
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.engine.command.RecentEntities;
import com.ash.drishti.engine.command.SuggestionService;
import com.ash.drishti.engine.live.LiveMetrics;
import com.ash.drishti.engine.live.LiveProperties;
import com.ash.drishti.engine.live.TopicHub;
import com.ash.drishti.engine.source.PluginDiscovery;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.source.SourcesProperties;
import com.ash.drishti.graph.BadgeRenderer;
import com.ash.drishti.graph.GraphConfiguration;
import com.ash.drishti.graph.GraphProperties;
import com.ash.drishti.graph.ReferenceCatalog;
import com.ash.drishti.inference.InferenceConfiguration;
import com.ash.drishti.inference.LayoutMerger;
import com.ash.drishti.rachana.LiveCompiler;
import com.ash.drishti.rachana.RachanaConfiguration;
import com.ash.drishti.rachana.SutraMatcher;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.rachana.format.Formats;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Beans contributed by {@code drishti-engine}, and the lower modules it assembles. */
@Configuration(proxyBeanMethods = false)
@Import({CommonConfiguration.class, RachanaConfiguration.class, InferenceConfiguration.class, GraphConfiguration.class})
@EnableConfigurationProperties({SourcesProperties.class, EngineProperties.class, CommandsProperties.class, LiveProperties.class,
        com.ash.drishti.engine.time.BusinessDateProperties.class, com.ash.drishti.engine.search.SearchProperties.class,
        com.ash.drishti.engine.rachana.PanelSettings.class, com.ash.drishti.engine.pivot.PivotProperties.class,
        com.ash.drishti.engine.shape.BuilderProperties.class, com.ash.drishti.engine.explain.ExplainProperties.class,
        com.ash.drishti.engine.history.HistoryProperties.class})
public class EngineConfiguration {

    /**
     * One virtual thread per task: fetches, link fan-out and searches block cheaply here. Shut down with
     * {@code shutdownNow}: long-lived tasks (live stream writers waiting for their next frame) are interrupted,
     * so shutdown does not wait out a heartbeat.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService drishtiVirtualExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("drishti-vt-", 0).factory());
    }

    /** Bounded CPU pool for binding panels; sized to cores by default. */
    @Bean(destroyMethod = "shutdown")
    public ForkJoinPool drishtiBindPool(EngineProperties props) {
        return new ForkJoinPool(props.bindParallelism());
    }

    @Bean(destroyMethod = "close")
    public SourceRegistry sourceRegistry(SourcesProperties props, JsonCodec codec, org.springframework.core.env.Environment env) {
        // a connector plugin of a module that is off is not offered (docs/architecture/KERNEL_AND_MODULES.md: module off = gone everywhere)
        java.lang.ref.WeakReference<org.springframework.core.env.Environment> ref = new java.lang.ref.WeakReference<>(env);
        com.ash.drishti.registry.AccessRegistry access = com.ash.drishti.registry.AccessRegistries.shared(env, k -> {
            org.springframework.core.env.Environment e = ref.get();
            try {
                return e == null ? null : e.getProperty(k);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }, EngineConfiguration.class.getClassLoader());
        // the site's shared lib folder (Tomcat's lib/; default <home>/lib/ext): read once, at start, before any plugin is looked for
        String homeSetting = env.getProperty(com.ash.drishti.api.Roots.HOME);
        java.nio.file.Path home = homeSetting == null || homeSetting.isBlank() ? com.ash.drishti.api.Roots.home() : java.nio.file.Path.of(homeSetting.strip());
        String libSetting = env.getProperty("drishti.lib.path");
        java.nio.file.Path lib = libSetting == null || libSetting.isBlank() ? home.resolve(com.ash.drishti.common.SharedLib.DEFAULT)
                : (java.nio.file.Path.of(libSetting.strip()).isAbsolute() ? java.nio.file.Path.of(libSetting.strip()) : home.resolve(libSetting.strip()));
        lib = lib.toAbsolutePath().normalize();
        warnOldLibSettings(env, props);
        com.ash.drishti.common.SharedLib.open(lib);
        java.util.List<com.ash.drishti.api.SourcePlugin> plugins = new PluginDiscovery().discover().stream()
                .filter(pl -> access.classOn((pl instanceof com.ash.drishti.engine.source.ComputedKindSource c ? c.provider() : pl).getClass().getName())).toList();
        return new SourceRegistry(plugins, props, codec, com.ash.drishti.common.RetryPolicy.of(env::getProperty, "connectors"),
                com.ash.drishti.engine.source.OfflineMode.of(env::getProperty));
    }

    /** One line each for a setting that is no longer read: connector jars and JDBC drivers go in the lib folder ({@code drishti.lib.path}). */
    static void warnOldLibSettings(org.springframework.core.env.Environment env, SourcesProperties props) {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EngineConfiguration.class);
        if (props.pluginDir() != null && !props.pluginDir().isBlank()) {
            log.warn("drishti.sources.plugin-dir (DRISHTI_PLUGIN_DIR) is no longer read: put connector jars and JDBC drivers in the lib folder (drishti.lib.path) and remove the setting");
        }
        String ext = env.getProperty("drishti.external-lib", System.getenv().getOrDefault("DRISHTI_EXTERNAL_LIB", ""));
        if (!ext.isBlank()) {
            log.warn("drishti.external-lib (DRISHTI_EXTERNAL_LIB) is no longer read: put jars in the lib folder (drishti.lib.path) and remove the setting");
        }
    }

    @Bean
    public com.ash.drishti.engine.time.BusinessDates businessDates(com.ash.drishti.engine.time.BusinessDateProperties props) {
        return new com.ash.drishti.engine.time.BusinessDates(props, java.time.Clock.systemUTC());
    }

    @Bean
    public SourceRouter sourceRouter(SourceRegistry registry, SourcesProperties props, ExecutorService drishtiVirtualExecutor,
            org.springframework.core.env.Environment env) {
        SourceRouter router = new SourceRouter(registry, props, drishtiVirtualExecutor);
        router.prepare(env).run();                              // the packs' routes and their templates' default connectors of each kind
        return router;
    }

    @Bean
    public Mnemonics mnemonics(CommandsProperties props) {
        return new Mnemonics(props);
    }

    @Bean
    public CommandParser commandParser(Mnemonics mnemonics, ReferenceCatalog catalog) {
        return new CommandParser(mnemonics, catalog);
    }

    @Bean
    public RecentEntities recentEntities(CommandsProperties props) {
        return new RecentEntities(props.recentSize());
    }

    @Bean
    public SuggestionService suggestionService(Mnemonics mnemonics, SourceRouter router, RecentEntities recents, CommandsProperties props) {
        return new SuggestionService(mnemonics, router, recents, props);
    }

    @Bean
    public com.ash.drishti.engine.search.StructuredSearch structuredSearch(SourceRouter router, Mnemonics mnemonics, LiveCompiler el, Formats formats,
            com.ash.drishti.engine.search.SearchProperties props) {
        return new com.ash.drishti.engine.search.StructuredSearch(router, mnemonics, el, formats, props);
    }

    @Bean
    public com.ash.drishti.engine.impact.ImpactService impactService(SourceRouter router, Mnemonics mnemonics, ReferenceCatalog catalog,
            GraphProperties graph, LiveCompiler el, Formats formats, ExecutorService drishtiVirtualExecutor) {
        return new com.ash.drishti.engine.impact.ImpactService(router, mnemonics, catalog, graph, el, formats, drishtiVirtualExecutor);
    }

    /**
     * The view pipeline. Its binder is Drishti's hooks on the compiler now: built again when a module starts or stops (the compiler holder,
     * {@link LiveCompiler}), never fixed to the functions of the moment the server started.
     */
    @Bean
    public ViewPipeline viewPipeline(SourceRouter router, SutraMatcher matcher, SutraRegistry registry, LayoutMerger merger,
            ShapeFingerprinter fingerprinter, ReferenceCatalog catalog, GraphProperties graph, BadgeRenderer badges, LiveCompiler el,
            Formats formats, Mnemonics mnemonics, ForkJoinPool drishtiBindPool, EngineProperties props,
            com.ash.drishti.engine.rachana.PanelSettings panels, com.ash.drishti.engine.pivot.PivotProperties pivot,
            com.ash.drishti.engine.time.BusinessDates businessDates,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.kernel.api.view.ViewAugmenter> augmenters) {
        ViewPipeline p = new ViewPipeline(router, matcher, registry, merger, fingerprinter, catalog, graph,
                c -> com.ash.drishti.engine.rachana.DrishtiBinder.binder(c, formats, catalog, badges, mnemonics, panels.limits(), pivot), el,
                formats, mnemonics, drishtiBindPool, props, businessDates);
        p.augmentersFrom(() -> augmenters.orderedStream().toList());    // the server's own; a module's through the registration bridge
        return p;
    }

    /** About this page: the explanation of a view (layers 3 and 4), derived on request. */
    @Bean
    public com.ash.drishti.engine.explain.ExplainService explainService(ViewPipeline pipeline, SutraMatcher matcher,
            SourceRouter router, LiveCompiler el, SutraRegistry registry, com.ash.drishti.rachana.about.AboutCatalog about, Formats formats,
            com.ash.drishti.engine.explain.ExplainProperties props,
            io.micrometer.core.instrument.MeterRegistry meters) {
        return new com.ash.drishti.engine.explain.ExplainService(pipeline, matcher, router, el, registry, about, formats, props, meters);
    }

    /** About this page in the Build workbench: the About card of a Design's preview. */
    @Bean
    public com.ash.drishti.engine.explain.AboutPreview aboutPreview(SourceRouter router, Formats formats) {
        return new com.ash.drishti.engine.explain.AboutPreview(router, formats);
    }

    /** Frame timer for live topics: two platform threads only schedule; delivery work is tiny. */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService drishtiFrameScheduler() {
        return Executors.newScheduledThreadPool(2, Thread.ofPlatform().daemon().name("drishti-frame-", 0).factory());
    }

    @Bean(destroyMethod = "close")
    public TopicHub topicHub(SourceRouter router, ScheduledExecutorService drishtiFrameScheduler, LiveProperties props) {
        return new TopicHub(router, drishtiFrameScheduler, props);
    }

    @Bean
    public LiveMetrics liveMetrics(LiveProperties props) {
        return new LiveMetrics(props.window());
    }

    @Bean
    public com.ash.drishti.engine.shape.ShapeService shapeService(com.ash.drishti.engine.shape.BuilderProperties props,
            com.ash.drishti.inference.InferenceEngine inference, com.ash.drishti.graph.ReferenceCatalog references) {
        return new com.ash.drishti.engine.shape.ShapeService(props, inference.semantics(), references);
    }

    @Bean
    public com.ash.drishti.engine.design.AutoDesigner autoDesigner(com.ash.drishti.engine.shape.BuilderProperties props,
            com.ash.drishti.inference.InferenceEngine inference) {
        return new com.ash.drishti.engine.design.AutoDesigner(props, inference.semantics());
    }
}
