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
package com.ash.drishti.server.bi.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * What is left of the 2.1 composed and derived datasets (removed in 2.2: docs/architecture/BI_DESIGN.md › The model layer: the specification of wave W1, section 8): their text is still read, by the
 * dataset-to-model converter ({@code drishti.py bi migrate --models}) and by impact analysis, but never run. A {@code rachana: 1} dataset with
 * {@code from:} or {@code derived:} parses with {@link #REMOVED} as an error. Pure.
 */
public final class LegacySources {

    /** The code of a composed or derived dataset's problems (its old shape). */
    public static final String COMPOSE = "DRS-9014";
    public static final String DERIVED = "DRS-9017";
    /** The refusal of a composed or derived dataset since 2.2. */
    public static final String REMOVED = "DRS-9190";
    /** The description of a column a 2.1 Transform's result taught its dataset. */
    public static final String LEARNED = "column of the Transform's last result";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private LegacySources() {}

    /** The {@code dataset:} name of a dataset text without checking it (cheap, never recursive); empty when there is none. */
    public static String declaredName(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return "";
        }
        try {
            JsonNode n = YAML.readTree(yaml);
            return n != null && n.path("dataset").isTextual() ? n.path("dataset").asText() : "";
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "";
        }
    }

    /** True when the text declares {@code from:} or {@code derived:} (a 2.1 composed or derived dataset, which reads other datasets). */
    public static boolean dependsOnOthers(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return false;
        }
        try {
            JsonNode n = YAML.readTree(yaml);
            return n != null && (n.has("from") || n.has("derived"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;
        }
    }

    /** The name a measure has as a table column: an identifier (a space or an ampersand becomes an underscore), unique among {@code taken}. */
    public static String identifier(String name, java.util.Collection<String> taken) {
        StringBuilder b = new StringBuilder();
        for (char ch : name.toCharArray()) {
            b.append(ch < 128 && Character.isLetterOrDigit(ch) || ch == '_' ? ch : '_');
        }
        String base = b.toString().replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (base.isEmpty() || Character.isDigit(base.charAt(0))) {
            base = "c_" + base;
        }
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        if (Dataset.ID.equals(base) || Dataset.DATE.equals(base) || Dataset.INDEX.equals(base)) {
            base = base + "_";
        }
        String candidate = base;
        for (int n = 2; taken.contains(candidate); n++) {
            candidate = base + "_" + n;
        }
        return candidate;
    }

    /** The sources of a 2.1 dataset (composed: its {@code from}; derived: its inputs), in order. */
    public static List<Dataset.Input> sources(Dataset ds) {
        if (ds.compose() != null) {
            return new ArrayList<>(ds.compose().from().values());
        }
        if (ds.derived() != null) {
            return new ArrayList<>(ds.derived().inputs().values());
        }
        return List.of();
    }
}
