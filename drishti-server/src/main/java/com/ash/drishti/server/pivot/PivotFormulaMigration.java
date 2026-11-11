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
package com.ash.drishti.server.pivot;

import com.ash.drishti.bicompute.formula.FormulaLowering;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.rachana.el.Printer;
import com.ash.drishti.server.bi.expr.DelParser;
import com.ash.drishti.server.bi.expr.DelToRachana;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The start-up migration of saved pivot formulas into Rachana (BI_GUIDE.md, formulas; HOW_IT_FITS.md §A28 Q20): every measure of every
 * saved pivot view and of every kept version of it ({@code pivot/views/}, {@code pivot/view-versions/}), and the code of every custom function
 * written as a formula ({@code pivot/functions/}), in the content store. A text the pivot reads already (Rachana) is left as it is; a text in
 * the old syntax ({@code and}, {@code or}, {@code not}, {@code x if c else y}, {@code True}, {@code False}, {@code None}, {@code **},
 * {@code //}, a chained comparison) is read by the migration tool's DEL reader, written in Rachana's one spelling and kept when the pivot reads
 * the new text; one audit line per document ({@code pivot-formula-migrate}: each text before and after). A text neither reads is left and
 * logged. Idempotent; a document changed meanwhile is retried at the next start (the store's version check). Deleted with the DEL reader in
 * 2.9.14 (Q24).
 */
public final class PivotFormulaMigration {

    private static final Logger LOG = LoggerFactory.getLogger(PivotFormulaMigration.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> PREFIXES = List.of("pivot/views/", "pivot/view-versions/", "pivot/functions/");

    /** One text rewritten (or refused: {@code after} null). */
    public record Change(String key, String before, String after) {}

    private final ContentStore store;
    private final AuditLog audit;

    public PivotFormulaMigration(ContentStore store, AuditLog audit) {
        this.store = store;
        this.audit = audit;
    }

    /**
     * A formula's text as the pivot reads it: the text itself when it reads, else its Rachana form when the old syntax reads it and the pivot reads
     * the result; empty when neither does (it is not a formula of the old syntax).
     */
    public static Optional<String> migrate(String text) {
        if (text == null || text.isBlank() || text.strip().startsWith("fn:") || FormulaLowering.isFormula(text)) {
            return Optional.of(text);
        }
        try {
            String r = Printer.print(DelToRachana.map(DelParser.parse(text)).expr());
            return FormulaLowering.isFormula(r) ? Optional.of(r) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Migrates every saved formula ({@code dryRun}: works everything out, writes nothing); the changes made (or that would be made). */
    public List<Change> run(boolean dryRun) {
        List<Change> all = new ArrayList<>();
        for (String prefix : PREFIXES) {
            for (String key : store.list(prefix)) {
                if (!key.endsWith(".json")) {
                    continue;
                }
                try {
                    all.addAll(document(key, dryRun));
                } catch (IOException | RuntimeException e) {
                    LOG.warn("saved pivot formulas of {} not migrated: {}", key, e.toString());
                }
            }
        }
        return all;
    }

    private List<Change> document(String key, boolean dryRun) throws IOException {
        ContentStore.Item item = store.get(key).orElse(null);
        if (item == null) {
            return List.of();
        }
        JsonNode root = JSON.readTree(item.text());
        List<Change> changes = new ArrayList<>();
        if (key.startsWith("pivot/functions/")) {
            for (JsonNode v : root.path("versions")) {
                if ("formula".equalsIgnoreCase(v.path("language").asText()) && v instanceof ObjectNode o) {
                    rewrite(key, o, "code", changes);
                }
            }
        } else {
            JsonNode values = root.path("view").path("values");
            for (JsonNode v : values) {
                if (v instanceof ObjectNode o) {
                    rewrite(key, o, "measure", changes);
                }
            }
        }
        List<Change> done = changes.stream().filter(c -> c.after() != null).toList();
        changes.stream().filter(c -> c.after() == null).forEach(c -> LOG.warn("saved pivot formula in {} left as it is (neither Rachana nor the old syntax reads it): {}",
                c.key(), c.before()));
        if (done.isEmpty() || dryRun) {
            return changes;
        }
        store.put(key, JSON.writeValueAsString(root).getBytes(StandardCharsets.UTF_8), item.version());
        StringBuilder detail = new StringBuilder();
        done.forEach(c -> detail.append(detail.isEmpty() ? "" : "; ").append(c.before()).append(" -> ").append(c.after()));
        audit.record("system", "pivot-formula-migrate", key, detail.toString());
        LOG.info("saved pivot formulas of {} written in Rachana: {}", key, detail);
        return changes;
    }

    private static void rewrite(String key, ObjectNode o, String field, List<Change> changes) {
        String text = o.path(field).asText(null);
        if (text == null) {
            return;
        }
        Optional<String> r = migrate(text);
        if (r.isEmpty()) {
            if (looksLikeOldFormula(text)) {
                changes.add(new Change(key, text, null));
            }
            return;
        }
        if (!r.get().equals(text)) {
            o.set(field, TextNode.valueOf(r.get()));
            changes.add(new Change(key, text, r.get()));
        }
    }

    /** A text worth a warning when it is left: it calls something (a field path or a plain name is not a formula). */
    private static boolean looksLikeOldFormula(String text) {
        return text.contains("(") && !text.contains("$.") && !text.contains("@.");
    }
}
