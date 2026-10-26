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

import com.ash.drishti.identity.collab.FileInboxStore;
import com.ash.drishti.identity.collab.Notice;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The live stream pushes new inbox rows only: opening the first stream after a quiet spell replays nothing already in the inbox. */
class InboxHubTest {

    private static Notice notice(String user, String entity) {
        return new Notice(0, user, Instant.now(), "bi-run", "bi-run", entity, null, null, null, null, "drishti", null);
    }

    private static void waitFor(List<?> got, int size) throws InterruptedException {
        long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (got.size() < size && System.nanoTime() < until) {
            Thread.sleep(20);
        }
    }

    @Test
    void theFirstStreamAfterAQuietSpellReceivesOnlyRowsWrittenAfterItOpened(@TempDir Path dir) throws InterruptedException {
        FileInboxStore store = new FileInboxStore(dir, 500);
        for (int i = 0; i < 30; i++) {
            store.add(notice("ann", "r-old-" + i));                       // finished runs from days ago, already in the inbox
        }
        try (InboxHub hub = new InboxHub(store, Duration.ofMillis(50))) {
            List<Notice> got = new CopyOnWriteArrayList<>();
            hub.listen("ann", got::add);
            Thread.sleep(400);                                             // several polls over the re-read window
            assertThat(got).isEmpty();

            store.add(notice("ann", "r-new"));                              // written by another server: found by the poll
            waitFor(got, 1);
            assertThat(got).extracting(Notice::entityId).containsExactly("r-new");

            hub.publish(store.add(notice("ann", "r-here")));                // written on this server: pushed at once, once
            waitFor(got, 2);
            Thread.sleep(200);
            assertThat(got).extracting(Notice::entityId).containsExactly("r-new", "r-here");
        }
    }
}
