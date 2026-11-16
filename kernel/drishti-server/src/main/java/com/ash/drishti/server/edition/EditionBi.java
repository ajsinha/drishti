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
package com.ash.drishti.server.edition;

import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.packs.edition.Edition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BI reduced to the edition's datasets (the table layer's tables, by name) (docs/guides/ADMIN_GUIDE.md › The design; {@code bi.datasets} and {@code bi.models} of the edition
 * file): a dataset is offered when the edition lists it, a model when the edition lists it, and a report, board, Python visual or
 * notebook when the dataset or model it reads is offered (a report that names neither is not). Themes and other items that read no
 * data are always offered. Applied where the BI catalogue is built and where an item is opened by its id, so a hidden item is neither
 * listed nor reachable. Without an edition (or one that lists no datasets or models) everything is offered, as today.
 */
public final class EditionBi {

    /** No edition: every BI item offered. */
    public static final EditionBi NONE = new EditionBi(EditionContext.NONE);

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private final EditionContext context;
    private final Map<String, Boolean> seen = new ConcurrentHashMap<>();

    public EditionBi(EditionContext context) {
        this.context = context;
    }

    /** Whether a dataset (or model) is built on a compute engine that runs nowhere in the cluster (ADMIN_GUIDE.md section 5.2). */
    private volatile java.util.function.Predicate<String> engineOff = name -> false;
    private final Map<String, String> reads = new ConcurrentHashMap<>();

    /** Wired by the engines: a dataset or model name that an engine off everywhere computes is not offered, by any edition. */
    public void engines(java.util.function.Predicate<String> datasetOff) {
        this.engineOff = datasetOff == null ? name -> false : datasetOff;
    }

    /** True when the edition (or the person's lens) offers the item, and no engine it is built on is off everywhere. */
    public boolean shown(BiStore.Item i) {
        if (i == null) {
            return true;
        }
        String body = i.body() == null ? "" : i.body();
        if (engineOff.test(reads.computeIfAbsent(i.id() + "#" + body.hashCode(), k -> reads(i.id(), body)))) {
            return false;
        }
        Edition edition = context.current();
        if (!edition.biReduced()) {
            return true;
        }
        return seen.computeIfAbsent(edition.name() + "#" + i.id() + "#" + body.hashCode(), k -> decide(edition, i.kind(), i.id(), body));
    }

    /** The dataset (or model) an item reads, by name: its own name for a dataset or model, else its body's {@code dataset:} or {@code model:}. */
    private static String reads(String id, String body) {
        String own = id.contains(":") ? id.substring(id.lastIndexOf(':') + 1) : id;
        if (body.isBlank()) {
            return own;
        }
        try {
            JsonNode root = YAML.readTree(body);
            if (root != null && root.isObject()) {
                String t = root.path("table").asText("");                   // a table of the table layer: its name
                String d = root.path("dataset").asText("");
                String m = root.path("model").asText("");
                return !t.isEmpty() ? t : !d.isEmpty() ? d : !m.isEmpty() ? m : own;
            }
        } catch (java.io.IOException | RuntimeException e) {
            // an item that cannot be read reads nothing an engine computes
        }
        return own;
    }

    private static boolean decide(Edition edition, String kind, String id, String body) {
        JsonNode root;
        try {
            root = body.isBlank() ? YAML.createObjectNode() : YAML.readTree(body);
        } catch (java.io.IOException | RuntimeException e) {
            root = YAML.createObjectNode();
        }
        if (root == null || !root.isObject()) {
            root = YAML.createObjectNode();
        }
        String own = id.contains(":") ? id.substring(id.lastIndexOf(':') + 1) : id;
        String dataset = root.path("dataset").asText("");
        String model = root.path("model").asText("");
        switch (kind == null ? "" : kind) {
            case "dataset":
                return edition.datasetShown(dataset.isEmpty() ? own : dataset);
            case "table":                                                   // the table layer's tables are BI's datasets (docs/architecture/ARCHITECTURE.md §28 step 5)
                return edition.datasetShown(root.path("table").asText(own));
            case "model":
                return edition.modelShown(model.isEmpty() ? own : model);
            case "theme":
                return true;
            default:
                if (!model.isEmpty()) {
                    return edition.modelShown(model);
                }
                if (!dataset.isEmpty()) {
                    return edition.datasetShown(dataset);
                }
                return !"report".equals(kind) && !"board".equals(kind);
        }
    }
}
