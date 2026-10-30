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
package com.ash.drishti.api;

import java.lang.ref.WeakReference;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * The business dates an engine has open now (VaR, exposure), asked by the connectors before they expire a date's id map
 * ({@link IdMapFolder}, {@code id-map-disk.ttl}, 2.9.7). The engines register themselves here; the connectors only ask, so neither depends
 * on the other. An engine is held weakly: one that is gone (a closed test context) drops out by itself.
 */
public final class DatesInUse {

    private record Entry<T>(WeakReference<T> owner, Function<T, Collection<LocalDate>> dates) {
        Collection<LocalDate> now() {
            T o = owner.get();
            return o == null ? null : dates.apply(o);
        }
    }

    private static final List<Entry<?>> ENGINES = new CopyOnWriteArrayList<>();

    private DatesInUse() {
    }

    /**
     * Registers {@code owner}'s open dates, read through {@code dates} (which must not capture the owner: it is held weakly). Returns
     * what unregisters it.
     */
    public static <T> Runnable register(T owner, Function<T, Collection<LocalDate>> dates) {
        Entry<T> e = new Entry<>(new WeakReference<>(owner), dates);
        ENGINES.add(e);
        return () -> ENGINES.remove(e);
    }

    /** True when at least one engine says which dates it has open: a connector may then expire a mapped map of another date. */
    public static boolean known() {
        ENGINES.removeIf(e -> e.owner().get() == null);
        return !ENGINES.isEmpty();
    }

    /** Every date open in any engine now. An engine that cannot answer now is taken to hold every date: {@code null}. */
    public static Set<LocalDate> now() {
        Set<LocalDate> out = new HashSet<>();
        for (Entry<?> e : ENGINES) {
            try {
                Collection<LocalDate> d = e.now();
                if (d != null) {
                    out.addAll(d);
                }
            } catch (RuntimeException ex) {
                return null;                                            // unsure: nothing expires this round
            }
        }
        return out;
    }
}
