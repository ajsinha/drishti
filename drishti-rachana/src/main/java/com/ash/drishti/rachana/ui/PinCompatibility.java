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
package com.ash.drishti.rachana.ui;

import com.ash.drishti.rachana.ui.BoardDoc.Transform;
import com.ash.drishti.rachana.ui.BoardDoc.TransformKind;
import com.ash.drishti.rachana.ui.PinSpec.ColumnSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * May this source feed this pin? The compatibility table of UI_BOARD.md 4.2, decided from the declared types only, never
 * from data. A source is a supplier attribute (after the map), an out pin (through a trace) or a board parameter.
 */
public final class PinCompatibility {

    /** No kind is a subtype of another unless a pack says so. */
    public static final BiPredicate<String, String> NO_SUBTYPES = (k, j) -> false;

    private PinCompatibility() {}

    /** Yes, yes with a warning, or no. */
    public enum Verdict { YES, WARN, NO }

    /**
     * The answer.
     *
     * @param verdict yes, warn or no
     * @param rule the warning's rule ({@code BC-W1}) or {@code BC-04} for a no; null for a plain yes
     * @param reason why (null for a plain yes)
     * @param fix the fix, or null
     */
    public record Result(Verdict verdict, String rule, String reason, String fix) {

        static final Result YES = new Result(Verdict.YES, null, null, null);

        static Result no(String reason, String fix) {
            return new Result(Verdict.NO, "BC-04", reason, fix);
        }

        static Result warn(String reason) {
            return new Result(Verdict.WARN, "BC-W1", reason, null);
        }

        public boolean ok() {
            return verdict != Verdict.NO;
        }
    }

    /** {@link #check(TypeRef, TypeRef, Transform, BiPredicate)} with no transform and no subtypes. */
    public static Result check(TypeRef source, TypeRef pin) {
        return check(source, pin, null, NO_SUBTYPES);
    }

    /**
     * May {@code source} feed {@code pin} (through {@code transform}, null for none)? {@code subtype.test(k, j)} says whether
     * the pack declares kind K a subtype of kind J. A rowset against a rowset is decided by {@link #checkShape}.
     */
    public static Result check(TypeRef source, TypeRef pin, Transform transform, BiPredicate<String, String> subtype) {
        PinType s = source.type();
        PinType t = pin.type();
        if (transform != null) {
            if (transform.kind() == TransformKind.FIRST) {
                if (s != PinType.SELECTION) {
                    return Result.no("'transform: first' takes a selection, not " + s.yamlName(), "remove the transform");
                }
                return check(TypeRef.of(PinType.ID, source.of()), pin, null, subtype);
            }
            if (transform.kind() == TransformKind.DATE) {
                if (s != PinType.DATETIME) {
                    return Result.no("'transform: date' takes a datetime, not " + s.yamlName(), "remove the transform");
                }
                return check(TypeRef.of(PinType.DATE), pin, null, subtype);
            }
            if (s != PinType.ID && s != PinType.SELECTION && s != PinType.PERIOD) {
                return Result.no("'filter-on' takes an id, a selection or a period, not " + s.yamlName(), null);
            }
            return t == PinType.FILTER ? Result.YES : Result.no("'filter-on' makes a filter; the pin takes " + t.yamlName(), "remove the transform");
        }
        if (t == PinType.FILTER && (s == PinType.ID || s == PinType.SELECTION || s == PinType.PERIOD)) {
            return Result.no("an " + s.yamlName() + " becomes a filter only through a transform", "add transform: { filter-on: <pin>.<column> }");
        }
        if (s == t) {
            return refinements(source, pin, subtype);
        }
        return switch (s) {
            case ID -> t == PinType.SELECTION ? kinds(source.of(), pin.of(), subtype)
                    : t == PinType.DIMENSION ? Result.YES : no(source, pin, null);
            case SELECTION -> t == PinType.ID ? Result.no("a selection is a set of ids; the pin takes one", "add transform: first") : no(source, pin, null);
            case DATE -> t == PinType.DATETIME || t == PinType.PERIOD || t == PinType.DIMENSION ? Result.YES : no(source, pin, null);
            case DATETIME -> t == PinType.DATE ? Result.no("a datetime is an instant; the pin takes a date", "add transform: date")
                    : t == PinType.DIMENSION ? Result.YES : no(source, pin, null);
            case MEASURE -> t == PinType.NUMBER || t == PinType.DIMENSION ? Result.YES : no(source, pin, null);
            case NUMBER -> t == PinType.MEASURE
                    ? (pin.ccy() != null && !"any".equals(pin.ccy()) ? Result.warn("a plain number feeds a measure in " + pin.ccy() + "; its currency is unknown") : Result.YES)
                    : t == PinType.DIMENSION ? Result.YES : no(source, pin, null);
            case DIMENSION -> t == PinType.TEXT ? Result.YES : no(source, pin, null);
            case DOCUMENT -> t == PinType.ROWSET ? Result.no("a document feeds rows only through the map", "map <pin>: <path of an array in the document>")
                    : no(source, pin, null);
            default -> t == PinType.DIMENSION && s.scalar() ? Result.YES : no(source, pin, null);
        };
    }

