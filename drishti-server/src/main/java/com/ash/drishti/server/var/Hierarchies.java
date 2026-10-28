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
package com.ash.drishti.server.var;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.var.Cells;
import com.ash.drishti.compute.var.HierarchySpec;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The hierarchies the VaR and exposure engines build (VAR_GUIDE.md section 16, 2.9.5), shared by both: the {@code drishti.var.hierarchies}
 * setting when set, else the first loaded pack's {@code hierarchies:} block (the market-risk pack's), else MGMT and LEGAL from the
 * {@code risk-hierarchy} rows, as before 2.9.5. A hierarchy of the {@code risk-hierarchy} rows that none of them names is still built
 * (book-based, its id as its title): the data decides, as before.
 *
 * @param specs the configured hierarchies, in order
 * @param origin where they were read from ({@code drishti.var.hierarchies}, {@code pack market-risk}, {@code default})
 */
public record Hierarchies(List<HierarchySpec> specs, String origin) {

    public Hierarchies {
        specs = List.copyOf(specs);
    }

    /** The default: MGMT and LEGAL from the risk-hierarchy rows. */
    public static Hierarchies defaults() {
        return new Hierarchies(HierarchySpec.defaults(), "default");
    }

    /** The configured hierarchies now: the setting, else a loaded pack's block, else the default. Refused (DRS-5213) when not valid. */
    public static Supplier<Hierarchies> of(VarProperties props, Supplier<PackRegistry> packs) {
        Hierarchies own = props.hierarchies().isEmpty() ? null : parse(props.hierarchies(), "drishti.var.hierarchies");
        return () -> {
            if (own != null) {
                return own;
            }
            PackRegistry r = packs == null ? null : packs.get();
            if (r != null) {
                for (Pack p : r.packs()) {
                    if (p.manifest().get("hierarchies") instanceof Map<?, ?> m && !m.isEmpty()) {
                        return parse(m, "pack " + p.name());
                    }
                }
            }
            return defaults();
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

    /** The hierarchy of this id, or null. */
    public HierarchySpec spec(String id) {
        return specs.stream().filter(s -> s.id().equalsIgnoreCase(id)).findFirst().orElse(null);
    }

    /** The hierarchies built from the trades' columns. */
    public List<HierarchySpec> columnSpecs() {
        return specs.stream().filter(HierarchySpec::columns).toList();
    }

    /** True when the risk-hierarchy rows of this hierarchy are read (it is not configured as built from columns). */
    public boolean fromRows(String hierarchy) {
        HierarchySpec s = spec(hierarchy);
        return s == null || !s.columns();
    }

    /** The level columns the trades must carry (every column hierarchy's), in order. */
    public List<String> levelColumns() {
        return HierarchySpec.levelColumns(specs);
    }

    /** The cell columns ({@link Cells}): empty when no hierarchy is built from columns. */
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

    /** What the configuration adds to a hot file's digest (empty for the default, so earlier files stay valid). */
    public String digest() {
        return HierarchySpec.digest(specs);
    }

    /** A hierarchy's title (its id when not configured). */
    public String title(String id) {
        HierarchySpec s = spec(id);
        return s == null ? id : s.title();
    }

    /** The hierarchies a date has, with their titles and words, in the configured order then the data's: the screens' selector. */
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
            m.put("source", s == null ? HierarchySpec.RISK_HIERARCHY : s.source());
            if (s != null && s.word() != null) {
                m.put("word", s.word());
            }
            if (s != null && s.columns()) {
                m.put("levels", s.levels());
            }
            out.add(m);
        }
        return out;
    }
}
