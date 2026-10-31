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
import com.ash.drishti.server.bi.layer.DatasetDefParser;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The stored-item dataset-to-model migration (docs/architecture/BI_DESIGN.md › The model layer: the specification of wave W1, section 8.3, step 5): the {@link ModelConverter} over every stored
 * {@code rachana: 1} dataset and its consumers. A dataset item becomes the model item (same id, so every reference by id still holds; its 2.1 text stays in
 * its history), each table a new dataset item in the same workspace with the same owner and audience, published when the old dataset was; a derived dataset
 * becomes its model and a Python dataset item; reports are rebound through {@link StoredRewrite} (the text before kept, a published report published again).
 * Stored items are converted with {@link ModelConverter.Exposure#CLOSED}: a restricted role is never given rows it did not see, and the report says how to
 * open them. A composed dataset is folded into its host model; the item itself is left for its owner to delete (listed). An item the converter refuses, a
 * draft that is ahead of its published dataset, or a name already taken is never changed: it is listed in {@link MigrationProblems} (step {@code models}).
 * Idempotent: a converted item is {@code rachana: 2}.
 */
final class ModelUpgrade {

    static final String STEP = "models";
    private static final Logger LOG = LoggerFactory.getLogger(ModelUpgrade.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BiStore store;
    private final StoredRewrite rewrite;
    private final MigrationProblems problems;
    private final AuditLog audit;
    private final int versionsKeep;
    private final int revisionsKeep;

    ModelUpgrade(BiStore store, StoredRewrite rewrite, MigrationProblems problems, AuditLog audit, int versionsKeep, int revisionsKeep) {
        this.store = store;
        this.rewrite = rewrite;
        this.problems = problems;
        this.audit = audit;
        this.versionsKeep = versionsKeep;
        this.revisionsKeep = revisionsKeep;
    }

    Map<String, Object> run(String actor, boolean dryRun) {
        List<BiStore.Item> all = store.items();
        Map<String, BiStore.Item> byId = new LinkedHashMap<>();
        List<ModelConverter.Source> sources = new ArrayList<>();
        List<String> ahead = new ArrayList<>();
        for (BiStore.Item i : all) {
            if (i.body() == null || i.body().isBlank() || !List.of("dataset", "report", "board", "notebook", "python").contains(i.kind())) {
                continue;
            }
            if ("dataset".equals(i.kind()) && !DatasetDefParser.isVersion2(i.body())) {
                Optional<String> pub = rewrite.published(i);
                if (pub.isPresent() && !pub.get().equals(i.body())) {
                    ahead.add(i.id());                                                     // two texts to convert: the owner publishes or discards the draft first
                    continue;
                }
            }
            byId.put(i.id(), i);
            sources.add(new ModelConverter.Source(i.id(), i.kind(), i.body()));
        }
        ModelConverter.Result plan = ModelConverter.convert(sources, ModelConverter.Exposure.CLOSED);
        Instant now = Instant.now();
        List<Map<String, Object>> items = new ArrayList<>();
        int converted = 0;
        int failed = 0;
        java.util.Set<String> claimed = new java.util.HashSet<>();                       // names this run gives out: a dry run claims them as the run would
        for (String id : ahead) {
            BiStore.Item i = byId.getOrDefault(id, store.item(id).orElseThrow());
            problems.set(STEP, id, List.of(new MigrationProblems.Problem(STEP, id, i.title(), i.kind(), "body", "not converted: its draft is ahead of its published version; "
                    + "publish or discard the draft, then run drishti.py bi migrate --models", now)));
            items.add(Map.of("key", id, "kind", i.kind(), "name", i.title(), "state", "problem", "problem", "the draft is ahead of the published version"));
            failed++;
        }
        for (ModelConverter.Item it : plan.items()) {
            BiStore.Item src = byId.get(it.key());
            if (src == null) {
                continue;
            }
            if ("problem".equals(it.state())) {
                problems.set(STEP, src.id(), List.of(new MigrationProblems.Problem(STEP, src.id(), src.title(), src.kind(), "body", "not converted, the item keeps its text: "
                        + it.problem(), now)));
                failed++;
                items.add(it.toMap(false));
                continue;
            }
            problems.set(STEP, src.id(), List.of());
            if ("unchanged".equals(it.state())) {
                continue;
            }
            Map<String, Object> view = it.toMap(false);
            items.add(view);
            if (!"converted".equals(it.state())) {
                continue;
            }
            String taken = "dataset".equals(src.kind()) ? taken(it, src, all, claimed) : null;     // the same check in a dry run and a real one
            if (taken != null) {
                problems.set(STEP, src.id(), List.of(new MigrationProblems.Problem(STEP, src.id(), src.title(), src.kind(), "body", "not converted: " + taken, now)));
                view.put("state", "problem");
                view.put("problem", taken);
                failed++;
                continue;
            }
            if (dryRun) {
                converted++;
                continue;
            }
            if ("dataset".equals(src.kind())) {
                view.put("created", dataset(src, it, actor, now));
            } else {
                Map<String, String> texts = new LinkedHashMap<>();
                rewrite.apply(src, body -> texts.computeIfAbsent(body, b -> again(b, src, sources)), actor, "migrate", "bi-model-migrated",
                        "bound to " + it.became() + " (the dataset-to-model migration)");
            }
            converted++;
        }
        if (!dryRun && (converted > 0 || failed > 0)) {
            LOG.info("BI model migration: {} stored item(s) converted, {} left with a problem (Admin, BI settings)", converted, failed);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", STEP);
        m.put("dryRun", dryRun);
        m.put("scanned", sources.size() + ahead.size());
        m.put(dryRun ? "wouldConvert" : "converted", converted);
        m.put("problems", failed);
        m.put("items", items);
        m.put("report", plan.report());
        return m;
    }

    /** A consumer's other text (its published version, when the draft differs) converted with the same bindings. */
    private static String again(String body, BiStore.Item src, List<ModelConverter.Source> sources) {
        List<ModelConverter.Source> with = new ArrayList<>(sources.stream().filter(s -> !s.key().equals(src.id())).toList());
        with.add(new ModelConverter.Source(src.id(), src.kind(), body));
        return ModelConverter.convert(with, ModelConverter.Exposure.CLOSED).items().stream().filter(i -> i.key().equals(src.id()) && "converted".equals(i.state()))
                .map(ModelConverter.Item::yaml).findFirst().orElse(body);
    }

    /**
     * Why the dataset item cannot be converted, or null: a stored {@code rachana: 2} dataset already has the name of a dataset it would make, another model
     * of its workspace already has the model's name (one model per name, ModelNames), or an item converted earlier in this run took either name. When it
     * can be, its names are claimed for the rest of the run (a dry run claims them too, so it finds what the real run would).
     */
    private static String taken(ModelConverter.Item it, BiStore.Item src, List<BiStore.Item> all, java.util.Set<String> claimed) {
        for (ModelConverter.Output o : it.outputs()) {
            if ("dataset".equals(o.kind()) && (claimed.contains("dataset:" + o.name()) || all.stream().anyMatch(x -> "dataset".equals(x.kind()) && x.body() != null
                    && DatasetDefParser.isVersion2(x.body()) && o.name().equals(DatasetDefParser.parse(x.body()).dataset() == null ? null : DatasetDefParser.parse(x.body()).dataset().name())))) {
                return "a dataset named '" + o.name() + "' exists already: rename it, then run drishti.py bi migrate --models";
            }
            String model = "model".equals(o.kind()) ? com.ash.drishti.server.bi.ModelNames.of(o.yaml()) : null;
            if (model != null && (claimed.contains("model:" + src.workspace() + "|" + model) || all.stream().anyMatch(x -> "model".equals(x.kind()) && !x.id().equals(src.id())
                    && x.workspace().equals(src.workspace()) && model.equals(com.ash.drishti.server.bi.ModelNames.of(x.body()))))) {
                return "its workspace has a model named '" + model + "' already: rename one of them, then run drishti.py bi migrate --models";
            }
        }
        for (ModelConverter.Output o : it.outputs()) {
            if ("dataset".equals(o.kind())) {
                claimed.add("dataset:" + o.name());
            } else if ("model".equals(o.kind()) && com.ash.drishti.server.bi.ModelNames.of(o.yaml()) != null) {
                claimed.add("model:" + src.workspace() + "|" + com.ash.drishti.server.bi.ModelNames.of(o.yaml()));
            }
        }
        return null;
    }

    /** The dataset item becomes the model (same id); each new dataset is an item of the same workspace, owner and audience. */
    private List<String> dataset(BiStore.Item src, ModelConverter.Item it, String actor, Instant at) {
        List<String> created = new ArrayList<>();
        boolean published = src.isPublished();
        for (ModelConverter.Output o : it.outputs()) {
            if (!"dataset".equals(o.kind())) {
                continue;
            }
            String id = "bi-" + HexFormat.of().formatHex(bytes());
            BiStore.Item d = new BiStore.Item(id, src.workspace(), "dataset", o.name(), "", src.owner(), src.tags(), o.yaml(), published ? "published" : "draft",
                    published ? 1 : 0, "none", "", src.audienceRoles(), src.audienceUsers(), src.audiencePacks(), "", null, at, at, actor, published ? at : null,
                    published ? actor : "");
            store.saveItem(d);
            if (published) {
                store.addVersion(new BiStore.Version(id, 1, o.yaml(), "the table of " + src.title() + " (the dataset-to-model migration)", actor, at), versionsKeep);
            }
            store.addRevision(id, o.yaml(), "made by the dataset-to-model migration from " + src.title(), actor, at, "migrate", published ? 1 : 0, revisionsKeep);
            store.addEvent(id, "migrate", actor, "a table of " + src.title() + " (" + src.id() + ")", 1, at);
            audit.record(actor, "bi-item-created", id, "dataset '" + o.name() + "' in " + src.workspace() + " (the dataset-to-model migration of " + src.id() + ")");
            created.add(id);
        }
        String model = it.outputs().stream().filter(o -> "model".equals(o.kind())).map(ModelConverter.Output::yaml).findFirst().orElseThrow();
        BiStore.Item now = store.item(src.id()).orElse(src);
        int before = rewrite.keepBefore(now, now.body(), actor, at);
        int version = published ? now.publishedVersion() + 1 : now.publishedVersion();
        store.saveItem(new BiStore.Item(now.id(), now.workspace(), "model", now.title(), now.description(), now.owner(), now.tags(), model, published ? "published" : now.status(),
                version, now.endorsement(), now.endorsedBy(), now.audienceRoles(), now.audienceUsers(), now.audiencePacks(), now.proposedBy(), now.proposedAt(), now.createdAt(),
                at, actor, published ? at : now.publishedAt(), published ? actor : now.publishedBy()));
        if (published) {
            store.addVersion(new BiStore.Version(now.id(), version, model, "the dataset-to-model migration (from version " + now.publishedVersion() + ")", actor, at), versionsKeep);
        }
        BiStore.Revision r = store.addRevision(now.id(), model, "the dataset-to-model migration: now the model over " + created.size() + " dataset(s)", actor, at, "migrate",
                published ? version : 0, revisionsKeep);
        String note = "the dataset became the " + it.became() + "; new datasets " + created + "; the 2.1 text is saved state " + before;   // became: "model x@1 over [...]"
        store.addEvent(now.id(), "migrate", actor, note, r.seq(), at);
        audit.record(actor, "bi-model-migrated", now.id(), note);
        return created;
    }

    private static byte[] bytes() {
        byte[] b = new byte[5];
        RANDOM.nextBytes(b);
        return b;
    }
}
