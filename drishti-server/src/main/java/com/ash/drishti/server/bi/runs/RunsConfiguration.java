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
            ObjectProvider<MeterRegistry> meters, ObjectProvider<com.ash.drishti.server.bi.engine.QueryService> queries) {
        HikariDataSource ds = identity.getIfAvailable();
        RunRecords records = ds == null ? new RunRecords.Memory() : new RunRecords.Jdbc(ds);
        ExecProperties e = exec.getIfAvailable();
        String node = e == null || e.node().isBlank() ? "local" : e.node();
        RunService runs = new RunService(props, records, new RunStore(props), Clock.systemUTC(), node, RunMetrics.of(meters.getIfAvailable()));
        RunMetrics.gauges(meters.getIfAvailable(), runs);                       // queue, running, store.bytes (17.1)
        runs.planner(new RunPlanner(props.planner(), new RunRates(props.planner())));      // the planner (section 4), its rates learnt from runs
        com.ash.drishti.server.bi.engine.QueryService q = queries.getIfAvailable();
        if (q != null) {
            q.history(new RunHistory());                                        // rows per day learnt from built partitions: the estimate's third source
        }
        return runs;
    }

    @Bean
    RunSources runSources(RunService runs, ModelCatalog catalog, MqService mq, SqlService sql, BiReportService reports, com.ash.drishti.server.bi.BiService bi,
            ObjectProvider<BiNotifier> notifier,
            ObjectProvider<Principals> principals, ObjectProvider<com.ash.drishti.server.bi.DatasetController> datasets,
            ObjectProvider<com.ash.drishti.server.dist.StatementService> statements, ObjectProvider<com.ash.drishti.identity.AuditLog> audit) {
        datasets.ifAvailable(c -> c.runs(runs::engineStats));               // GET /api/v1/bi/engine reports runs
        RunSources sources = new RunSources(runs, catalog, mq, sql, reports);
        sources.statements((p, body, kind) -> {                                // a statement run after a restart is made again as its runner
            com.ash.drishti.server.dist.StatementController c = new com.ash.drishti.server.dist.StatementController(statements.getObject(), audit.getObject());
            return "file".equals(kind) ? c.fileAsRun(body, p) : c.preview(body, p);
        });
        BiNotifier n = notifier.getIfAvailable();
        if (n != null) {
            runs.notifier((user, run, summary) -> n.tell(List.of(user), "bi-run", run.id(), summary, "drishti"));
        }
        Principals who = principals.getIfAvailable();
        runs.replay(r -> who == null ? Optional.empty() : sources.replay(r, who.of(r.runner())));
        runs.suggestions().drafter((admin, model, rollup) -> {                 // an accepted suggestion: the rollup joins the model's draft (8.3a)
            Optional<String> id = catalog.itemId(admin, model);
            if (id.isEmpty()) {
                return Optional.empty();
            }
            com.ash.drishti.identity.bi.BiStore.Item item = bi.readableItem(admin, id.get());
            bi.updateItem(admin, id.get(), java.util.Map.of("body", RunSuggestions.withRollup(item.body(), rollup),
                    "message", "rollup " + rollup.get("name") + " suggested by repeated heavy runs (BI runs 8.3a)"));
            return id;
        });
        runs.startSweeper();
        try {
            runs.recover();
        } catch (RuntimeException ex) {
            LOG.warn("BI runs: the unfinished runs of this node could not be recovered: {}", ex.getMessage());
        }
        return sources;
    }

    /** Scheduled runs (14.1): the owner's question asked again on a schedule, as the owner now; checked every 30 s. */
    @Bean(destroyMethod = "close")
    RunSchedules runSchedules(RunService runs, RunSources sources, ObjectProvider<HikariDataSource> identity, ObjectProvider<Principals> principals,
            ObjectProvider<com.ash.drishti.engine.time.BusinessDates> dates) {
        HikariDataSource ds = identity.getIfAvailable();
        Principals who = principals.getIfAvailable();
        com.ash.drishti.engine.time.BusinessDates d = dates.getIfAvailable();
        RunSchedules s = new RunSchedules(runs, ds == null ? new RunSchedules.Memory() : new RunSchedules.Jdbc(ds),
                r -> who == null ? Optional.empty() : sources.replay(r, who.of(r.runner())), d == null ? null : d.zone(),
                d == null ? com.ash.drishti.common.BusinessCalendar.of("WEEKENDS") : d.calendar(), Clock.systemUTC());
        s.start(java.time.Duration.ofSeconds(30));
        return s;
    }

    /** Promotion (section 9.4): snapshots under {@code store.dir}/snapshots, read by the model layer as the dataset's staged rows; drafts through BI. */
    @Bean
    RunPromotion runPromotion(RunService runs, ModelCatalog catalog, com.ash.drishti.server.bi.BiService bi) {
        RunPromotion p = new RunPromotion(runs);
        catalog.snapshots(p);
        p.drafter((who, itemId, kind, title, body) -> {
            if (itemId != null) {
                try {
                    bi.updateItem(who, itemId, java.util.Map.of("body", body, "message", "promoted again from a run (BI runs 9.4)"));
                    return itemId;
                } catch (com.ash.drishti.common.DrishtiException e) {
                    // the item was deleted or moved out of reach: a new draft
                }
            }
            return String.valueOf(bi.createItem(who, "me", java.util.Map.of("kind", kind, "title", title, "body", body,
                    "message", "promoted from a run (BI runs 9.4)")).get("id"));
        });
        return p;
    }

    /** Sharing (section 9.3): share rows in the identity database, recipients bound as themselves, roles expanded to their members when shared. */
    @Bean
    RunSharing runSharing(RunService runs, RunSources sources, ModelCatalog catalog, MqService mq, ObjectProvider<HikariDataSource> identity,
            ObjectProvider<Principals> principals, ObjectProvider<com.ash.drishti.server.collab.DirectoryService> directory) {
        HikariDataSource ds = identity.getIfAvailable();
        RunShares shares = ds == null ? new RunShares.Memory() : new RunShares.Jdbc(ds);
        Principals who = principals.getIfAvailable();
        com.ash.drishti.server.collab.DirectoryService dir = directory.getIfAvailable();
        return new RunSharing(runs, shares, catalog::bind, mq,
                u -> who == null ? new com.ash.drishti.server.security.Principal(u, List.of()) : who.of(u),
                role -> dir == null ? List.of() : dir.members(role).stream().map(com.ash.drishti.identity.User::username).toList(),
                sources::replay, Clock.systemUTC());
    }
}
