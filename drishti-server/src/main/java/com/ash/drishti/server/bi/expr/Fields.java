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
package com.ash.drishti.server.bi.expr;

import java.util.List;
import java.util.Optional;

/**
 * What an expression may name, as the checker sees it: the fields of a model (or of one dataset, for row and rule scope), the dataset aliases (for
 * {@code count(trades)}), and the measures. Built from a model or a dataset as the caller sees it, so a hidden column does not exist and a masked one is marked.
 * Without one ({@link #NONE}), the checker checks the language and the catalogue only and types every field as {@link DelType#ANY}.
 */
public interface Fields {

    /** A field: its full path ({@code trades.mtm} or {@code trades.tradeDate.month}), type, and whether it is masked for the caller. */
    record Field(String path, DelType type, boolean masked) {}

    /** The field the parts name ({@code alias.column[.part]}, or a bare {@code column} of the dataset in row and rule scope); empty when there is none. */
    Optional<Field> field(List<String> parts);

    /** True when the name is a dataset alias of the model ({@code trades} in {@code count(trades)}). */
    boolean alias(String name);

    /** The measure a name or stable id names (its name); empty when there is none. */
    Optional<String> measure(String nameOrId);

    /** The names the caller may use, for "did you mean" suggestions. */
    default List<String> names() {
        return List.of();
    }

    /** True when names are checked (a model or dataset is known). */
    default boolean known() {
        return true;
    }

    Fields NONE = new Fields() {
        @Override
        public Optional<Field> field(List<String> parts) {
            return Optional.of(new Field(String.join(".", parts), DelType.ANY, false));
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
