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

import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.bi.board.BoardConverter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Boards become a report layout (docs/architecture/BI_DESIGN.md section 11.1, item R7; docs/guides/REPORTS.md): every stored {@code board} item (drafts, proposals and the
 * published text) is converted by {@link BoardConverter} into a report whose pages have {@code layout: board}, keeping its id, so links, subscriptions, audit and
 * history stay with it. Through {@link StoredRewrite}: the board text before is a saved state (Restore brings it back), the published text becomes the next
 * published version, a draft stays a draft, and the change is audited ({@code bi-boards-migrated}). A board whose text does not parse is not changed: it is
 * listed in {@link MigrationProblems} (step {@code boards}). Idempotent: a converted item is a report.
 */
final class BoardUpgrade {

    static final String STEP = "boards";
    private static final Logger LOG = LoggerFactory.getLogger(BoardUpgrade.class);

    private final BiStore store;
    private final StoredRewrite rewrite;
    private final MigrationProblems problems;

    BoardUpgrade(BiStore store, StoredRewrite rewrite, MigrationProblems problems) {
        this.store = store;
        this.rewrite = rewrite;
        this.problems = problems;
    }

    /** Converts (or, with {@code dryRun}, lists) the stored boards. */
    Map<String, Object> run(String actor, boolean dryRun) {
        List<Map<String, Object>> items = new ArrayList<>();
        int converted = 0;
        int failed = 0;
        int scanned = 0;
        for (BiStore.Item i : store.items()) {
            if (!"board".equals(i.kind())) {
                continue;
            }
            scanned++;
            Optional<String> pub = rewrite.published(i);
            boolean draftOk = i.body() == null || i.body().isBlank() || BoardConverter.toReport(i.body(), i.title()) != null;
            boolean pubOk = pub.isEmpty() || BoardConverter.toReport(pub.get(), i.title()) != null;
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", i.id());
            one.put("title", i.title());
            one.put("kind", i.kind());
            one.put("workspace", i.workspace());
            one.put("status", i.status());
            if (!draftOk || !pubOk) {
                failed++;
                String where = !draftOk ? "the draft" : "the published text";
                problems.set(STEP, i.id(), List.of(new MigrationProblems.Problem(STEP, i.id(), i.title(), i.kind(), where,
                        "not converted, the board keeps its text: " + where + " does not parse as a board (open it, fix it, and the next start converts it)", Instant.now())));
                if (!dryRun) {
                    LOG.warn("BI board migration: '{}' ({}) keeps its board text: {} does not parse", i.title(), i.id(), where);
                }
                one.put("state", "problem");
                items.add(one);
                continue;
            }
            problems.set(STEP, i.id(), List.of());
            if (dryRun) {
                one.put("state", "would convert");
            } else {
                StoredRewrite.Done done = rewrite.apply(i, body -> {
                    String r = BoardConverter.toReport(body, i.title());
                    return r == null ? body : r;
                }, actor, "migrate", "bi-boards-migrated", "the board became a report with board pages (2.4.0)", "report");
                one.put("state", "converted");
                one.put("before", done.before());
                one.put("publishedVersion", done.version());
            }
            converted++;
            items.add(one);
        }
        if (!dryRun && (converted > 0 || failed > 0)) {
            LOG.info("BI board migration: {} stored board(s) converted to reports with board pages, {} left with a problem (Admin, BI settings)", converted, failed);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", STEP);
        m.put("dryRun", dryRun);
        m.put("scanned", scanned);
        m.put(dryRun ? "wouldConvert" : "converted", converted);
        m.put("problems", failed);
        m.put("items", items);
        return m;
    }
}
