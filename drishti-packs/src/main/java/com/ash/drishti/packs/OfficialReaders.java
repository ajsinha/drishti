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
package com.ash.drishti.packs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The readers of the official results follow the format they are published in (2.9.5). A pack's read-only connector over
 * {@code risk-results} (BI's {@code var-results}, {@code exposure-results}: {@code plugin: delta} in the packs) becomes, when the server
 * publishes those results to Iceberg ({@code drishti.var.official.format} or {@code drishti.ccr.exposure.official.format}:
 * {@code iceberg}), an Iceberg connector over the official root ({@code root: ${<prefix>.root:<the template's root>}}, {@code engine:
 * native}, and the {@code s3.*} settings of {@code <prefix>.storage} as placeholders). This is the template itself as the server sees
 * it: the file generated from it at start says {@code plugin: iceberg}, a file never edited follows when the format changes (its
 * template's fingerprint changed), and an edited file is kept and its difference listed (Admin &rarr; Connectors &rarr; Template
 * updates). Delta (the default): the template unchanged. Stateless.
 */
public final class OfficialReaders {

    /** The settings prefix of each official kind's publisher. */
    static final Map<String, String> PREFIX = Map.of("var-official", "drishti.var.official", "exposure-official", "drishti.ccr.exposure.official",
            "exposure-official-cpty", "drishti.ccr.exposure.official");

    private OfficialReaders() {
    }

    /** The prefix of the publisher whose results the template reads, or null when it is not a reader of official results. */
    static String prefixOf(Map<String, Object> t) {
        if (t == null || !(t.get("settings") instanceof Map<?, ?> s) || !"risk-results".equals(String.valueOf(s.get("domain")))
                || !"delta".equals(String.valueOf(t.get("plugin")))) {
            return null;
        }
        for (String k : ConnectorFiles.kindNames(t.get("kinds"))) {
            if (PREFIX.containsKey(k)) {
                return PREFIX.get(k);
            }
        }
        return null;
    }

    /**
     * The template as the server reads it.
     *
     * @param t the pack's template
     * @param property a setting of the server, resolved (null when not set)
     * @param storage the keys of a map setting of the server ({@code <prefix>.storage}: {@code s3.region} …)
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> adjust(Map<String, Object> t, Function<String, String> property, Function<String, List<String>> storage) {
        String prefix = prefixOf(t);
        if (prefix == null) {
            return t;
        }
        String format = property.apply(prefix + ".format");
        if (format == null || !"iceberg".equalsIgnoreCase(format.trim())) {
            return t;
        }
        Map<String, Object> out = new LinkedHashMap<>(t);
        Map<String, Object> settings = new LinkedHashMap<>((Map<String, Object>) t.get("settings"));
        Object root = settings.get("root");
        out.put("plugin", "iceberg");
        settings.put("root", "${" + prefix + ".root" + (root == null ? "" : ":" + root) + "}");
        settings.put("engine", "native");
        for (String k : storage.apply(prefix + ".storage")) {
            settings.put(k, "${" + prefix + ".storage." + k + "}");
        }
        out.put("settings", settings);
        return out;
    }
}
