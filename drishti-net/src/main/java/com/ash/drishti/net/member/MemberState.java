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
 * Where a member stands, as gossip spreads it. At equal incarnation the worse state wins a merge
 * ({@code left} &gt; {@code dead} &gt; {@code suspect} &gt; {@code alive}): news of a failure is never undone by an older
 * "alive", only by the member itself raising its incarnation.
 */
public enum MemberState {
    ALIVE(0), SUSPECT(1), DEAD(2), LEFT(3);

    private final int rank;

    MemberState(int rank) {
        this.rank = rank;
    }

    /** True when this state is worse than {@code other} (wins a merge at equal incarnation). */
    public boolean worseThan(MemberState other) {
        return rank > other.rank;
    }

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
