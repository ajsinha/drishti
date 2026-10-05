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
package com.ash.drishti.identity.collab;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** What a legal hold covers: each scope, and the date range that narrows it. */
class HoldTest {

    private static final Instant D1 = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant D2 = Instant.parse("2026-09-10T00:00:00Z");
    private static final Instant D3 = Instant.parse("2026-09-20T00:00:00Z");

    private static Hold hold(String scope, String kind, String entity, String user, String thread, Instant from, Instant to) {
        return new Hold(1, scope, kind, entity, user, thread, from, to, "case", "carol", D1, null, null);
    }

    private static CommentThread thread(String id, String kind, String entity, Instant created, Instant last) {
        return new CommentThread(id, kind, entity, CommentThread.ENTITY, null, null, null, "view", CommentThread.OPEN, "ann", created, last, 1);
    }

    private static Share share(String kind, String entity, Instant at) {
        return new Share("sh_1", "ann", at, kind, entity, null, null, new Pin(null, true, at, 1, null), "x", List.of(), "in-app", null, null);
    }

    @Test
    void eachScopeCoversWhatItNames() {
        CommentThread t = thread("th_1", "trade", "MX-1", D1, D2);
        assertThat(hold(Hold.ALL, null, null, null, null, null, null).coversThread(t, Set.of())).isTrue();
        assertThat(hold(Hold.KIND, "trade", null, null, null, null, null).coversThread(t, Set.of())).isTrue();
        assertThat(hold(Hold.KIND, "gene", null, null, null, null, null).coversThread(t, Set.of())).isFalse();
        assertThat(hold(Hold.ENTITY, "trade", "MX-1", null, null, null, null).coversThread(t, Set.of())).isTrue();
        assertThat(hold(Hold.ENTITY, "trade", "MX-2", null, null, null, null).coversThread(t, Set.of())).isFalse();
        assertThat(hold(Hold.THREAD, null, null, null, "th_1", null, null).coversThread(t, Set.of())).isTrue();
        assertThat(hold(Hold.THREAD, null, null, null, "th_2", null, null).coversThread(t, Set.of())).isFalse();
        assertThat(hold(Hold.USER, null, null, "ravi", null, null, null).coversThread(t, Set.of("ann", "ravi"))).isTrue();
        assertThat(hold(Hold.USER, null, null, "sam", null, null, null).coversThread(t, Set.of("ann", "ravi"))).isFalse();
        Share s = share("trade", "MX-1", D2);
        assertThat(hold(Hold.ENTITY, "trade", "MX-1", null, null, null, null).coversShare(s, Set.of())).isTrue();
        assertThat(hold(Hold.USER, null, null, "ravi", null, null, null).coversShare(s, Set.of("ann", "ravi"))).isTrue();
        assertThat(hold(Hold.USER, null, null, "sam", null, null, null).coversShare(s, Set.of("ann", "ravi"))).isFalse();
    }

    @Test
    void aDateRangeNarrowsAHold() {
        Hold h = hold(Hold.ENTITY, "trade", "MX-1", null, null, D2, D3);
        assertThat(h.coversThread(thread("th_1", "trade", "MX-1", D1, D1.plusSeconds(5)), Set.of())).as("ended before the range").isFalse();
        assertThat(h.coversThread(thread("th_1", "trade", "MX-1", D1, D2.plusSeconds(5)), Set.of())).as("overlaps the start").isTrue();
        assertThat(h.coversThread(thread("th_1", "trade", "MX-1", D2, D2), Set.of())).isTrue();
        assertThat(h.coversThread(thread("th_1", "trade", "MX-1", D3.plusSeconds(5), D3.plusSeconds(9)), Set.of())).as("began after").isFalse();
        assertThat(h.coversShare(share("trade", "MX-1", D2.plusSeconds(1)), Set.of())).isTrue();
        assertThat(h.coversShare(share("trade", "MX-1", D1), Set.of())).isFalse();
        assertThat(h.coversShare(share("trade", "MX-1", D3.plusSeconds(1)), Set.of())).isFalse();
    }

    @Test
    void aReleasedHoldCoversNothing() {
        Hold h = hold(Hold.ALL, null, null, null, null, null, null).released("dave", D2);
        assertThat(h.active()).isFalse();
        assertThat(h.coversThread(thread("th_1", "trade", "MX-1", D1, D2), Set.of())).isFalse();
        assertThat(h.coversShare(share("trade", "MX-1", D1), Set.of())).isFalse();
    }
}
