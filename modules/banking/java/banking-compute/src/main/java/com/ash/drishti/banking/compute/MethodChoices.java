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
package com.ash.drishti.banking.compute;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A measure's method choices as named switches: what a record of choices shares once it names its switches, its value of each, and how to
 * set one. A run with every switch at its default and no override is the regulatory figure; anything else is an analysis.
 *
 * @param <C> the record of choices
 */
public interface MethodChoices<C extends MethodChoices<C>> {

    /** One switch as the Choices panel and the workbook's Config tab show it. */
    record Switch(String name, String dflt, List<String> options, String rule, String why) {}

    /** Every switch, in the panel's order. */
    List<Switch> switches();

    /** The value of a switch by name. */
    String get(String name);

    /** The same choices with one switch set. */
    C with(String name, String value);

    /** The parameter overrides (empty: none). */
    Map<String, String> overrides();

    /** A switch's value: its default when blank, refused when it is not one of the options. */
    static String pick(List<Switch> switches, String name, String value) {
        Switch s = switches.stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
        if (value == null || value.isBlank()) {
            return s.dflt();
        }
        String v = value.strip().toLowerCase(Locale.ROOT);
        if (!s.options().contains(v)) {
            throw new IllegalArgumentException(name + " is one of " + s.options() + ", not " + value);
        }
        return v;
    }

    /** Every switch name → value, in the panel's order. */
    default Map<String, String> asMap() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Switch s : switches()) {
            m.put(s.name(), get(s.name()));
        }
        return m;
    }

    /** The switches that differ from the defaults (name → value). */
    default Map<String, String> changed() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Switch s : switches()) {
            if (!s.dflt().equals(get(s.name()))) {
                m.put(s.name(), get(s.name()));
            }
        }
        return m;
    }

    /** True when every switch is its default and nothing is overridden. */
    default boolean regulatory() {
        return changed().isEmpty() && overrides().isEmpty();
    }

    /** Each single-switch alternative of these choices (one switch flipped to each of its other options). */
    default List<C> alternatives() {
        List<C> out = new ArrayList<>();
        for (Switch s : switches()) {
            for (String o : s.options()) {
                if (!o.equals(get(s.name()))) {
                    out.add(with(s.name(), o));
                }
            }
        }
        return out;
    }

    /** A stable text of the choices (a recipe's and a cache's key). */
    default String key() {
        StringBuilder b = new StringBuilder();
        asMap().forEach((k, v) -> b.append(k).append('=').append(v).append(';'));
        overrides().forEach((k, v) -> b.append("param.").append(k).append('=').append(v).append(';'));
        return b.toString();
    }
}
