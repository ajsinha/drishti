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
package com.ash.drishti.module.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A module's manifest, {@code module.yaml} (KERNEL_AND_MODULES.md section 3.3), with every field of the design. The fields the runtime acts
 * on are typed; the declarative sections other kernel parts read (compute, python, packs, lenses, feeds, BI, docs, settings, events,
 * parts, health) are kept as read, under {@link #section(String)}. Reading checks V1: the format, the required fields, readable versions
 * and ranges, and no field the design does not know ({@code taint} is the kernel's, never the publisher's).
 *
 * @param format      the manifest format, 1
 * @param id          lower-case id: the folder, the class-loader name, the events prefix
 * @param version     the module's version
 * @param title       words for Admin, About and Help
 * @param about       one line of what it brings
 * @param domain      the domain it serves
 * @param publisher   {@code name}, {@code id}, {@code contact}
 * @param licence     {@code feature} and {@code limits}
 * @param ranges      the API ranges it was built for: {@code kernel}, {@code rachana}, {@code contracts}, {@code plugin-api}, {@code elements}
 * @param requires    the modules it needs (or uses when present: {@code optional})
 * @param conflicts   modules it cannot run beside
 * @param kinds       {@code provides.kinds}: the kinds it owns, by pack
 * @param java        its Java part, or null for a module without code
 * @param api         its REST namespace and aliases
 * @param console     its console part ({@code plugin}, {@code handlers}, {@code elements})
 * @param memoryMb    {@code resources.memory-mb}, 0 when not declared
 * @param sections    every other field, as read
 */
public record DmodManifest(int format, String id, String version, String title, String about, String domain, Map<String, String> publisher,
        Licence licence, Map<String, String> ranges, List<Requirement> requires, List<String> conflicts, Map<String, List<String>> kinds,
        JavaPart java, ApiPart api, Map<String, Object> console, long memoryMb, Map<String, Object> sections) {

    /** The manifest's name in a package. */
    public static final String FILE = "module.yaml";

    /** The API ranges every manifest declares (V1 requires them; V3 checks those the host knows). */
    public static final List<String> RANGES = List.of("kernel", "rachana", "contracts", "plugin-api", "elements");

    /** Every top-level field the design knows. */
    public static final Set<String> FIELDS = Set.of("format", "id", "version", "title", "about", "domain", "publisher", "licence", "kernel",
            "rachana", "contracts", "plugin-api", "elements", "requires", "conflicts", "provides", "java", "compute", "python", "packs", "data",
            "api", "console", "functions", "lenses", "home", "feeds", "bi", "docs", "settings", "events", "parts", "resources", "health",
            "taint", "origin", "kernel-internals", "kernel-build");

    /** The licence part: the feature that unlocks the module and the limits it understands. */
    public record Licence(String feature, List<String> limits) {
    }

    /** One dependency: a module id, the range it needs, and whether it is optional. */
    public record Requirement(String id, VersionRange range, boolean optional) {
    }

    /**
     * The Java part.
     *
     * @param jars    the module's own jar files under {@code java/} (one or more: a service jar, a compute jar ...)
     * @param lib     third-party jars under the package's {@code lib/}, on the module's class path after its own
     * @param entry   the module's entry class (made a bean of its context), or null
     * @param spring  its configuration classes, registered in its child context
     * @param exports packages other modules may see (none by default)
     * @param nativeCode true when a jar bundles native code
     */
    public record JavaPart(List<String> jars, List<String> lib, String entry, List<String> spring, List<String> exports, boolean nativeCode) {

        public JavaPart {
            jars = List.copyOf(jars);
            lib = lib == null ? List.of() : List.copyOf(lib);
        }

        /** Every jar of the module's class path, relative to the package: {@code java/<jar>} for its own, then {@code lib/<jar>}. */
        public List<String> classPath() {
            List<String> out = new ArrayList<>();
            jars.forEach(j -> out.add("java/" + j));
            lib.forEach(l -> out.add("lib/" + l));
            return out;
        }
    }

    /** The REST part: the namespace ({@code /api/v1/<prefix>/}), old paths kept for one release, and token scopes. */
    public record ApiPart(String prefix, List<String> aliases, Object tokens) {
    }

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** One field outside the typed ones, as read (null when absent). */
    public Object section(String name) {
        return sections.get(name);
    }

    /** True when the manifest says {@code origin: first-party} (a claim: {@link FirstParty} verifies it). */
    public boolean firstPartyClaim() {
        return "first-party".equals(str(sections.get("origin")));
    }

    /** The kernel packages the module declares it still reaches ({@code kernel-internals:}), empty for none. */
    public List<String> kernelInternals() {
        return strings(sections.get("kernel-internals"));
    }

    /** The one kernel build a module with an allowance was built for ({@code kernel-build:}), or null. */
    public String kernelBuild() {
        return str(sections.get("kernel-build"));
    }

    /** {@code <id> <version>}, the way refusals name a module. */
    public String label() {
        return id + " " + version;
    }

    /** Reads a manifest; refuses (V1) with the field that is wrong. */
    public static DmodManifest read(InputStream in, String where) {
        Map<String, Object> m;
        try {
            Object raw = YAML.readValue(in, Object.class);
            if (!(raw instanceof Map<?, ?>)) {
                throw new ModuleRefusedException("V1", where + ": module.yaml is not a map of fields");
            }
            m = cast(raw);
        } catch (IOException e) {
            throw new ModuleRefusedException("V1", where + ": module.yaml does not read: " + firstLine(e.getMessage()), e);
        }
        String label = where + ": module.yaml";
        for (String k : m.keySet()) {
            if (!FIELDS.contains(k)) {
                throw new ModuleRefusedException("V1", label + ": unknown field `" + k + "`");
            }
        }
        if (m.containsKey("taint")) {
            throw new ModuleRefusedException("V1", label + ": `taint` is set by the kernel, never by the publisher");
        }
        if (!"1".equals(str(m.get("format")))) {
            throw new ModuleRefusedException("V1", label + ": `format` must be 1 (found " + m.get("format") + ")");
        }
        String id = req(m, "id", label);
        if (!id.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new ModuleRefusedException("V1", label + ": `id` must be lower-case letters, digits and dashes (found " + id + ")");
        }
        label = id + ": module.yaml";
        String version = req(m, "version", label);
        if (!VersionRange.isVersion(version)) {
            throw new ModuleRefusedException("V1", label + ": `version` is not a version (" + version + ")");
        }
        label = id + " " + version + ": module.yaml";
        for (String f : List.of("title", "about", "domain")) {
            req(m, f, label);
        }
        Map<String, Object> pub = map(m.get("publisher"));
        if (pub.isEmpty() || str(pub.get("name")) == null || str(pub.get("id")) == null) {
            throw new ModuleRefusedException("V1", label + ": `publisher` needs a name and an id");
        }
        Map<String, Object> lic = map(m.get("licence"));
        if (str(lic.get("feature")) == null) {
            throw new ModuleRefusedException("V1", label + ": `licence.feature` missing");
        }
        Map<String, String> ranges = new LinkedHashMap<>();
        for (String r : RANGES) {
            String v = req(m, r, label);
            try {
                VersionRange.parse(v);
            } catch (IllegalArgumentException e) {
                throw new ModuleRefusedException("V1", label + ": `" + r + "` is not a version range (" + v + ")");
            }
            ranges.put(r, v);
        }
        List<Requirement> requires = requirements(m.get("requires"), label);
        Map<String, List<String>> kinds = new LinkedHashMap<>();                       // in the file's order: the claims list them so (Admin -> Modules)
        map(map(m.get("provides")).get("kinds")).forEach((pack, l) -> kinds.put(pack, strings(l)));
        JavaPart java = null;
        if (m.get("java") != null) {
            Map<String, Object> j = map(m.get("java"));
            java = new JavaPart(strings(j.get("jars")), strings(j.get("lib")), str(j.get("entry")), strings(j.get("spring")), strings(j.get("exports")),
                    Boolean.TRUE.equals(j.get("native")) || "true".equals(str(j.get("native"))));
        }
        ApiPart api = null;
        if (m.get("api") != null) {
            Map<String, Object> a = map(m.get("api"));
            String prefix = str(a.get("prefix"));
            if (prefix == null || !prefix.matches("[a-z][a-z0-9-]*(/[a-z0-9-]+)*")) {
                throw new ModuleRefusedException("V1", label + ": `api.prefix` must be a plain path such as `" + id + "` (found " + prefix + ")");
            }
            api = new ApiPart(prefix, strings(a.get("aliases")), a.get("tokens"));
        }
        long memory = 0;
        Object mem = map(m.get("resources")).get("memory-mb");
        if (mem != null) {
            try {
                memory = Long.parseLong(str(mem));
            } catch (NumberFormatException e) {
                throw new ModuleRefusedException("V1", label + ": `resources.memory-mb` is not a number (" + mem + ")");
            }
        }
        String origin = str(m.get("origin"));
        if (origin != null && !origin.equals("first-party") && !origin.equals("third-party")) {
            throw new ModuleRefusedException("V1", label + ": `origin` is first-party or third-party (found " + origin + ")");
        }
        Map<String, Object> rest = new LinkedHashMap<>();
        for (String k : List.of("compute", "python", "packs", "data", "functions", "lenses", "home", "feeds", "bi", "docs", "settings",
                "events", "parts", "resources", "health", "origin", "kernel-internals", "kernel-build")) {
            if (m.containsKey(k)) {
                rest.put(k, m.get(k));
            }
        }
        Map<String, String> publisher = new LinkedHashMap<>();
        pub.forEach((k, v) -> { if (str(v) != null) { publisher.put(k, str(v)); } });
        return new DmodManifest(1, id, version, str(m.get("title")), str(m.get("about")), str(m.get("domain")), Map.copyOf(publisher),
                new Licence(str(lic.get("feature")), strings(lic.get("limits"))), Map.copyOf(ranges), requires, strings(m.get("conflicts")),
                Collections.unmodifiableMap(kinds), java, api, map(m.get("console")), memory, Collections.unmodifiableMap(rest));
    }

    private static List<Requirement> requirements(Object o, String label) {
        List<Requirement> out = new ArrayList<>();
        Map<String, Object> r = map(o);
        Map<String, Object> optional = map(r.get("optional"));
        for (Map.Entry<String, Object> e : r.entrySet()) {
            if (!"optional".equals(e.getKey())) {
                out.add(requirement(e.getKey(), e.getValue(), false, label));
            }
        }
        optional.forEach((k, v) -> out.add(requirement(k, v, true, label)));
        return List.copyOf(out);
    }

    private static Requirement requirement(String id, Object range, boolean optional, String label) {
        try {
            return new Requirement(id, VersionRange.parse(str(range)), optional);
        } catch (IllegalArgumentException e) {
            throw new ModuleRefusedException("V1", label + ": `requires." + id + "` is not a version range (" + range + ")");
        }
    }

    private static String req(Map<String, Object> m, String field, String label) {
        String v = str(m.get(field));
        if (v == null) {
            throw new ModuleRefusedException("V1", label + ": `" + field + "` missing");
        }
        return v;
    }

    static String str(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o).strip();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }

    static Map<String, Object> map(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            l.stream().map(DmodManifest::str).filter(s -> s != null).forEach(out::add);
        } else if (str(o) != null) {
            out.add(str(o));
        }
        return List.copyOf(out);
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("");
    }
}
