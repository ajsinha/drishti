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

import com.ash.drishti.rachana.el.check.Signatures;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The catalogue of DEL functions (docs/architecture/BI_DESIGN.md section 3.3.8), loaded once from {@code bi/expr/functions.yaml} on the class path
 * by Rachana's signature reader ({@link Signatures}, where the entries' shape moved with checked mode): the DEL names DEL's own checks accept, the
 * old (W1) names and their conversion, what {@code GET /api/v1/bi/expr/functions} serves to the expression editor and AI, and what the
 * per-function reference of BI_LANGUAGE_REFERENCE.md is generated from. Types and scopes are checked by Rachana's checked mode over
 * Rachana's own signatures ({@code rachana/el/signatures.yaml}, the same functions under their Rachana names). Deleted with DEL in
 * BI_FOUNDATION_2911E.md part B. Immutable and shared.
 */
public final class FunctionCatalog {

    public static final String RESOURCE = "bi/expr/functions.yaml";
    /** A function that works now; any other status names the wave that builds it. */
    public static final String BUILT = Signatures.BUILT;

    private static final FunctionCatalog DEFAULT = new FunctionCatalog(Signatures.load(RESOURCE));

    private final Signatures signatures;
    private final Map<String, String> byLegacy;

    private FunctionCatalog(Signatures signatures) {
        this.signatures = signatures;
        Map<String, String> legacy = new LinkedHashMap<>();
        signatures.all().forEach(f -> {
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

    public Optional<Signatures.Signature> function(String name) {
        return signatures.function(name);
    }

    public List<Signatures.Signature> all() {
        return signatures.all();
    }

    public Set<String> names() {
        return signatures.names();
    }

    /** The DEL name of a W1 function name ({@code startsWith} is {@code startswith}); the name itself when it did not change. */
    public String fromLegacy(String w1) {
        return byLegacy.getOrDefault(w1, w1);
    }

    /** The W1 name a DEL function lowers to ({@code len} lowers to the compiler's {@code length}). */
    public String toLegacy(String del) {
        Signatures.Signature f = signatures.function(del).orElse(null);
        return f == null || f.legacy() == null || f.legacy().startsWith("[") ? del : f.legacy();
    }

    /** The families in catalogue order. */
    public List<String> families() {
        return signatures.families();
    }

    /** The functions as {@code GET /api/v1/bi/expr/functions} serves them. */
    public List<Map<String, Object>> toMaps() {
        return signatures.toMaps();
    }

    static FunctionCatalog parse(JsonNode root) {
        return new FunctionCatalog(Signatures.parse(root, RESOURCE));
    }
}
