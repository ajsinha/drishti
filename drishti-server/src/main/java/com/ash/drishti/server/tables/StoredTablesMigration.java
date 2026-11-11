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
package com.ash.drishti.server.tables;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.tables.migrate.DelTranslator;
import com.ash.drishti.tables.migrate.MigrationAudit;
import com.ash.drishti.tables.migrate.TablesMigrator;
import com.ash.drishti.tables.yaml.Yaml;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The first-start migration of stored items (HOW_IT_FITS.md §A28 section 4.3, step 1; Q16, Q17): every BI dataset and model in the item
 * store, in every workspace, becomes a {@code table} or {@code model} item of the layer under its id, <b>every version rewritten in place</b>
 * (consumers pinned to an old major read it), the draft too. Before anything is written each text is migrated with the latest of every other
 * item as its context (a derived dataset reads its base, a model its datasets); an item any of whose texts is refused is left as it is and
 * the reason is an event on its page. The text before each version is a saved state of the item's history; one audit line per item
 * ({@code tables-migrate}: the person or {@code system}, and the hashes before and after of each version). Idempotent: a table item, and a model
 * item already in the layer's form ({@code rachana: 3}; BI's models and the layer's share the kind {@code model}), are not read. Runs before BI answers its first query once the switch lands; until then it is called by nothing at start.
 */
public final class StoredTablesMigration {

    /** What happened to one item. */
    public record Outcome(String item, String kind, String state, int versions, String reason) {}

    private final BiStore store;
    private final AuditLog audit;
    private final TablesMigrator migrator;
    private final Clock clock;

    public StoredTablesMigration(BiStore store, AuditLog audit, DelTranslator translator, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.migrator = new TablesMigrator(translator);
        this.clock = clock;
    }

    /** Migrates every stored dataset and model ({@code dryRun}: works everything out, writes nothing). */
    public List<Outcome> run(String actor, boolean dryRun) {
        String who = actor == null || actor.isBlank() ? "system" : actor;
        Map<String, BiStore.Item> items = new LinkedHashMap<>();
        Map<String, String> context = new LinkedHashMap<>();
        for (BiStore.Item i : store.items()) {
            if (!"dataset".equals(i.kind()) && !"model".equals(i.kind())) {
                continue;
            }
            String latest = i.isPublished() ? store.version(i.id(), i.publishedVersion()).map(BiStore.Version::body).orElse(i.body()) : i.body();
            if ("model".equals(i.kind()) && BiItemTableStore.layerForm(latest)) {
                continue;                                                   // a model of the layer already (BI's models share the kind's name)
            }
            String key = key(i.kind(), latest, i.id());
            items.put(key, i);
            context.put(key, latest);
        }
        List<Outcome> out = new ArrayList<>();
        for (Map.Entry<String, BiStore.Item> e : items.entrySet()) {
            out.add(migrate(e.getKey(), e.getValue(), context, who, dryRun));
        }
        return out;
    }

    private Outcome migrate(String key, BiStore.Item i, Map<String, String> context, String who, boolean dryRun) {
        Map<Integer, String> before = new LinkedHashMap<>();
        for (BiStore.Version v : store.versions(i.id())) {
            before.put(v.version(), v.body());
        }
        before.put(0, i.body() == null ? "" : i.body());                    // 0: the draft
        Map<Integer, String> after = new LinkedHashMap<>();
        String kind = null;
        for (Map.Entry<Integer, String> v : before.entrySet()) {
            if (v.getValue().isBlank()) {
                after.put(v.getKey(), v.getValue());
                continue;
            }
            Map<String, String> files = new LinkedHashMap<>(context);
            files.put(key, v.getValue());
            TablesMigrator.Plan plan;
            try {
                plan = migrator.plan(files, null);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            TablesMigrator.Item item = plan.items().stream().filter(x -> x.source().equals(key)).findFirst().orElse(null);
            if (item == null || !"converted".equals(item.state()) || item.outputs().size() != 1 || !plan.writes().containsKey(item.outputs().get(0))) {
                String why = item == null ? "not read" : item.refusal() != null ? item.refusal()
                        : item.outputs().size() != 1 ? "it gives " + item.outputs().size() + " definitions: migrate it as files (tables migrate), then store them"
                        : "nothing to write";
                if (!dryRun) {
                    store.addEvent(i.id(), "tables-migrate-refused", who, "version " + (v.getKey() == 0 ? "draft" : v.getKey()) + ": " + why, 0, clock.instant());
                }
                return new Outcome(i.id(), i.kind(), "refused", 0, "version " + (v.getKey() == 0 ? "draft" : v.getKey()) + ": " + why);
            }
            String output = item.outputs().get(0);
            kind = output.endsWith(".model.yaml") ? "model" : "table";
            after.put(v.getKey(), plan.writes().get(output));
        }
        if (dryRun) {
            return new Outcome(i.id(), kind, "would migrate", before.size() - 1, null);
        }
        Instant at = clock.instant();
        StringBuilder detail = new StringBuilder("a BI " + i.kind() + " into a " + kind + " of the table layer (every version rewritten in place):");
        for (Map.Entry<Integer, String> v : before.entrySet()) {
            int n = v.getKey();
            String text = after.get(n);
            detail.append(' ').append(n == 0 ? "draft" : "v" + n).append(' ').append(MigrationAudit.hash(v.getValue())).append(" -> ").append(MigrationAudit.hash(text)).append(';');
            if (text.equals(v.getValue())) {
                continue;
            }
            store.addRevision(i.id(), v.getValue(), "the text of " + (n == 0 ? "the draft" : "version " + n) + " before the table layer's migration", who, at,
                    "migrate", 0, Integer.MAX_VALUE);
            if (n > 0) {
                BiStore.Version old = store.version(i.id(), n).orElseThrow();
                store.addVersion(new BiStore.Version(i.id(), n, text, old.note(), old.createdBy(), old.createdAt()), Integer.MAX_VALUE);
            }
        }
        store.saveItem(new BiStore.Item(i.id(), i.workspace(), kind, i.title(), i.description(), i.owner(), i.tags(), after.get(0), i.status(),
                i.publishedVersion(), i.endorsement(), i.endorsedBy(), i.audienceRoles(), i.audienceUsers(), i.audiencePacks(), i.proposedBy(),
                i.proposedAt(), i.createdAt(), at, who, i.publishedAt(), i.publishedBy()));
        store.addEvent(i.id(), "tables-migrate", who, detail.toString(), 0, at);
        audit.record(who, MigrationAudit.ACTION, i.id(), detail.toString());
        return new Outcome(i.id(), kind, "migrated", before.size() - 1, null);
    }

    /** The name a text has in a plan, as a file of the BI folder would: {@code datasets/<name>.dataset.yaml}, {@code models/<name>.model.yaml}. */
    private static String key(String kind, String text, String id) {
        String field = "model".equals(kind) ? "model" : "dataset";
        String name = id;
        try {
            JsonNode n = Yaml.MAPPER.readTree(text == null ? "" : text);
            if (n != null && n.hasNonNull(field)) {
                name = n.get(field).asText();
            }
        } catch (IOException e) {
            // an unreadable text keeps the item's id as its name: the plan refuses it
        }
        return ("model".equals(kind) ? "models/" : "datasets/") + name + ("model".equals(kind) ? ".model.yaml" : ".dataset.yaml");
    }
}
