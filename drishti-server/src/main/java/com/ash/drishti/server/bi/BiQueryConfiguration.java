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
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.bi.engine.AnalyticEngine;
import com.ash.drishti.server.bi.gov.ImpactAnalyzer;
import com.ash.drishti.server.bi.engine.BiLiveProperties;
import com.ash.drishti.server.bi.engine.BiRefreshProperties;
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
@EnableConfigurationProperties({QueryProperties.class, BiReportProperties.class, BiLiveProperties.class, BiRefreshProperties.class,
        com.ash.drishti.server.bi.transform.TransformProperties.class})
public class BiQueryConfiguration {

    @Bean(destroyMethod = "close")
    public AnalyticEngine biEngine(QueryProperties props) {
        return new DuckDbEngine(props);
    }

    @Bean
    public SourceGateway biSources(SourceRegistry registry, SourceRouter router, BusinessDates dates, QueryProperties props, com.ash.drishti.engine.live.TopicHub hub) {
        return new ServerSourceGateway(registry, router, dates, props, hub);
    }

    @Bean(destroyMethod = "")
    public QueryService biQueries(QueryProperties props, BiLiveProperties live, AnalyticEngine engine, SourceGateway sources) {
        return new QueryService(props, live, engine, sources, Clock.systemUTC());
    }

    @Bean
    public com.ash.drishti.server.bi.transform.TransformRunner transformRunner(com.ash.drishti.server.bi.transform.TransformProperties props, QueryProperties query, BiReportProperties report) {
        java.nio.file.Path scratch = props.workDir().isBlank() ? java.nio.file.Path.of(query.storeDir()).toAbsolutePath().resolve("transform-tmp") : java.nio.file.Path.of(props.workDir());
        return new com.ash.drishti.server.bi.transform.ProcessTransformRunner(props, scratch, report.pythonPackages(), report.pythonMaxSeconds(), report.pythonMaxMemoryMb());
    }

    @Bean(destroyMethod = "close")
    public com.ash.drishti.server.bi.transform.TransformService transformService(com.ash.drishti.server.bi.transform.TransformProperties props,
            com.ash.drishti.server.bi.transform.TransformRunner runner, QueryProperties query, ImpactAnalyzer impact) {
        com.ash.drishti.server.bi.transform.TransformService service = new com.ash.drishti.server.bi.transform.TransformService(props, runner,
                java.nio.file.Path.of(query.storeDir()).toAbsolutePath().resolve("transform"), Clock.systemUTC());
        impact.learnedColumns(service::learned);
        return service;
    }

    @Bean
    public FrameService frameService(BiService bi, QueryService queries, com.ash.drishti.server.bi.python.PythonItems pythonItems,
            com.ash.drishti.server.bi.transform.TransformService transforms, com.ash.drishti.server.bi.transform.TransformProperties props, Entitlements entitlements,
            DatasetService datasets, DatasetRefresher refresher) {
        FrameService frames = new FrameService(bi, queries, pythonItems, transforms, props, entitlements);
        datasets.attach(frames);
        refresher.attach(frames);
        return frames;
    }

    @Bean
    public DatasetService datasetService(BiService bi, QueryService queries, QueryProperties props, Entitlements entitlements, SecurityProperties security, UserService users,
            AuditLog audit, PackRegistry packs, ReferenceCatalog graph, SourceGateway sources, LoadService loads) {
        DatasetService service = new DatasetService(bi, queries, props, entitlements, security, users, audit, packs, graph, sources);
        loads.onReady(service::onLoad);                                  // a data-load signal rebuilds exactly the date it names
        return service;
    }

    @Bean(destroyMethod = "close")
    public DatasetRefresher datasetRefresher(QueryService queries, DatasetService datasets, BiRefreshProperties props, BusinessDates dates, AuditLog audit,
            org.springframework.core.env.Environment env, QueryProperties queryProps) {
        DatasetRefresher refresher = new DatasetRefresher(queries, datasets::published, props, com.ash.drishti.common.RetryPolicy.of(env::getProperty, "bi"), dates, Clock.systemUTC(),
                audit, true, java.nio.file.Path.of(queryProps.storeDir()).resolve("refresh").resolve("state.json"));
        datasets.attach(refresher);
        return refresher;
    }

