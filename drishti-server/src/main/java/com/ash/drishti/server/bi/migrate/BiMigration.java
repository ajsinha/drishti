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
package com.ash.drishti.server.bi.migrate;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.bi.BiService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The BI migrations of stored items (docs/architecture/BI_MODEL_SPEC.md section 8.3; docs/guides/TROUBLESHOOTING.md "Migration problems"): old-syntax
 * expressions to DEL v2 ({@link ExpressionUpgrade}), then {@code rachana: 1} datasets to datasets and models ({@link ModelUpgrade}). Run once at start-up ({@code drishti.bi.migrate.on-start}), and by a bi-admin from the API or
 * {@code drishti.py bi migrate --expressions [--dry-run]}. Runs are serialised; each is idempotent; what a run could not convert is listed in
 * {@link MigrationProblems}, shown in Admin, BI settings and on the item's page.
 */
public final class BiMigration {

    private static final Logger LOG = LoggerFactory.getLogger(BiMigration.class);

    private final MigrateProperties props;
    private final MigrationProblems problems = new MigrationProblems();
    private final ExpressionUpgrade expressions;
    private final ModelUpgrade models;
    private final BoardUpgrade boards;
    private final AuditLog audit;
    private final Object running = new Object();

    public BiMigration(MigrateProperties props, BiStore store, AuditLog audit, BiService bi, int versionsKeep, int revisionsKeep) {
        this.props = props;
        this.audit = audit;
        StoredRewrite rewrite = new StoredRewrite(store, audit, versionsKeep, revisionsKeep);
        this.expressions = new ExpressionUpgrade(store, rewrite, problems);
        this.models = new ModelUpgrade(store, rewrite, problems, audit, versionsKeep, revisionsKeep);
        this.boards = new BoardUpgrade(store, rewrite, problems);
        bi.migrationProblems(item -> item == null ? problems.view() : problems.of(item).stream().map(MigrationProblems.Problem::toMap).toList());
    }

    /** The start-up run ({@code drishti.bi.migrate.on-start}); a failure is logged and never stops the server. */
    public void start() {
        if (!props.onStart()) {
            LOG.info("BI migrations at start-up are off (drishti.bi.migrate.on-start: false)");
            return;
        }
        try {
            expressions(props.actor(), false);
        } catch (RuntimeException e) {
            LOG.error("BI expression migration at start-up failed; stored items are unchanged where it stopped: {}", e.toString(), e);
        }
        try {
            models(props.actor(), false);                                          // after the expressions: the converter reads DEL v2
        } catch (RuntimeException e) {
            LOG.error("BI model migration at start-up failed; stored items are unchanged where it stopped: {}", e.toString(), e);
        }
        try {
            boards(props.actor(), false);                                          // 2.4.0 (R7): boards become reports with board pages
        } catch (RuntimeException e) {
            LOG.error("BI board migration at start-up failed; stored boards are unchanged where it stopped: {}", e.toString(), e);
        }
    }

    /** Converts (or, with {@code dryRun}, lists) the stored 2.3 boards into reports whose pages have {@code layout: board}. */
    public Map<String, Object> boards(String actor, boolean dryRun) {
        synchronized (running) {
            Map<String, Object> r = boards.run(actor, dryRun);
            if (!dryRun) {
                audit.record(actor, "bi-migration-run", BoardUpgrade.STEP, r.get("converted") + " converted, " + r.get("problems") + " with problems, " + r.get("scanned")
                        + " scanned");
            }
            return r;
        }
    }

    /** Converts (or, with {@code dryRun}, lists) the stored items in the old expression syntax. */
    public Map<String, Object> expressions(String actor, boolean dryRun) {
        synchronized (running) {
            Map<String, Object> r = expressions.run(actor, dryRun);
            if (!dryRun) {
                audit.record(actor, "bi-migration-run", ExpressionUpgrade.STEP, r.get("converted") + " converted, " + r.get("problems") + " with problems, "
                        + r.get("scanned") + " scanned");
            }
            return r;
        }
    }

    /** Converts (or, with {@code dryRun}, plans) the stored {@code rachana: 1} datasets to datasets and models, and rebinds their reports. */
    public Map<String, Object> models(String actor, boolean dryRun) {
        synchronized (running) {
            Map<String, Object> r = models.run(actor, dryRun);
            if (!dryRun) {
                audit.record(actor, "bi-migration-run", ModelUpgrade.STEP, r.get("converted") + " converted, " + r.get("problems") + " with problems, " + r.get("scanned")
                        + " scanned");
            }
            return r;
        }
    }

    public MigrationProblems problems() {
        return problems;
    }

    public Map<String, Object> problemsView() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> all = problems.view();
        m.put("count", all.size());
        m.put("problems", all);
        return m;
    }
}
