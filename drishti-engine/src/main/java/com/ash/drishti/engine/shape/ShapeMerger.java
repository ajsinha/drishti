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
package com.ash.drishti.engine.shape;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Merges sample documents into one tree of {@link Facts}, then recognises the two shapes a plain merge would
 * get wrong: maps (ids or dates as keys) and trees (a list of records that each hold a list of the same records).
 * One instance per request; not shared.
 */
final class ShapeMerger {

    private static final Pattern LETTERS = Pattern.compile("[A-Za-z]+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final int MAP_MIN_KEYS = 4;
    private static final int MAP_PATTERN_KEYS = 5;
    private static final double TREE_OVERLAP = 0.6;

    private final BuilderProperties props;

    ShapeMerger(BuilderProperties props) {
        this.props = props;
    }

    /** Walk every document into one root; the document at index i is named {@code names.get(i)}. */
    Facts merge(List<String> names, List<JsonNode> documents) {
        Facts root = new Facts("$");
        for (int i = 0; i < documents.size(); i++) {
            root.see(documents.get(i), i, names.get(i), 1, props.maxDepth(), props.examples());
        }
        finish(root);
        return root;
    }

    private void finish(Facts f) {
        if (f.objects > 0) {
            detectMap(f);
            if (!f.isMap()) {
                detectTree(f);
            }
        }
        if (f.isMap()) {
            finish(f.mapValue);
            return;
        }
        for (Facts p : f.props.values()) {
            if (!p.tree) {
                finish(p);
            }
        }
        if (f.items != null) {
            finish(f.items);
        }
    }

    // ----------------------------------------------------------------------------------------------------- maps

    private void detectMap(Facts f) {
        int keys = f.props.size();
        if (keys < MAP_MIN_KEYS || !f.onlyObjects()) {
            return;
        }
        boolean keysVary = f.objects >= 2 && f.props.values().stream().mapToInt(p -> p.occ).max().orElse(0) <= f.objects / 2.0;
        boolean keysShaped = keys >= MAP_PATTERN_KEYS && sameKeyPattern(f.props.keySet());
        if (!(keysVary || keysShaped) || !sameKind(f)) {
            return;
        }
        Facts value = new Facts("{}");
        for (Facts p : f.props.values()) {
            value.absorb(p);
        }
        f.mapKeys = keys;
        f.mapValue = value;
        f.props.clear();
    }

    private static boolean sameKind(Facts f) {
        Set<String> kinds = new HashSet<>();
        for (Facts p : f.props.values()) {
            kinds.add(String.join(",", p.types().stream().filter(t -> !"null".equals(t)).sorted().toList()));
        }
        return kinds.size() == 1;
    }

    private static boolean sameKeyPattern(Set<String> keys) {
        Set<String> masks = new HashSet<>();
        boolean digit = false;
        for (String k : keys) {
            digit |= k.chars().anyMatch(Character::isDigit);
            masks.add(DIGITS.matcher(LETTERS.matcher(k).replaceAll("a")).replaceAll("9"));
        }
        return digit && masks.size() == 1;
    }

    // ----------------------------------------------------------------------------------------------------- trees

    /**
     * {@code record} is an object some of whose fields is a list of records like itself: fold every level of that list
     * into {@code record} and mark the list as a tree.
     */
    private void detectTree(Facts record) {
        for (Facts p : List.copyOf(record.props.values())) {
            Facts child = p.items;
            if (child == null || child.objects == 0 || p.tree || !p.only("array") || !child.onlyObjects() || !similar(record, child)) {
                continue;
            }
            Facts level = child;
            int occ = record.occ;
            int nulls = record.nulls;
            while (level != null) {
                record.absorbCounts(level);
                p.treeNulls += level.nulls;
                for (var e : level.props.entrySet()) {
                    if (!e.getKey().equals(p.name)) {
                        record.props.computeIfAbsent(e.getKey(), Facts::new).absorb(e.getValue());
                    }
                }
                Facts next = level.props.get(p.name);
                if (next != null) {
                    p.absorbCounts(next);
                    p.minLen = Math.min(p.minLen, next.minLen);
                    p.maxLen = Math.max(p.maxLen, next.maxLen);
                    p.elements += next.elements;
                }
                level = next == null ? null : next.items;
            }
            record.occ = occ;
            record.nulls = nulls;
            p.items = null;
            p.tree = true;
        }
    }

    /** Most of the scalar fields of {@code child} are fields of {@code record}: the same kind of record. */
    private static boolean similar(Facts record, Facts child) {
        List<String> theirs = child.props.entrySet().stream().filter(e -> e.getValue().scalarOnly()).map(java.util.Map.Entry::getKey).toList();
        if (theirs.isEmpty()) {
            return false;
        }
        long shared = theirs.stream().filter(k -> record.props.containsKey(k) && record.props.get(k).scalarOnly()).count();
        return shared / (double) theirs.size() >= TREE_OVERLAP;
    }
}
