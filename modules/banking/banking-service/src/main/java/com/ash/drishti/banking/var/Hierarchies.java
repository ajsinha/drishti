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
package com.ash.drishti.banking.var;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.banking.compute.var.Cells;
import com.ash.drishti.banking.compute.var.HierarchySpec;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The hierarchies the VaR and exposure engines build (VAR_GUIDE.md section 15.14), shared by both, every one from the trades' promoted
 * columns: the {@code drishti.var.hierarchies} setting when set, else the first loaded pack's {@code hierarchies:} block (the market-risk
 * pack's: MGMT, LEGAL and LL), else none (the engines then answer the bank, positions, trades and baskets, with no tree). Each (Blank)
 * node is named by its hierarchy's {@code empty-label}, else {@code drishti.var.hierarchy.empty-label}.
 *
 * @param specs the configured hierarchies, in order
 * @param origin where they were read from ({@code drishti.var.hierarchies}, {@code pack market-risk}, {@code none})
 * @param known every column the loaded packs' connector layouts promote for any kind (empty: no pack loaded, no typo check): a level column none of
 *     them names and no source promotes is a typo, refused; one they name but a source does not promote (a connector file written before
 *     the column was added) is a warning
 */
public record Hierarchies(List<HierarchySpec> specs, String origin, Set<String> known) {

    public Hierarchies {
        specs = List.copyOf(specs);
        known = known == null ? Set.of() : Set.copyOf(known);
    }

    public Hierarchies(List<HierarchySpec> specs, String origin) {
        this(specs, origin, Set.of());
    }

    /** No hierarchy configured. */
    public static Hierarchies none() {
        return new Hierarchies(List.of(), "none");
    }

    /** The columns the loaded packs' connector layouts promote, for any kind ({@code connectors.<c>.settings.layout.<kind>.columns}). */
    static Set<String> packColumns(PackRegistry r) {
        Set<String> out = new java.util.TreeSet<>();
        if (r == null) {
            return out;
        }
        for (Pack p : r.packs()) {
            if (!(p.manifest().get("connectors") instanceof Map<?, ?> cs)) {
                continue;
            }
            for (Object c : cs.values()) {
                if (c instanceof Map<?, ?> cm && cm.get("settings") instanceof Map<?, ?> st && st.get("layout") instanceof Map<?, ?> lay) {
                    for (Object k : lay.values()) {
                        if (k instanceof Map<?, ?> km && km.get("columns") instanceof List<?> cols) {
                            cols.forEach(x -> out.add(String.valueOf(x)));
                        }
                    }
                }
            }
        }
        return out;
    }

    /** The configured hierarchies now: the setting, else a loaded pack's block, else none. Refused (DRS-5213) when not valid. */
    public static Supplier<Hierarchies> of(VarProperties props, Supplier<PackRegistry> packs) {
        String label = props.hierarchy().emptyLabel();
        Hierarchies own = props.hierarchies().isEmpty() ? null : parse(props.hierarchies(), "drishti.var.hierarchies").withEmptyLabel(label);
        return () -> {
            PackRegistry r = packs == null ? null : packs.get();
            if (own != null) {
                return own.withKnown(packColumns(r));
            }
            if (r != null) {
                for (Pack p : r.packs()) {
                    if (p.manifest().get("hierarchies") instanceof Map<?, ?> m && !m.isEmpty()) {
                        return parse(m, "pack " + p.name()).withEmptyLabel(label).withKnown(packColumns(r));
                    }
                }
            }
            return none();
        };
    }

