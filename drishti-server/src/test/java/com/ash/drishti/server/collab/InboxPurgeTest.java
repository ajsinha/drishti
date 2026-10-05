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
package com.ash.drishti.server.collab;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.FileInboxStore;
import com.ash.drishti.identity.collab.Notice;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** {@code drishti.collab.inbox.keep-days}: rows older than it are removed by the scheduled purge (and by a manual run), newer ones stay. */
class InboxPurgeTest {

    @Test
    void rowsOlderThanKeepDaysAreRemoved() throws Exception {
        Instant now = Instant.parse("2026-10-05T09:00:00Z");
        FileInboxStore store = new FileInboxStore(Files.createTempDirectory("inbox-purge"), 1000);
        store.add(new Notice(0, "ravi", now.minus(Duration.ofDays(200)), "share", "trade", "MX-1", null, "sh_1", null, null, "ann", null));
        store.add(new Notice(0, "ravi", now.minus(Duration.ofDays(181)), "share", "trade", "MX-2", null, "sh_2", null, null, "ann", null));
        store.add(new Notice(0, "ravi", now.minus(Duration.ofDays(10)), "share", "trade", "MX-3", null, "sh_3", null, null, "ann", null));
        store.add(new Notice(0, "sam", now.minus(Duration.ofDays(300)), "mention", "trade", "MX-4", null, null, "th_1", "cm_1", "ann", null));
        CollabProperties props = new CollabProperties(true, null, null, null, null, null, null, null, null, null, null,
                new CollabProperties.Inbox(1000, 180, null, null), null, null, null, null, null, null, null);
        InboxPurge purge = new InboxPurge(store, props, Clock.fixed(now, ZoneOffset.UTC));
        assertThat(purge.run()).isEqualTo(3);
        assertThat(store.list("ravi", null, false, 10, 0)).extracting(Notice::entityId).containsExactly("MX-3");
        assertThat(store.list("sam", null, false, 10, 0)).isEmpty();
        assertThat(purge.run()).isZero();
    }

    @Test
    void theScheduleDoesNotStartWhenCollaborationIsOff() throws Exception {
        FileInboxStore store = new FileInboxStore(Files.createTempDirectory("inbox-purge-off"), 1000);
        CollabProperties off = new CollabProperties(false, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null);
        try (InboxPurge purge = new InboxPurge(store, off, Clock.systemUTC())) {
            purge.start();
        }
    }
}
