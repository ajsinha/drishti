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
package com.ash.drishti.net.digest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Whether the nodes of a cluster booted the same modules (KERNEL_AND_MODULES.md section 4.2.1, CLUSTER_GUIDE.md): per module, the nodes
 * grouped by what they booted ({@link Digest.ModuleEntry#identity()}: the content hash, never the file's bytes), the majority's group and
 * the nodes that differ from it. A node that has not reported its modules (an older node, or one still starting) is left out; a node that
 * reported and has no entry for a module counts as {@link #ABSENT}.
 */
public final class ModuleAgreement {

    /** The identity of a module a reporting node did not boot at all. */
    public static final String ABSENT = "not installed";

    /**
     * One module across the nodes.
     *
     * @param module   its id
     * @param majority the identity most nodes booted (a tie: the group holding {@code self}, else the first identity in order)
     * @param groups   identity → the nodes that booted it, the majority first
     * @param versions identity → the version those nodes booted (null for {@link #ABSENT} or a refused file)
     */
    public record Module(String module, String majority, Map<String, List<String>> groups, Map<String, String> versions) {

        /** True when every reporting node booted the same. */
        public boolean agree() {
            return groups.size() <= 1;
        }

        /** The nodes whose identity is not the majority's. */
        public List<String> differing() {
            List<String> out = new ArrayList<>();
            groups.forEach((id, nodes) -> {
                if (!id.equals(majority)) {
                    out.addAll(nodes);
                }
            });
            return out;
        }

        /** True when the node booted something other than the majority. */
        public boolean differs(String node) {
            return differing().contains(node);
        }

        /** The words Admin → Health and Admin → Net show: which module and which nodes, with the short hashes. */
        public String words() {
            StringBuilder b = new StringBuilder("nodes disagree on module ").append(module).append(':');
            String sep = " ";
            for (Map.Entry<String, List<String>> g : groups.entrySet()) {
                b.append(sep).append(String.join(", ", g.getValue()))
                        .append(ABSENT.equals(g.getKey()) ? " (not installed)" : " booted " + label(g.getKey(), versions.get(g.getKey())));
                if (g.getKey().equals(majority) && groups.size() > 1) {
                    b.append(" (the majority)");
                }
                sep = "; ";
            }
            return b.toString();
        }
    }

    private final List<Module> modules;
    private final List<String> nodes;

    private ModuleAgreement(List<Module> modules, List<String> nodes) {
        this.modules = List.copyOf(modules);
        this.nodes = List.copyOf(nodes);
    }

    /**
     * Compares the modules of these digests.
     *
     * @param self    this node's id (breaks a tie)
     * @param digests node id → its digest; nodes that have not reported their modules are left out
     */
    public static ModuleAgreement of(String self, Map<String, Digest> digests) {
        Map<String, List<Digest.ModuleEntry>> reporting = new TreeMap<>();
        digests.forEach((id, d) -> {
            if (d != null && d.modulesReported()) {
                reporting.put(id, d.modules());
            }
        });
        return ofEntries(self, reporting);
    }

    /**
     * Compares the modules of these nodes.
     *
     * @param self      this node's id (breaks a tie)
     * @param reporting node id → the modules it reported (only nodes that reported)
     */
    public static ModuleAgreement ofEntries(String self, Map<String, List<Digest.ModuleEntry>> reporting) {
        TreeSet<String> ids = new TreeSet<>();
        reporting.values().forEach(l -> l.forEach(m -> ids.add(m.id())));
        List<Module> out = new ArrayList<>();
        for (String id : ids) {
            Map<String, List<String>> byIdentity = new TreeMap<>();
            Map<String, String> versions = new TreeMap<>();
            new TreeMap<>(reporting).forEach((node, l) -> {
                Optional<Digest.ModuleEntry> e = l.stream().filter(x -> x.id().equals(id)).findFirst();
                String identity = e.map(Digest.ModuleEntry::identity).orElse(ABSENT);
                byIdentity.computeIfAbsent(identity, x -> new ArrayList<>()).add(node);
                e.ifPresent(x -> versions.putIfAbsent(identity, x.version()));
            });
            String majority = byIdentity.entrySet().stream()
                    .max(Comparator.<Map.Entry<String, List<String>>>comparingInt(g -> g.getValue().size())
                            .thenComparing(g -> g.getValue().contains(self))
                            .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                    .map(Map.Entry::getKey).orElse(ABSENT);
            Map<String, List<String>> groups = new LinkedHashMap<>();
            groups.put(majority, List.copyOf(byIdentity.get(majority)));
            byIdentity.forEach((k, v) -> groups.putIfAbsent(k, List.copyOf(v)));
            out.add(new Module(id, majority, groups, versions));
        }
        return new ModuleAgreement(out, List.copyOf(new TreeSet<>(reporting.keySet())));
    }

    /** Every module any reporting node booted, in id order. */
    public List<Module> modules() {
        return modules;
    }

    /** The modules the nodes disagree on. */
    public List<Module> disagreements() {
        return modules.stream().filter(m -> !m.agree()).toList();
    }

    /** The nodes that reported their modules, in id order. */
    public List<String> nodes() {
        return nodes;
    }

    public Optional<Module> module(String id) {
        return modules.stream().filter(m -> m.module().equals(id)).findFirst();
    }

    /** The first 8 hex digits of a content hash, or the identity as it is (a state, {@link #ABSENT}). */
    public static String shortHash(String identity) {
        return identity != null && identity.matches("[0-9a-f]{64}") ? identity.substring(0, 8) : identity;
    }

    private static String label(String identity, String version) {
        String h = shortHash(identity);
        return version == null || ABSENT.equals(identity) ? h : h + " (" + version + ")";
    }
}
