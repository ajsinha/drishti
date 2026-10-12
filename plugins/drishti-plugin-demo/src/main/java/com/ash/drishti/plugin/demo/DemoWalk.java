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
package com.ash.drishti.plugin.demo;

import com.ash.drishti.api.DataNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

/**
 * Applies a sample's {@code _meta.walk} to one document. Three parts, in this order:
 * <ol>
 *   <li><b>steps</b>: {@code "field": step} walks one number (the original form); {@code "field": {step, with, scale, shape,
 *       jitter, min, max, places}} draws ONE random step and moves a whole group with it (the field plus every path in
 *       {@code with}, wildcards allowed, each times its {@code scale} so a decimal rate and a percent rate move together).</li>
 *   <li><b>derive</b>: {@code [{set, expr, places}]} recomputes dependent fields from the moved ones (a discount factor
 *       from its zero rate, a total from its parts, a status from its thresholds).</li>
 *   <li><b>append</b>: {@code [{to, key, format, row, keep}]} keeps a time series growing: a new row when the clock label
 *       ({@code format}) changes, otherwise the latest row is updated; the oldest rows fall off at {@code keep}.</li>
 * </ol>
 * Each entry stands alone: one that cannot be applied (a path that left the document, an expression that does not parse) is
 * skipped and the rest still run. Used from the single tick thread.
 */
final class DemoWalk {

    private final DoubleUnaryOperator gauss;
    private final Clock clock;

    DemoWalk(DoubleUnaryOperator gauss, Clock clock) {
        this.gauss = gauss;
        this.clock = clock;
    }

    /** Applies the walk to {@code doc} in place. */
    void apply(Map<String, Object> doc, DataNode.Obj walk) {
        walk.fields().forEach((field, spec) -> {
            if ("derive".equals(field) || "append".equals(field)) {
                return;
            }
            try {
                step(doc, field, spec);
            } catch (RuntimeException e) {
                // one entry that cannot be applied leaves the others walking
            }
        });
        if (walk.get("derive") instanceof DataNode.Arr derive) {
            derive.elements().forEach(d -> {
                try {
                    derive(doc, d);
                } catch (RuntimeException e) {
                    // skipped: a derive whose inputs are missing or whose expression is broken
                }
            });
        }
        if (walk.get("append") instanceof DataNode.Arr append) {
            append.elements().forEach(a -> {
                try {
                    append(doc, a);
                } catch (RuntimeException e) {
                    // skipped
                }
            });
        }
    }

    // ---- steps -----------------------------------------------------------------------------------------------
    private void step(Map<String, Object> doc, String field, DataNode spec) {
        if (!(spec instanceof DataNode.Obj group)) {
            DemoPaths.Slot slot = DemoPaths.first(doc, field);
            if (slot != null && slot.get() instanceof Number n) {
                move(slot, n.doubleValue() + gauss.applyAsDouble(spec.asDouble()), spec.asDouble(), null);
            }
            return;
        }
        double sd = group.get("step").asDouble();
        double scale = number(group, "scale", 1);
        boolean relative = "rel".equals(text(group, "mode"));
        boolean twist = "twist".equals(text(group, "shape"));
        double jitter = number(group, "jitter", 0);
        double shift = gauss.applyAsDouble(sd);
        double tilt = twist ? gauss.applyAsDouble(sd * 0.6) : 0;
        Integer places = group.get("places") instanceof DataNode.Val ? (int) group.get("places").asDouble() : null;
        DemoPaths.Slot main = DemoPaths.first(doc, field);
        if (main != null && main.get() instanceof Number n) {
            double v = relative ? n.doubleValue() * (1 + shift) : n.doubleValue() + shift;
            if (group.get("min") instanceof DataNode.Val) {
                v = Math.max(group.get("min").asDouble(), v);
            }
            if (group.get("max") instanceof DataNode.Val) {
                v = Math.min(group.get("max").asDouble(), v);
            }
            move(main, v, relative ? 0 : sd, places);
        }
        if (group.get("with") instanceof DataNode.Arr with) {
            for (DataNode w : with.elements()) {
                String path = w instanceof DataNode.Obj o ? o.get("path").asText() : w.asText();
                double s = w instanceof DataNode.Obj o ? number(o, "scale", scale) : scale;
                Integer wPlaces = w instanceof DataNode.Obj o && o.get("places") instanceof DataNode.Val ? Integer.valueOf((int) o.get("places").asDouble()) : places;
                for (DemoPaths.Slot slot : DemoPaths.find(doc, path)) {
                    if (slot.get() instanceof Number n) {
                        double weight = slot.count() > 1 ? 2.0 * slot.pos() / (slot.count() - 1) - 1 : 0;
                        double move = (shift + tilt * weight + (jitter > 0 ? gauss.applyAsDouble(sd * jitter) : 0)) * s;
                        move(slot, relative ? n.doubleValue() * (1 + move) : n.doubleValue() + move, relative ? 0 : Math.abs(sd * s), wPlaces);
                    }
                }
            }
        }
    }

    private static double number(DataNode o, String key, double fallback) {
        return o.get(key) instanceof DataNode.Val ? o.get(key).asDouble() : fallback;
    }

    private static String text(DataNode o, String key) {
        return o.get(key) instanceof DataNode.Val ? o.get(key).asText() : "";
    }

