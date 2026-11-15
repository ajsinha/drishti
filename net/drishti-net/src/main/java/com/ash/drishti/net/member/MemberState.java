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
package com.ash.drishti.net.member;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Where a member stands (CLUSTER_I5_DESIGN.md section 3.1): {@code alive} while it is in the agreed view; {@code suspect} while the
 * failure detector suspects it and the suspicion is being verified (it is still in the view); {@code dead} once it left the view without
 * saying so (a crash, a cut-off); {@code left} once it left cleanly (its last vitals said it was leaving, or it was drained out). Dead
 * and left members are remembered for {@code forget-after} for display and discovery, never routed to.
 */
public enum MemberState {
    ALIVE, SUSPECT, DEAD, LEFT;

    /** Gone: a member a request is never sent to and that is forgotten after {@code forget-after}. */
    public boolean gone() {
        return this == DEAD || this == LEFT;
    }

    /** The name as written in JSON and shown: {@code alive}, {@code suspect}, {@code dead}, {@code left}. */
    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Reads {@link #wire()}; anything else is {@code alive}'s opposite: a refused, unknown state counts as {@code suspect}. */
    @JsonCreator
    public static MemberState of(String text) {
        if (text == null) {
            return SUSPECT;
        }
        try {
            return valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SUSPECT;
        }
    }
}
