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
package com.ash.drishti.bicompute.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One axis of a view (rows or columns) as a tree of nodes opened lazily (PIVOT_ENGINE.md section 11): the root is the axis total; a node's
 * children are the members of one dimension (the axis's next, or the one it is expanded by) within it. A {@link Group} is "the children of
 * one node by one dimension": one declarative cell set, never a list of members, so it can be planned before its members are known.
 */
public final class Axis {

    /** rows or columns. */
    public final String name;
    /** The axis's dimensions, outermost first. */
    public final List<Dim> dims;
    public final Node root;

    Axis(String name, List<Dim> dims) {
        this.name = name;
        this.dims = List.copyOf(dims);
        this.root = new Node(null, List.of(), null, false, 0);
    }

    /** The dimension the children of a node are broken down by when nothing else is said: the axis's next not on its path; null at the last. */
    String defaultChild(Node n) {
        List<String> used = n.path.stream().map(p -> p[0]).toList();
        for (Dim d : dims) {
            if (!used.contains(d.id())) {
                return d.id();
            }
        }
        return null;
    }

    /**
     * A node: the axis total ({@code path} empty), a member, or the Others of a level.
     */
    public static final class Node {
        public final Node parent;
        /** (dimension id, value) pairs from the top. */
        public final List<String[]> path;
        /** The member's value (null for the root). */
        public final String value;
        public final boolean others;
        /** For Others: how many members it holds. */
        public final int othersCount;
        /** The dimension its children are broken down by, when open. */
        public String childDim;
        public final List<Node> children = new ArrayList<>();
        /** The members of the level the node's Others stands for (for its label: 31 of 41). */
        public int levelMembers;

        Node(Node parent, List<String[]> path, String value, boolean others, int othersCount) {
            this.parent = parent;
            this.path = List.copyOf(path);
            this.value = value;
            this.others = others;
            this.othersCount = othersCount;
        }

        public int depth() {
            return path.size();
        }

        public boolean open() {
            return childDim != null;
        }

        /** The dimension of the node's own level (null for the root). */
        public String dim() {
            return path.isEmpty() ? null : path.get(path.size() - 1)[0];
        }

        /** Its coordinates: dimension id to value. */
        public Map<String, String> coords() {
            Map<String, String> m = new LinkedHashMap<>();
            path.forEach(p -> m.put(p[0], p[1]));
            return m;
        }

        /** The path as [dimension, value] pairs (what an expansion names). */
        public List<List<String>> pairs() {
            return path.stream().map(p -> List.of(p[0], p[1])).toList();
        }

        /** A key of the node: its coordinates' canonical text. */
        public String key() {
            return CellKey.of(coords()).canonical();
        }

        Node child(String dim, String v, boolean isOthers, int count) {
            List<String[]> p = new ArrayList<>(path);
            p.add(new String[] {dim, v});
            Node c = new Node(this, p, v, isOthers, count);
            children.add(c);
            return c;
        }
    }

    /**
     * The children of {@code parent} by {@code dim}, as a cell set; {@code othersOf}: the Others of that level (its members are the level's
     * less {@code kept}).
     */
    record Group(Node parent, String dim, boolean others, List<String> kept) {

        /** The axis total's group (the root itself, no dimension). */
        static Group total(Node root) {
            return new Group(root, null, false, List.of());
        }

        List<String> grouping() {
            List<String> g = new ArrayList<>(parent.path.stream().map(p -> p[0]).toList());
            if (dim != null && !others) {
                g.add(dim);
            }
            return g;
        }

        Map<String, String> at() {
            return parent.coords();
        }

        Map<String, List<String>> exclude() {
            return others ? Map.of(dim, kept) : Map.of();
        }

        Map<String, String> marks() {
            return others ? Map.of(dim, CellKey.OTHERS) : Map.of();
        }

        /** This group crossed with another axis's group: one cell set. */
        CellSet cross(Group o) {
            List<String> g = new ArrayList<>(grouping());
            for (String d : o.grouping()) {
                if (!g.contains(d)) {
                    g.add(d);
                }
            }
            Map<String, String> at = new LinkedHashMap<>(at());
            at.putAll(o.at());
            Map<String, List<String>> ex = new LinkedHashMap<>(exclude());
            ex.putAll(o.exclude());
            Map<String, String> mk = new LinkedHashMap<>(marks());
            mk.putAll(o.marks());
            return new CellSet(g, at, ex, mk);
        }
    }
}
