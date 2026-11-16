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
package com.ash.drishti.banking;

import com.ash.drishti.kernel.api.vocabulary.DomainVocabulary;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Banking's words for the kernel's generic code. */
public final class BankingVocabulary implements DomainVocabulary {

    @Override
    public Set<String> reservedFunctionNames() {
        return Set.of("var", "es", "svar", "stressed_var", "expected_shortfall", "frtb", "frtb_sa", "simm", "saccr", "sa_ccr", "ead", "pfe", "ee",
                "epe", "eepe", "exposure", "cva", "irc", "drc", "rrao", "ima", "official");
    }

    @Override
    public List<String> phraseMeasures() {
        return List.of("exposure");
    }

    @Override
    public Set<String> namedLakeWriters() {
        return Set.of("var-pv-stream");
    }

    @Override
    public Map<String, String> engineDependencyNotes() {
        return Map.of("frtb-sa", ": its scopes (desks, books, limit letters) are VaR's and its inputs are the position sensitivities",
                "simm", ": it reads the position sensitivities and the factor dictionary");
    }

    @Override
    public boolean diversifies(String n, int inputs) {
        return n.equals("var") || n.equals("es") || n.startsWith("var") && inputs == 0;
    }
}
