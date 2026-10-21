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
package com.ash.drishti.rachana.ui;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Where the board check finds chips: the Chip Store (P2 implements it over the content store), seed chips, or a folder for
 * the command line. Board-local chips are looked up in the board itself, not here.
 */
public interface ChipCatalog {

    /** The chip version, or empty when there is none. */
    Optional<Entry> find(ChipRef ref);

    /**
     * One chip version as the store holds it.
     *
     * @param doc the chip document
     * @param state its lifecycle state
     * @param newerCompatible a newer published version that claims {@code compatible-with} this one (BC-W2), or null
     */
    record Entry(ChipDoc doc, ChipState state, Integer newerCompatible) {}

    /** No chips. */
    static ChipCatalog empty() {
        return ref -> Optional.empty();
    }

    /** These chips, each in state {@code state}. */
    static ChipCatalog of(ChipState state, Collection<ChipDoc> chips) {
        Map<ChipRef, Entry> m = new LinkedHashMap<>();
        for (ChipDoc c : chips) {
            m.put(c.ref(), new Entry(c, state, null));
        }
        return of(m);
    }

    /** These entries. */
    static ChipCatalog of(Map<ChipRef, Entry> entries) {
        Map<ChipRef, Entry> copy = Map.copyOf(entries);
        return ref -> Optional.ofNullable(copy.get(ref));
    }

    /** This catalogue, then {@code next} for what it lacks. */
    default ChipCatalog then(ChipCatalog next) {
        return ref -> find(ref).or(() -> next.find(ref));
    }
}
