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
 * BI reduced to the edition's datasets (docs/architecture/EDITIONS.md, section 5.5; {@code bi.datasets} and {@code bi.models} of the edition
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

    /** True when the edition (or the person's lens) offers the item. */
    public boolean shown(BiStore.Item i) {
        Edition edition = context.current();
        if (!edition.biReduced() || i == null) {
            return true;
        }
        String body = i.body() == null ? "" : i.body();
        return seen.computeIfAbsent(edition.name() + "#" + i.id() + "#" + body.hashCode(), k -> decide(edition, i.kind(), i.id(), body));
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
