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
package com.ash.drishti.server.bi.expr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The one catalogue of DEL functions (docs/architecture/BI_DESIGN.md section 3.3.8), loaded once from {@code bi/expr/functions.yaml} on the class path: what
 * the checker validates calls against (names, parameters, keywords, scopes, types, the wave that builds a function), what
 * {@code GET /api/v1/bi/expr/functions} serves to the expression editor and AI, and what the per-function reference of EXPRESSIONS.md is generated from.
 * Immutable and shared.
 */
public final class FunctionCatalog {

    public static final String RESOURCE = "bi/expr/functions.yaml";
    /** A function that works now; any other status names the wave that builds it. */
    public static final String BUILT = "built";

    /** A parameter: its name, type (see the catalogue's header), default text (null: required), keyword-only, variadic. */
    public record Param(String name, String type, String defaultText, boolean keywordOnly, boolean variadic) {
        public boolean optional() {
            return defaultText != null || variadic;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("type", type);
            if (defaultText != null) {
                m.put("default", defaultText);
            }
            if (keywordOnly) {
                m.put("keywordOnly", true);
            }
            if (variadic) {
                m.put("variadic", true);
            }
            return m;
        }
    }

    /** An example: the expression and what it says. */
    public record Example(String expr, String says) {}

    /** One function. {@code edgeCases} by topic: nulls, empty, masked, currencies, types. */
    public record Fn(String name, String family, String signature, List<Param> params, String returns, List<String> scopes, String status, String since, String legacy,
            String description, List<Example> examples, Map<String, String> edgeCases) {
        public Fn {
            params = List.copyOf(params);
            scopes = List.copyOf(scopes);
            examples = List.copyOf(examples);
            edgeCases = Collections.unmodifiableMap(new LinkedHashMap<>(edgeCases));
        }

        public boolean built() {
            return BUILT.equals(status);
        }

        public boolean aggregate() {
            return "aggregate".equals(family);
        }

        /** An aggregate-scope function that is not itself an aggregate but takes one (time intelligence, windows, balances, context). */
        public boolean takesAggregate() {
            return params.stream().anyMatch(p -> "aggregate".equals(p.type()));
        }

        public boolean allowedIn(Scope s) {
            return scopes.contains(s.word());
        }

        public Optional<Param> param(String name) {
            return params.stream().filter(p -> p.name().equals(name)).findFirst();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("family", family);
            m.put("signature", signature);
            m.put("params", params.stream().map(Param::toMap).toList());
            m.put("returns", returns);
            m.put("scopes", scopes);
            m.put("status", status);
            m.put("since", since);
            if (legacy != null) {
                m.put("legacy", legacy);
            }
            m.put("description", description);
            m.put("examples", examples.stream().map(e -> Map.of("expr", e.expr(), "says", e.says())).toList());
            m.put("edgeCases", edgeCases);
            return m;
        }
    }

    private static final FunctionCatalog DEFAULT = load();

    private final Map<String, Fn> byName;
    private final Map<String, String> byLegacy;

    private FunctionCatalog(Map<String, Fn> byName) {
        this.byName = Collections.unmodifiableMap(new LinkedHashMap<>(byName));
        Map<String, String> legacy = new LinkedHashMap<>();
        byName.values().forEach(f -> {
            if (f.legacy() != null && !f.legacy().startsWith("[")) {
                legacy.put(f.legacy(), f.name());
            }
        });
        this.byLegacy = Collections.unmodifiableMap(legacy);
    }

    /** The shipped catalogue. */
    public static FunctionCatalog get() {
        return DEFAULT;
    }

    public Optional<Fn> function(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<Fn> all() {
        return List.copyOf(byName.values());
    }

    public Set<String> names() {
        return byName.keySet();
    }

    /** The DEL name of a W1 function name ({@code startsWith} is {@code startswith}); the name itself when it did not change. */
    public String fromLegacy(String w1) {
        return byLegacy.getOrDefault(w1, w1);
    }

    /** The W1 name a DEL function lowers to ({@code len} lowers to the compiler's {@code length}). */
    public String toLegacy(String del) {
        Fn f = byName.get(del);
        return f == null || f.legacy() == null || f.legacy().startsWith("[") ? del : f.legacy();
    }

    /** The families in catalogue order. */
    public List<String> families() {
        List<String> out = new ArrayList<>();
        byName.values().forEach(f -> {
            if (!out.contains(f.family())) {
                out.add(f.family());
            }
        });
        return out;
    }

    /** The functions as {@code GET /api/v1/bi/expr/functions} serves them. */
    public List<Map<String, Object>> toMaps() {
        return byName.values().stream().map(Fn::toMap).toList();
    }

    // ---- loading -----------------------------------------------------------------------------------------------------

    static FunctionCatalog load() {
        try (InputStream in = FunctionCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the class path");
            }
            return parse(new ObjectMapper(new YAMLFactory()).readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + RESOURCE, e);
        }
    }

    static FunctionCatalog parse(JsonNode root) {
        Map<String, Fn> out = new LinkedHashMap<>();
        for (JsonNode f : root.path("functions")) {
            String name = f.path("name").asText();
            List<Param> params = new ArrayList<>();
            for (JsonNode p : f.path("params")) {
                JsonNode d = p.get("default");
                String def = d == null ? null : d.isNull() ? "None" : d.asText();
                params.add(new Param(p.path("name").asText(), p.path("type").asText("any"), def, p.path("keyword-only").asBoolean(false), p.path("variadic").asBoolean(false)));
            }
            List<String> scopes = new ArrayList<>();
            f.path("scopes").forEach(s -> scopes.add(s.asText()));
            List<Example> examples = new ArrayList<>();
            f.path("examples").forEach(e -> examples.add(new Example(e.path("expr").asText(), e.path("says").asText())));
            Map<String, String> edges = new LinkedHashMap<>();
            f.path("edge-cases").properties().forEach(e -> edges.put(e.getKey(), e.getValue().asText()));
            String legacy = f.hasNonNull("legacy") ? f.path("legacy").asText() : null;
            if (out.put(name, new Fn(name, f.path("family").asText(), f.path("signature").asText(), params, f.path("returns").asText("any"), scopes,
                    f.path("status").asText(BUILT), f.path("since").asText(""), legacy, f.path("description").asText(""), examples, edges)) != null) {
                throw new IllegalStateException(RESOURCE + ": the function " + name + " is listed twice");
            }
        }
        return new FunctionCatalog(out);
    }
}
