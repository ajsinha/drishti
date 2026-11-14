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
package com.ash.drishti.lakeconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The connector templates a source module brings to the pack it requires (docs/guides/DATA_GUIDE.md, "Connector layers"): the template
 * layer of {@code trade-endur} and its kin, read-only and versioned with the module. In {@code modules/source-<system>/connectors/},
 * {@code delta.yaml} is the system's read template, connector {@code <kind>-<system>}; any other file is the connector of its own name
 * ({@code pack new --source-for} writes {@code connectors/<module>.yaml}); the store variants ({@code iceberg.yaml}, {@code files.yaml},
 * {@code kafka.yaml}, {@code cassandra.yaml}, {@code s3tables.yaml}, {@code delta-s3.yaml}) are examples to copy, not templates. The same
 * rule is {@code tools/packlake.py with_modules}; the shared fixture {@code tools/connector_layers_fixture.json} holds both to it.
 */
public final class ModuleConnectors {

    /** The template key that names the module a template came from (never a connector setting). */
    public static final String FROM_MODULE = "from-module";
    /** The store variants: examples, never templates. */
    public static final Set<String> VARIANTS = Set.of("iceberg", "files", "file", "kafka", "cassandra", "s3tables", "delta-s3");

    private ModuleConnectors() {}

    /** The templates the source modules under {@code modules} bring to {@code pack}, by connector name, each marked with its module. */
    public static Map<String, Map<String, Object>> of(Path modules, String pack) throws IOException {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (modules == null || pack == null || pack.isEmpty()) {
            return out;
        }
        YAMLMapper yaml = new YAMLMapper();
        for (Path dir : ModuleDirs.of(modules)) {            // the shipped folders and the modules installed as a .dmod
            String id = ModuleDirs.idOf(dir);
            Path folder = dir.resolve("connectors");
            if (!id.startsWith("source-") || !Files.isDirectory(folder) || !Files.isRegularFile(dir.resolve("module.yaml"))
                    || !yaml.readTree(Files.readString(dir.resolve("module.yaml"))).path("requires").has(pack)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> s = Files.list(folder)) {
                files = s.filter(f -> f.getFileName().toString().endsWith(".yaml")).sorted().toList();
            }
            for (Path tpl : files) {
                String stem = tpl.getFileName().toString().replaceFirst("\\.yaml$", "");
                Map<String, Object> c = yaml.readValue(Files.readString(tpl), new TypeReference<Map<String, Object>>() {});
                Object kinds = c == null ? null : c.get("kinds");
                String kind = kinds instanceof List<?> l && !l.isEmpty() ? String.valueOf(l.get(0)) : "";
                String name = stem.equals("delta") ? kind + "-" + id.substring("source-".length()) : stem;
                if (!kind.isEmpty() && !VARIANTS.contains(stem) && !out.containsKey(name)) {
                    Map<String, Object> t = new LinkedHashMap<>(c);
                    t.put(FROM_MODULE, id);
                    out.put(name, t);
                }
            }
        }
        return out;
    }

    /** The modules folder of a pack's manifest: {@code drishti.modules.dir}, else the {@code modules} folder of the checkout above it. */
    public static Path modulesDir(Path packFile) {
        String set = System.getProperty("drishti.modules.dir");
        if (set != null && Files.isDirectory(Path.of(set))) {
            return Path.of(set);
        }
        for (Path p = packFile.toAbsolutePath().getParent(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("modules")) && Files.isDirectory(p.resolve("config"))) {
                return p.resolve("modules");
            }
        }
        return null;
    }
}
