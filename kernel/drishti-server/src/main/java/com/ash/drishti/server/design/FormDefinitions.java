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
package com.ash.drishti.server.design;

import com.ash.drishti.engine.search.form.FormSpec;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.springframework.stereotype.Component;

/**
 * The search forms in force: what the packs declare in {@code pack.yaml} ({@code form:}), overlaid by forms a reviewer approved from the
 * Build workbench (kept on the server, in its preference store, until the pack itself carries the change). A form is a YAML list of
 * fields for one kind. Thread-safe: it keeps no state of its own.
 */
@Component
public class FormDefinitions {

    private static final Logger LOG = LoggerFactory.getLogger(FormDefinitions.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String SYSTEM_USER = "_system.forms";
    static final String NAMESPACE = "forms";

    private final PackRegistry registry;
    private final PreferenceStore store;

    public FormDefinitions(PackRegistry registry, PreferenceStore store) {
        this.registry = registry;
        this.store = store;
    }

    /** The pack that owns a kind, if one does. */
    public Optional<Pack> packOf(String kind) {
        return registry.packs().stream().filter(p -> p.kinds().contains(kind)).findFirst();
    }

    /** The form in force for a kind: an approved one, else the pack's, else empty (the form is then inferred). */
    public Optional<FormSpec> declared(Pack pack, String kind) {
        Optional<String> approved = approved(kind);
        Object raw = null;
        if (approved.isPresent()) {
            raw = new Yaml().load(approved.get());
        } else if (pack.manifest().get("form") instanceof Map<?, ?> m) {
            raw = m.get(kind);
        }
        if (raw == null) {
            return Optional.empty();
        }
        List<String> problems = new ArrayList<>();
        FormSpec s = FormSpec.parse(kind, raw, problems);
        if (!problems.isEmpty()) {
            LOG.warn("form of {}: {}", kind, String.join("; ", problems));
        }
        return Optional.of(s);
    }

    /** The form in force for a kind as the YAML list a Design edits ("" when none is declared). */
    public String yamlOf(String kind) {
        Optional<String> approved = approved(kind);
        if (approved.isPresent()) {
            return approved.get();
        }
        Optional<Pack> pack = packOf(kind);
        if (pack.isEmpty() || !(pack.get().manifest().get("form") instanceof Map<?, ?> m) || m.get(kind) == null) {
            return "";
        }
        return dump(m.get(kind));
    }

    /** Keeps a form a reviewer approved (or that was saved at once when review is off) for a kind. */
    public void approve(String kind, String yaml, String by, String proposal) {
        ObjectNode doc = JSON.createObjectNode();
        doc.put("yaml", yaml).put("by", by == null ? "" : by).put("proposal", proposal == null ? "" : proposal).put("at", Instant.now().toString());
        store.put(SYSTEM_USER, NAMESPACE, kind, doc);
    }

    /** Removes an approved form, so the pack's own form is in force again. */
    public boolean revert(String kind) {
        return store.delete(SYSTEM_USER, NAMESPACE, kind);
    }

    private Optional<String> approved(String kind) {
        return store.get(SYSTEM_USER, NAMESPACE, kind).map(n -> n.path("yaml")).filter(JsonNode::isTextual).map(JsonNode::asText).filter(t -> !t.isBlank());
    }

    /** A YAML list of fields as text, one field to a line in flow style, as the workbench writes it. */
    public static String dump(Object form) {
        DumperOptions o = new DumperOptions();
        o.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        o.setWidth(140);
        Object list = form instanceof Map<?, ?> m && m.get("fields") != null ? m.get("fields") : form;
        return new Yaml(o).dump(list);
    }
}
