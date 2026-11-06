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
package com.ash.drishti.rachana.el.check;

import java.util.List;
import java.util.Optional;

/**
 * What an expression may name, as the checker sees it (moved from DEL): the fields of a model (or of one table, for row and rule
 * scope), the table aliases (for {@code count(trades)}), the measures and the parameters. Built from a table or model as the caller
 * sees it, so a hidden column does not exist and a masked one is marked. Without one ({@link #NONE}), the checker checks the language
 * and the catalogue only and types every field as {@link ElType#ANY}.
 *
 * <p>A name reaches {@link #field} as its parts: {@code trades.MTM} is {@code [trades, MTM]}, a bare {@code DESK} is {@code [DESK]}, and
 * a path of the source starts with its root: {@code $.LEGS} is {@code [$, LEGS]}, {@code @.AMOUNT} {@code [@, AMOUNT]}, {@code ^.LEG_ID}
 * {@code [^, LEG_ID]}.
 */
public interface Fields {

    /** A field: its full path ({@code trades.MTM} or {@code trades.TRADE_DATE.month}), type, and whether it is masked for the caller. */
    record Field(String path, ElType type, boolean masked) {}

    /** The field the parts name; empty when there is none. */
    Optional<Field> field(List<String> parts);

    /** True when the name is a table alias of the model ({@code trades} in {@code count(trades)}). */
    boolean alias(String name);

    /** The measure a name or stable id names (its name); empty when there is none. */
    Optional<String> measure(String nameOrId);

    /** The names the caller may use, for "did you mean" suggestions. */
    default List<String> names() {
        return List.of();
    }

    /** True when names are checked (a table or model is known). */
    default boolean known() {
        return true;
    }

    /** The type of a parameter ({@code param.name}); empty when there is none. Without a model every name is accepted. */
    default Optional<ElType> param(String name) {
        return known() ? Optional.empty() : Optional.of(ElType.ANY);
    }

    /** The parameters' names, for the suggestion in a problem. */
    default List<String> params() {
        return List.of();
    }

    Fields NONE = new Fields() {
        @Override
        public Optional<Field> field(List<String> parts) {
            return Optional.of(new Field(String.join(".", parts), ElType.ANY, false));
        }

        @Override
        public boolean alias(String name) {
            return false;
        }

        @Override
        public Optional<String> measure(String nameOrId) {
            return Optional.of(nameOrId);
        }

        @Override
        public boolean known() {
            return false;
        }
    };
}
