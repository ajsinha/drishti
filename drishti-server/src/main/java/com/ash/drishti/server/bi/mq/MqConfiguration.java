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
package com.ash.drishti.server.bi.mq;

import com.ash.drishti.graph.ReferenceCatalog;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.DatasetService;
import com.ash.drishti.server.bi.engine.BiLiveProperties;
import com.ash.drishti.server.bi.engine.QueryProperties;
import com.ash.drishti.server.bi.engine.SourceGateway;
import com.ash.drishti.server.bi.layer.PackDatasets;
import com.ash.drishti.server.bi.model.PackModels;
import com.ash.drishti.server.security.Entitlements;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The model layer's beans (docs/architecture/BI_MODEL_SPEC.md): the pack-generated datasets, the model catalogue, the query language and standing queries. */
@Configuration
@EnableConfigurationProperties(MqProperties.class)
public class MqConfiguration {

    @Bean
    public PackDatasets packDatasets(PackRegistry packs, ReferenceCatalog graph, SourceGateway sources, QueryProperties query, MqProperties props) {
        PackModels models = new PackModels(packs, graph, sources, query, kind -> true);
        return new PackDatasets(packs::packs, packs::version, models::describe, graph::kindOfField, kind -> {
            try {
                return sources.plugin("", kind).map(s -> s.manifest().capabilities() != null && s.manifest().capabilities().live()).orElse(false);
            } catch (RuntimeException e) {
                return false;
            }
        }, props.packLive());
    }

    @Bean
    public ModelCatalog modelCatalog(BiService bi, DatasetService datasets, PackDatasets packs, MqService mq) {
        ModelCatalog catalog = new ModelCatalog(bi, datasets, packs, mq);
        bi.layerCheck(catalog::gate);                                    // publishing a model or a rachana: 2 dataset runs its Check, contract and safeguard
        datasets.attach(new ConsumerQueries(catalog, mq));               // every consumer's question goes through the model query language
        return catalog;
    }

    @Bean
    public MqService mqService(MqProperties props) {
        return new MqService(props);
    }

    @Bean
    public StandingQueries standingQueries(ModelCatalog catalog, MqService mq, MqProperties props) {
        return new StandingQueries(catalog, mq, props, Clock.systemUTC());
    }

    @Bean
    public MqController mqController(ModelCatalog catalog, MqService mq, StandingQueries standing, BiService bi, Entitlements entitlements, BiLiveProperties live,
            ExecutorService drishtiVirtualExecutor) {
        return new MqController(catalog, mq, standing, bi, entitlements, live, drishtiVirtualExecutor);
    }
}
