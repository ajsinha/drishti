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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One lake: where it is and every table in it, from {@code config/lakes/<name>.yaml} (docs/architecture/LAKE_CONFIG.md section 3).
 * Values that named an environment variable ({@code ${AWS_REGION:us-east-1}}) are resolved; a secret is never in the file.
 *
 * @param name    the lake's name (the file's name); connectors and loaders refer to it
 * @param format  delta or iceberg; a table may say otherwise
 * @param root    a local folder, {@code s3://bucket/prefix}, or {@code mem://<store>} for an in-memory store
 * @param storage how to reach the root (region, endpoint, path-style, ...), as the platform's role or {@code ${ENV}} references only
 * @param catalog Iceberg's catalog: {@code type} none (path tables), rest, glue or s3tables, with its settings; empty for Delta
 * @param memory  for a {@code mem://} lake: {@code max-mb} (the hard cap), {@code chunk-kb}, {@code contract}, {@code owner},
 *                {@code idle-trim-minutes}; empty otherwise
 * @param tables  the tables by {@code <domain>.<kind>}, the lake's {@code defaults:} applied
 * @param file    the file it was read from (null when built in code)
 */
public record Lake(String name, String format, String root, Map<String, String> storage, Map<String, String> catalog,
        Map<String, String> memory, Map<String, LakeTable> tables, Path file) {

    /** The scheme of an in-memory store's root. */
    public static final String MEMORY_SCHEME = "mem://";

    public Lake {
        storage = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(storage));
        catalog = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(catalog));
        memory = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(memory));
        tables = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(tables));
    }

    /** Whether the lake is an in-memory store ({@code mem://<store>}). */
    public boolean inMemory() {
        return root.startsWith(MEMORY_SCHEME);
    }

    /** The in-memory store's name ({@code feeds} of {@code mem://feeds}); empty for a lake on a disk or S3. */
    public String store() {
        return inMemory() ? root.substring(MEMORY_SCHEME.length()).replaceAll("/.*$", "") : "";
    }

    /** The table of {@code <domain>.<kind>}. */
    public Optional<LakeTable> table(String domain, String kind) {
        return Optional.ofNullable(tables.get(domain + "." + kind));
    }

    /**
     * The table of a kind in whatever domain: the one entry whose kind it is. Two domains with the same kind are refused here, naming
     * both, so a caller that knows the domain asks {@link #table(String, String)}.
     */
    public Optional<LakeTable> table(String kind) {
        List<LakeTable> hits = new ArrayList<>();
        for (LakeTable t : tables.values()) {
            if (t.kind().equals(kind)) {
                hits.add(t);
            }
        }
        if (hits.size() > 1) {
            throw new IllegalArgumentException("lake " + name + ": the kind " + kind + " is in more than one domain ("
                    + hits.stream().map(LakeTable::name).toList() + "): name the domain");
        }
        return hits.stream().findFirst();
    }

    /** The table of a kind, or a refusal naming the lake file: a writer never creates a table the lake file has no entry for. */
    public LakeTable require(String domain, String kind) {
        Optional<LakeTable> t = domain == null || domain.isBlank() ? table(kind) : table(domain, kind);
        return t.orElseThrow(() -> new IllegalArgumentException("lake " + name + " has no table " + (domain == null || domain.isBlank() ? kind
                : domain + "." + kind) + ": add its entry to " + (file == null ? "the lake file" : file) + " (tables: " + tables.keySet()
                + "); a writer never creates a table the lake file does not describe"));
    }

    /** The table's location: the root and its path ({@code s3://b/delta/trading/trade_endur}). */
    public String location(LakeTable t) {
        return (root.endsWith("/") ? root.substring(0, root.length() - 1) : root) + "/" + t.path();
    }

    /** The lake as plain maps and lists, for {@code drishti.py} (the JSON dump) and Admin -> Lakes. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lake", name);
        m.put("format", format);
        m.put("root", root);
        m.put("in-memory", inMemory());
        m.put("storage", storage);
        m.put("catalog", catalog);
        m.put("memory", memory);
        m.put("file", file == null ? null : file.toString());
        List<Map<String, Object>> ts = new ArrayList<>();
        tables.values().forEach(t -> {
            Map<String, Object> d = t.describe();
            d.put("location", location(t));
            ts.add(d);
        });
        m.put("tables", ts);
        return m;
    }
}
