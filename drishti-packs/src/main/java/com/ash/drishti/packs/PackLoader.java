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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code <dir>/<name>/pack.yaml} for each enabled pack, in the order given, and turns their content into
 * Drishti properties. A pack contributes: mnemonics, reference patterns and fields, link badges, roles, Sutra
 * directories, format and semantic-hint files, and sample directories for the demo source. Conflicts (two
 * packs claiming the same mnemonic or field) are errors, reported with both pack names.
 */
public final class PackLoader {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public List<Pack> load(Path dir, List<String> enabled) {
        List<Pack> out = new ArrayList<>();
        for (String name : enabled) {
            String n = name.trim();
            if (n.isEmpty()) {
                continue;
            }
            Path packDir = dir.resolve(n).normalize();
            Path manifest = packDir.resolve("pack.yaml");
            if (!packDir.startsWith(dir.normalize()) || !Files.isRegularFile(manifest)) {
                throw new IllegalStateException("pack '" + n + "' not found at " + manifest);
            }
            try {
                Map<String, Object> m = yaml.readValue(manifest.toFile(), new TypeReference<Map<String, Object>>() {});
                if (!n.equals(m.get("pack"))) {
                    throw new IllegalStateException(manifest + " declares pack '" + m.get("pack") + "', expected '" + n + "'");
                }
                out.add(new Pack(n, String.valueOf(m.getOrDefault("version", "0")), String.valueOf(m.getOrDefault("title", n)),
                        String.valueOf(m.getOrDefault("description", "")), packDir.toAbsolutePath(), m));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + manifest, e);
            }
        }
        return out;
    }

    /** Flattened Spring properties for the given packs, lowest precedence. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> properties(List<Pack> packs) {
        Map<String, Object> p = new LinkedHashMap<>();
        Map<String, String> owner = new LinkedHashMap<>();
        int patterns = 0;
        int follows = 0;
        List<String> sutraDirs = new ArrayList<>();
        List<String> formats = new ArrayList<>();
        List<String> semantics = new ArrayList<>();
        List<String> samples = new ArrayList<>();
        for (Pack pack : packs) {
            Map<String, Object> m = pack.manifest();
            List<Object> owned = (List<Object>) m.getOrDefault("kinds", List.of());
            for (int i = 0; i < owned.size(); i++) {
                claim(owner, "kind " + owned.get(i), pack.name());
                p.put("drishti.packs.kinds." + pack.name() + "[" + i + "]", owned.get(i));
            }
            for (Map.Entry<String, Object> e : map(m.get("mnemonics")).entrySet()) {
                claim(owner, "mnemonic " + e.getKey(), pack.name());
                Map<String, Object> def = map(e.getValue());
                p.put("drishti.commands.mnemonics." + e.getKey() + ".kind", def.get("kind"));
                p.put("drishti.commands.mnemonics." + e.getKey() + ".label", def.getOrDefault("label", e.getKey()));
            }
            Map<String, Object> graph = map(m.get("graph"));
            for (Object o : (List<Object>) graph.getOrDefault("id-patterns", List.of())) {
                Map<String, Object> ip = map(o);
                p.put("drishti.graph.id-patterns[" + patterns + "].pattern", ip.get("pattern"));
                p.put("drishti.graph.id-patterns[" + patterns + "].kind", ip.get("kind"));
                patterns++;
            }
            for (Map.Entry<String, Object> e : map(graph.get("fields")).entrySet()) {
                claim(owner, "field " + e.getKey(), pack.name());
                Map<String, Object> f = map(e.getValue());
                p.put("drishti.graph.fields." + e.getKey() + ".kind", f.get("kind"));
                if (f.get("label") != null) {
                    p.put("drishti.graph.fields." + e.getKey() + ".label", f.get("label"));
                }
            }
            Map<String, Object> impact = map(graph.get("impact"));
            for (Object f : (List<Object>) impact.getOrDefault("follow", List.of())) {
                p.put("drishti.graph.impact.follow[" + follows++ + "]", f);
            }
            map(impact.get("measures")).forEach((kind, expr) -> p.put("drishti.graph.impact.measures." + kind, expr));
            map(impact.get("formats")).forEach((kind, fmt) -> p.put("drishti.graph.impact.formats." + kind, fmt));
            map(graph.get("badges")).forEach((kind, expr) -> {
                claim(owner, "badge " + kind, pack.name());
                p.put("drishti.graph.badges." + kind, expr);
            });
            for (Map.Entry<String, Object> e : map(m.get("roles")).entrySet()) {
                claim(owner, "role " + e.getKey(), pack.name());
                Map<String, Object> r = map(e.getValue());
                List<Object> kinds = (List<Object>) r.getOrDefault("kinds", List.of());
                for (int i = 0; i < kinds.size(); i++) {
                    p.put("drishti.security.roles." + e.getKey() + ".kinds[" + i + "]", kinds.get(i));
                }
                for (String flag : new String[] {"raw", "author", "admin"}) {
                    if (r.get(flag) != null) {
                        p.put("drishti.security.roles." + e.getKey() + "." + flag, r.get(flag));
                    }
                }
            }
            addIfExists(sutraDirs, pack, m.getOrDefault("sutras", "sutras"));
            addIfExists(formats, pack, m.getOrDefault("formats", "config/formats.yaml"));
            addIfExists(semantics, pack, m.getOrDefault("semantics", "config/semantics.yaml"));
            addIfExists(samples, pack, m.getOrDefault("samples", "samples"));
        }
        indexed(p, "drishti.rachana.pack-dirs", sutraDirs);
        indexed(p, "drishti.rachana.pack-formats-files", formats);
        indexed(p, "drishti.inference.pack-semantics-files", semantics);
        if (!samples.isEmpty()) {
            p.put("drishti.sources.plugins.demo.settings.dirs", String.join(",", samples));
        }
        p.put("drishti.packs.loaded", String.join(",", packs.stream().map(Pack::name).toList()));
        return p;
    }

    private static void claim(Map<String, String> owner, String what, String pack) {
        String prev = owner.putIfAbsent(what, pack);
        if (prev != null && !prev.equals(pack)) {
            throw new IllegalStateException(what + " is defined by both pack '" + prev + "' and pack '" + pack + "'");
        }
    }

    private static void addIfExists(List<String> into, Pack pack, Object rel) {
        Path path = pack.resolve(String.valueOf(rel));
        if (Files.exists(path)) {
            into.add(path.toString());
        }
    }

    private static void indexed(Map<String, Object> p, String key, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            p.put(key + "[" + i + "]", values.get(i));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