    /** Parses and checks one configuration; DRS-5213 says what is wrong and where it was read from. */
    public static Hierarchies parse(Map<?, ?> m, String origin) {
        try {
            return new Hierarchies(HierarchySpec.parse(m), origin);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.VAR_HIERARCHY_INVALID, "the hierarchies of " + origin + " are not valid: " + e.getMessage());
        }
    }

    /** These hierarchies, each (Blank) node named {@code label} where the hierarchy sets no {@code empty-label} of its own. */
    public Hierarchies withEmptyLabel(String label) {
        return new Hierarchies(specs.stream().map(s -> s.withDefaultEmptyLabel(label)).toList(), origin, known);
    }

    /** These hierarchies with the columns the loaded packs' layouts promote. */
    public Hierarchies withKnown(Set<String> columns) {
        return new Hierarchies(specs, origin, columns);
    }

    /**
     * The hierarchy warnings now, by engine, hierarchy, level column and kind ({@code var LL LIMIT_LETTER scenario-pv}) to the words: a
     * level column a source kind does not promote (its rows sit under (Blank) at that level), with the fix. Admin → Health lists them.
     */
    private static final Map<String, String> WARNINGS = new java.util.concurrent.ConcurrentSkipListMap<>();

    /** Replaces an engine's hierarchy warnings ({@code key} without the engine's word, to the words). */
    public static void warnings(String engine, Map<String, String> now) {
        WARNINGS.keySet().removeIf(k -> k.startsWith(engine + " "));
        now.forEach((k, v) -> WARNINGS.put(engine + " " + k, v));
    }

    /** Every hierarchy warning now, engine, hierarchy, level column and kind to the words (Admin → Health). */
    public static Map<String, String> warnings() {
        return Map.copyOf(WARNINGS);
    }

    /**
     * The promoted-columns guard (VAR_GUIDE.md section 15.14): every level column must be a promoted column of each source kind a
     * hierarchy reads ({@code kept}: kind to its promoted columns; an empty set: the connector cannot say, not checked). A column that a
     * kind does not promote is a warning (that kind's rows sit under (Blank) at its level), keyed {@code <hierarchy> <column> <kind>}; a
     * column promoted by no source that can say its columns, and named by no loaded pack's layout ({@link #known}), is refused when
     * {@code refuse} (DRS-5213: a typo), naming the hierarchy, the column and the layouts (VaR, which reads every source of the trades;
     * exposure reads one of them and only warns).
     */
    public Map<String, String> unpromoted(Map<String, Set<String>> kept, boolean refuse) {
        Map<String, String> out = new LinkedHashMap<>();
        for (HierarchySpec s : specs) {
            for (int i = 0; i < s.levels().size(); i++) {
                String c = s.levels().get(i);
                List<String> missing = new ArrayList<>();
                int known = 0;
                for (Map.Entry<String, Set<String>> e : kept.entrySet()) {
                    if (e.getValue().isEmpty()) {
                        continue;
                    }
                    known++;
                    if (!e.getValue().contains(c)) {
                        missing.add(e.getKey());
                    }
                }
                if (refuse && known > 0 && missing.size() == known && !this.known.isEmpty() && !this.known.contains(c)) {
                    throw new DrishtiException(ErrorCode.VAR_HIERARCHY_INVALID, "the hierarchy " + s.id() + " (" + s.title() + ", " + origin
                            + ") names the level column " + c + ", which no source promotes: check the name, or add " + c + " to "
                            + String.join(" and ", missing.stream().map(k -> "layout." + k + ".columns").toList())
                            + " of the connector (a hierarchy is built from promoted columns only)");
                }
                for (String k : missing) {
                    out.put(s.id() + " " + c + " " + k, ErrorCode.VAR_HIERARCHY_COLUMN.code() + " the hierarchy " + s.id() + " (" + s.title()
                            + "): " + k + " does not promote its level column " + c + ", so every " + k + " row sits under "
                            + s.blankName() + " at the " + s.levelNames().get(i) + " level; fix: promote " + c + " in layout." + k
                            + ".columns of the connector and send it on every row");
                }
            }
        }
        return out;
    }

    /** The hierarchy of this id, or null. */
    public HierarchySpec spec(String id) {
        return specs.stream().filter(s -> s.id().equalsIgnoreCase(id)).findFirst().orElse(null);
    }

    /** The level columns the trades must carry (every hierarchy's), in order. */
    public List<String> levelColumns() {
        return HierarchySpec.levelColumns(specs);
    }

    /** The cell columns ({@link Cells}). */
    public List<String> cellColumns() {
        return Cells.columns(specs);
    }

    /** The scope words: word to hierarchy id ({@code LL -> LL}). */
    public Map<String, String> words() {
        Map<String, String> out = new LinkedHashMap<>();
        specs.forEach(s -> {
            if (s.word() != null) {
                out.put(s.word(), s.id());
            }
        });
        return out;
    }

    /** The limits kind of each hierarchy that reads its limits from one ({@code var-limit}): hierarchy id to kind. */
    public Map<String, String> limitKinds() {
        Map<String, String> out = new LinkedHashMap<>();
        specs.forEach(s -> {
            if (s.limits() != null) {
                out.put(s.id(), s.limits());
            }
        });
        return out;
    }

    /**
     * Why a var-limit row's node cannot be a node of its hierarchy, or null when it can (DRS-5212): the hierarchy is not configured, or
     * the id is not one its levels can build ({@code <id>~<root>}, a leaf value, or a path of at most as many values as levels).
     */
    public String unbuildable(String hierarchy, String nodeId) {
        HierarchySpec s = hierarchy == null ? null : spec(hierarchy);
        if (s == null) {
            return "the hierarchy " + hierarchy + " is not configured (the hierarchies are " + String.join(", ", specs.stream().map(HierarchySpec::id)
                    .toList()) + ")";
        }
        String prefix = s.id() + "~";
        if (nodeId == null || !nodeId.startsWith(prefix) || nodeId.length() == prefix.length()) {
            return nodeId + " is not a node id of " + s.id() + " (" + prefix + "<value>)";
        }
        String rest = nodeId.substring(prefix.length());
        int depth = rest.split("/", -1).length;
        if (depth > s.levels().size()) {
            return nodeId + " has " + depth + " values; " + s.id() + " has " + s.levels().size() + " levels (" + String.join(" > ", s.levels()) + ")";
        }
        return null;
    }

    /** What the configuration adds to a hot file's digest. */
    public String digest() {
        return HierarchySpec.digest(specs);
    }

    /** A hierarchy's title (its id when not configured). */
    public String title(String id) {
        HierarchySpec s = spec(id);
        return s == null ? id : s.title();
    }

    /**
     * The words of a (Blank) node's {@code ?}: how many trades have no value in its level column, and the fix. Null for a node that is
     * not a (Blank) one.
     */
    public String blankNote(String hierarchy, String nodeId, String level, int trades) {
        HierarchySpec s = spec(hierarchy);
        if (s == null || !HierarchySpec.blankNode(nodeId)) {
            return null;
        }
        int i = s.levelNames().indexOf(level);
        String c = i < 0 ? level : s.levels().get(i);
        return "rows with no " + c + " value: " + trades + " trade" + (trades == 1 ? "" : "s") + "; fix: send " + c + " on every row and "
                + "promote it in the connector's layout.<kind>.columns";
    }

    /** The hierarchies a date has, with their titles, words and levels, in the configured order then the data's: the screens' selector. */
    public List<Map<String, Object>> describe(List<String> present) {
        List<String> order = new ArrayList<>();
        specs.forEach(s -> {
            if (present.contains(s.id())) {
                order.add(s.id());
            }
        });
        present.stream().filter(h -> !order.contains(h)).sorted().forEach(order::add);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String h : order) {
            HierarchySpec s = spec(h);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h);
            m.put("title", s == null ? h : s.title());
            if (s != null && s.word() != null) {
                m.put("word", s.word());
            }
            if (s != null) {
                m.put("levels", s.levels());
                m.put("emptyLabel", s.blankName());
            }
            out.add(m);
        }
        return out;
    }
}
