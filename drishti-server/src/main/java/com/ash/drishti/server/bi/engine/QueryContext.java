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
package com.ash.drishti.server.bi.engine;

import java.util.function.Supplier;

/**
 * What the work on this thread is (wave B, docs/architecture/BI_DESIGN.md sections 6.3 and 6.7): its work class (who waits for whom), and the budget of the
 * question that asked (rows read, milliseconds: {@code options.budget}). Set around a model query, a standing query's maintenance, a refresh; read by the
 * engine's admission, its cost estimate and its time limit. A thread's own context, never shared between requests.
 */
public final class QueryContext {

    /** Who waits for whom: an interactive question before a standing query's maintenance before background work. */
    public enum WorkClass {
        INTERACTIVE, STANDING, BACKGROUND
    }

    /** The context of one piece of work. */
    public record Ctx(WorkClass workClass, Long budgetRows, Long budgetMillis) {
        public static final Ctx INTERACTIVE = new Ctx(WorkClass.INTERACTIVE, null, null);
    }

    private static final ThreadLocal<Ctx> CURRENT = new ThreadLocal<>();

    private QueryContext() {}

    /** The context of this thread (interactive when nobody set one). */
    public static Ctx current() {
        Ctx c = CURRENT.get();
        return c == null ? Ctx.INTERACTIVE : c;
    }

    /** Runs {@code work} in {@code ctx}, restoring the context before it. */
    public static <T> T with(Ctx ctx, Supplier<T> work) {
        Ctx before = CURRENT.get();
        CURRENT.set(ctx);
        try {
            return work.get();
        } finally {
            if (before == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(before);
            }
        }
    }
}
