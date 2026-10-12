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
                move(slot, n.doubleValue() + gauss.applyAsDouble(spec.asDouble()), spec.asDouble());
            }
            return;
        }
        double sd = group.get("step").asDouble();
        double scale = group.get("scale") instanceof DataNode.Val ? group.get("scale").asDouble() : 1;
        String shape = group.get("shape") instanceof DataNode.Val ? group.get("shape").asText() : "parallel";
        double jitter = group.get("jitter") instanceof DataNode.Val ? group.get("jitter").asDouble() : 0;
        double shift = gauss.applyAsDouble(sd);
        double tilt = "twist".equals(shape) ? gauss.applyAsDouble(sd * 0.6) : 0;
        Integer places = group.get("places") instanceof DataNode.Val ? (int) group.get("places").asDouble() : null;
        DemoPaths.Slot main = DemoPaths.first(doc, field);
        if (main != null && main.get() instanceof Number n) {
            double v = n.doubleValue() + shift;
            if (group.get("min") instanceof DataNode.Val) {
                v = Math.max(group.get("min").asDouble(), v);
            }
            if (group.get("max") instanceof DataNode.Val) {
                v = Math.min(group.get("max").asDouble(), v);
            }
            move(main, v, sd, places);
        }
        if (group.get("with") instanceof DataNode.Arr with) {
            for (DataNode w : with.elements()) {
                String path = w instanceof DataNode.Obj o ? o.get("path").asText() : w.asText();
                double s = w instanceof DataNode.Obj o && o.get("scale") instanceof DataNode.Val ? o.get("scale").asDouble() : scale;
                for (DemoPaths.Slot slot : DemoPaths.find(doc, path)) {
                    if (slot.get() instanceof Number n) {
                        double weight = slot.count() > 1 ? 2.0 * slot.pos() / (slot.count() - 1) - 1 : 0;
                        double delta = (shift + tilt * weight + (jitter > 0 ? gauss.applyAsDouble(sd * jitter) : 0)) * s;
                        move(slot, n.doubleValue() + delta, Math.abs(sd * s), places);
                    }
                }
            }
        }
    }

    private static void move(DemoPaths.Slot slot, double v, double sd) {
        move(slot, v, sd, null);
    }

    /** Stores {@code v} keeping the step's precision: an FX spot walking by 0.0005 must not round back to two decimals and freeze. */
    private static void move(DemoPaths.Slot slot, double v, double sd, Integer places) {
        int p = places != null ? places : sd <= 0 ? 2 : Math.max(2, (int) Math.ceil(-Math.log10(sd)) + 2);
        slot.set(sd >= 1 ? (Object) Math.round(v) : (Object) round(v, p));
    }

    // ---- derive ----------------------------------------------------------------------------------------------
    private void derive(Map<String, Object> doc, DataNode spec) {
        String set = spec.get("set").asText();
        String expr = spec.get("expr").asText();
        Integer places = spec.get("places") instanceof DataNode.Val ? (int) spec.get("places").asDouble() : null;
        for (DemoPaths.Slot slot : DemoPaths.find(doc, set)) {
            Object v = DemoExpr.eval(expr, new DemoExpr.Ctx(doc, slot.holder(), null, gauss));
            store(slot, v, places);
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
        boolean sameBucket = last != null && label.equals(String.valueOf(last.get(key)));
        Map<String, Object> row = sameBucket ? last : new LinkedHashMap<>();
        Map<String, Object> before = last == null ? Map.of() : new LinkedHashMap<>(last);
        row.put(key, label);
        if (spec.get("row") instanceof DataNode.Obj fields) {
            fields.fields().forEach((name, expr) -> {
                Object v = DemoExpr.eval(expr.asText(), new DemoExpr.Ctx(doc, null, sameBucket ? before : last, gauss));
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
