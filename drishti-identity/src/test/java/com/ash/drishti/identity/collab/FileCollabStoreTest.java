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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.identity.IdentityProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The file stores pass the same contract as the database stores ({@link CollabStoreChecks}), and survive a restart. */
class FileCollabStoreTest {

    @Test
    void sharesFollowTheContract() throws Exception {
        Path dir = Files.createTempDirectory("collab-shares");
        CollabStoreChecks.shares(new FileShareStore(dir));
    }

    @Test
    void inboxFollowsTheContract() throws Exception {
        Path dir = Files.createTempDirectory("collab-inbox");
        CollabStoreChecks.inbox(new FileInboxStore(dir, 1000));
    }

    @Test
    void whatWasWrittenIsThereAfterARestart() throws Exception {
        Path dir = Files.createTempDirectory("collab-restart");
        FileShareStore shares = new FileShareStore(dir);
        FileInboxStore inbox = new FileInboxStore(dir, 1000);
        Share s = new Share(Ulid.next("sh_"), "ann", Instant.now(), "trade", "MX-1", null, null,
                new Pin(null, true, Instant.now(), 5, null), "hello", List.of(), "in-app", null, null).signed();
        shares.save(s, List.of(new Recipient("user:ravi", "ravi", Recipient.NOTIFIED, null)));
        shares.markOpened(s.id(), "ravi", Instant.now());
        Notice n = inbox.add(new Notice(0, "ravi", Instant.now(), "share", "trade", "MX-1", null, s.id(), null, null, "ann", null));
        inbox.markRead("ravi", List.of(n.seq()), Instant.now());
        inbox.add(new Notice(0, "ravi", Instant.now(), "share", "trade", "MX-2", null, null, null, null, "ann", null));

        FileShareStore shares2 = new FileShareStore(dir);
        FileInboxStore inbox2 = new FileInboxStore(dir, 1000);
        assertThat(shares2.find(s.id())).contains(s);
        assertThat(shares2.recipients(s.id()).get(0).openedAt()).isNotNull();
        assertThat(inbox2.unread("ravi")).isEqualTo(1);
        assertThat(inbox2.maxSeq()).isEqualTo(2);
        assertThat(inbox2.add(new Notice(0, "ravi", Instant.now(), "share", "trade", "MX-3", null, null, null, null, "ann", null)).seq())
                .isEqualTo(3);
    }

    @Test
    void aFileStoreIsRefusedBesideASharedDatabase() {
        CollabProperties file = new CollabProperties(null, "file", null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
        IdentityProperties postgres = new IdentityProperties(null, null, null, null, null, null, null, null, null, null, null, null, null,
                "jdbc:postgresql://db.invalid/x", null, null, null, null);
        assertThat(file.jpa()).isFalse();
        assertThatThrownBy(() -> com.ash.drishti.identity.IdentityConfiguration.requireSingleServerForFiles(file, postgres))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("shared database");
    }
}
