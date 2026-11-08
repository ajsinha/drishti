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
 * The BI migrations of stored items (docs/guides/OPERATIONS_GUIDE.md "Migration problems"): every stored dataset and model into the table layer
 * ({@link com.ash.drishti.server.tables.StoredTablesMigration}, docs/architecture/ARCHITECTURE.md §28 4.3), then the 2.3 boards into reports ({@link BoardUpgrade}).
 * Run once at start-up ({@code drishti.bi.migrate.on-start}); boards also by a bi-admin from the API or {@code drishti.py bi migrate --boards [--dry-run]}.
 * Runs are serialised; each is idempotent; what a run could not convert is listed in {@link MigrationProblems}, shown in Admin, BI settings and on
 * the item's page.
 */
public final class BiMigration {

    private static final Logger LOG = LoggerFactory.getLogger(BiMigration.class);

    private final MigrateProperties props;
    private final MigrationProblems problems = new MigrationProblems();
    private final BoardUpgrade boards;
    private final com.ash.drishti.server.tables.StoredTablesMigration tables;
    private final AuditLog audit;
    private final Object running = new Object();

    public BiMigration(MigrateProperties props, BiStore store, AuditLog audit, BiService bi, int versionsKeep, int revisionsKeep) {
        this.props = props;
        this.audit = audit;
        StoredRewrite rewrite = new StoredRewrite(store, audit, versionsKeep, revisionsKeep);
        this.boards = new BoardUpgrade(store, rewrite, problems);
        this.tables = new com.ash.drishti.server.tables.StoredTablesMigration(store, audit, new com.ash.drishti.server.bi.expr.DelTablesTranslator(),
                java.time.Clock.systemUTC());
        bi.migrationProblems(item -> item == null ? problems.view() : problems.of(item).stream().map(MigrationProblems.Problem::toMap).toList());
    }

    /** The start-up run ({@code drishti.bi.migrate.on-start}); a failure is logged and never stops the server. */
    public void start() {
        if (!props.onStart()) {
            LOG.info("BI migrations at start-up are off (drishti.bi.migrate.on-start: false)");
            return;
        }
        try {
            tables(props.actor(), false);                                          // docs/architecture/ARCHITECTURE.md §28 4.3: every stored dataset and model into the layer
        } catch (RuntimeException e) {
            LOG.error("the table layer's migration of stored items at start-up failed; stored items are unchanged where it stopped: {}", e.toString(), e);
        }
        try {
            boards(props.actor(), false);                                          // 2.4.0 (R7): boards become reports with board pages
        } catch (RuntimeException e) {
            LOG.error("BI board migration at start-up failed; stored boards are unchanged where it stopped: {}", e.toString(), e);
        }
    }

    /**
     * Migrates (or, with {@code dryRun}, plans) every stored BI dataset and model into a table or model of the table layer, every version in place,
     * DEL into Rachana ({@link com.ash.drishti.server.tables.StoredTablesMigration}); a refused item keeps its text and the reason on its page.
     */
    public Map<String, Object> tables(String actor, boolean dryRun) {
        synchronized (running) {
            List<com.ash.drishti.server.tables.StoredTablesMigration.Outcome> out = tables.run(actor, dryRun);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("scanned", out.size());
            r.put("converted", out.stream().filter(o -> "migrated".equals(o.state()) || "would migrate".equals(o.state())).count());
            r.put("problems", out.stream().filter(o -> "refused".equals(o.state())).count());
            r.put("items", out.stream().map(o -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("item", o.item());
                m.put("kind", o.kind());
                m.put("state", o.state());
                m.put("versions", o.versions());
                m.put("reason", o.reason());
                return m;
            }).toList());
            if (!dryRun) {
                audit.record(actor, "bi-migration-run", "tables", r.get("converted") + " migrated, " + r.get("problems") + " refused, " + r.get("scanned") + " scanned");
                out.stream().filter(o -> "refused".equals(o.state())).forEach(o -> LOG.warn("stored item {} not migrated into the table layer: {}", o.item(), o.reason()));
            }
            return r;
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
