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

import com.ash.drishti.kernel.api.official.OfficialResults;
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
 * updates). Delta (the default): the template unchanged. A reader in the lake form ({@code lake: var-official} or {@code ccr-official},
 * whose lake files take format, root and storage from the publisher's settings) only has its plugin made Iceberg. Stateless.
 */
public final class OfficialReaders {

    /**
     * The lake of each publisher's results (config/lakes/banking/var-official.yaml, ccr-official.yaml): its format, root and storage are the
     * publisher's own settings, so a reader that names it needs only its plugin made Iceberg when the publisher writes Iceberg.
     */
    static final Map<String, String> LAKES = collect(OfficialResults::lakes);

    /** The settings prefix of each official kind's publisher. */
    static final Map<String, String> PREFIX = collect(OfficialResults::kinds);

    private static Map<String, String> collect(java.util.function.Function<OfficialResults, Map<String, String>> part) {
        Map<String, String> out = new LinkedHashMap<>();
        for (OfficialResults r : java.util.ServiceLoader.load(OfficialResults.class, OfficialReaders.class.getClassLoader())) {
            part.apply(r).forEach(out::putIfAbsent);
        }
        return Map.copyOf(out);
    }

    /** The lakes of official results: the class path's publishers, then a loaded module's. */
    static Map<String, String> lakes() {
        return withLive(LAKES, OfficialResults::lakes);
    }

    /** The settings prefix of each official kind's publisher: the class path's, then a loaded module's. */
    static Map<String, String> prefixes() {
        return withLive(PREFIX, OfficialResults::kinds);
    }

    private static Map<String, String> withLive(Map<String, String> fixed, java.util.function.Function<OfficialResults, Map<String, String>> part) {
        java.util.List<OfficialResults> live = com.ash.drishti.common.Extensions.of(OfficialResults.class);
        if (live.isEmpty()) {
            return fixed;
        }
        Map<String, String> out = new LinkedHashMap<>(fixed);
        live.forEach(r -> part.apply(r).forEach(out::putIfAbsent));
        return out;
    }

    private OfficialReaders() {
    }

    /** The prefix of the publisher whose results the template reads, or null when it is not a reader of official results. */
    static String prefixOf(Map<String, Object> t) {
        if (t == null || !(t.get("settings") instanceof Map<?, ?> s) || !"delta".equals(String.valueOf(t.get("plugin")))) {
            return null;
        }
        if (lakes().containsKey(String.valueOf(s.get("lake")))) {
            return lakes().get(String.valueOf(s.get("lake")));
        }
        if (!"risk-results".equals(String.valueOf(s.get("domain")))) {
            return null;
        }
        for (String k : ConnectorFiles.kindNames(t.get("kinds"))) {
            if (prefixes().containsKey(k)) {
                return prefixes().get(k);
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
        if (lakes().containsKey(String.valueOf(settings.get("lake")))) {   // the lake form: root and storage come from the lake file
            out.put("plugin", "iceberg");
            settings.put("engine", "native");
            out.put("settings", settings);
            return out;
        }
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
