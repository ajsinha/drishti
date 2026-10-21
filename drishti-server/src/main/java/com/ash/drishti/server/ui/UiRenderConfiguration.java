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
package com.ash.drishti.server.ui;

import com.ash.drishti.engine.live.TopicHub;
import com.ash.drishti.engine.search.StructuredSearch;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.server.bi.DatasetService;
import com.ash.drishti.server.bi.runs.RunSources;
import com.ash.drishti.server.security.Entitlements;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** The UI Board's render API wiring (UI_BOARD.md, phase P3): its settings from {@code config/ui/ui.yaml}, the suppliers as the caller, the sessions. */
@Configuration
public class UiRenderConfiguration {

    @Bean
    public RenderSettings renderSettings(Environment env, UiSettings ui) {
        return RenderSettings.load(Path.of(env.getProperty("drishti.ui.dir", "./config/ui")), ui);
    }

    @Bean
    public IdTokens uiIdTokens(RenderSettings settings) {
        return new IdTokens(Clock.systemUTC(), settings.idle().multipliedBy(6));
    }

    @Bean
    public BoardSuppliers boardSuppliers(SourceRouter router, StructuredSearch search, Entitlements entitlements, RunSources runs,
            ObjectProvider<DatasetService> datasets, ObjectProvider<TopicHub> hub, RenderSettings settings, ExecutorService drishtiVirtualExecutor) {
        return new ServerBoardSuppliers(router, search, entitlements, runs, datasets.getIfAvailable(), hub.getIfAvailable(), settings, drishtiVirtualExecutor);
    }

    /** Phase P5: the per-pack switch of converted screens ({@code packs:} of ui.yaml) and the converted boards. */
    @Bean
    public PackScreens packScreens(Environment env, com.ash.drishti.server.bi.BiProperties bi, ChipStore chips,
            com.ash.drishti.rachana.SutraRegistry registry, com.ash.drishti.rachana.SutraMatcher matcher, com.ash.drishti.engine.ViewPipeline pipeline) {
        return new PackScreens(Path.of(env.getProperty("drishti.ui.dir", "./config/ui")), Path.of(com.ash.drishti.packs.PackPaths.packsDir(env)),
                Path.of(bi.dir()), chips, registry, matcher, pipeline);
    }

    /** Phase P5: the proof that a converted pack shows the same screens. */
    @Bean
    public BoardProof boardProof(PackScreens screens, com.ash.drishti.engine.ViewPipeline pipeline, com.ash.drishti.rachana.SutraMatcher matcher,
            StructuredSearch search, Entitlements entitlements, BoardSuppliers suppliers) {
        return new BoardProof(screens, pipeline, matcher, search, entitlements, suppliers);
    }

    @Bean(destroyMethod = "close")
    public RenderService renderService(ChipStore chips, BoardStore boards, UiAccess access, UiSettings ui, RenderSettings settings, BoardSuppliers suppliers,
            IdTokens tokens, ExecutorService drishtiVirtualExecutor, Environment env) {
        Path dynamic = Path.of(env.getProperty("drishti.data.dir", "./data")).resolve("ui").resolve("dynamic");
        return new RenderService(chips, boards, access, ui, settings, suppliers, tokens, drishtiVirtualExecutor, dynamic);
    }
}
