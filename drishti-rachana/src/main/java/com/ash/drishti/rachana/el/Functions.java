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
package com.ash.drishti.rachana.el;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The one table of Rachana-EL functions, filled by the families: {@link LogicFunctions}, {@link MathFunctions},
 * {@link TextFunctions}, {@link DateFunctions} and {@link ListFunctions} (FUNCTION_CATALOGUE.md sections 1, 2, 3 and 6).
 * Every function is a scalar (one value from values): aggregates belong to the pivot engine (ONE_GRAMMAR.md), except the
 * list forms Rachana had before ({@code sum}, {@code min}, {@code max} over a list, {@code group}), which keep their names
 * and results until they move there. Functions are pure and cheap; an empty argument gives an empty result unless the
 * function says otherwise, a masked one a masked result, and a value of the wrong type (text where a number is needed)
 * raises {@code DRS-2102} naming the function and the value, which {@code iferror(...)} turns into a fallback.
 *
 * <p>Three kinds of entry: an <b>eager</b> function ({@link ElFunction}) gets its arguments evaluated; a <b>form</b>
 * ({@link ElForm}) gets them unevaluated, to evaluate lazily or once per list element with {@code @} bound
 * ({@code switch}, {@code iferror}, {@code map}, {@code reduce}, {@code sort}); a <b>syntax</b> entry is built by the
 * parser itself ({@code if}, {@code ifs}, {@code let}). An old spelling ({@code startsWith}, {@code days} …) is read for
 * existing Sutras and is the same entry as its one name.
 */
public final class Functions {

    /** Name to {min args, max args}, every kind of entry, old spellings included. */
    static final Map<String, int[]> ARITY;
    private static final Map<String, ElFunction> FUNCTIONS;
    private static final Map<String, ElForm> FORMS;
    private static final Set<String> SYNTAX;
    private static final Set<String> PASS_MASK;
    private static final Map<String, Function<List<Expr>, String>> CHECKS;
    private static final Map<String, String> OLD_SPELLINGS;

    static {
        Table t = new Table();
        LogicFunctions.register(t);
        MathFunctions.register(t);
        TextFunctions.register(t);
        DateFunctions.register(t);
        ListFunctions.register(t);
        ARITY = Collections.unmodifiableMap(t.arity);
        FUNCTIONS = Map.copyOf(t.functions);
        FORMS = Map.copyOf(t.forms);
        SYNTAX = Set.copyOf(t.syntax);
        PASS_MASK = Set.copyOf(t.passMask);
        CHECKS = Map.copyOf(t.checks);
        OLD_SPELLINGS = Collections.unmodifiableMap(new TreeMap<>(t.old));
    }

    private Functions() {}

    /** Every function of Rachana-EL with its argument count: {name: [min, max]}, old spellings included. */
    public static Map<String, int[]> arity() {
        return ARITY;
    }

    /**
     * The spellings read for compatibility with existing Sutras, each with the one name to write instead
     * ({@code startsWith} → {@code starts_with}); never offered for new work.
     */
    public static Map<String, String> oldSpellings() {
        return OLD_SPELLINGS;
    }

    static ElFunction get(String name) {
        return FUNCTIONS.get(name);
    }

    static ElForm form(String name) {
        return FORMS.get(name);
    }

    /** True for a name the parser builds itself ({@code if}, {@code ifs}, {@code let}). */
    static boolean syntax(String name) {
        return SYNTAX.contains(name);
    }

    /** True for a function given a masked argument as it is ({@code coalesce}, {@code nonblank}), not masked for it. */
    static boolean passesMask(String name) {
        return PASS_MASK.contains(name);
    }

    /** A problem with the written (literal) arguments of a call, found when it compiles; null when there is none. */
    static String check(String name, List<Expr> args) {
        Function<List<Expr>, String> c = CHECKS.get(name);
        return c == null ? null : c.apply(args);
    }

    /** The value of a literal argument, or {@link #NOT_LITERAL} for anything computed. */
    static Object literal(List<Expr> args, int i) {
        return i < args.size() && args.get(i) instanceof Expr.Lit lit ? lit.value() : NOT_LITERAL;
    }

    /** What {@link #literal} gives for an argument that is not written as a literal. */
    static final Object NOT_LITERAL = new Object();

    /** What the families register into; each name once. */
    static final class Table {
        private final Map<String, int[]> arity = new TreeMap<>();
        private final Map<String, ElFunction> functions = new HashMap<>();
        private final Map<String, ElForm> forms = new HashMap<>();
        private final Set<String> syntax = new HashSet<>();
        private final Set<String> passMask = new HashSet<>();
        private final Map<String, Function<List<Expr>, String>> checks = new HashMap<>();
        private final Map<String, String> old = new HashMap<>();

        private void name(String name, int min, int max) {
            if (arity.putIfAbsent(name, new int[] {min, max}) != null) {
                throw new IllegalStateException("Rachana-EL function " + name + " is registered twice");
            }
        }

        /** An eager function: its arguments evaluated, a masked one making the result masked. */
        Table fn(String name, int min, int max, ElFunction f) {
            name(name, min, max);
            functions.put(name, f);
            return this;
        }

        /** A form: its arguments unevaluated. */
        Table form(String name, int min, int max, ElForm f) {
            name(name, min, max);
            forms.put(name, f);
            return this;
        }

        /** A name the parser builds itself. */
        Table syntax(String name, int min, int max) {
            name(name, min, max);
            syntax.add(name);
            return this;
        }

        /** {@code name} sees a masked argument as it is (it decides itself what a mask means). */
        Table passesMask(String name) {
            passMask.add(name);
            return this;
        }

        /** A check of the literal arguments, run when an expression compiles. */
        Table check(String name, Function<List<Expr>, String> c) {
            checks.put(name, c);
            return this;
        }

        /** {@code oldName} read for compatibility: the same entry as {@code name}, with its own argument count. */
        Table old(String oldName, String name, int min, int max) {
            name(oldName, min, max);
            if (functions.containsKey(name)) {
                functions.put(oldName, functions.get(name));
            }
            if (checks.containsKey(name)) {
                checks.put(oldName, checks.get(name));
            }
            old.put(oldName, name);
            return this;
        }

        /** {@code oldName} read for compatibility with the results it always gave ({@code f}, sharing the one name's core). */
        Table old(String oldName, String name, int min, int max, ElFunction f) {
            name(oldName, min, max);
            functions.put(oldName, f);
            old.put(oldName, name);
            return this;
        }
    }
}
