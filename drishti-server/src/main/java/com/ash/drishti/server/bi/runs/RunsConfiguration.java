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
package com.ash.drishti.server.bi.runs;

import com.ash.drishti.server.bi.BiNotifier;
import com.ash.drishti.server.bi.engine.ExecProperties;
import com.ash.drishti.server.bi.mq.ModelCatalog;
import com.ash.drishti.server.bi.mq.MqService;
import com.ash.drishti.server.bi.report.BiReportService;
import com.ash.drishti.server.bi.sql.SqlService;
import com.ash.drishti.server.collab.Principals;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * BI runs wiring (docs/architecture/BI_RUNS.md): reads {@code config/runs/runs.yaml} (the file named by {@code drishti.bi.runs.file}) once at start,
 * keeps run rows in the identity database, results under the data folder, and recovers this node's unfinished runs before requests are served.
 */
@Configuration
public class RunsConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(RunsConfiguration.class);

    @Bean
    RunsProperties runsProperties(Environment env) {
        Path file = Path.of(env.getProperty("drishti.bi.runs.file", "./config/runs/runs.yaml"));
        Path data = Path.of(env.getProperty("drishti.data.dir", "./data"));
        RunsProperties p = RunsProperties.load(file, data, env::resolvePlaceholders);
        LOG.info("BI runs: {} (settings {}, results in {})", p.enabled() ? "on" : "off", p.origin(), p.store().dir());
        return p;
    }

    @Bean(destroyMethod = "close")
    RunService runService(RunsProperties props, ObjectProvider<HikariDataSource> identity, ObjectProvider<ExecProperties> exec,
            ObjectProvider<MeterRegistry> meters) {
        HikariDataSource ds = identity.getIfAvailable();
        RunRecords records = ds == null ? new RunRecords.Memory() : new RunRecords.Jdbc(ds);
        ExecProperties e = exec.getIfAvailable();
        String node = e == null || e.node().isBlank() ? "local" : e.node();
        return new RunService(props, records, new RunStore(props), Clock.systemUTC(), node, RunMetrics.of(meters.getIfAvailable()));
    }

    @Bean
    RunSources runSources(RunService runs, ModelCatalog catalog, MqService mq, SqlService sql, BiReportService reports, ObjectProvider<BiNotifier> notifier,
            ObjectProvider<Principals> principals) {
        RunSources sources = new RunSources(runs, catalog, mq, sql, reports);
        BiNotifier n = notifier.getIfAvailable();
        if (n != null) {
            runs.notifier((user, run, summary) -> n.tell(List.of(user), "bi-run", run.id(), summary, "drishti"));
        }
        Principals who = principals.getIfAvailable();
        runs.replay(r -> who == null ? Optional.empty() : sources.replay(r, who.of(r.runner())));
        runs.startSweeper();
        try {
            runs.recover();
        } catch (RuntimeException ex) {
            LOG.warn("BI runs: the unfinished runs of this node could not be recovered: {}", ex.getMessage());
        }
        return sources;
    }
}
