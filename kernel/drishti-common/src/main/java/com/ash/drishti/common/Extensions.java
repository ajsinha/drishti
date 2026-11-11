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
package com.ash.drishti.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The live contributions of loaded modules (docs/architecture/KERNEL_AND_MODULES.md section 12.3): the objects a module adds to one of the
 * kernel's extension types (a health figure, an explainer, an approval source, a vocabulary ...) while it is loaded. Each collection point of
 * the kernel reads its fixed set (its beans, its {@link java.util.ServiceLoader} finds) and then this one, so a module's contribution is
 * seen the moment it is added and gone the moment it is withdrawn. Only the kernel's module host adds; the action it gets back withdraws.
 * Nothing per person is held here.
 */
public final class Extensions {

    private record Entry(Class<?> type, String owner, Object value) {
    }

    private static final List<Entry> LIVE = new CopyOnWriteArrayList<>();
    private static final AtomicLong VERSION = new AtomicLong();

    private Extensions() {
    }

    /** Adds a module's object of an extension type; the returned action withdraws it (idempotent). */
    public static <T> Runnable add(Class<T> type, String owner, T value) {
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException(value + " is not a " + type.getName());
        }
        Entry e = new Entry(type, owner, value);
        LIVE.add(e);
        VERSION.incrementAndGet();
        AtomicBoolean done = new AtomicBoolean();
        return () -> {
            if (done.compareAndSet(false, true) && LIVE.remove(e)) {
                VERSION.incrementAndGet();
            }
        };
    }

    /** The live objects of a type, in the order they were added. */
    public static <T> List<T> of(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Entry e : LIVE) {
            if (e.type() == type) {
                out.add(type.cast(e.value()));
            }
        }
        return out;
    }

    /** The fixed set followed by the live objects of the type: what a collection point iterates. */
    public static <T> List<T> with(Collection<? extends T> fixed, Class<T> type) {
        List<T> live = of(type);
        if (live.isEmpty()) {
            return fixed == null ? List.of() : List.copyOf(fixed);
        }
        List<T> out = new ArrayList<>(fixed == null ? List.of() : fixed);
        out.addAll(live);
        return out;
    }

    /** Every live object's owner and type, in words ({@code demo: HealthFigures}); one owner's, or every one's for null. */
    public static List<String> describe(String owner) {
        List<String> out = new ArrayList<>();
        for (Entry e : LIVE) {
            if (owner == null || owner.equals(e.owner())) {
                out.add(e.owner() + ": " + e.type().getSimpleName());
            }
        }
        return out;
    }

    /** A counter that changes whenever a contribution is added or withdrawn (caches over the live set follow it). */
    public static long version() {
        return VERSION.get();
    }
}
