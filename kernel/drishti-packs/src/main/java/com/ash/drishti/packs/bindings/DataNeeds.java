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
package com.ash.drishti.packs.bindings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the modules say they need (docs/architecture/KERNEL_AND_MODULES.md section 5.3): each built-in module's
 * {@code META-INF/drishti/needs.yaml}, read from the class path, and each loaded {@code .dmod}'s needs file (its manifest's
 * {@code data: {needs: …}}, read by {@link #of(String, Map)} when it loads). A need names a kind and never a connector; the kernel binds it.
 *
 * @param needs every need of every module, in file order
 * @param computed the kinds the modules compute (their {@code KindProvider}s), by kind to its module
 * @param problems files that could not be read, with the reason
 */
public record DataNeeds(List<Need> needs, Map<String, String> computed, List<String> problems) {

    /** The resource each module ships. */
    public static final String RESOURCE = "META-INF/drishti/needs.yaml";

    public static final DataNeeds NONE = new DataNeeds(List.of(), Map.of(), List.of());

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /**
     * One need.
     *
     * @param module the module that needs it
     * @param kind the kind
     * @param contract the contract range ({@code >=1.0 <2})
     * @param use read, live, write
     * @param required true when the module's functions in {@code usedBy} cannot work without it
     * @param requiredFor when set, only these functions need it
     * @param requiredWith when set, required only while this other kind is bound
     * @param anyOf the group satisfied by one bound member, or null
     * @param demo true when the demo lake's samples may serve it
     * @param usedBy the functions (mnemonics) that depend on it
     */
    public record Need(String module, String kind, String contract, Set<String> use, boolean required, List<String> requiredFor,
            String requiredWith, String anyOf, boolean demo, List<String> usedBy) {

        /** The functions an unserved need switches off: {@code requiredFor}, else {@code usedBy} when required; mnemonics only. */
        public List<String> gates() {
            List<String> base = !requiredFor.isEmpty() ? requiredFor : (required || anyOf != null || requiredWith != null ? usedBy : List.of());
            return base.stream().filter(f -> f.matches("[A-Z][A-Z0-9]{1,11}")).toList();
        }

        /** True when it is a produced kind (only written). */
        public boolean writeOnly() {
            return use.equals(Set.of("write"));
        }
    }

    /** Every module's needs on {@code loader}'s class path. */
    public static DataNeeds load(ClassLoader loader) {
        List<Need> needs = new ArrayList<>();
        Map<String, String> computed = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        try {
            Enumeration<URL> urls = loader.getResources(RESOURCE);
            while (urls.hasMoreElements()) {
                URL u = urls.nextElement();
                try (InputStream in = u.openStream()) {
                    read(YAML.readValue(in, new TypeReference<LinkedHashMap<String, Object>>() {}), needs, computed);
                } catch (IOException | RuntimeException e) {
                    problems.add(u + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            problems.add(RESOURCE + ": " + e.getMessage());
        }
        return new DataNeeds(List.copyOf(needs), Map.copyOf(computed), List.copyOf(problems));
    }

    /** One loaded module's needs file ({@code needs:}, {@code computed:}); the module is {@code module} whatever the file says. */
    public static DataNeeds of(String module, Map<String, Object> file) {
        List<Need> needs = new ArrayList<>();
        Map<String, String> computed = new LinkedHashMap<>();
        Map<String, Object> m = new LinkedHashMap<>(file == null ? Map.of() : file);
        m.put("module", module);
        read(m, needs, computed);
        return new DataNeeds(List.copyOf(needs), Map.copyOf(computed), List.of());
    }

    /** These needs and {@code more}'s, in that order. */
    public DataNeeds plus(DataNeeds more) {
        if (more.needs.isEmpty() && more.computed.isEmpty() && more.problems.isEmpty()) {
            return this;
        }
        List<Need> n = new ArrayList<>(needs);
        n.addAll(more.needs);
        Map<String, String> c = new LinkedHashMap<>(computed);
        more.computed.forEach(c::putIfAbsent);
        List<String> p = new ArrayList<>(problems);
        p.addAll(more.problems);
        return new DataNeeds(List.copyOf(n), Map.copyOf(c), List.copyOf(p));
    }

    /** One file's values. */
    @SuppressWarnings("unchecked")
    static void read(Map<String, Object> m, List<Need> needs, Map<String, String> computed) {
        String module = String.valueOf(m.get("module"));
        if (m.get("needs") instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> n = (Map<String, Object>) o;
                Object req = n.get("required");
                List<String> forList = List.of();
                String with = null;
                boolean required = false;
                if (req instanceof Map<?, ?> rm) {
                    forList = strings(rm.get("for"));
                    with = rm.get("with") == null ? null : String.valueOf(rm.get("with"));
                } else {
                    required = Boolean.parseBoolean(String.valueOf(req));
                }
                needs.add(new Need(module, String.valueOf(n.get("kind")), n.get("contract") == null ? "" : String.valueOf(n.get("contract")),
                        new LinkedHashSet<>(strings(n.get("use"))), required, forList, with,
                        n.get("any-of") == null ? null : String.valueOf(n.get("any-of")), !"false".equals(String.valueOf(n.get("demo"))),
                        strings(n.get("used-by"))));
            }
        }
        if (m.get("computed") instanceof List<?> c) {
            for (Object o : c) {
                computed.put(String.valueOf(((Map<String, Object>) o).get("kind")), module);
            }
        }
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            l.forEach(x -> out.add(String.valueOf(x)));
        } else if (o != null) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    /** The needs of the modules that are on. */
    public DataNeeds of(java.util.function.Predicate<String> moduleOn) {
        return new DataNeeds(needs.stream().filter(n -> moduleOn.test(n.module())).toList(), computed, problems);
    }
}
