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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.identity.collab.Share.Span;
import com.ash.drishti.identity.collab.Ulid;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The small pieces of collaboration that need no server: rate windows, masked-value spans, ids. */
class CollabUnitTest {

    @Test
    void aWindowAllowsTheLimitThenRefusesWithTheTimeUntilTheOldestHitLeaves() {
        AtomicLong clock = new AtomicLong();
        RateLimits limits = new RateLimits(clock::get);
        limits.hit("share", "ann", 2, Duration.ofMinutes(1), "shares");
        clock.addAndGet(Duration.ofSeconds(10).toNanos());
        limits.hit("share", "ann", 2, Duration.ofMinutes(1), "shares");
        assertThatThrownBy(() -> limits.hit("share", "ann", 2, Duration.ofMinutes(1), "shares")).isInstanceOfSatisfying(RateLimitedException.class,
                e -> assertThat(e.retryAfterSeconds()).isEqualTo(50));
        limits.hit("share", "bob", 2, Duration.ofMinutes(1), "shares");        // per user
        limits.hit("comment", "ann", 2, Duration.ofMinutes(1), "comments");    // per bucket
        clock.addAndGet(Duration.ofSeconds(51).toNanos());
        limits.hit("share", "ann", 2, Duration.ofMinutes(1), "shares");        // the first hit has left the window
    }

    @Test
    void spansFindExactCopiesLongestFirstAndMergeOverlaps() {
        String text = "J. Smith and J. Smithson agreed; J. Smith left";
        List<Span> spans = NoteText.spans(text, List.of("J. Smithson", "J. Smith"));
        assertThat(spans).containsExactly(new Span(0, 8), new Span(13, 24), new Span(33, 41));
        assertThat(NoteText.render(text, spans, true)).isEqualTo("••• and ••• agreed; ••• left");
        assertThat(NoteText.render(text, spans, false)).isEqualTo(text);
        assertThat(NoteText.spans("j. smith", List.of("J. Smith"))).as("a case variant is a copy too (QA S3-07)").containsExactly(new Span(0, 8));
        assertThat(NoteText.spans("abc", List.of())).isEmpty();
        assertThat(NoteText.render("ab", List.of(new Span(0, 99)), true)).as("a span past the end is clipped").isEqualTo("•••");
        assertThat(NoteText.excerpt("x".repeat(200), 10)).hasSize(10).endsWith("…");
    }

    @Test
    void idsAreTimeOrderedUniqueAndValidated() {
        long t = Instant.parse("2026-09-30T10:00:00Z").toEpochMilli();
        String a = Ulid.next("sh_", t);
        String b = Ulid.next("sh_", t + 1000);
        assertThat(a).hasSize(29).startsWith("sh_");
        assertThat(a).isNotEqualTo(Ulid.next("sh_", t));
        assertThat(b).isGreaterThan(a);
        assertThat(Ulid.valid(a, "sh_")).isTrue();
        assertThat(Ulid.valid(a, "th_")).isFalse();
        assertThat(Ulid.valid("sh_lowercase0000000000000000", "sh_")).isFalse();
        assertThat(Ulid.timeOf(a, "sh_").toEpochMilli()).isEqualTo(t);
    }

    @Test
    void aShareRequestThatDoesNotSayFollowsTheConfiguredPostToThreadDefaultAndTheRequestStillWins() {
        assertThat(ShareService.postsToThread(null, true)).isTrue();
        assertThat(ShareService.postsToThread(null, false)).isFalse();
        assertThat(ShareService.postsToThread(false, true)).isFalse();
        assertThat(ShareService.postsToThread(true, false)).isTrue();
    }
}
