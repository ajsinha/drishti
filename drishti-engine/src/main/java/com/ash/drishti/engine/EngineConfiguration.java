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
import com.ash.drishti.engine.bind.Binder;
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
import com.ash.drishti.rachana.RachanaConfiguration;
import com.ash.drishti.rachana.SutraMatcher;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.format.Formats;
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
@EnableConfigurationProperties({SourcesProperties.class, EngineProperties.class, CommandsProperties.class, LiveProperties.class})
public class EngineConfiguration {

    /** One virtual thread per task: fetches, link fan-out and searches block cheaply here. */
    @Bean(destroyMethod = "close")
    public ExecutorService drishtiVirtualExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("drishti-vt-", 0).factory());
    }

    /** Bounded CPU pool for binding panels; sized to cores by default. */
    @Bean(destroyMethod = "shutdown")
    public ForkJoinPool drishtiBindPool(EngineProperties props) {
        return new ForkJoinPool(props.bindParallelism());
    }

    @Bean(destroyMethod = "close")
    public SourceRegistry sourceRegistry(SourcesProperties props, JsonCodec codec) {
        return new SourceRegistry(new PluginDiscovery().discover(props.pluginDir()), props, codec);
    }

    @Bean
    public SourceRouter sourceRouter(SourceRegistry registry, SourcesProperties props, ExecutorService drishtiVirtualExecutor) {
        return new SourceRouter(registry, props, drishtiVirtualExecutor);
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
    public com.ash.drishti.engine.impact.ImpactService impactService(SourceRouter router, Mnemonics mnemonics, ReferenceCatalog catalog,
            GraphProperties graph, ElCompiler el, Formats formats, ExecutorService drishtiVirtualExecutor) {
        return new com.ash.drishti.engine.impact.ImpactService(router, mnemonics, catalog, graph, el, formats, drishtiVirtualExecutor);
    }

    @Bean
    public Binder binder(ElCompiler el, Formats formats, ReferenceCatalog catalog, BadgeRenderer badges, Mnemonics mnemonics) {
        return new Binder(el, formats, catalog, badges, mnemonics);
    }

    @Bean
    public ViewPipeline viewPipeline(SourceRouter router, SutraMatcher matcher, SutraRegistry registry, LayoutMerger merger,
            ShapeFingerprinter fingerprinter, ReferenceCatalog catalog, GraphProperties graph, Binder binder, ElCompiler el,
            Formats formats, Mnemonics mnemonics, ForkJoinPool drishtiBindPool, EngineProperties props) {
        return new ViewPipeline(router, matcher, registry, merger, fingerprinter, catalog, graph, binder, el, formats, mnemonics,
                drishtiBindPool, props);
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
}
