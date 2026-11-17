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
package com.ash.drishti.server.modules;

import com.ash.drishti.net.digest.Digest;
import com.ash.drishti.net.digest.ModuleAgreement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * The cluster part of Admin → Modules ({@code GET /api/v1/admin/modules}, contract {@value #CONTRACT}, KERNEL_AND_MODULES.md section
 * 4.2.2): the modules × nodes matrix (each cell the content hash a node booted, marked when it differs from the majority), each node's
 * fingerprint and whether it agrees, the problems on every node and the install or upgrade steps. Built from what each node reported in
 * its digest ({@link ModuleNode#identity()}); this node's own answer is read directly, so a single server has a one-column matrix.
 */
public final class ModuleClusterView {

    /** The name and version of the answer's shape; a change that removes or renames a field raises the number. */
    public static final String CONTRACT = "admin-modules/1";

    /**
     * One node as Admin → Modules sees it.
     *
     * @param id          its node id
     * @param self        this node
     * @param up          reachable now (a peer that is gone keeps its last digest)
     * @param fingerprint the fingerprint of the modules it booted; empty when it has not reported them (an older node, still starting)
     * @param modules     what it reported
     */
    public record NodeModules(String id, boolean self, boolean up, String fingerprint, List<Digest.ModuleEntry> modules) {
        public NodeModules {
            fingerprint = fingerprint == null ? "" : fingerprint;
            modules = modules == null ? List.of() : List.copyOf(modules);
        }

        boolean reported() {
            return !fingerprint.isBlank();
        }
    }

    private ModuleClusterView() {
    }

    /**
     * The answer's cluster fields, added to {@code out}.
     *
     * @param out          the answer (its {@code modules} rows, this node's switch rows, gain the cluster fields; a module only a peer
     *                     booted gains a row)
     * @param self         this node's id
     * @param kernel       this kernel's version
     * @param modulesHome  the modules folder of this node (absolute), or null when it has none
     * @param nodes        every node, this one included
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> fill(Map<String, Object> out, String self, String kernel, String modulesHome, List<NodeModules> nodes) {
        List<NodeModules> ordered = new ArrayList<>(nodes);
        ordered.sort((a, b) -> a.self() != b.self() ? (a.self() ? -1 : 1) : a.id().compareTo(b.id()));
        Map<String, List<Digest.ModuleEntry>> reporting = new TreeMap<>();
        ordered.stream().filter(NodeModules::reported).forEach(n -> reporting.put(n.id(), n.modules()));
        ModuleAgreement agreement = ModuleAgreement.ofEntries(self, reporting);

        // the fingerprints: the one most nodes have (a tie: this node's)
        Map<String, List<String>> prints = new LinkedHashMap<>();
        ordered.stream().filter(NodeModules::reported).forEach(n -> prints.computeIfAbsent(n.fingerprint(), k -> new ArrayList<>()).add(n.id()));
        String majorityPrint = prints.entrySet().stream()
                .max(java.util.Comparator.<Map.Entry<String, List<String>>>comparingInt(e -> e.getValue().size())
                        .thenComparing(e -> e.getValue().contains(self)))
                .map(Map.Entry::getKey).orElse("");

        List<Map<String, Object>> nodeRows = new ArrayList<>();
        int agree = 0;
        int differ = 0;
        for (NodeModules n : ordered) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", n.id());
            r.put("self", n.self());
            r.put("up", n.up());
            r.put("reported", n.reported());
            r.put("fingerprint", n.reported() ? n.fingerprint() : null);
            r.put("short", n.reported() ? shortPrint(n.fingerprint()) : null);
            boolean agrees = n.reported() && n.fingerprint().equals(majorityPrint);
            r.put("agrees", n.reported() ? agrees : null);
            if (n.reported()) {
                if (agrees) {
                    agree++;
                } else {
                    differ++;
                }
            }
            nodeRows.add(r);
        }

        // the rows: this node's switch rows first, then any module only a peer booted
        List<Map<String, Object>> rows = new ArrayList<>((List<Map<String, Object>>) out.getOrDefault("modules", List.of()));
        Map<String, Map<String, Object>> byId = new TreeMap<>();
        rows.forEach(r -> byId.put(String.valueOf(r.get("id")), r));
        Set<String> ids = new LinkedHashSet<>(byId.keySet());
        ordered.forEach(n -> n.modules().forEach(m -> ids.add(m.id())));
        List<Map<String, Object>> problems = new ArrayList<>();
        int stale = 0;
        int notLoaded = 0;
        for (String id : new java.util.TreeSet<>(ids)) {
            Map<String, Object> row = byId.computeIfAbsent(id, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", k);
                m.put("installed", false);                         // not on this node: only a peer booted it
                rows.add(m);
                return m;
            });
            ModuleAgreement.Module a = agreement.module(id).orElse(null);
            List<Map<String, Object>> cells = new ArrayList<>();
            List<Map<String, Object>> rowProblems = new ArrayList<>();
            boolean isStale = false;
            boolean isNotLoaded = false;
            Digest.ModuleEntry mine = null;
            for (NodeModules n : ordered) {
                Digest.ModuleEntry e = n.modules().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
                if (n.self()) {
                    mine = e;
                }
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("node", n.id());
                if (!n.reported()) {
                    c.put("state", "unknown");
                } else if (e == null) {
                    c.put("state", "absent");
                } else {
                    c.put("state", e.state());
                    c.put("version", e.version());
                    c.put("contentHash", e.contentHash());
                    c.put("short", e.contentHash() == null ? null : ModuleAgreement.shortHash(e.contentHash()));
                    c.put("origin", e.origin());
                    c.put("parts", e.parts());
                }
                c.put("differs", n.reported() && a != null && a.differs(n.id()));
                cells.add(c);
                if (e != null) {
                    for (Digest.Problem p : e.problems()) {
                        Map<String, Object> pr = new LinkedHashMap<>();
                        pr.put("module", id);
                        pr.put("node", n.id());
                        pr.put("kind", p.kind());
                        pr.put("severity", p.severity());
                        pr.put("text", p.text());
                        rowProblems.add(pr);
                        isStale |= "stale".equals(p.kind());
                        isNotLoaded |= "problem".equals(p.severity()) && !"stale".equals(p.kind());
                    }
                }
            }
            if (a != null && !a.agree()) {
                Map<String, Object> pr = new LinkedHashMap<>();
                pr.put("module", id);
                pr.put("node", String.join(", ", a.differing()));
                pr.put("kind", "differs");
                pr.put("severity", "problem");
                pr.put("text", a.words());
                rowProblems.add(0, pr);
            }
            Digest.ModuleEntry any = mine != null ? mine : ordered.stream().flatMap(n -> n.modules().stream())
                    .filter(x -> x.id().equals(id)).findFirst().orElse(null);
            if (any != null) {
                row.putIfAbsent("version", any.version());
                row.put("origin", any.origin());
                row.put("contentHash", any.contentHash());
                row.put("short", any.contentHash() == null ? null : ModuleAgreement.shortHash(any.contentHash()));
                row.put("state", any.state());
                row.put("parts", partsByNode(ordered, id));
                row.put("file", any.version() == null ? null : id + "-" + any.version() + ".dmod");
            }
            row.put("majority", a == null ? null : a.majority());
            row.put("differs", a != null && !a.agree());
            row.put("cells", cells);
            row.put("problems", rowProblems);
            problems.addAll(rowProblems);
            stale += isStale ? 1 : 0;
            notLoaded += isNotLoaded ? 1 : 0;
        }
        rows.sort(java.util.Comparator.comparing(r -> String.valueOf(r.get("id"))));

        long on = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("on"))).count();
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("modules", rows.size());
        counts.put("on", on);
        counts.put("off", rows.size() - on);
        counts.put("nodes", ordered.size());
        counts.put("nodesAgree", agree);
        counts.put("nodesDiffer", differ);
        counts.put("problems", problems.stream().filter(p -> "problem".equals(p.get("severity"))).count());
        counts.put("warnings", problems.stream().filter(p -> "warning".equals(p.get("severity"))).count());
        counts.put("stale", stale);
        counts.put("notLoaded", notLoaded);

        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("majority", majorityPrint.isEmpty() ? null : majorityPrint);
        fingerprint.put("short", majorityPrint.isEmpty() ? null : shortPrint(majorityPrint));
        fingerprint.put("nodes", prints.getOrDefault(majorityPrint, List.of()));
        fingerprint.put("agree", differ == 0);

        List<Map<String, Object>> disagree = new ArrayList<>();
        for (ModuleAgreement.Module m : agreement.disagreements()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("module", m.module());
            d.put("majority", m.majority());
            d.put("differing", m.differing());
            Map<String, List<String>> groups = new LinkedHashMap<>();
            m.groups().forEach((k, v) -> groups.put(ModuleAgreement.shortHash(k), v));
            d.put("groups", groups);
            d.put("text", m.words());
            disagree.add(d);
        }

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("contract", CONTRACT);
        answer.put("self", self);
        answer.put("kernel", kernel);
        answer.put("counts", counts);
        answer.put("fingerprint", fingerprint);
        answer.put("nodes", nodeRows);
        answer.put("modules", rows);
        answer.put("disagree", disagree);
        answer.put("problems", problems);
        answer.put("install", install(modulesHome, ordered.size() > 1));
        out.forEach(answer::putIfAbsent);                          // the switch answer's other fields: licence, lenses, store, warnings
        return answer;
    }

    /** Node → the parts of the module placed there (only nodes that reported it). */
    private static Map<String, List<String>> partsByNode(List<NodeModules> nodes, String id) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (NodeModules n : nodes) {
            n.modules().stream().filter(x -> x.id().equals(id)).findFirst().ifPresent(e -> out.put(n.id(), e.parts()));
        }
        return out;
    }

    /**
     * The install or upgrade steps: nothing deploys from the browser; a module is a file in the modules folder and a restart, on every
     * node. {@code <file>} stands for the module's {@code <id>-<version>.dmod} (each row's {@code file}).
     */
    static Map<String, Object> install(String modulesHome, boolean cluster) {
        String folder = Objects.requireNonNullElse(modulesHome, "<home>/modules");
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(step("python3 tools/drishti.py module verify <file>", "the same verifier the server runs at start"));
        steps.add(step("copy <file> into " + folder, "replace the module's older file: one file per module id"));
        steps.add(step("python3 tools/drishti.py module list", "the files of the modules folder: module, version, size, hash"));
        steps.add(step("python3 tools/drishti.py stop server && python3 tools/drishti.py start server",
                "a module is a file and a restart" + (cluster ? "; on every node, one node at a time" : "")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modulesFolder", modulesHome);
        out.put("steps", steps);
        out.put("text", String.join("\n", steps.stream().map(s -> s.get("command") + "    # " + s.get("comment")).toList()));
        out.put("note", "nothing deploys from the browser: install, upgrade or remove a module by its file in the modules folder and a "
                + "restart" + (cluster ? ", on every node, so every node boots the same content" : ""));
        return out;
    }

    private static Map<String, Object> step(String command, String comment) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("command", command);
        m.put("comment", comment);
        return m;
    }

    /** {@code e91c7a02}: the first 8 hex digits of a fingerprint. */
    static String shortPrint(String fingerprint) {
        return fingerprint == null || fingerprint.length() < 8 ? fingerprint : fingerprint.substring(0, 8);
    }
}
