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
package com.ash.drishti.server.collab.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ash.drishti.identity.User;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.Comment;
import com.ash.drishti.identity.collab.CommentThread;
import com.ash.drishti.identity.collab.FileOutboxStore;
import com.ash.drishti.identity.collab.OutboxItem;
import com.ash.drishti.identity.collab.OutboxStore;
import com.ash.drishti.identity.collab.Pin;
import com.ash.drishti.identity.collab.Share;
import com.ash.drishti.identity.collab.ShareStore;
import com.ash.drishti.identity.collab.ThreadStore;
import com.ash.drishti.server.collab.LinkBuilder;
import com.ash.drishti.server.collab.Notifier;
import com.ash.drishti.server.collab.PanelTitles;
import com.ash.drishti.server.collab.Principals;
import com.ash.drishti.server.collab.thread.CommentRenderer;
import com.ash.drishti.server.collab.thread.PinnedDocs;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.PackAccess;
import com.ash.drishti.server.security.Principal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Email for mentions and replies (step 4's item-renderer path, now with its beans) and the panel's title instead of its id in every
 * email: the content, the recipient's own view of the note, what cancels a row, who is queued.
 */
class CommentMailTest {

    private static final String SECRET = "4815162342";
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-05T09:00:00Z"), ZoneOffset.UTC);
    private final ThreadStore threads = mock(ThreadStore.class);
    private final ShareStore shares = mock(ShareStore.class);
    private final Principals principals = mock(Principals.class);
    private final Entitlements entitlements = mock(Entitlements.class);
    private final NotifyPrefs prefs = mock(NotifyPrefs.class);
    private final PackAccess packs = mock(PackAccess.class);
    private final PinnedDocs docs = mock(PinnedDocs.class);
    private CollabProperties props;
    private PanelTitles titles;
    private LinkBuilder links;
    private CommentItemRenderer mention;
    private CommentItemRenderer reply;
    private final Principal ravi = new Principal("ravi", List.of("trader"));

    @BeforeEach
    void setUp() {
        props = new CollabProperties(true, null, null, "https://drishti.example", null, null, null, null, null, null, null, null,
                new CollabProperties.Email(true, null, null, null, java.time.Duration.ZERO), null, null, null, null, null, null);
        when(entitlements.mayReach(any(), any())).thenReturn(true);
        when(entitlements.masks(any())).thenReturn(true);
        when(prefs.emailOn(any(), any())).thenReturn(true);
        when(docs.at(any(), any(), any())).thenReturn(Optional.empty());
        User ann = mock(User.class);
        when(ann.displayName()).thenReturn("Ann Shah");
        when(principals.user("ann")).thenReturn(Optional.of(ann));
        titles = new PanelTitles((k, i, w) -> Map.of("cashflows", "Cashflows"), entitlements);
        links = new LinkBuilder(props.consoleUrl());
        MailContentPolicy policy = new MailContentPolicy(props, packs);
        CommentRenderer renderer = new CommentRenderer(entitlements, docs);
        mention = new CommentItemRenderer("mention", threads, principals, entitlements, renderer, policy, prefs, titles, links);
        reply = new CommentItemRenderer("reply", threads, principals, entitlements, renderer, policy, prefs, titles, links);
        CommentThread t = new CommentThread("th_1", "trade", "IRS-48213", CommentThread.PANEL, "cashflows", null, null, "cashflows", CommentThread.OPEN, "ann",
                clock.instant(), clock.instant(), 1);
        when(threads.thread("th_1")).thenReturn(Optional.of(t));
        comment("cm_1", Comment.LIVE);
    }

    private void comment(String id, String state) {
        Comment c = new Comment(id, "th_1", "ann", clock.instant(), null, 1, new Pin(LocalDate.parse("2026-09-30"), false, clock.instant(), 3, "demo"),
                "@ravi check " + SECRET + " and {$.notional}", List.of(new Share.Span(12, 12 + SECRET.length())), state, null);
        when(threads.comment(id)).thenReturn(Optional.of(c));
    }

    private OutboxItem item(String template) {
        return OutboxItem.pending("email", "ravi", template, "cm_1", clock.instant());
    }

    @Test
    void aMentionNamesThePanelByItsTitleShowsTheNoteAsTheRecipientMaySeeItAndLinksToTheViewAsItWas() {
        MailRenderer.Content c = mention.content(item("mention"), ravi);
        assertThat(c.template()).isEqualTo("mention");
        assertThat(c.headline()).isEqualTo("Ann Shah mentioned you in a comment");
        assertThat(c.kind()).isEqualTo("Trade");
        assertThat(c.entityId()).isEqualTo("IRS-48213");
        assertThat(c.panel()).isEqualTo("Cashflows");
        assertThat(c.when()).isEqualTo("2026-09-30");
        assertThat(c.note()).isEqualTo("@ravi check ••• and —").doesNotContain(SECRET);
        assertThat(c.link()).startsWith("https://drishti.example/v/trade/IRS-48213?asOf=2026-09-30&knownAt=2026-10-05T09%3A00%3A00Z&gen=3").endsWith("#p-cashflows");
    }

    @Test
    void aReplyHasItsOwnHeadlineAndTemplate() {
        MailRenderer.Content c = reply.content(item("reply"), ravi);
        assertThat(c.template()).isEqualTo("reply");
        assertThat(c.headline()).isEqualTo("Ann Shah replied in a discussion you follow");
        assertThat(reply.template()).isEqualTo("reply");
    }

    @Test
    void theMessageRendersThroughTheDispatcherWithTheTitleAndNeverTheValue() throws Exception {
        OutboxStore store = new FileOutboxStore(Files.createTempDirectory("comment-mail"));
        List<RenderedMail> sent = new ArrayList<>();
        User u = mock(User.class);
        when(u.enabled()).thenReturn(true);
        when(u.email()).thenReturn("ravi@desk.test");
        when(principals.user("ravi")).thenReturn(Optional.of(u));
        when(principals.of("ravi")).thenReturn(ravi);
        OutboxDispatcher d = new OutboxDispatcher(store, props, principals, List.of(mention, reply), new MailRenderer(new MailTemplates(""), "Drishti", "x"),
                (m, f) -> sent.add(m), new SimpleMeterRegistry(), clock);
        store.add(item("mention"));
        store.add(item("reply"));
        assertThat(d.tick()).isEqualTo(2);
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).subject()).isEqualTo("Drishti: Ann Shah mentioned you in a comment");
        assertThat(sent.get(0).text()).contains("Panel: Cashflows", "Trade IRS-48213", "@ravi check ••• and —").doesNotContain(SECRET, "Panel: cashflows");
        assertThat(sent.get(0).html()).doesNotContain(SECRET);
        assertThat(sent.get(1).subject()).contains("replied in a discussion you follow");
    }

    @Test
    void aRetractedCommentAnAccessLostOrAMutedEventCancelsTheRow() {
        comment("cm_1", Comment.RETRACTED);
        assertThatThrownBy(() -> mention.content(item("mention"), ravi)).isInstanceOf(ItemRenderer.Skip.class).hasMessageContaining("retracted");
        comment("cm_1", Comment.LIVE);
        when(entitlements.mayReach(any(), any())).thenReturn(false);
        assertThatThrownBy(() -> mention.content(item("mention"), ravi)).isInstanceOf(ItemRenderer.Skip.class).hasMessageContaining("can no longer open");
        when(entitlements.mayReach(any(), any())).thenReturn(true);
        when(prefs.emailOn("ravi", "reply")).thenReturn(false);
        assertThatThrownBy(() -> reply.content(item("reply"), ravi)).isInstanceOf(ItemRenderer.Skip.class).hasMessageContaining("turned reply mail off");
    }

    @Test
    void linkOnlyPacksSendNeitherIdNorPanelNorNote() {
        CollabProperties linkOnly = new CollabProperties(true, null, null, "https://drishti.example", null, null, null, null, null, null, null, null,
                new CollabProperties.Email(true, "link-only", null, null, java.time.Duration.ZERO), null, null, null, null, null, null);
        CommentItemRenderer r = new CommentItemRenderer("mention", threads, principals, entitlements, new CommentRenderer(entitlements, docs),
                new MailContentPolicy(linkOnly, packs), prefs, titles, links);
        MailRenderer.Content c = r.content(item("mention"), ravi);
        assertThat(c.kind()).isNull();
        assertThat(c.entityId()).isNull();
        assertThat(c.panel()).isNull();
        assertThat(c.note()).isNull();
        assertThat(c.headline()).isEqualTo("Ann Shah mentioned you in a comment");
    }

    @Test
    void theEmailNotifierQueuesOneRowForEachPersonWhoWantsTheEventAndHasAnAddress() throws Exception {
        OutboxStore store = new FileOutboxStore(Files.createTempDirectory("comment-queue"));
        User withMail = mock(User.class);
        when(withMail.email()).thenReturn("ravi@desk.test");
        User noMail = mock(User.class);
        when(noMail.email()).thenReturn(" ");
        when(principals.user("ravi")).thenReturn(Optional.of(withMail));
        when(principals.user("sam")).thenReturn(Optional.of(noMail));
        when(principals.user("meg")).thenReturn(Optional.of(withMail));
        when(prefs.emailOn("meg", "mention")).thenReturn(false);
        EmailNotifier n = new EmailNotifier(props, store, principals, prefs, true);
        CommentThread t = threads.thread("th_1").orElseThrow();
        Comment c = threads.comment("cm_1").orElseThrow();
        n.onComment(new Notifier.CommentEvent("mention", t, c, null, List.of("ravi", "sam", "meg")));
        n.onComment(new Notifier.CommentEvent("reply", t, c, null, List.of("meg")));
        List<OutboxItem> rows = store.list(null, 10);
        assertThat(rows).extracting(OutboxItem::recipient, OutboxItem::template, OutboxItem::refId)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("ravi", "mention", "cm_1"), org.assertj.core.groups.Tuple.tuple("meg", "reply", "cm_1"));
        assertThat(rows).allSatisfy(i -> assertThat(i.channel()).isEqualTo("email"));
    }

    @Test
    void aShareEmailNamesThePanelByItsTitleToo() {
        Share s = new Share("sh_1", "ann", clock.instant(), "trade", "IRS-48213", "cashflows", null, new Pin(null, true, clock.instant(), 3, "demo"), "look",
                List.of(), "in-app", null, null);
        when(shares.find("sh_1")).thenReturn(Optional.of(s));
        ShareItemRenderer r = new ShareItemRenderer(shares, principals, entitlements, new MailContentPolicy(props, packs), prefs, props.consoleUrl(), titles);
        MailRenderer.Content c = r.content(OutboxItem.pending("email", "ravi", "share", "sh_1", clock.instant()), ravi);
        assertThat(c.panel()).isEqualTo("Cashflows");
        assertThat(c.link()).isEqualTo("https://drishti.example/share/sh_1");
    }

    @Test
    void aPanelThatHasNoTitleOrAViewThatCannotBeBuiltFallsBackToTheId() {
        PanelTitles unknown = new PanelTitles((k, i, w) -> Map.of(), entitlements);
        assertThat(unknown.title("trade", "X", "cashflows", ravi)).isEqualTo("cashflows");
        PanelTitles broken = new PanelTitles((k, i, w) -> {
            throw new IllegalStateException("source down");
        }, entitlements);
        assertThat(broken.title("trade", "X", "cashflows", ravi)).isEqualTo("cashflows");
        assertThat(broken.title("trade", "X", null, ravi)).isNull();
    }
}
