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
package com.ash.drishti.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One module's manifest (ACCESS_MODEL.md section 3): what the module is and what it contributes. A module with code ships its manifest in
 * its own jar ({@code META-INF/drishti/module.yaml}), so it is installed exactly when its jar is on the class path; a content-only module
 * (packs, lenses, reports and no code) may be declared by a site file alone ({@code config/modules/<id>.yaml} with {@code code: false}).
 *
 * @param id         the module's id ({@code banking}); {@value #CORE} is the core, always on
 * @param domain     the domain it serves ({@code banking}, {@code economy}); a pack names its domain with the same word
 * @param title      the name people read ({@code Banking and risk})
 * @param about      one line of what it brings
 * @param version    its version (the product's for a shipped module)
 * @param requires   the modules it needs on (a module whose requirement is off is off)
 * @param feature    the licence feature that unlocks it (its id when not given)
 * @param code       true when it brings code (a jar); false for content only
 * @param shipped    whether it is enabled as shipped (an administrator's switch overrides it)
 * @param installed  true when it is present here: its jar's manifest was found, or it is a content module a site file declares
 * @param packages   the Java package roots of its code (as the module's manifest writes them): a class there belongs to it
 * @param engines    the compute engines it brings (ids of the engine catalogue)
 * @param packs      the packs it owns (ids)
 * @param connectors the connectors and connector plugins it owns (names)
 * @param api        the server API prefixes it answers (as the module's manifest writes them)
 * @param functions  the functions it brings (command words: {@code VAR}, {@code EXP}, {@code TRD}), each with the APIs behind it
 * @param source     where the manifest was read, in words
 */
public record ModuleManifest(String id, String domain, String title, String about, String version, List<String> requires, String feature,
        boolean code, boolean shipped, boolean installed, List<String> packages, List<String> engines, List<String> packs,
        List<String> connectors, List<String> api, List<FunctionSpec> functions, String source) {

    /** The core's id: always installed, licensed and on; it cannot be switched off. */
    public static final String CORE = "core";

    /** The core as a manifest. */
    public static final ModuleManifest CORE_MODULE = new ModuleManifest(CORE, CORE, "Core", "The generic platform: entities, packs, Rachana, Pivot, BI, "
            + "lakes, search, security, the cluster", "", List.of(), CORE, true, true, true, List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), "built in");

    public ModuleManifest {
        id = norm(id);
        domain = domain == null || domain.isBlank() ? id : norm(domain);
        title = title == null || title.isBlank() ? id : title.strip();
        about = about == null ? "" : about.strip();
        version = version == null ? "" : version.strip();
        requires = clean(requires).stream().map(ModuleManifest::norm).toList();
        feature = feature == null || feature.isBlank() ? id : feature.strip();
        packages = clean(packages);
        engines = clean(engines);
        packs = clean(packs);
        connectors = clean(connectors);
        api = clean(api);
        functions = functions == null ? List.of() : functions.stream().filter(f -> f != null && !f.word().isEmpty()).toList();
        source = source == null ? "" : source;
    }

    /** The manifest a YAML map describes ({@code module: {id, domain, ...}} or the keys at the top). */
    @SuppressWarnings("unchecked")
    static ModuleManifest of(Map<String, Object> raw, boolean installed, String source) {
        Map<String, Object> m = raw.get("module") instanceof Map<?, ?> inner ? (Map<String, Object>) inner : raw;
        Object id = m.get("id");
        if (id == null || String.valueOf(id).isBlank()) {
            throw new IllegalArgumentException(source + ": a module manifest needs an id");
        }
        Map<String, Object> c = m.get("contributes") instanceof Map<?, ?> x ? (Map<String, Object>) x : Map.of();
        return new ModuleManifest(String.valueOf(id), str(m.get("domain")), str(m.get("title")), str(m.get("about")), str(m.get("version")),
                list(m.get("requires")), str(m.get("feature")), !Boolean.FALSE.equals(bool(m.get("code"))), !Boolean.FALSE.equals(bool(m.get("enabled"))),
                installed, list(c.get("packages")), list(c.get("engines")), list(c.get("packs")), list(c.get("connectors")), list(c.get("api")),
                functions(c.get("functions")), source);
    }

    /**
     * A {@code .dmod} manifest ({@code module.yaml} of a package, KERNEL_AND_MODULES.md section 3.3) as the registry reads it: the module's
     * packages ({@code java.packages}), engines ({@code compute.engines}), connectors ({@code compute.connectors}), packs
     * ({@code packs.list}), its API namespace with the old prefixes it still answers ({@code api.legacy}) and its functions.
     */
    @SuppressWarnings("unchecked")
    static ModuleManifest ofDmod(Map<String, Object> m, boolean installed, String source) {
        Object id = m.get("id");
        if (id == null || String.valueOf(id).isBlank()) {
            throw new IllegalArgumentException(source + ": a module manifest needs an id");
        }
        Map<String, Object> java = m.get("java") instanceof Map<?, ?> j ? (Map<String, Object>) j : Map.of();
        Map<String, Object> compute = m.get("compute") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of();
        Map<String, Object> packs = m.get("packs") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
        Map<String, Object> api = m.get("api") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
        Map<String, Object> licence = m.get("licence") instanceof Map<?, ?> l ? (Map<String, Object>) l : Map.of();
        List<String> requires = new ArrayList<>();
        if (m.get("requires") instanceof Map<?, ?> req) {
            req.keySet().stream().map(String::valueOf).filter(k -> !"optional".equals(k)).forEach(requires::add);
        } else {
            requires.addAll(list(m.get("requires")));
        }
        List<String> engines = new ArrayList<>();
        if (compute.get("engines") instanceof List<?> es) {
            for (Object e : es) {
                engines.add(e instanceof Map<?, ?> em ? String.valueOf(em.get("id")) : String.valueOf(e));
            }
        }
        List<String> paths = new ArrayList<>();
        if (api.get("prefix") != null) {
            paths.add("/api/v1/" + api.get("prefix"));
        }
        paths.addAll(list(api.get("legacy")));
        return new ModuleManifest(String.valueOf(id), str(m.get("domain")), str(m.get("title")), str(m.get("about")), str(m.get("version")),
                requires, str(licence.get("feature")), m.get("java") != null, true, installed, list(java.get("packages")), engines,
                list(packs.get("list")), list(compute.get("connectors")), paths, functions(m.get("functions")), source);
    }

    /** True when the class (its binary name) belongs to this module's code. */
    public boolean ownsClass(String className) {
        if (className == null || CORE.equals(id)) {
            return false;
        }
        for (String p : packages) {
            if (className.equals(p) || className.startsWith(p + ".")) {
                return true;
            }
        }
        return false;
    }

    /** The words of its functions. */
    public List<String> words() {
        return functions.stream().map(FunctionSpec::word).toList();
    }

    /** True when the API path is one of this module's (a prefix, at a segment boundary). */
    public boolean ownsApi(String path) {
        return under(path, api);
    }

    /** True when the path is under one of the prefixes (at a segment boundary, or a {@code .} suffix such as {@code .xlsx}). */
    static boolean under(String path, List<String> prefixes) {
        if (path == null) {
            return false;
        }
        for (String p : prefixes) {
            String q = p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
            if (path.equals(q) || path.startsWith(q + "/") || path.startsWith(q + ".")) {
                return true;
            }
        }
        return false;
    }

    private static List<FunctionSpec> functions(Object o) {
        List<FunctionSpec> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                if (x != null) {
                    out.add(FunctionSpec.of(x));
                }
            }
        }
        return List.copyOf(out);
    }

    static String norm(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static Boolean bool(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(o).strip().toLowerCase(Locale.ROOT);
        return "true".equals(s) || "on".equals(s) || "yes".equals(s);
    }

    static List<String> list(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                if (x != null && !String.valueOf(x).isBlank()) {
                    out.add(String.valueOf(x).strip());
                }
            }
        } else if (o != null && !String.valueOf(o).isBlank()) {
            for (String x : String.valueOf(o).split(",")) {
                if (!x.isBlank()) {
                    out.add(x.strip());
                }
            }
        }
        return List.copyOf(out);
    }

    private static List<String> clean(List<String> l) {
        return l == null ? List.of() : l.stream().filter(s -> s != null && !s.isBlank()).map(String::strip).toList();
    }
}
