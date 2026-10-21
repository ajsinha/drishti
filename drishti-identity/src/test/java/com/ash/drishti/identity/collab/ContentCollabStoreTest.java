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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.content.FileContentStore;
import com.ash.drishti.identity.content.TxContentStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Collaboration in the content store (CONTENT_STORE.md section 9): the same contract as the database and file stores, and two
 * in-process nodes sharing one filesystem store root (a cluster on a shared mount).
 */
class ContentCollabStoreTest {

    private Path dir;
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        dir = Path.of("target", "content-tests", "collab-" + UUID.randomUUID()).toAbsolutePath();
        Files.createDirectories(dir);
        root = dir.resolve("fstore");
    }

    /** One node: its own view of the shared root. */
    private TxContentStore node() {
        return new TxContentStore(new FileContentStore(root));
    }

    private static CommentThread thread(String entity, Instant at) {
        return new CommentThread(Ulid.next("th_", at.toEpochMilli()), "trade", entity, CommentThread.ENTITY, null, null, null, "whole view",
                CommentThread.OPEN, "ann", at, at, 0);
    }

    private static Comment comment(String threadId, String author, String body, Instant at) {
        return new Comment(Ulid.next("cm_", at.toEpochMilli()), threadId, author, at, null, 1, new Pin(null, false, at, 0, null), body,
                List.of(), Comment.LIVE, null);
    }

    private static Revision append(ThreadStore s, Comment c) {
        return s.append(c, Revision.draft(c.id(), 1, c.createdAt(), c.author(), Revision.CREATED, c.body(), null), List.of("user:ravi"));
    }

    @Test
    void theStoresFollowTheContract() {
        TxContentStore s = node();
        CollabStoreChecks.shares(new ContentShareStore(s));
        CollabStoreChecks.inbox(new ContentInboxStore(s, 1000));
        CollabStoreChecks.outbox(new ContentOutboxStore(s));
        CollabStoreChecks.threads(new ContentThreadStore(s));
        CollabStoreChecks.holds(new ContentHoldStore(s));
    }

    @Test
    void removalAndPurgeFollowTheContract() {
        TxContentStore s = node();
        CollabStoreChecks.removal(new ContentThreadStore(s), new ContentShareStore(s));
        CollabStoreChecks.inboxPurge(new ContentInboxStore(node(), 1000));
    }

    @Test
    void aThreadWrittenOnOneNodeIsReadAndRepliedToOnAnother() {
        ContentThreadStore a = new ContentThreadStore(node());
        ContentThreadStore b = new ContentThreadStore(node());
        Instant t0 = Instant.now().minusSeconds(30);
        CommentThread t = thread("MX-1", t0);
        a.saveThread(t);
        Comment c1 = comment(t.id(), "ann", "first", t0);
        append(a, c1);
        assertThat(b.thread(t.id())).contains(t);
        assertThat(b.comment(c1.id())).contains(c1);
        assertThat(b.mentionsOf(List.of("user:ravi"), 10, null)).extracting(Mention::commentId).containsExactly(c1.id());
        Comment c2 = comment(t.id(), "ravi", "reply", t0.plusSeconds(1));
        append(b, c2);
        b.follow(new Follow(t.id(), "ravi", false, t0));
        assertThat(a.comments(t.id())).extracting(Comment::id).containsExactly(c1.id(), c2.id());
        assertThat(a.followers(t.id())).extracting(Follow::username).containsExactly("ravi");
        assertThat(HashChain.verify(t.id(), a.chain(t.id()))).isNull();
        assertThat(a.threads("trade", "MX-1")).extracting(CommentThread::id).containsExactly(t.id());
    }

    @Test
    void twoNodesChangingOneThreadInOneTransactionGiveOneConflictAndTheChainHolds() throws Exception {
        TxContentStore sa = node();
        ContentThreadStore a = new ContentThreadStore(sa);
        ContentThreadStore b = new ContentThreadStore(node());
        Instant t0 = Instant.now().minusSeconds(30);
        CommentThread t = thread("MX-2", t0);
        a.saveThread(t);
        Comment fromA = comment(t.id(), "ann", "from A", t0.plusSeconds(1));
        Comment fromB = comment(t.id(), "ravi", "from B", t0.plusSeconds(2));
        assertThatThrownBy(() -> CollabTx.of(sa).run(() -> {
            append(a, fromA);
            append(b, fromB);                       // the other node commits first, while A's transaction is open
            return null;
        })).isInstanceOf(DrishtiException.class).matches(e -> ((DrishtiException) e).errorCode() == ErrorCode.CONTENT_CONFLICT);
        assertThat(a.comments(t.id())).extracting(Comment::id).containsExactly(fromB.id());
        assertThat(a.comment(fromA.id())).as("nothing of the lost transaction is left, not even its index").isEmpty();
        assertThat(HashChain.verify(t.id(), a.chain(t.id()))).isNull();
    }

    @Test
    void concurrentRepliesFromTwoNodesAllLandOnOneUnbrokenChain() throws Exception {
        ContentThreadStore a = new ContentThreadStore(node());
        ContentThreadStore b = new ContentThreadStore(node());
        Instant t0 = Instant.now().minusSeconds(60);
        CommentThread t = thread("MX-3", t0);
        a.saveThread(t);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int n = 0; n < 2; n++) {
            ContentThreadStore s = n == 0 ? a : b;
            int node = n;
            fs.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < 5; i++) {
                    append(s, comment(t.id(), "u" + node, "n" + node + "-" + i, t0.plusMillis(node * 10L + i)));
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(a.comments(t.id())).hasSize(10);
        assertThat(a.chain(t.id())).hasSize(10);
        assertThat(HashChain.verify(t.id(), b.chain(t.id()))).isNull();
    }

    @Test
    void aCommitThatDiesHalfwayIsFinishedByAnyNode() {
        TxContentStore sa = node();
        ContentShareStore shares = new ContentShareStore(sa);
        ContentInboxStore inbox = new ContentInboxStore(sa, 1000);
        Share s = new Share(Ulid.next("sh_"), "ann", Instant.now(), "trade", "MX-4", null, null, new Pin(null, true, Instant.now(), 5, null),
                "hello", List.of(), "in-app", null, null).signed();
        sa.simulateCrashAfter(2);
        assertThatThrownBy(() -> CollabTx.of(sa).run(() -> {
            shares.save(s, List.of(new Recipient("user:ravi", "ravi", Recipient.NOTIFIED, null)));
            inbox.add(new Notice(0, "ravi", Instant.now(), "share", "trade", "MX-4", null, s.id(), null, null, "ann", null));
            return null;
        })).hasMessageContaining("simulated crash");
        assertThat(sa.list(TxContentStore.TX)).hasSize(1);
        TxContentStore b = new TxContentStore(new FileContentStore(root), 0);
        assertThat(b.recover()).isEqualTo(1);
        assertThat(new ContentShareStore(b).find(s.id())).isPresent();
        assertThat(new ContentShareStore(b).received("ravi", 10, null)).extracting(Share::id).containsExactly(s.id());
        assertThat(new ContentInboxStore(b, 1000).list("ravi", null, false, 10, 0)).extracting(Notice::shareId).containsExactly(s.id());
        assertThat(b.list(TxContentStore.TX)).isEmpty();
        assertThat(b.recover()).isZero();
    }

    @Test
    void aFailedTransactionWritesNothing() {
        TxContentStore sa = node();
        ContentShareStore shares = new ContentShareStore(sa);
        Share s = new Share(Ulid.next("sh_"), "ann", Instant.now(), "trade", "MX-5", null, null, new Pin(null, true, Instant.now(), 5, null),
                "hello", List.of(), "in-app", null, null).signed();
        assertThatThrownBy(() -> CollabTx.of(sa).run(() -> {
            shares.save(s, List.of(new Recipient("user:ravi", "ravi", Recipient.NOTIFIED, null)));
            throw new IllegalArgumentException("refused");
        })).hasMessage("refused");
        assertThat(new ContentShareStore(node()).find(s.id())).isEmpty();
        assertThat(sa.list("collab/")).isEmpty();
    }

    @Test
    void theOutboxDeliversOnceAcrossTwoNodes() throws Exception {
        ContentOutboxStore a = new ContentOutboxStore(node());
        ContentOutboxStore b = new ContentOutboxStore(node());
        Instant now = Instant.now();
        for (int i = 0; i < 30; i++) {
            (i % 2 == 0 ? a : b).add(OutboxItem.pending("email", "r" + i, "share", "sh_" + i, now.minusSeconds(1)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<List<Long>>> fs = new ArrayList<>();
        for (ContentOutboxStore s : List.of(a, b)) {
            fs.add(pool.submit(() -> {
                go.await();
                List<Long> mine = new ArrayList<>();
                for (List<OutboxItem> got; !(got = s.claim(Instant.now(), 4, Instant.now().plusSeconds(300), s == a ? "A" : "B")).isEmpty(); ) {
                    for (OutboxItem i : got) {
                        mine.add(i.seq());
                        s.sent(i.seq(), Instant.now());
                    }
                }
                return mine;
            }));
        }
        go.countDown();
        Set<Long> seen = new HashSet<>();
        int total = 0;
        for (Future<List<Long>> f : fs) {
            List<Long> mine = f.get(60, TimeUnit.SECONDS);
            total += mine.size();
            seen.addAll(mine);
        }
        pool.shutdown();
        assertThat(total).as("no delivery was claimed twice").isEqualTo(30);
        assertThat(seen).hasSize(30);
        assertThat(a.counts().get(OutboxItem.SENT)).isEqualTo(30L);
    }

    @Test
    void sequencesAreClusterWide() {
        ContentInboxStore a = new ContentInboxStore(node(), 1000);
        ContentInboxStore b = new ContentInboxStore(node(), 1000);
        Notice n1 = a.add(new Notice(0, "ravi", Instant.now(), "share", "trade", "MX-6", null, null, null, null, "ann", null));
        Notice n2 = b.add(new Notice(0, "mei", Instant.now(), "share", "trade", "MX-6", null, null, null, null, "ann", null));
        assertThat(n2.seq()).isGreaterThan(n1.seq());
        assertThat(a.after(0, 10)).extracting(Notice::seq).containsExactly(n1.seq(), n2.seq());
        assertThat(b.markRead("ravi", List.of(n1.seq()), Instant.now())).isEqualTo(1);
        assertThat(a.unread("ravi")).isZero();
        ContentHoldStore ha = new ContentHoldStore(node());
        ContentHoldStore hb = new ContentHoldStore(node());
        Hold h = ha.place(new Hold(0, Hold.ALL, null, null, null, null, null, null, "audit", "admin", Instant.now(), null, null));
        assertThat(hb.release(h.id(), "admin", Instant.now())).isTrue();
        assertThat(ha.release(h.id(), "admin", Instant.now())).isFalse();
    }

    @Test
    void theImportBringsTheOldFilesInOnceAndKeepsTheChain() throws Exception {
        Path old = dir.resolve("collab");
        FileThreadStore ft = new FileThreadStore(old);
        Instant t0 = Instant.now().minusSeconds(120);
        CommentThread t = thread("MX-7", t0);
        ft.saveThread(t);
        Comment c = comment(t.id(), "ann", "old note", t0);
        append(ft, c);
        ft.follow(new Follow(t.id(), "ann", false, t0));
        ft.linkNote(7, c.id());
        FileShareStore fs = new FileShareStore(old);
        Share s = new Share(Ulid.next("sh_"), "ann", Instant.now(), "trade", "MX-7", null, null, new Pin(null, true, Instant.now(), 5, null),
                "hello", List.of(), "in-app", null, null).signed();
        fs.save(s, List.of(new Recipient("user:ravi", "ravi", Recipient.NOTIFIED, null)));
        FileInboxStore fi = new FileInboxStore(old, 1000);
        Notice n = fi.add(new Notice(0, "ravi", Instant.now(), "share", "trade", "MX-7", null, s.id(), null, null, "ann", null));
        FileOutboxStore fo = new FileOutboxStore(old);
        OutboxItem o = fo.add(OutboxItem.pending("email", "ravi", "share", s.id(), Instant.now()));
        FileHoldStore fh = new FileHoldStore(old);
        Hold h = fh.place(new Hold(0, Hold.ALL, null, null, null, null, null, null, "audit", "admin", Instant.now(), null, null));
        FileSealStore seals = new FileSealStore(old);
        seals.put("thread:" + t.id(), new Seal(1, "abc"));
        CollabImport.Source src = new CollabImport.Source("file", new FileThreadStore(old), new FileShareStore(old), new FileInboxStore(old, 1000),
                new FileOutboxStore(old), new FileHoldStore(old), new FileSealStore(old));

        CollabImport.Target to = CollabImport.Target.of(node());
        assertThat(CollabImport.run(src, to)).isEqualTo(5);
        assertThat(CollabImport.run(src, to)).as("the marker: once").isZero();
        assertThat(CollabImport.run(src, CollabImport.Target.of(node()))).isZero();
        ContentThreadStore threads = new ContentThreadStore(node());
        assertThat(threads.chain(t.id())).isEqualTo(ft.chain(t.id()));
        assertThat(HashChain.verify(t.id(), threads.chain(t.id()))).isNull();
        assertThat(threads.commentOfNote(7)).contains(c.id());
        assertThat(threads.noteOfComment(c.id())).contains(7L);
        assertThat(threads.followers(t.id())).hasSize(1);
        assertThat(new ContentShareStore(node()).recipients(s.id())).hasSize(1);
        assertThat(new ContentInboxStore(node(), 1000).list("ravi", null, false, 10, 0)).extracting(Notice::seq).containsExactly(n.seq());
        assertThat(new ContentInboxStore(node(), 1000).add(new Notice(0, "ravi", Instant.now(), "share", "trade", "MX-7", null, null, null, null,
                "ann", null)).seq()).isGreaterThan(n.seq());
        assertThat(new ContentOutboxStore(node()).find(o.seq())).isPresent();
        assertThat(new ContentHoldStore(node()).place(h).id()).isGreaterThan(h.id());
        assertThat(new ContentSealStore(node()).get("thread:" + t.id())).contains(new Seal(1, "abc"));
        assertThat(Files.isDirectory(old.resolve("threads"))).as("the old files are kept").isTrue();
    }
}
