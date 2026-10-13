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
package com.ash.drishti.server.bi.layer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The contract rules of a publish (docs/architecture/BI_MODEL_SPEC.md section 2.8), as wave W1 detects them: what consumers could use and the new version
 * no longer has. A <em>breaking</em> change (a measure, column or relationship removed, by stable id or, without ids, by name; an id kept under another type;
 * an active relationship made inactive; a grain key changed) needs a new major version, else {@code DRS-9126}. A rename keeps the id and is not breaking:
 * consumers store ids. The version may never go down. Wave W2 adds the full detection (a measure whose meaning changed, contract fields).
 */
public final class Contracts {

    /** What changed between the published and the new version: the breaking changes, the renames (same id, new name), and the version problem if any. */
    public record Change(List<String> breaking, List<String> renames, String versionProblem) {

        /** The refusal to publish, or null when the change is allowed. */
        public String refusal() {
            if (versionProblem != null) {
                return versionProblem;
            }
            return null;
        }
    }

    private Contracts() {}

    public static Change models(ModelDef was, ModelDef now) {
        return models(was, now, Map.of(), Map.of());
    }

    /**
     * The full detection of wave A (docs/architecture/BI_DESIGN.md section 7): with the datasets each version reads, also a field consumers could use that is
     * removed or retyped, a measure whose meaning changes (its expression compiles to another tree, its currency or additivity changes), and a grain change.
     */
    public static Change models(ModelDef was, ModelDef now, Map<String, ResolvedDataset> wasData, Map<String, ResolvedDataset> nowData) {
        List<String> breaking = new ArrayList<>();
        List<String> renames = new ArrayList<>();
        meanings(was, now, breaking);
        wasData.forEach((alias, w) -> {
            ResolvedDataset n = nowData.get(alias);
            if (n == null) {
                return;
            }
            w.def().columns().forEach((name, c) -> {
                DatasetDef.Col same = n.def().columns().get(name);
                if (same == null) {
                    breaking.add("the field " + alias + "." + name + " is removed");
                } else if (!same.type().equals(c.type())) {
                    breaking.add("the field " + alias + "." + name + " changes type from " + c.type() + " to " + same.type());
                }
            });
            if (w.def().grain() != null && n.def().grain() != null && !w.def().grain().keys().equals(n.def().grain().keys())) {
                breaking.add("the grain of " + alias + " changes from " + w.def().grain().keys() + " to " + n.def().grain().keys());
            }
        });
        Map<String, String> wasById = new LinkedHashMap<>();
        was.measures().values().forEach(m -> wasById.put(m.id().isBlank() ? "name:" + m.name() : m.id(), m.name()));
        Map<String, String> nowById = new LinkedHashMap<>();
        now.measures().values().forEach(m -> nowById.put(m.id().isBlank() ? "name:" + m.name() : m.id(), m.name()));
        wasById.forEach((id, name) -> {
            String n = nowById.get(id);
            if (n == null && !(id.startsWith("name:") && now.measures().containsKey(name))) {
                breaking.add("the measure '" + name + "'" + (id.startsWith("name:") ? "" : " (" + id + ")") + " is removed");
            } else if (n != null && !n.equals(name)) {
                renames.add("measure " + id + ": '" + name + "' is now '" + n + "'");
            }
        });
        for (ModelDef.Rel r : was.relationships()) {
            ModelDef.Rel same = now.relationships().stream().filter(x -> !r.id().isBlank() ? r.id().equals(x.id()) : x.label().equals(r.label())).findFirst().orElse(null);
            if (same == null) {
                breaking.add("the relationship " + r.label() + " is removed");
            } else if (r.active() && !same.active()) {
                breaking.add("the relationship " + r.label() + " is made inactive");
            }
        }
        for (String alias : was.datasets().keySet()) {
            if (!now.datasets().containsKey(alias)) {
                breaking.add("the dataset '" + alias + "' is removed from the model");
            }
        }
        return new Change(breaking, renames, versionProblem(was.version(), now.version(), breaking));
    }

    /** A measure kept (by id, or by name without ids) whose meaning changes: another expression tree, another currency or additivity. */
    private static void meanings(ModelDef was, ModelDef now, List<String> breaking) {
        for (ModelDef.Meas w : was.measures().values()) {
            ModelDef.Meas n = now.measures().values().stream().filter(x -> !w.id().isBlank() ? w.id().equals(x.id()) : w.name().equals(x.name())).findFirst().orElse(null);
            if (n == null) {
                continue;
            }
            if (!sameTree(w.expr(), n.expr()) || !java.util.Objects.equals(w.python(), n.python())) {
                breaking.add("the measure '" + w.name() + "' changes meaning (" + (w.expr() == null ? w.python() : w.expr()) + " is now " + (n.expr() == null ? n.python() : n.expr()) + ")");
            } else if (!java.util.Objects.equals(w.currency(), n.currency()) || !java.util.Objects.equals(w.additive(), n.additive())) {
                breaking.add("the measure '" + w.name() + "' changes its currency or additivity");
            }
        }
    }

    /** True when two expressions compile to the same tree (a reformatting or the converter's DEL v2 text is not a change of meaning). */
    static boolean sameTree(String a, String b) {
        if (java.util.Objects.equals(a, b)) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        try {
            return com.ash.drishti.server.bi.expr.Expressions.parse(a).equals(com.ash.drishti.server.bi.expr.Expressions.parse(b));
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static Change datasets(DatasetDef was, DatasetDef now) {
        List<String> breaking = new ArrayList<>();
        List<String> renames = new ArrayList<>();
        for (DatasetDef.Col c : was.columns().values()) {
            DatasetDef.Col same = c.id().isBlank() ? now.columns().get(c.name())
                    : now.columns().values().stream().filter(x -> c.id().equals(x.id())).findFirst().orElse(now.columns().get(c.name()));
            if (same == null) {
                breaking.add("the column '" + c.name() + "' is removed");
            } else {
                if (!same.type().equals(c.type())) {
                    breaking.add("the column '" + c.name() + "' changes type from " + c.type() + " to " + same.type());
                }
                if (!same.name().equals(c.name())) {
                    renames.add("column " + c.id() + ": '" + c.name() + "' is now '" + same.name() + "'");
                }
            }
        }
        if (was.grain() != null && now.grain() != null && !was.grain().keys().equals(now.grain().keys())) {
            breaking.add("the grain keys change from " + was.grain().keys() + " to " + now.grain().keys());
        }
        return new Change(breaking, renames, versionProblem(was.version(), now.version(), breaking));
    }

    private static String versionProblem(String was, String now, List<String> breaking) {
        Semver a = Semver.parse(was);
        Semver b = Semver.parse(now);
        if (a == null || b == null) {
            return null;
        }
        if (b.compareTo(a) < 0) {
            return "the version goes down from " + a + " to " + b;
        }
        if (!breaking.isEmpty() && b.major() <= a.major()) {
            return "a breaking change needs a new major version (" + (a.major() + 1) + ".0.0, not " + b + "): " + String.join("; ", breaking);
        }
        return null;
    }
}
