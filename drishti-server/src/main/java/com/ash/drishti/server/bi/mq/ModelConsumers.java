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
package com.ash.drishti.server.bi.mq;

import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.layer.ImplicitModels;
import com.ash.drishti.server.bi.layer.ModelDef;
import com.ash.drishti.server.bi.layer.ModelDefParser;
import com.ash.drishti.server.bi.layer.Semver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Who reads a model (docs/architecture/BI_MODEL_SPEC.md section 4): a report, board, notebook, alert or subscription <b>references</b> a model, it never owns
 * one, and many consumers share one model. This finds them, for the model page's "Used by" and for the rule that a model is not deleted while something
 * still reads it. Items are found by their bodies (draft and published): a report's or notebook's {@code model:}, a dataset's {@code source.model}, and a
 * board that shows a report on the model; alerts and live subscriptions are added by their services ({@link #source}).
 */
public final class ModelConsumers {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final BiService bi;
    private final List<Function<Predicate<String>, List<Map<String, Object>>>> sources = new CopyOnWriteArrayList<>();

    public ModelConsumers(BiService bi) {
        this.bi = bi;
    }

    /** Adds a source of consumers that are not BI items (alerts on a model, standing queries): given the test of a model reference, those that name it. */
    public void source(Function<Predicate<String>, List<Map<String, Object>>> more) {
        sources.add(more);
    }

    /** The test of whether a consumer's model reference names this model item: its id, or its name at a range its version is in (or no range). */
    public static Predicate<String> namesModel(BiStore.Item model, List<String> bodies) {
        Set<String> names = new LinkedHashSet<>(List.of(model.id()));
        List<Semver> versions = new ArrayList<>();
        for (String body : bodies) {
            ModelDef m = body == null || body.isBlank() ? null : ModelDefParser.parse(body).model();
            if (m != null) {
                names.add(m.name());
                if (Semver.parse(m.version()) != null) {
                    versions.add(Semver.parse(m.version()));
                }
            }
        }
        return ref -> {
            if (ref == null || ref.isBlank() || ref.startsWith(ImplicitModels.QUERY_PREFIX)) {
                return false;
            }
            if (names.contains(ref)) {
                return true;
            }
            Semver.Ref r = Semver.Ref.parse(ref);
            return r != null && names.contains(r.name()) && (versions.isEmpty() || versions.stream().anyMatch(v -> r.range().matches(v)));
        };
    }

    /** The test for the implicit model of a dataset ({@code dataset:<name>}): one shared model per dataset, named by the dataset. */
    public static Predicate<String> namesImplicit(String dataset) {
        String ref = ImplicitModels.QUERY_PREFIX + dataset;
        return ref::equals;
    }

    /**
     * The consumers of a model item: its draft and every published version name it, so a consumer pinned to an earlier major (which keeps reading that major's
     * latest version) is still one.
     */
    public List<Map<String, Object>> of(BiStore.Item model) {
        List<String> bodies = new ArrayList<>(List.of(model.body()));
        if (model.publishedVersion() > 0) {
            bodies.addAll(bi.publishedBodies(model));
        }
        return find(namesModel(model, bodies), model.id());
    }

    /** Every consumer whose model reference passes {@code names}, except the item {@code self}: {@code {kind, id, title, status, owner, workspace}}. */
    public List<Map<String, Object>> find(Predicate<String> names, String self) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> reports = new LinkedHashSet<>();
        List<BiStore.Item> boards = new ArrayList<>();
        for (BiStore.Item i : bi.allItems()) {
            if (i.id().equals(self)) {
                continue;
            }
            if ("board".equals(i.kind())) {
                boards.add(i);
                continue;
            }
            if (!Set.of("report", "notebook", "dataset").contains(i.kind())) {
                continue;
            }
            String published = i.isPublished() ? bi.publishedBody(i) : null;
            if (refs(i.body()).stream().anyMatch(names) || published != null && refs(published).stream().anyMatch(names)) {
                out.add(entry(i));
                if ("report".equals(i.kind())) {
                    reports.add(i.id());
                }
            }
        }
        for (BiStore.Item b : boards) {                                         // a board reads the model through the reports it shows
            String text = b.body() + (b.isPublished() ? bi.publishedBody(b) : "");
            if (reports.stream().anyMatch(text::contains)) {
                out.add(entry(b));
            }
        }
        for (Function<Predicate<String>, List<Map<String, Object>>> s : sources) {
            try {
                out.addAll(s.apply(names));
            } catch (RuntimeException e) {
                // a source that cannot answer leaves its consumers out; the items are still listed
            }
        }
        return out;
    }

    /** The prefix of a notebook's model binding ({@code dataset: mq:trading-risk@1}, the same in an extract's {@code dataset:}). */
    static final String NOTEBOOK_MODEL = "mq:";

    /**
     * The model references a body makes: a top-level {@code model:}, a dataset's {@code source.model} and each {@code source.inputs} model query's
     * {@code model} (a Python dataset), a notebook's {@code dataset: mq:<model>} and its extracts' ({@code dataset: mq:}, {@code model}, {@code query.model}).
     */
    static List<String> refs(String body) {
        List<String> out = new ArrayList<>();
        if (body == null || body.isBlank() || !body.contains("model")) {
            return out;
        }
        JsonNode root;
        try {
            root = YAML.readTree(body);
        } catch (java.io.IOException | RuntimeException e) {
            return out;
        }
        if (root == null || !root.isObject()) {
            return out;
        }
        if (root.path("model").isTextual()) {
            out.add(root.path("model").asText().strip());
        }
        if (root.path("source").path("model").isTextual()) {
            out.add(root.path("source").path("model").asText().strip());
        }
        root.path("source").path("inputs").forEach(x -> {
            if (x.path("model").isTextual()) {
                out.add(x.path("model").asText().strip());
            }
        });
        if (root.path("dataset").isTextual() && root.path("dataset").asText().strip().startsWith(NOTEBOOK_MODEL)) {
            out.add(root.path("dataset").asText().strip().substring(NOTEBOOK_MODEL.length()));
        }
        for (JsonNode x : root.path("extracts")) {
            if (x.path("dataset").isTextual() && x.path("dataset").asText().strip().startsWith(NOTEBOOK_MODEL)) {
                out.add(x.path("dataset").asText().strip().substring(NOTEBOOK_MODEL.length()));
            }
            if (x.path("model").isTextual()) {
                out.add(x.path("model").asText().strip());
            }
            if (x.path("query").path("model").isTextual()) {
                out.add(x.path("query").path("model").asText().strip());
            }
        }
        return out;
    }

    private static Map<String, Object> entry(BiStore.Item i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", i.kind());
        m.put("id", i.id());
        m.put("title", i.title());
        m.put("status", i.status());
        m.put("owner", i.owner());
        m.put("workspace", i.workspace());
        return m;
    }
}
