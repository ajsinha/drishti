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
package com.ash.drishti.engine;

import com.ash.drishti.api.DataNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Who a view is built for, passed with every call of the view pipeline and never kept anywhere shared (multi-user isolation: nothing
 * per person in a global, a cache key or a thread-local). The server makes one per request from the signed-in person.
 *
 * @param user the person's id, or null for a view built for nobody in particular (a warm-up, a tool): such a viewer reads no virtual table
 * @param roles the person's roles
 * @param attributes what a row rule may know of them ({@code user.name}, {@code user.roles}, {@code user.desk}, {@code user.desks},
 *     {@code user.email}), each a list
 * @param raw true when they hold the raw power (a column a table declares {@code masked} is then shown); without it such a column is
 *     masked, whatever the installation's own field masks are
 * @param redact their masks on a document (the identity when nothing is masked)
 * @param mayOpen the kinds they may open
 */
public record ViewerContext(String user, Set<String> roles, Map<String, List<Object>> attributes, boolean raw, UnaryOperator<DataNode> redact,
        Predicate<String> mayOpen) {

    /** Everything, unmasked, for nobody in particular: the pipeline's own reads (warm-ups, tests, tools). */
    public static final ViewerContext SYSTEM = new ViewerContext(null, Set.of(), Map.of(), true, UnaryOperator.identity(), k -> true);

    public ViewerContext {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        redact = redact == null ? UnaryOperator.identity() : redact;
        mayOpen = mayOpen == null ? k -> true : mayOpen;
    }

    /** A viewer known only by their masks and kinds (no person): what callers that name no person pass. */
    public static ViewerContext of(UnaryOperator<DataNode> redact, Predicate<String> mayOpen) {
        return new ViewerContext(null, Set.of(), Map.of(), false, redact, mayOpen);
    }

    /** True when a person is named (a virtual table is read only for a named person: its rules are theirs). */
    public boolean named() {
        return user != null;
    }
}