    private static Result refinements(TypeRef source, TypeRef pin, BiPredicate<String, String> subtype) {
        switch (source.type()) {
            case ID, SELECTION, DOCUMENT -> {
                return kinds(source.of(), pin.of(), subtype);
            }
            case MEASURE -> {
                if (pin.ccy() == null || "any".equals(pin.ccy()) || pin.ccy().equals(source.ccy())) {
                    return Result.YES;
                }
                if (source.ccy() == null) {
                    return Result.warn("the measure's currency is unknown; the pin wants " + pin.ccy());
                }
                return Result.no("a measure in " + source.ccy() + " cannot feed a measure in " + pin.ccy() + " (never summed across currencies)",
                        "convert in the supplier, or declare the pin ccy: any");
            }
            case ENUM -> {
                if (!pin.values().isEmpty() && !source.values().isEmpty() && !pin.values().containsAll(source.values())) {
                    List<String> extra = new ArrayList<>(source.values());
                    extra.removeAll(pin.values());
                    return Result.no("the source may give " + extra + ", which the pin does not take", null);
                }
                return Result.YES;
            }
            default -> {
                return Result.YES;
            }
        }
    }

    private static Result kinds(String k, String j, BiPredicate<String, String> subtype) {
        if (j == null || j.equals(k)) {
            return Result.YES;
        }
        if (k == null) {
            return Result.warn("an id without a kind feeds an id of " + j + "; the link cannot be checked");
        }
        return subtype.test(k, j) ? Result.YES : Result.no("an id of " + k + " cannot feed an id of " + j, "feed it an id of " + j);
    }

    private static Result no(TypeRef source, TypeRef pin, String fix) {
        return Result.no("a " + source.type().yamlName() + " cannot feed a " + pin.type().yamlName() + " pin", fix);
    }

    /**
     * Rowset S to rowset P: every required column of P must come from a column of S whose type is compatible; optional
     * columns may be absent; extra columns of S are ignored. Answers the problems (empty when compatible), each a message.
     */
    public static List<String> checkShape(Map<String, TypeRef> source, Map<String, ColumnSpec> pin, BiPredicate<String, String> subtype) {
        List<String> out = new ArrayList<>();
        for (ColumnSpec c : pin.values()) {
            TypeRef s = source.get(c.name());
            if (s == null) {
                if (c.required()) {
                    out.add("the rows have no column '" + c.name() + "', which the pin requires");
                }
                continue;
            }
            Result r = check(s, c.type(), null, subtype);
            if (!r.ok()) {
                out.add("column '" + c.name() + "': " + r.reason());
            }
        }
        return out;
    }
}
