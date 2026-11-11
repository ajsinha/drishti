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
package com.ash.drishti.kernel.api.data;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Answers a kind a module computes (docs/architecture/KERNEL_AND_MODULES.md sections 2.3 and 5.7): {@code position} from the trades,
 * {@code system-basket} from the hierarchies. A computed kind is never bound to a connector: the kernel serves it from its provider, always
 * the module's, and shows it as {@link BindingState#COMPUTED}. Replaces the modules' source plugins; found with the
 * {@link java.util.ServiceLoader}.
 */
public interface KindProvider {

    /** The provider's name, unique in the kernel ({@code qc-samples}): the source a document's provenance names. */
    String name();

    /** The kinds it computes. */
    Set<String> kinds();

    /** One entity on a business date ({@link AsOf#LATEST}: the newest it holds). */
    Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf);

    /** Every entity of the kind on the date, to {@code sink}; false when the kind is not this provider's. */
    boolean documents(String kind, AsOf asOf, Consumer<EntityDocument> sink);

    /** Entities whose id contains {@code text}; by default a scan of {@link #documents}. */
    default List<EntityHit> search(String kind, String text, int limit, AsOf asOf) {
        List<EntityHit> out = new ArrayList<>();
        String q = text == null ? "" : text.trim().toUpperCase(Locale.ROOT);
        documents(kind, asOf, d -> {
            if (out.size() < limit && (q.isEmpty() || d.ref().id().toUpperCase(Locale.ROOT).contains(q))) {
                out.add(new EntityHit(d.ref(), d.ref().id(), ""));
            }
        });
        return out;
    }

    /** The newest business date it can answer. */
    default Optional<LocalDate> latestDate(String kind) {
        return Optional.empty();
    }

    /** A token that changes when the kind's answer on {@code date} changes (caches follow it). */
    default Optional<String> dataVersion(String kind, LocalDate date) {
        return Optional.empty();
    }

    /** True when a load of {@code kind} makes its answers stale (one of the kinds it is computed from). */
    default boolean dependsOn(String kind) {
        return false;
    }

    /** Its figures for Health and Admin → Caches. */
    default Map<String, Object> stats() {
        return Map.of();
    }
}
