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
package com.ash.drishti.server.collab.bridge;

import com.ash.drishti.identity.User;
import com.ash.drishti.identity.collab.Comment;
import com.ash.drishti.identity.collab.CommentThread;
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.OutboxItem;
import com.ash.drishti.identity.collab.Pin;
import com.ash.drishti.identity.collab.Share;
import com.ash.drishti.identity.collab.ShareStore;
import com.ash.drishti.identity.collab.ThreadStore;
import com.ash.drishti.server.collab.LinkBuilder;
import com.ash.drishti.server.collab.NoteText;
import com.ash.drishti.server.collab.PanelTitles;
import com.ash.drishti.server.collab.Principals;
import com.ash.drishti.server.collab.mail.ItemRenderer;
import com.ash.drishti.server.collab.mail.MailContentPolicy;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.Clock;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds the message of one outbox row for its bridge, at the moment of sending, from the stored share or comment. The note is read
 * as the <em>least-privileged audience</em> would read it: the reader is a synthetic principal holding only the role named by
 * {@code bridges.render-as} (the bundled {@code viewer}, which has no {@code raw}), so a range that copies a masked field's value
 * reads as the mask. A value quote ({@code {$.mtm}}) is never filled in: it reads as the path it quotes. The pack's
 * {@code email.content} setting applies as well ({@code link-only} sends no id and no note, {@code title} no note). A share that is
 * gone, or a comment since retracted or hidden, is skipped (the row is cancelled).
 */
public final class BridgeItemRenderer {

    private static final Pattern QUOTE = Pattern.compile("\\{(\\$[A-Za-z0-9_.\\[\\]'\"-]*)}");

    private final ShareStore shares;
    private final ThreadStore threads;
    private final Principals principals;
    private final Entitlements entitlements;
    private final MailContentPolicy policy;
    private final PanelTitles titles;
    private final LinkBuilder links;
    private final CollabProperties props;
    private final String product;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public BridgeItemRenderer(ShareStore shares, ThreadStore threads, Principals principals, Entitlements entitlements, MailContentPolicy policy,
            PanelTitles titles, LinkBuilder links, CollabProperties props, String product, Clock clock) {
        this.shares = shares;
        this.threads = threads;
        this.principals = principals;
        this.entitlements = entitlements;
        this.policy = policy;
        this.titles = titles;
        this.links = links;
        this.props = props;
        this.product = product == null || product.isBlank() ? "Drishti" : product;
        this.clock = clock;
    }

    /** The reader the posted text is written for: nobody in particular, with the role {@code bridges.render-as}. */
    Principal reader(String bridge) {
        return new Principal("bridge:" + bridge, List.of(props.bridges().renderAs()));
    }

    /** The message for the row; throws {@link ItemRenderer.Skip} when nothing should be posted. */
    public BridgeMessage render(OutboxItem item) {
        Principal reader = reader(item.recipient());
        return switch (item.template()) {
            case BridgeRegistry.SHARE -> share(item, reader);
            case BridgeRegistry.COMMENT, BridgeRegistry.MENTION -> comment(item, reader);
            default -> throw new ItemRenderer.Skip("no bridge template '" + item.template() + "'");
        };
    }

    private BridgeMessage share(OutboxItem item, Principal reader) {
        Share s = shares.find(item.refId()).orElseThrow(() -> new ItemRenderer.Skip("the share no longer exists"));
        String mode = policy.modeFor(s.kind());
        String text = MailContentPolicy.COMMENT.equals(mode) ? note(s.body(), s.maskedSpans(), reader) : null;
        return message(BridgeRegistry.SHARE, s.id(), name(s.sender()) + " shared a view", mode, s.kind(), s.entityId(), s.panelId(), s.pin(), text,
                links.share(s.id()), reader, s.createdAt());
    }

    private BridgeMessage comment(OutboxItem item, Principal reader) {
        Comment c = threads.comment(item.refId()).orElseThrow(() -> new ItemRenderer.Skip("the comment no longer exists"));
        if (!Comment.LIVE.equals(c.state())) {
            throw new ItemRenderer.Skip("the comment was retracted or hidden");
        }
        CommentThread t = threads.thread(c.threadId()).orElseThrow(() -> new ItemRenderer.Skip("the thread no longer exists"));
        String mode = policy.modeFor(t.kind());
        String text = MailContentPolicy.COMMENT.equals(mode) ? note(c.body(), c.maskedSpans(), reader) : null;
        String verb = BridgeRegistry.MENTION.equals(item.template()) ? " mentioned a colleague in a comment" : " commented on a view";
        return message(item.template(), c.id(), name(c.author()) + verb, mode, t.kind(), t.entityId(), t.panelId(), c.pin(), text,
                links.view(t.kind(), t.entityId(), c.pin(), t.panelId()), reader, c.createdAt());
    }

    private BridgeMessage message(String event, String id, String headline, String mode, String kind, String entityId, String panel, Pin pin,
            String note, String link, Principal reader, java.time.Instant at) {
        boolean linkOnly = MailContentPolicy.LINK_ONLY.equals(mode);
        String when = linkOnly || pin == null ? null : pin.live() || pin.businessDate() == null ? "live when shared" : pin.businessDate().toString();
        return new BridgeMessage(event, id, product, line(headline), linkOnly ? null : label(kind), linkOnly ? null : entityId,
                linkOnly ? null : line(titles.title(kind, entityId, panel, reader)), when, note, link, at == null ? clock.instant() : at);
    }

    /** The note with masked ranges as the mask, value quotes as their paths, control characters gone, cut to {@code max-note}. */
    String note(String body, List<Share.Span> spans, Principal reader) {
        String scrubbed = NoteText.render(body, spans, entitlements.masks(reader));
        String unquoted = QUOTE.matcher(scrubbed).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(m.group(1)));
        String clean = unquoted.replace("\r\n", "\n").replace('\r', '\n').replaceAll("[\\p{Cntrl}&&[^\n\t]]", "").strip();
        return NoteText.excerpt(clean, props.bridges().maxNote());
    }

    private String name(String user) {
        return principals.user(user).map(User::displayName).filter(n -> n != null && !n.isBlank()).orElse(user);
    }

    private static String line(String s) {
        return s == null ? null : s.replaceAll("[\\p{Cntrl}]+", " ").strip();
    }

    private static String label(String kind) {
        String t = kind.replace('-', ' ').replace('_', ' ').strip();
        return t.isEmpty() ? kind : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }
}