    @Bean
    public DatasetController datasetController(DatasetService datasets, BiService bi) {
        return new DatasetController(datasets, bi);
    }

    @Bean
    public BiReportService biReportService(BiService bi, DatasetService datasets, BiReportProperties props, com.ash.drishti.server.bi.python.PythonItems pythonItems) {
        BiReportService reports = new BiReportService(bi, datasets, props);
        reports.pythonItems(pythonItems);
        return reports;
    }

    @Bean
    public com.ash.drishti.server.bi.python.PythonItems pythonItems(BiService bi, BiReportProperties props) {
        return new com.ash.drishti.server.bi.python.PythonItems(bi, props);
    }

    @Bean
    public com.ash.drishti.server.bi.python.PythonController pythonController(com.ash.drishti.server.bi.python.PythonItems items) {
        return new com.ash.drishti.server.bi.python.PythonController(items);
    }

    @Bean
    public BiReportController biReportController(BiReportService reports, BiLiveProperties live, java.util.concurrent.ExecutorService drishtiVirtualExecutor) {
        return new BiReportController(reports, live, drishtiVirtualExecutor);
    }

    @Bean
    public com.ash.drishti.server.bi.board.BiBoardService biBoardService(BiService bi, BiReportService reports) {
        return new com.ash.drishti.server.bi.board.BiBoardService(bi, reports);
    }

    @Bean
    public com.ash.drishti.server.bi.board.BiBoardController biBoardController(com.ash.drishti.server.bi.board.BiBoardService boards) {
        return new com.ash.drishti.server.bi.board.BiBoardController(boards);
    }

    @Bean
    public com.ash.drishti.server.bi.ai.BiAiService biAiService(com.ash.drishti.ai.llm.LLMFactory gateway, com.ash.drishti.server.ai.AiProperties ai,
            com.ash.drishti.server.bi.ai.BiAiProperties props, BiService bi, BiReportService reports, DatasetService datasets, AuditLog audit) {
        return new com.ash.drishti.server.bi.ai.BiAiService(new com.ash.drishti.ai.llm.LLMFactory(gateway.gateway(), java.time.Duration.ofSeconds(props.timeoutSeconds())), ai, props, bi, reports,
                datasets, audit);
    }

    @Bean
    public com.ash.drishti.server.bi.ai.BiAiCopilot biAiCopilot(com.ash.drishti.server.bi.ai.BiAiService service) {
        return new com.ash.drishti.server.bi.ai.BiAiCopilot(service);
    }

    @Bean
    public com.ash.drishti.server.bi.ai.BiAiController biAiController(com.ash.drishti.server.bi.ai.BiAiService service, com.ash.drishti.server.bi.ai.BiAiCopilot copilot) {
        return new com.ash.drishti.server.bi.ai.BiAiController(service, copilot);
    }

    @Bean
    public ConnectorSource connectorSource(org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.connectors.ConnectorManager> connectors,
            com.ash.drishti.server.security.Entitlements entitlements) {
        return new ConnectorSource(connectors.getIfAvailable(), entitlements);
    }

    @Bean
    public BiGovernance biGovernance(BiService bi, BiStore store, BiAccess access, ImpactAnalyzer impact, BiGovProperties gov, PackRegistry packs, DatasetService datasets,
            ConnectorSource sources) {
        return new BiGovernance(bi, store, access, impact, gov, packs, datasets, sources);
    }

    @Bean
    public ExpectedValueRunner expectedValueRunner(BiService bi, DatasetService datasets, BiReportService reports, AuditLog audit) {
        bi.drawCheck(reports::errors);                                  // propose, approve and publish refuse a report that cannot be drawn
        ExpectedValueRunner runner = new ExpectedValueRunner(bi, datasets, reports, audit);
        bi.testGate(runner::failures);                                   // publishing runs the item's tests (docs/guides/REPORTS.md section 14)
        return runner;
    }

    @Bean
    public BiGovController biGovController(BiGovernance gov, BiBundles bundles, BiService bi, ExpectedValueRunner tests) {
        return new BiGovController(gov, bundles, bi, tests);
    }
}
