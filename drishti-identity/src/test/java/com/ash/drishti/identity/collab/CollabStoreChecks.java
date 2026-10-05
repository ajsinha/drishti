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
import java.time.LocalDate;
import java.util.List;

/** What every {@link ShareStore} and {@link InboxStore} must do the same way: the file stores and the database stores both run this. */
public final class CollabStoreChecks {

    private CollabStoreChecks() {}

    private static Share share(String sender, String kind, String id, Instant at, String body) {
        return new Share(Ulid.next("sh_", at.toEpochMilli()), sender, at, kind, id, "cashflows", "trade",
                new Pin(LocalDate.of(2026, 9, 30), false, Instant.parse("2026-09-30T18:00:00Z"), 1674, "demo"), body,
                List.of(new Share.Span(4, 9)), "in-app", null, null).signed();
    }

    public static void shares(ShareStore s) {
        String u = "u" + System.nanoTime();
        Instant t0 = Instant.now().minusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Share a = share(u, "trade", "MX-1", t0, "see J. Smith here");
        Share b = share(u, "trade", "MX-2", t0.plusSeconds(10), "second");
        s.save(a, List.of(new Recipient("user:ravi", "ravi-" + u, Recipient.NOTIFIED, null),
                new Recipient("role:risk", "rng-" + u, Recipient.NO_ACCESS, null)));
        s.save(b, List.of(new Recipient("user:ravi", "ravi-" + u, Recipient.NOTIFIED, null)));

        Share back = s.find(a.id()).orElseThrow();
        assertThat(back).isEqualTo(a);
        assertThat(back.intact()).isTrue();
        assertThat(back.maskedSpans()).containsExactly(new Share.Span(4, 9));
        assertThat(back.pin().businessDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(back.pin().knownAt()).isEqualTo(Instant.parse("2026-09-30T18:00:00Z"));
        assertThat(back.pin().generation()).isEqualTo(1674);
        assertThat(s.find("sh_NOPE")).isEmpty();
        assertThat(s.recipients(a.id())).extracting(Recipient::username).containsExactly("ravi-" + u, "rng-" + u);
        assertThat(s.recipients(a.id())).extracting(Recipient::state).containsExactly(Recipient.NOTIFIED, Recipient.NO_ACCESS);

        assertThat(s.sent(u, 10, null)).extracting(Share::id).containsExactly(b.id(), a.id());
        assertThat(s.sent(u, 10, b.id())).extracting(Share::id).containsExactly(a.id());
        assertThat(s.received("ravi-" + u, 10, null)).extracting(Share::id).containsExactly(b.id(), a.id());
        assertThat(s.received("rng-" + u, 10, null)).as("a recipient who was not notified did not receive it").isEmpty();
        assertThat(s.countSentSince(u, t0.minusSeconds(1))).isEqualTo(2);
        assertThat(s.countSentSince(u, t0.plusSeconds(5))).isEqualTo(1);

        Instant open = Instant.now();
        assertThat(s.markOpened(a.id(), "ravi-" + u, open)).isTrue();
        assertThat(s.markOpened(a.id(), "ravi-" + u, open.plusSeconds(9))).as("only the first open counts").isFalse();
        assertThat(s.recipients(a.id()).get(0).openedAt()).isNotNull();
        assertThat(s.recipients(a.id()).get(1).openedAt()).isNull();
        Share tampered = new Share(a.id(), a.sender(), a.createdAt(), a.kind(), a.entityId(), a.panelId(), a.gateKind(), a.pin(), "other",
                a.maskedSpans(), a.channels(), a.threadId(), a.hash());
        assertThat(tampered.intact()).isFalse();
    }

    public static void inbox(InboxStore s) {
        String u = "inb" + System.nanoTime();
        Instant now = Instant.now();
        long before = s.maxSeq();
        Notice n1 = s.add(new Notice(0, u, now, "share", "trade", "MX-1", "cashflows", "sh_1", null, null, "ann", null));
        Notice n2 = s.add(new Notice(0, u, now.plusSeconds(1), "mention", "var", "V-1", null, null, "th_1", "cm_1", "bob", null));
        Notice n3 = s.add(new Notice(0, u, now.plusSeconds(2), "share", "trade", "MX-3", null, "sh_3", null, null, "ann", null));
        assertThat(n1.seq()).isGreaterThan(before);
        assertThat(n2.seq()).isGreaterThan(n1.seq());
        assertThat(s.maxSeq()).isEqualTo(n3.seq());

        assertThat(s.list(u, null, false, 10, 0)).extracting(Notice::seq).containsExactly(n3.seq(), n2.seq(), n1.seq());
        assertThat(s.list(u, "share", false, 10, 0)).extracting(Notice::seq).containsExactly(n3.seq(), n1.seq());
        assertThat(s.list(u, null, false, 10, n3.seq())).extracting(Notice::seq).containsExactly(n2.seq(), n1.seq());
        assertThat(s.list(u, null, false, 1, 0)).hasSize(1);
        assertThat(s.list("nobody-" + u, null, false, 10, 0)).isEmpty();
        assertThat(s.unread(u)).isEqualTo(3);
        assertThat(s.after(before, 100)).extracting(Notice::seq).contains(n1.seq(), n2.seq(), n3.seq());
        assertThat(s.after(n3.seq(), 100)).isEmpty();

        assertThat(s.markRead(u, List.of(n2.seq()), now)).isEqualTo(1);
        assertThat(s.markRead(u, List.of(n2.seq()), now)).as("already read").isZero();
        assertThat(s.unread(u)).isEqualTo(2);
        assertThat(s.list(u, null, true, 10, 0)).extracting(Notice::seq).containsExactly(n3.seq(), n1.seq());
        assertThat(s.markRead("someone-else", List.of(n1.seq()), now)).as("only the owner's rows").isZero();
        assertThat(s.markReadUpTo(u, n3.seq(), now)).isEqualTo(2);
        assertThat(s.unread(u)).isZero();
        assertThat(s.list(u, null, false, 10, 0)).allMatch(n -> n.readAt() != null);

        s.prune(u, 2);
        assertThat(s.list(u, null, false, 10, 0)).extracting(Notice::seq).containsExactly(n3.seq(), n2.seq());
        s.forget(u);
        assertThat(s.list(u, null, false, 10, 0)).isEmpty();
    }

    /** Comment threads: the thread, comments, immutable revisions chained per thread, mentions, followers and the note link. */
    public static void threads(ThreadStore s) {
        String u = "e" + System.nanoTime();
        Instant t0 = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Pin pin = new Pin(LocalDate.of(2026, 9, 30), false, Instant.parse("2026-09-30T18:00:00Z"), 1674, "demo");
        CommentThread th = new CommentThread(Ulid.next("th_", t0.toEpochMilli()), "trade", u, CommentThread.PANEL, "cashflows", null, "trade",
                "Cashflows", CommentThread.OPEN, "ann", t0, t0, 1);
        CommentThread other = new CommentThread(Ulid.next("th_", t0.toEpochMilli() + 5), "trade", u, CommentThread.ENTITY, null, null, null,
                "whole view", CommentThread.OPEN, "ravi", t0.plusSeconds(5), t0.plusSeconds(5), 0);
        s.saveThread(th);
        s.saveThread(other);
        assertThat(s.thread(th.id())).contains(th);
        assertThat(s.thread("th_NOPE")).isEmpty();
        assertThat(s.threads("trade", u)).extracting(CommentThread::id).containsExactly(other.id(), th.id());
        assertThat(s.threads("trade", u + "x")).isEmpty();

        Comment c1 = new Comment(Ulid.next("cm_", t0.toEpochMilli()), th.id(), "ann", t0, null, 1, pin, "see J. Smith @ravi @role-risk",
                List.of(new Share.Span(4, 12)), Comment.LIVE, null);
        Revision r1 = s.append(c1, Revision.draft(c1.id(), 1, t0, "ann", Revision.CREATED, c1.body(), null), List.of("user:ravi", "role:risk"));
        assertThat(r1.prevHash()).isEqualTo(HashChain.genesis(th.id()));
        assertThat(r1.hash()).hasSize(64);
        assertThat(s.comment(c1.id())).contains(c1);
        assertThat(s.comment(c1.id()).orElseThrow().pin()).isEqualTo(pin);
        assertThat(s.comment(c1.id()).orElseThrow().maskedSpans()).containsExactly(new Share.Span(4, 12));

        Comment c2 = new Comment(Ulid.next("cm_", t0.toEpochMilli() + 1000), th.id(), "ravi", t0.plusSeconds(1), null, 1, pin, "yes", List.of(),
                Comment.LIVE, null);
        Revision r2 = s.append(c2, Revision.draft(c2.id(), 1, t0.plusSeconds(1), "ravi", Revision.CREATED, "yes", null), List.of());
        assertThat(r2.prevHash()).as("the chain runs through the thread, across comments").isEqualTo(r1.hash());
        Comment c1b = c1.edited("see it @ravi", List.of(), t0.plusSeconds(2), 2);
        Revision r3 = s.append(c1b, Revision.draft(c1.id(), 2, t0.plusSeconds(2), "ann", Revision.EDITED, "see it @ravi", null), List.of("user:ravi"));
        assertThat(r3.prevHash()).isEqualTo(r2.hash());
        Revision same = s.append(c2, Revision.draft(c2.id(), 2, t0.plusSeconds(2), "ravi", Revision.RETRACTED, null, "mistake"), List.of());
        assertThat(same.at()).as("a step never shares its time with the one before").isAfter(r3.at());

        assertThat(s.comments(th.id())).extracting(Comment::id).containsExactly(c1.id(), c2.id());
        assertThat(s.comment(c1.id()).orElseThrow().body()).isEqualTo("see it @ravi");
        assertThat(s.revisions(c1.id())).extracting(Revision::action).containsExactly(Revision.CREATED, Revision.EDITED);
        assertThat(s.revisions(c1.id()).get(0).body()).as("the first text is kept").isEqualTo("see J. Smith @ravi @role-risk");
        assertThat(s.chain(th.id())).extracting(Revision::hash).containsExactly(r1.hash(), r2.hash(), r3.hash(), same.hash());
        assertThat(HashChain.verify(th.id(), s.chain(th.id()))).isNull();
        List<Revision> broken = new java.util.ArrayList<>(s.chain(th.id()));
        broken.set(1, new Revision(r2.commentId(), r2.revision(), r2.at(), r2.actor(), r2.action(), "changed", r2.reason(), r2.prevHash(), r2.hash()));
        assertThat(HashChain.verify(th.id(), broken)).contains("was changed");

        assertThat(s.mentions(c1.id())).extracting(Mention::target).containsExactlyInAnyOrder("user:ravi", "role:risk");
        assertThat(s.mentionsOf(List.of("user:ravi"), 10, null)).extracting(Mention::commentId).containsExactly(c1.id());
        assertThat(s.mentionsOf(List.of("user:ravi", "role:risk"), 10, c1.id())).isEmpty();
        assertThat(s.mentionsOf(List.of(), 10, null)).isEmpty();

        assertThat(s.following(th.id(), "ann")).isEmpty();
        s.follow(new Follow(th.id(), "ann", false, t0));
        s.follow(new Follow(th.id(), "ravi", false, t0));
        s.follow(new Follow(th.id(), "ravi", true, t0));
        assertThat(s.followers(th.id())).extracting(Follow::username).containsExactlyInAnyOrder("ann", "ravi");
        assertThat(s.following(th.id(), "ravi").orElseThrow().muted()).isTrue();
        assertThat(s.unfollow(th.id(), "ann")).isTrue();
        assertThat(s.unfollow(th.id(), "ann")).isFalse();

        CommentThread bumped = th.withActivity(t0.plusSeconds(9), 2).withState(CommentThread.RESOLVED);
        s.saveThread(bumped);
        assertThat(s.thread(th.id())).contains(bumped);
        assertThat(s.threads("trade", u)).extracting(CommentThread::id).containsExactly(th.id(), other.id());

        long note = System.nanoTime();
        assertThat(s.commentOfNote(note)).isEmpty();
        s.linkNote(note, c1.id());
        assertThat(s.commentOfNote(note)).contains(c1.id());
        assertThat(s.noteOfComment(c1.id())).contains(note);
        assertThat(s.linkedNotes()).contains(note);
    }
}
