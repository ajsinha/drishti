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
package com.ash.drishti.module.runtime.placement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves a placement against the modules and the nodes (KERNEL_AND_MODULES.md section 4.11): for every module part, the nodes it is
 * placed on, those up, and for a one-runner part ({@code feed}, {@code singleton}) its owner, so every node that sees the same placement and
 * the same members agrees on it without a vote, and failover only ever moves to the next placed node: never to an unplaced one. The
 * owner comes from the one {@link SingletonElection} (today {@link FirstLiveElection}: the first live placed node by name).
 *
 * <p>A module still built into the server ({@link ModuleParts#inProcess()}) counts as placed on every node, unguarded, until it becomes a
 * module. Pure functions: no state, no I/O.
 */
public final class PlacementResolver {

    private PlacementResolver() {
    }

    /** One cell of the node × part matrix. */
    public enum State {
        /** placed here and running (a one-runner part: this node owns it) */
        ACTIVE,
        /** placed here and up, waiting: a one-runner part another placed node owns */
        STANDBY,
        /** placed here but the node is not up */
        DOWN,
        /** not placed here: this node forwards the part's requests, never runs them */
        NOT_PLACED,
        /** a module still built into the server: on every node */
        IN_PROCESS
    }

    /** One cell: the node and its state for the part. */
    public record Cell(String node, State state) {
    }

    /**
     * One module part, resolved.
     *
     * @param status {@code placed}, {@code not-placed} (no placement: runs nowhere), {@code unavailable} (placed, no placed node up) or
     *     {@code in-process}
     */
    public record PartView(String module, String version, String part, PartClass cls, long memoryMb, int maxNodes, List<String> placed,
            List<String> up, String owner, String status, List<Cell> cells) {
    }

    /** The matrix: the nodes (columns), the parts (rows), and words for what is wrong. */
    public record Matrix(List<NodeView> nodes, List<PartView> parts, List<String> problems) {

        /** The row of one part. */
        public Optional<PartView> part(String module, String part) {
            return parts.stream().filter(p -> p.module().equals(module) && p.part().equals(part)).findFirst();
        }
    }

    /** The nodes a target names: named nodes as listed (known ones), then the role's nodes by name. */
    public static List<String> placed(Placement.Target t, List<NodeView> nodes) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> known = nodes.stream().map(NodeView::id).collect(Collectors.toSet());
        t.nodes().stream().filter(known::contains).forEach(out::add);
        nodes.stream().sorted(Comparator.comparing(NodeView::id)).filter(n -> t.roles().stream().anyMatch(n::has)).map(NodeView::id)
                .forEach(out::add);
        return List.copyOf(out);
    }

    /** Resolves every known module's parts, electing with {@link FirstLiveElection} on the nodes' up flags. */
    public static Matrix resolve(Placement placement, Collection<ModuleParts> modules, List<NodeView> nodes) {
        return resolve(placement, modules, nodes, new FirstLiveElection(() -> nodes.stream().filter(NodeView::up).map(NodeView::id).toList()));
    }

    /** Resolves every known module's parts; one-runner parts' owners from {@code election} (told each part's placed nodes first). */
    public static Matrix resolve(Placement placement, Collection<ModuleParts> modules, List<NodeView> nodes, SingletonElection election) {
        List<PartView> rows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Map<String, NodeView> byId = nodes.stream().collect(Collectors.toMap(NodeView::id, n -> n, (a, b) -> a, java.util.LinkedHashMap::new));
        for (ModuleParts m : modules) {
            boolean any = false;
            for (ModuleParts.Part part : m.parts().values()) {
                if (m.inProcess()) {
                    List<String> all = List.copyOf(byId.keySet());
                    rows.add(new PartView(m.module(), m.version(), part.name(), part.cls(), part.memoryMb(), 0, all,
                            nodes.stream().filter(NodeView::up).map(NodeView::id).toList(), null, "in-process",
                            nodes.stream().map(n -> new Cell(n.id(), State.IN_PROCESS)).toList()));
                    continue;
                }
                Optional<Placement.Target> t = placement.target(m.module(), part.name());
                List<String> placed = t.map(x -> placed(x, nodes)).orElse(List.of());
                List<String> up = placed.stream().filter(id -> byId.get(id).up()).toList();
                String owner = null;
                if (part.cls().oneRunner()) {
                    String key = SingletonElection.key(m.module(), part.name());
                    election.candidates(key, placed);
                    owner = election.owner(key).filter(up::contains).orElse(null);
                }
                any |= !placed.isEmpty();
                String status = placed.isEmpty() ? "not-placed" : up.isEmpty() ? "unavailable" : "placed";
                List<Cell> cells = new ArrayList<>();
                for (NodeView n : nodes) {
                    State s = !placed.contains(n.id()) ? State.NOT_PLACED : !n.up() ? State.DOWN
                            : owner != null && !owner.equals(n.id()) ? State.STANDBY : State.ACTIVE;
                    cells.add(new Cell(n.id(), s));
                }
                rows.add(new PartView(m.module(), m.version(), part.name(), part.cls(), part.memoryMb(), t.map(Placement.Target::maxNodes)
                        .orElse(0), placed, up, owner, status, cells));
                if (t.isPresent() && placed.isEmpty()) {
                    problems.add(m.module() + "." + part.name() + " is placed on no node of the cluster now (" + describe(t.get()) + ")");
                } else if (!placed.isEmpty() && up.isEmpty()) {
                    problems.add(m.module() + "." + part.name() + " is unavailable: none of its placed nodes is up (" + String.join(", ", placed)
                            + "); it never runs on another node");
                }
            }
            if (!m.inProcess() && !any) {
                problems.add(m.module() + " is not placed: it runs on no node (config/placement.yaml has no part of it on a node of the cluster)");
            }
        }
        return new Matrix(List.copyOf(nodes), rows, problems);
    }

    /**
     * What makes a placement unacceptable (a plan with any of these is refused): a module built into the server placed by hand, a part the
     * module does not declare, a heavy part over its max-nodes, or a heavy part on every node of a cluster of two or more without
     * {@code allow-all: true}. Modules the placement names that are not installed are not refused (a placement may come first).
     */
    public static List<String> refusals(Placement placement, Collection<ModuleParts> modules, List<NodeView> nodes) {
        List<String> out = new ArrayList<>();
        Map<String, ModuleParts> byId = modules.stream().collect(Collectors.toMap(ModuleParts::module, m -> m, (a, b) -> a));
        placement.modules().forEach((id, parts) -> {
            ModuleParts m = byId.get(id);
            if (m == null) {
                return;
            }
            if (m.inProcess()) {
                out.add(id + " is built into the server: it runs on every node until it becomes a module, so it cannot be placed yet");
                return;
            }
            parts.forEach((name, t) -> {
                ModuleParts.Part part = m.parts().get(name);
                if (part == null) {
                    out.add(id + " has no part `" + name + "` (its parts: " + String.join(", ", m.parts().keySet()) + ")");
                    return;
                }
                if (part.cls() != PartClass.HEAVY) {
                    return;
                }
                int n = placed(t, nodes).size();
                if (t.maxNodes() > 0 && n > t.maxNodes()) {
                    out.add(id + "." + name + " (heavy) on " + n + " nodes: its max-nodes is " + t.maxNodes());
                }
                if (nodes.size() > 1 && n == nodes.size() && !t.allowAll()) {
                    out.add(id + "." + name + " (heavy) on " + n + " of " + nodes.size() + " nodes: heavy work never goes everywhere by itself;"
                            + " confirm with allow-all: true");
                }
            });
        });
        return out;
    }

    private static String describe(Placement.Target t) {
        List<String> w = new ArrayList<>();
        if (!t.nodes().isEmpty()) {
            w.add("nodes " + String.join(", ", t.nodes()));
        }
        if (!t.roles().isEmpty()) {
            w.add("roles " + String.join(", ", t.roles()));
        }
        return String.join("; ", w);
    }
}
