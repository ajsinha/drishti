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

import java.util.function.Supplier;

/**
 * What a module worked out for one person, kept a short while ({@link KernelCalls#cache}: at most a number of answers, each for a time,
 * the least recently used going first); one computation per key at a time (two panels asking for the same reads wait for one answer).
 * Every key must hold the person's {@link KernelCalls.Caller#key()}: nothing is shared between people (the session-isolation rule). The
 * kernel implements it; a module only uses it.
 *
 * @param <V> the answer
 */
public interface PersonCache<V> {

    /** The answer kept for {@code key} while it is fresh, else null. */
    V get(String key);

    /** Keeps an answer (null is not kept) and gives it back. */
    V put(String key, V value);

    /**
     * The fresh answer for {@code key}, or {@code make}'s, computed once however many ask at the same time and kept. A failure is not
     * kept: it reaches every waiter and the next call tries again.
     */
    V once(String key, Supplier<V> make);

    /** Drops everything kept. */
    void clear();
}