    /**
     * Stores {@code v} keeping precision: a step of 0.0005 must not round back to two decimals and freeze, so the places come
     * from the step ({@code sd}); {@code sd} 0 means a relative move, which keeps the old value's decimals. A whole number
     * stays whole when its step is a whole number or more.
     */
    private static void move(DemoPaths.Slot slot, double v, double sd, Integer places) {
        Object old = slot.get();
        boolean whole = old instanceof Long || old instanceof Integer;
        if (whole && (sd >= 1 || sd == 0)) {
            slot.set(Math.round(v));
            return;
        }
        int p;
        if (places != null) {
            p = places;
        } else if (sd > 0) {
            p = Math.max(2, (int) Math.ceil(-Math.log10(sd)) + 2);
        } else {
            p = old instanceof Double od ? Math.min(8, decimals(od)) : 2;
        }
        slot.set(round(v, p));
    }

    // ---- derive ----------------------------------------------------------------------------------------------
    private void derive(Map<String, Object> doc, DataNode spec) {
        String set = spec.get("set").asText();
        String expr = spec.get("scaleTo") instanceof DataNode.Val ? "" : spec.get("expr").asText();
        Integer places = spec.get("places") instanceof DataNode.Val ? (int) spec.get("places").asDouble() : null;
        if (spec.get("scaleTo") instanceof DataNode.Val target) {
            scaleTo(doc, set, target.asText(), places);
            return;
        }
        for (DemoPaths.Slot slot : DemoPaths.find(doc, set)) {
            Object v = DemoExpr.eval(expr, new DemoExpr.Ctx(doc, slot.holder(), null, gauss));
            store(slot, v, places);
        }
    }

    /** Rescales every number the path reaches by one factor so that they add up to {@code target}: the cashflow PVs follow their leg's PV. */
    private void scaleTo(Map<String, Object> doc, String set, String target, Integer places) {
        List<DemoPaths.Slot> slots = DemoPaths.find(doc, set).stream().filter(x -> x.get() instanceof Number).toList();
        double total = slots.stream().mapToDouble(x -> ((Number) x.get()).doubleValue()).sum();
        Object to = DemoExpr.eval(target, new DemoExpr.Ctx(doc, null, null, gauss));
        if (total == 0 || !(to instanceof Double want) || want.isNaN()) {
            return;
        }
        double factor = want / total;
        for (DemoPaths.Slot slot : slots) {
            store(slot, ((Number) slot.get()).doubleValue() * factor, places);
        }
    }

    /** Writes a computed value into a slot: text as is; a number as a whole number where the old value was one, else to {@code places} (default: the old value's decimals). */
    private static void store(DemoPaths.Slot slot, Object v, Integer places) {
        if (v instanceof String s) {
            slot.set(s);
        } else if (v instanceof Double d && !d.isNaN() && !d.isInfinite()) {
            Object old = slot.get();
            if (places == null && (old instanceof Long || old instanceof Integer)) {
                slot.set(Math.round(d));
            } else {
                slot.set(round(d, places != null ? places : old instanceof Double od ? Math.min(8, Math.max(2, decimals(od))) : 4));
            }
        }
    }

    private static int decimals(double v) {
        return Math.max(0, BigDecimal.valueOf(v).stripTrailingZeros().scale());
    }

    // ---- append ----------------------------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private void append(Map<String, Object> doc, DataNode spec) {
        DemoPaths.Slot at = DemoPaths.first(doc, spec.get("to").asText());
        if (at == null || !(at.get() instanceof List<?> old)) {
            return;
        }
        String key = spec.get("key").asText();
        String label = LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern(spec.get("format").asText()));
        int keep = spec.get("keep") instanceof DataNode.Val ? (int) spec.get("keep").asDouble() : 60;
        Integer places = spec.get("places") instanceof DataNode.Val ? (int) spec.get("places").asDouble() : null;
        List<Object> rows = new ArrayList<>(old);
        Map<String, Object> last = !rows.isEmpty() && rows.get(rows.size() - 1) instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        Map<String, Object> existing = null;
        for (int i = rows.size() - 1; i >= 0 && existing == null; i--) {
            if (rows.get(i) instanceof Map<?, ?> m && label.equals(String.valueOf(m.get(key)))) {
                existing = (Map<String, Object>) m;
            }
        }
        boolean sameBucket = existing != null;
        Map<String, Object> row = sameBucket ? existing : new LinkedHashMap<>();
        Map<String, Object> before = existing != null ? new LinkedHashMap<>(existing) : last == null ? Map.of() : new LinkedHashMap<>(last);
        row.put(key, label);
        if (spec.get("row") instanceof DataNode.Obj fields) {
            fields.fields().forEach((name, expr) -> {
                Object v = DemoExpr.eval(expr.asText(), new DemoExpr.Ctx(doc, null, before, gauss));
                if (v instanceof Double d) {
                    Object prev = before.get(name);
                    int p = places != null ? places : prev instanceof Double pd ? Math.min(8, Math.max(2, decimals(pd))) : 0;
                    row.put(name, prev instanceof Double || places != null ? (Object) round(d, p) : (Object) Math.round(d));
                } else {
                    row.put(name, v);
                }
            });
        }
        if (!sameBucket) {
            rows.add(row);
            while (rows.size() > keep) {
                rows.remove(0);
            }
        }
        at.set(rows);
    }

    static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
