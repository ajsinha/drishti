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
package com.ash.drishti.rachana;

import com.ash.drishti.rachana.model.Sutra;
import java.util.List;
import java.util.Optional;

/**
 * Why a Sutra was (or was not) chosen for a document: every Sutra of the kind in priority order with what its
 * {@code where} gave. The first {@link Result#TRUE} (or the first without a {@code where}) is the one
 * {@link SutraMatcher#match} returns. Holds Sutra source text and verdicts, never document values.
 *
 * @param chosen the Sutra {@code match} picks, empty when none holds (the view is inferred)
 * @param candidates every Sutra of the kind, highest priority first, the chosen one included
 */
public record MatchTrace(Optional<Sutra> chosen, List<Candidate> candidates) {

    /** What a {@code where} gave for the document. */
    public enum Result {
        /** No {@code where}, or it held. */
        TRUE,
        /** It did not hold. */
        FALSE,
        /** It could not be evaluated for this document. */
        ERROR,
        /** The answer depends on a field the caller may not see, so it is not told. */
        MASKED;

        public String text() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * @param sutra the candidate
     * @param result what its {@code where} gave
     */
    public record Candidate(Sutra sutra, Result result) {

        /** The {@code where} source text, or null when the Sutra has none. */
        public String where() {
            return sutra.match().where();
        }
    }

    public MatchTrace {
        candidates = List.copyOf(candidates);
    }
}
