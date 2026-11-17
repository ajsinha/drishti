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
package com.ash.drishti.kernel.api.module;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The kernel's API (and every loaded module's), called again as the person of the request in hand (THIN_CONSOLE_DESIGN.md step S8a): a
 * module that works out figures from other answers (the trading and market-data landings) reads them exactly as that person would, with
 * their identity, roles, masks, row rules, lens and date box, and through the same doors (the same answers the console used to read).
 * A module never names another module's classes: it asks their API, as a client would.
 *
 * <p>{@link #caller()} is read on the request's own thread; the {@link Caller} it gives may then be used from any thread (it holds a copy
 * of what the request carried).
 */
public interface KernelCalls {

    /**
     * The person of the request being served on this thread.
     *
     * @throws IllegalStateException off a request
     */
    Caller caller();

    /**
     * A lens's file as the server reads it ({@code config/lenses/<module>/<lens>.yaml}): its arrangement and the blocks a module keeps
     * there (the trading landings' {@code trading:}, the quality landing's {@code quality:}). Empty for no such lens.
     */
    Map<String, Object> lens(String name);

    /**
     * A new {@link PersonCache} for what the module works out per person: at most {@code size} answers, each kept {@code ttlMillis}.
     * Every key holds the person's {@link Caller#key()}.
     */
    <V> PersonCache<V> cache(int size, long ttlMillis);

    /** One person's calls. Immutable. */
    interface Caller {

        /** The person's user name. */
        String user();

        /** Their display name as the identity store holds it ("" when it holds none). */
        String display();

        /** Whether the server's role definitions make them an administrator. */
        boolean admin();

        /** The lens they work in ("" for none). */
        String lens();

        /**
         * What tells one person's answers from another's and one view of the data from another (user, roles, lens, date box, known-at):
         * a cache keyed by it is never shared between people.
         */
        String key();

        /** The same person with their date box on another business date (the day before, a week ago). */
        Caller onDate(LocalDate day);

        /**
         * {@code GET /api/v1<path>} as the person; a JSON object's fields (an answer that is not an object comes as {@code {"value": …}}).
         *
         * @throws Refused the server's refusal, with its status, code and words
         */
        Map<String, Object> get(String path, Map<String, ?> params);

        /** {@code POST /api/v1<path>} with a JSON body, as the person; as {@link #get}. */
        Map<String, Object> post(String path, Object body, Map<String, ?> params);

        /**
         * The pivot engine's figures of {@code rows} ({@code POST /api/v1/pivot/inline}, as the person): the fields, the rows (a number as
         * {@link Answers#num} reads it, a member as text), the measures ({@link InlineFigures#measure}) and the arrangement (one level of
         * rows {@code by} and of columns {@code across}, either null; a top N or null). No rows: nothing is asked and every figure is null.
         *
         * @throws Refused the server's refusal (the pivot engine switched off among them)
         * @throws IllegalArgumentException more rows than the engine takes inline ({@link InlineFigures#MAX_ROWS})
         */
        InlineFigures.Figures figures(List<InlineFigures.Field> fields, List<List<Object>> rows, List<Map<String, Object>> values, String by,
                String across, Map<String, Object> top);
    }

    /**
     * The server's refusal of a call: its HTTP status, its code ({@code DRS-…}) and its words. The old name of
     * {@link com.ash.drishti.kernel.api.services.Kernel.Refused}, which holds all of it; deleted with {@code KernelCalls}.
     */
    final class Refused extends com.ash.drishti.kernel.api.services.Kernel.Refused {

        private static final long serialVersionUID = 1L;

        public Refused(int status, String code, String detail) {
            super(status, code, detail);
        }
    }
}
