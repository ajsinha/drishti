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

import java.util.Optional;

/** A {@link GlossaryLookup} over an {@link AboutSource}. A field in a row is known by its last name, so a key matches by suffix. */
public final class CatalogGlossaryLookup implements GlossaryLookup {

    private final AboutSource catalog;

    public CatalogGlossaryLookup(AboutSource catalog) {
        this.catalog = catalog;
    }

    @Override
    public boolean covers(String kind, String field) {
        Optional<AboutText> text = catalog.forKind(kind);
        if (text.isEmpty() || field == null || field.isBlank()) {
            return false;
        }
        String last = field.substring(field.lastIndexOf('.') + 1);
        AboutText t = text.get();
        for (String key : t.glossary().keySet()) {
            if (key.equals(field) || field.endsWith("." + key) || key.endsWith("." + field) || key.equals(last)) {
                return true;
            }
        }
        return t.vocabulary().containsKey(last) || catalog.core(last).isPresent();
    }
}
