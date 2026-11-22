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
package com.ash.drishti.server.home;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/** Reads a Home from its three files; every fault is said with the file and the key, never skipped. */
final class HomeFiles {

    private HomeFiles() {}

    static HomeDefinition read(Path homeFile) throws IOException {
        Map<String, Object> h = yaml(homeFile);
        String name = text(h, "home", homeFile);
        if (!name.matches("[a-z][a-z0-9-]{1,63}")) {
            throw new IOException(homeFile + ": home is a name of a-z, 0-9 and -");
        }
        Path dir = homeFile.getParent();
        String sutra = text(h, "sutra", homeFile);
        String arrange = h.getOrDefault("arrange", "allow").toString();
        if (!arrange.equals("allow") && !arrange.equals("locked")) {
            throw new IOException(homeFile + ": arrange is allow or locked, not " + arrange);
        }
        Path sutraFile = dir.resolve(sutra).normalize();
        if (!sutraFile.startsWith(dir) || !Files.isRegularFile(sutraFile)) {
            throw new IOException(homeFile + ": the Sutra " + sutra + " is not a file beside the Home");
        }
        HomeDefinition.Measures m = h.get("measures") == null ? new HomeDefinition.Measures(null, null, null, null, null, null)
                : measures(dir.resolve(h.get("measures").toString()).normalize());
        return new HomeDefinition(name, text(h, "title", homeFile), h.getOrDefault("description", "").toString(), strings(h.get("modules")), sutra,
                arrange.equals("allow"), strings(h.get("requires")), m, Files.readString(sutraFile, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    static HomeDefinition.Measures measures(Path file) throws IOException {
        Map<String, Object> m = yaml(file);
        Map<String, Object> ref = m.get("reference") instanceof Map<?, ?> r ? (Map<String, Object>) r : Map.of();
        List<HomeDefinition.Kpi> kpis = new ArrayList<>();
        for (Object o : list(m.get("kpis"))) {
            Map<String, Object> k = (Map<String, Object>) o;
            kpis.add(new HomeDefinition.Kpi(req(k, "label", file), req(k, "engine", file), req(k, "measure", file),
                    k.get("args") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of(), k.getOrDefault("fmt", "amount0").toString(),
                    k.get("history") instanceof Number n ? n.intValue() : 0, k.getOrDefault("open", "").toString()));
        }
        List<HomeDefinition.Follow> follow = new ArrayList<>();
        for (Object o : list(m.get("follow"))) {
            Map<String, Object> f = (Map<String, Object>) o;
            follow.add(new HomeDefinition.Follow(req(f, "measure", file), req(f, "engine", file), req(f, "subjects", file), num(f.get("min-abs")),
                    num(f.get("min-rel")), strings(f.get("drivers"))));
        }
        List<HomeDefinition.Rule> rules = new ArrayList<>();
        for (Object o : list(m.get("exceptions"))) {
            Map<String, Object> r = (Map<String, Object>) o;
            rules.add(new HomeDefinition.Rule(req(r, "when", file), r.getOrDefault("severity", "medium").toString(), req(r, "text", file),
                    r.getOrDefault("open", "").toString()));
        }
        return new HomeDefinition.Measures(ref.get("cut-off") == null ? null : ref.get("cut-off").toString(), kpis, follow, strings(m.get("events")), rules, quick(m.get("mnemonics")));
    }

    private static List<Map<String, String>> quick(Object o) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Object x : list(o)) {
            if (x instanceof Map<?, ?> m && m.get("mnemonic") != null) {
                Map<String, String> r = new LinkedHashMap<>();
                m.forEach((k, v) -> r.put(String.valueOf(k), String.valueOf(v)));
                out.add(r);
            }
        }
        return out;
    }

    private static Map<String, Object> yaml(Path f) throws IOException {
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            Object o = new Yaml().load(r);
            if (!(o instanceof Map<?, ?> m)) {
                throw new IOException(f + ": a map of keys is expected");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
    }

    private static String text(Map<String, Object> m, String key, Path f) throws IOException {
        return req(m, key, f);
    }

    private static String req(Map<String, Object> m, String key, Path f) throws IOException {
        Object v = m.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IOException(f + ": " + key + " is missing");
        }
        return v.toString();
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        for (Object x : list(o)) {
            out.add(String.valueOf(x));
        }
        return out;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }
}
