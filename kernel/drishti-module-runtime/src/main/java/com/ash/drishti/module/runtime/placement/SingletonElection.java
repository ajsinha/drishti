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
package com.ash.drishti.module.runtime.placement;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Who owns a one-runner part ({@code feed}, {@code singleton}; KERNEL_AND_MODULES.md section 4.11.3): the one seam placement elects through.
 * Placement tells it each part's candidates (the nodes the part is placed on) and asks for the owner; the owner is always one of the
 * candidates, so failover never leaves the placement. Today's implementation is {@link FirstLiveElection} (deterministic, on the gossip
 * membership); a consensus-based one (planned) replaces it without anything else changing.
 */
public interface SingletonElection {

    /** One part, as {@code module.part}. */
    static String key(String module, String part) {
        return module + "." + part;
    }

    /** The part's candidates now: the nodes it is placed on (the election group). An empty list leaves the part without an owner. */
    void candidates(String part, List<String> placed);

    /** The part's owner now: one of its candidates that is up, or empty (the part is unavailable). */
    Optional<String> owner(String part);

    /** Told {@code part} whenever its owner changes (failover, a new placement). */
    void onChange(Consumer<String> listener);
}
