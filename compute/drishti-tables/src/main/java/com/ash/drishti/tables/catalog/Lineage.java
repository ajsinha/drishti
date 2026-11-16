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
package com.ash.drishti.tables.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Who uses a table or a model (docs/architecture/ARCHITECTURE.md section 8): the catalogue's own uses ({@link Catalogue#usedBy}) and every consumer outside
 * the layer that registers a {@link Consumers} source: BI's reports, boards, alerts and subscriptions (their MQs name models), the pivot's
 * saved views, Flight. The layer never reads those consumers' types: they answer in names, so BI depends on the layer and not the
 * reverse.
 */
public final class Lineage {

    /**
     * One use.
     *
     * @param kind what uses it ({@code model}, {@code table}, {@code report}, {@code board}, {@code alert}, {@code view}, ...)
     * @param name its name or id
     * @param how in words ({@code reads it as trades (fact)})
     */
    public record Use(String kind, String name, String how) {}

    /** A consumer outside the layer: what of its items use a table or a model. */
    public interface Consumers {
        /** The uses of {@code name} (a table's or a model's name). */
        List<Use> uses(String name);
    }

    private final Catalogue catalogue;
    private final List<Consumers> consumers = new CopyOnWriteArrayList<>();

    public Lineage(Catalogue catalogue) {
        this.catalogue = catalogue;
    }

    /** Registers a consumer (BI does at start, once the switch lands). */
    public void register(Consumers c) {
        consumers.add(c);
    }

    /** Every use of {@code name}: the catalogue's first, then each consumer's. */
    public List<Use> of(String name) {
        List<Use> out = new ArrayList<>(catalogue.usedBy(name));
        for (Consumers c : consumers) {
            out.addAll(c.uses(name));
        }
        return out;
    }
}
