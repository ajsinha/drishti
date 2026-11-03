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
package com.ash.drishti.compute.simm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The SIMM method choices as named switches (docs/contracts/SIMM.md section 6.9, SENSITIVITIES_AND_FRTB_SA.md section 12.1a): the
 * concentration thresholds and the historical volatility ratios as the version sets them, or switched off to see what they add; the
 * parameter version is chosen beside them (the CSA's by default, another installed version for a recalibration date). Typed overrides of
 * any parameter ({@code key → value}) make every figure "not the regulatory figure".
 *
 * @param concentration {@code on} (the version's thresholds) or {@code off} (every concentration factor 1)
 * @param hvr {@code on} (the version's historical volatility ratios) or {@code off} (every HVR 1)
 * @param overrides parameter overrides by the Config tab's key (empty: none)
 */
public record SimmChoices(String concentration, String hvr, Map<String, String> overrides) {

    /** One switch as the Choices panel and the workbook's Config tab show it. */
    public record Switch(String name, String dflt, List<String> options, String rule, String why) {}

    public static final List<Switch> SWITCHES = List.of(
            new Switch("concentration", "on", List.of("on", "off"), "SIMM paragraphs 7(b), 8(b), 10(d), section J",
                    "the margin is the version's, concentration included; off shows what the concentration factors add"),
            new Switch("hvr", "on", List.of("on", "off"), "SIMM paragraphs 10(c), 11(d)",
                    "the version's historical volatility ratios scale vega (equity, FX, commodity) and rates curvature; off shows their effect"));

    public SimmChoices {
        concentration = pick("concentration", concentration);
        hvr = pick("hvr", hvr);
        overrides = overrides == null ? Map.of() : Map.copyOf(new TreeMap<>(overrides));
    }

    /** The defaults, no override. */
    public static SimmChoices defaults() {
        return new SimmChoices(null, null, Map.of());
    }

    private static String pick(String name, String value) {
        Switch s = SWITCHES.stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
        if (value == null || value.isBlank()) {
            return s.dflt();
        }
        String v = value.strip().toLowerCase(Locale.ROOT);
        if (!s.options().contains(v)) {
            throw new IllegalArgumentException(name + " is one of " + s.options() + ", not " + value);
        }
        return v;
    }

    public boolean concentrationOn() {
        return concentration.equals("on");
    }

    public boolean hvrOn() {
        return hvr.equals("on");
    }

    /** The value of a switch by name. */
    public String get(String name) {
        return switch (name) {
            case "concentration" -> concentration;
            case "hvr" -> hvr;
            default -> throw new IllegalArgumentException("no SIMM switch " + name + "; the switches are concentration and hvr");
        };
    }

    /** The same choices with one switch set. */
    public SimmChoices with(String name, String value) {
        Map<String, String> m = asMap();
        if (!m.containsKey(name)) {
            throw new IllegalArgumentException("no SIMM switch " + name + "; the switches are concentration and hvr");
        }
        m.put(name, value);
        return new SimmChoices(m.get("concentration"), m.get("hvr"), overrides);
    }

    /** The choices from switch name → value (missing: the default). */
    public static SimmChoices of(Map<String, String> switches, Map<String, String> overrides) {
        Map<String, String> s = switches == null ? Map.of() : switches;
        return new SimmChoices(s.get("concentration"), s.get("hvr"), overrides);
    }

    /** Every switch name → value, in the panel's order. */
    public Map<String, String> asMap() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Switch s : SWITCHES) {
            m.put(s.name(), get(s.name()));
        }
        return m;
    }

    /** The switches that differ from the defaults. */
    public Map<String, String> changed() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Switch s : SWITCHES) {
            if (!s.dflt().equals(get(s.name()))) {
                m.put(s.name(), get(s.name()));
            }
        }
        return m;
    }

    /** True when every switch is its default and nothing is overridden. */
    public boolean regulatory() {
        return changed().isEmpty() && overrides.isEmpty();
    }

    /** Each single-switch alternative of these choices. */
    public List<SimmChoices> alternatives() {
        List<SimmChoices> out = new ArrayList<>();
        for (Switch s : SWITCHES) {
            for (String o : s.options()) {
                if (!o.equals(get(s.name()))) {
                    out.add(with(s.name(), o));
                }
            }
        }
        return out;
    }

    /** A stable text of the choices (a recipe's and a cache's key). */
    public String key() {
        StringBuilder b = new StringBuilder();
        asMap().forEach((k, v) -> b.append(k).append('=').append(v).append(';'));
        overrides.forEach((k, v) -> b.append("param.").append(k).append('=').append(v).append(';'));
        return b.toString();
    }
}
