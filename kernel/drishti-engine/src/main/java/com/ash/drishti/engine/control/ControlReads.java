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
package com.ash.drishti.engine.control;

import com.ash.drishti.rachana.model.Column;
import com.ash.drishti.rachana.model.Control;
import com.ash.drishti.rachana.model.ControlRules;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.StripItem;
import com.ash.drishti.rachana.model.Sutra;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which parts of a Sutra read which controls: for each panel id (and {@code strip}, {@code title}) the control ids its expressions
 * and text mention as {@code ctl.<id>}. A control change rebuilds only the panels that read it, directly or through a cascading child.
 */
public final class ControlReads {

    /** The pseudo panel ids for the strip and the title. */
    public static final String STRIP = "strip";
    public static final String TITLE = "title";

    private ControlReads() {}

    /** Part id (panel id, {@code strip} or {@code title}) to the control ids it reads, directly. */
    public static Map<String, Set<String>> of(Sutra s) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        Set<String> strip = new LinkedHashSet<>();
        for (StripItem i : s.strip()) {
            strip.addAll(ControlRules.references(i.bind()));
            strip.addAll(ControlRules.references(i.label()));
        }
        out.put(STRIP, strip);
        Set<String> title = new LinkedHashSet<>();
        if (s.title() != null) {
            title.addAll(ControlRules.references(s.title().id()));
            title.addAll(ControlRules.references(s.title().with()));
            title.addAll(ControlRules.references(s.title().pill()));
        }
        out.put(TITLE, title);
        for (Panel p : s.panels()) {
            out.put(p.id(), panel(p));
        }
        return out;
    }

    private static Set<String> panel(Panel p) {
        Set<String> ids = new LinkedHashSet<>(ControlRules.references(p.title()));
        for (Column c : p.columns()) {
            ids.addAll(ControlRules.references(c.bind()));
        }
        p.options().values().forEach(v -> walk(v, ids));
        if (p.wiring().source() != null) {            // a source's id, where, search, as-of and parameters (params: { estimator: ctl.estimator })
            p.wiring().source().expressions().forEach(e -> ids.addAll(ControlRules.references(e)));
        }
        if (p.body() != null) {
            ids.addAll(panel(p.body()));
        }
        p.parts().forEach(part -> ids.addAll(panel(part)));
        return ids;
    }

    private static void walk(Object v, Set<String> ids) {
        if (v instanceof String s) {
            ids.addAll(ControlRules.references(s));
        } else if (v instanceof Map<?, ?> m) {
            m.values().forEach(x -> walk(x, ids));
        } else if (v instanceof List<?> l) {
            l.forEach(x -> walk(x, ids));
        }
    }

    /**
     * The parts to rebuild when control {@code id} changes: those that read it, and those that read a control whose options
     * depend on it (a cascading child, to any depth). Empty when nothing reads it.
     */
    public static Set<String> affected(Sutra s, String id) {
        Map<String, Set<String>> reads = of(s);
        Set<String> controls = new LinkedHashSet<>();
        controls.add(id);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Control c : s.controls()) {
                boolean dependsOn = (c.parent() != null && controls.contains(c.parent()))
                        || ControlRules.references(c.from()).stream().anyMatch(controls::contains)
                        || ControlRules.references(c.min()).stream().anyMatch(controls::contains)
                        || ControlRules.references(c.max()).stream().anyMatch(controls::contains);
                if (dependsOn && controls.add(c.id())) {
                    grew = true;
                }
            }
        }
        Set<String> out = new LinkedHashSet<>();
        reads.forEach((part, ctl) -> {
            if (ctl.stream().anyMatch(controls::contains)) {
                out.add(part);
            }
        });
        return out;
    }
}
