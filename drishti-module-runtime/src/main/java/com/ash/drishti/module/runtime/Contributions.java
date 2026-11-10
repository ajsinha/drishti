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
package com.ash.drishti.module.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The registry of every surface a module contributes (KERNEL_AND_MODULES.md section 12.3), so the unload sequence can withdraw each one
 * in the design's order. A host (the kernel for server parts, the console for console parts) records a surface when it adds it, with the
 * action that withdraws it; it never scans a module to find them. The absence check asks {@link #of(String)} and {@link #byLoader}.
 */
public final class Contributions {

    /** A kind of surface, with the unload step (section 12.2) that withdraws it. */
    public enum Surface {
        ROUTE(1), SPI(4), KIND_PROVIDER(4), CACHE(4), EVENT_SUBSCRIPTION(4), AI_TOOL(4), EXPORT(4), PYTHON_ITEM(4),
        PACK(5), KIND(5), SUTRA(5), FUNCTION(5), LENS(5), HOME_CARD(5), SEARCH(5), HELP(5), GLOSSARY(5), FEED(5), SCHEDULE(5),
        TOKEN_SCOPE(5), MENU(5), DESIGNER_PALETTE(5), TEMPLATES(6), STATIC(6), ELEMENTS(6), SETTINGS_PAGE(6), PYTHON_PATH(7), METRICS(9);

        private final int step;

        Surface(int step) {
            this.step = step;
        }

        /** The unload step that withdraws it. */
        public int step() {
            return step;
        }
    }

    /**
     * One surface.
     *
     * @param module   the module's id
     * @param surface  its kind
     * @param name     what it is, in words ({@code engine var-historical}, {@code /m/genomics/variants})
     * @param loader   the class loader of the object registered, or null for content
     * @param withdraw the action that removes it (idempotent)
     */
    public record Entry(String module, Surface surface, String name, ClassLoader loader, Runnable withdraw) {
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    /** Records a surface a module contributed. */
    public void add(String module, Surface surface, String name, ClassLoader loader, Runnable withdraw) {
        entries.add(new Entry(module, surface, name, loader, withdraw == null ? () -> { } : withdraw));
    }

    /** Every surface of a module. */
    public List<Entry> of(String module) {
        return entries.stream().filter(e -> e.module().equals(module)).toList();
    }

    /** Every surface whose registered object came from the class loader (the absence check: none after an unload). */
    public List<Entry> byLoader(ClassLoader loader) {
        return entries.stream().filter(e -> e.loader() == loader).toList();
    }

    /**
     * Withdraws a module's surfaces of one unload step, in the order they were added reversed (last in, first out); a failure is collected,
     * its entry kept for a retry, and the rest still go. Returns the failures, in words.
     */
    public List<String> withdraw(String module, int step) {
        List<String> failures = new ArrayList<>();
        List<Entry> mine = new ArrayList<>(entries.stream().filter(e -> e.module().equals(module) && e.surface().step() == step).toList());
        java.util.Collections.reverse(mine);
        for (Entry e : mine) {
            try {
                e.withdraw().run();
                entries.remove(e);
            } catch (RuntimeException ex) {
                failures.add(e.surface() + " " + e.name() + ": " + ex.getMessage());   // kept: the next unload retries it
            }
        }
        return failures;
    }
}
