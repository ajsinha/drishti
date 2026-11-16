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
package com.ash.drishti.rachana.about;

/**
 * Asks whether a field has a glossary entry for a kind. The integration point between pack lint (step 6) and the glossary
 * resolution of step 4: step 4's {@code GlossaryResolver} can implement this one method and replace
 * {@link CatalogGlossaryLookup}, which applies the same rule from the about catalogue alone (the kind's glossary, then the
 * shared vocabulary by the field's last name).
 */
@FunctionalInterface
public interface GlossaryLookup {

    /** A lookup that knows nothing, so every field is uncovered. */
    GlossaryLookup NONE = (kind, field) -> false;

    /**
     * @param kind the kind the Sutra is for
     * @param field a field path, dots between names, no sigil and no array steps ({@code legs.rate}, or {@code mtm} in a row)
     * @return true when the field resolves to an entry
     */
    boolean covers(String kind, String field);
}
