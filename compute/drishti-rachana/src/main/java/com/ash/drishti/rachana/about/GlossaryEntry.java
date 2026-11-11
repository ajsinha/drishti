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

import com.ash.drishti.rachana.model.SourceLocation;
import java.util.Map;

/**
 * What one field means, as a pack author wrote it. Plain text, never evaluated. Immutable.
 *
 * @param term the name of the thing
 * @param means one sentence of meaning
 * @param unit the unit, or null
 * @param sign the sign convention, or null
 * @param note anything else worth knowing, or null
 * @param formula how it is computed, shown as written, or null
 * @param values the meaning of each value of an enumerated field (empty otherwise)
 * @param use the vocabulary entry this one stands for (in a kind's glossary only), or null
 * @param origin where it came from, such as {@code market-risk:vocabulary.var99}
 * @param at where it was written
 */
public record GlossaryEntry(String term, String means, String unit, String sign, String note, String formula, Map<String, String> values,
        String use, String origin, SourceLocation at) {

    public GlossaryEntry {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    /** What a kind's own definition says of a field (a derived kind's formula), as an entry. */
    public static GlossaryEntry derived(String key, String means, String formula, String origin) {
        return new GlossaryEntry(key, means, null, null, null, formula, Map.of(), null, origin, null);
    }

    /** This entry taking the content of a vocabulary entry it names with {@code use}. */
    GlossaryEntry resolvedFrom(GlossaryEntry vocabulary) {
        return new GlossaryEntry(vocabulary.term, vocabulary.means, vocabulary.unit, vocabulary.sign, vocabulary.note, vocabulary.formula,
                vocabulary.values, use, vocabulary.origin, at);
    }

    GlossaryEntry from(String originName) {
        return new GlossaryEntry(term, means, unit, sign, note, formula, values, use, originName, at);
    }
}
