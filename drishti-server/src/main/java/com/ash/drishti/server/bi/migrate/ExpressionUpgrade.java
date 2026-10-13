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
import com.ash.drishti.server.bi.expr.DelException;
import com.ash.drishti.server.bi.expr.ExpressionMigrator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The stored-item expression migration (docs/architecture/BI_DESIGN.md section 12.2, task 6; docs/guides/EXPRESSIONS.md "Moving from the old syntax"): every
 * stored BI item (drafts, proposals and the published text) whose expressions are in the old syntax is converted to DEL v2 with {@link ExpressionMigrator}
 * (comments and layout kept, the same compiled tree asserted per expression), through {@link StoredRewrite} so the text before stays restorable and the change
 * is audited ({@code bi-expressions-migrated}). An item with an expression the converter cannot convert is not changed at all: it is listed in
 * {@link MigrationProblems} (step {@code expressions}) and logged. Idempotent: a converted item has nothing left to convert.
 */
final class ExpressionUpgrade {

    static final String STEP = "expressions";
    private static final Logger LOG = LoggerFactory.getLogger(ExpressionUpgrade.class);

    private final BiStore store;
    private final StoredRewrite rewrite;
    private final MigrationProblems problems;

    ExpressionUpgrade(BiStore store, StoredRewrite rewrite, MigrationProblems problems) {
        this.store = store;
        this.rewrite = rewrite;
        this.problems = problems;
    }

    /** Scans every stored item; converts unless {@code dryRun}. The answer lists every item with old-syntax expressions and what happened to it. */
    Map<String, Object> run(String actor, boolean dryRun) {
        List<Map<String, Object>> items = new ArrayList<>();
        int converted = 0;
        int failed = 0;
        int scanned = 0;
        for (BiStore.Item i : store.items()) {
            scanned++;
            Map<String, Object> one = item(i, actor, dryRun);
            if (one == null) {
                continue;
            }
            items.add(one);
            switch (String.valueOf(one.get("state"))) {
                case "converted", "would convert" -> converted++;
                case "problem" -> failed++;
                default -> { }
            }
        }
        if (!dryRun && (converted > 0 || failed > 0)) {
            LOG.info("BI expression migration: {} stored item(s) converted to DEL v2, {} left with a problem (Admin, BI settings)", converted, failed);
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

    private Map<String, Object> item(BiStore.Item i, String actor, boolean dryRun) {
        Optional<String> pub = rewrite.published(i);
        Text draft = text(i.body());
        Text published = pub.map(ExpressionUpgrade::text).orElse(Text.NONE);
        List<ExpressionMigrator.Change> bad = new ArrayList<>(draft.problems());
        published.problems().stream().filter(p -> bad.stream().noneMatch(b -> b.path().equals(p.path()) && b.from().equals(p.from()))).forEach(bad::add);
        boolean change = !draft.changes().isEmpty() || !published.changes().isEmpty();
        if (!bad.isEmpty()) {
            Instant now = Instant.now();
            problems.set(STEP, i.id(), bad.stream().map(b -> new MigrationProblems.Problem(STEP, i.id(), i.title(), i.kind(), b.path(),
                    "not converted, the item keeps its text: " + b.problem() + " (" + b.from() + ")", now)).toList());
            if (!dryRun) {
                LOG.warn("BI expression migration: '{}' ({}) keeps its old-syntax text: {}", i.title(), i.id(), bad.get(0).path() + ": " + bad.get(0).problem());
            }
            return view(i, draft, published, bad, "problem");
        }
        problems.set(STEP, i.id(), List.of());
        if (!change) {
            return null;
        }
        if (dryRun) {
            return view(i, draft, published, bad, "would convert");
        }
        int n = Math.max(draft.changes().size(), published.changes().size());
        StoredRewrite.Done done = rewrite.apply(i, body -> {
            Text t = text(body);
            return t.changes().isEmpty() || !t.problems().isEmpty() ? body : t.yaml();
        }, actor, "migrate", "bi-expressions-migrated", n + " expression(s) converted to DEL v2");
        Map<String, Object> v = view(i, draft, published, bad, "converted");
        v.put("before", done.before());
        v.put("publishedVersion", done.version());
        return v;
    }

    private static Map<String, Object> view(BiStore.Item i, Text draft, Text published, List<ExpressionMigrator.Change> bad, String state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id());
        m.put("title", i.title());
        m.put("kind", i.kind());
        m.put("workspace", i.workspace());
        m.put("status", i.status());
        m.put("state", state);
        m.put("draft", draft.changes().stream().map(ExpressionMigrator.Change::toMap).toList());
        m.put("published", published.changes().stream().map(ExpressionMigrator.Change::toMap).toList());
        m.put("problems", bad.stream().map(ExpressionMigrator.Change::toMap).toList());
        return m;
    }

    /** One text's conversion: the new YAML, what changed and what could not be converted. A text that is not YAML has nothing to convert. */
    private record Text(String yaml, List<ExpressionMigrator.Change> changes, List<ExpressionMigrator.Change> problems) {
        static final Text NONE = new Text("", List.of(), List.of());
    }

    private static Text text(String body) {
        if (body == null || body.isBlank()) {
            return Text.NONE;
        }
        try {
            ExpressionMigrator.Result r = ExpressionMigrator.migrate(body);
            return new Text(r.yaml(), r.changes(), r.problems());
        } catch (DelException e) {
            return new Text(body, List.of(), List.of());                          // not YAML: Check says so; nothing to convert
        }
    }
}
