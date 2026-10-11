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
package com.ash.drishti.server.bi;

import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.graph.ReferenceCatalog;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.bi.engine.AnalyticEngine;
import com.ash.drishti.server.bi.engine.DuckDbEngine;
import com.ash.drishti.server.bi.engine.QueryProperties;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.engine.SourceGateway;
import com.ash.drishti.server.bi.report.BiReportController;
import com.ash.drishti.server.bi.report.BiReportProperties;
import com.ash.drishti.server.bi.report.BiReportService;
import com.ash.drishti.server.loads.LoadService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.SecurityProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Rupaka phase 2: the beans of the dataset query engine (RUPAKA.md sections 6 and 7). The engine is an embedded DuckDB and starts its
 * connection on the first query, so a server that never runs a BI query pays nothing.
 */
@Configuration
@EnableConfigurationProperties({QueryProperties.class, BiReportProperties.class})
public class BiQueryConfiguration {

    @Bean(destroyMethod = "close")
    public AnalyticEngine biEngine(QueryProperties props) {
        return new DuckDbEngine(props);
    }

    @Bean
    public SourceGateway biSources(SourceRegistry registry, SourceRouter router, BusinessDates dates, QueryProperties props) {
        return new ServerSourceGateway(registry, router, dates, props);
    }

    @Bean(destroyMethod = "")
    public QueryService biQueries(QueryProperties props, AnalyticEngine engine, SourceGateway sources) {
        return new QueryService(props, engine, sources, Clock.systemUTC());
    }

    @Bean
    public DatasetService datasetService(BiService bi, QueryService queries, QueryProperties props, Entitlements entitlements, SecurityProperties security, UserService users,
            AuditLog audit, PackRegistry packs, ReferenceCatalog graph, SourceGateway sources, LoadService loads) {
        DatasetService service = new DatasetService(bi, queries, props, entitlements, security, users, audit, packs, graph, sources);
        loads.onReady(service::onLoad);                                  // a data-load signal rebuilds exactly the date it names
        return service;
    }

    @Bean
    public DatasetController datasetController(DatasetService datasets, BiService bi) {
        return new DatasetController(datasets, bi);
    }

    @Bean
    public BiReportService biReportService(BiService bi, DatasetService datasets, BiReportProperties props) {
        return new BiReportService(bi, datasets, props);
    }

    @Bean
    public BiReportController biReportController(BiReportService reports) {
        return new BiReportController(reports);
    }
}
