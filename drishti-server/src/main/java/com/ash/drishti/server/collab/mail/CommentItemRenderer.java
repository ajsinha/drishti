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

import com.ash.drishti.identity.User;
import com.ash.drishti.identity.collab.Comment;
import com.ash.drishti.identity.collab.CommentThread;
import com.ash.drishti.identity.collab.OutboxItem;
import com.ash.drishti.identity.collab.ThreadStore;
import com.ash.drishti.server.collab.LinkBuilder;
import com.ash.drishti.server.collab.PanelTitles;
import com.ash.drishti.server.collab.Principals;
import com.ash.drishti.server.collab.thread.CommentRenderer;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.Set;

/**
 * The email for a mention ({@code mention}) or a reply to a followed thread ({@code reply}): the author, the view's kind and id, the
 * panel's <em>title</em>, the pinned date and the comment as this recipient may read it now (masked ranges as the mask, value quotes
 * filled from the recipient's own view), with a link that opens the view as it was. One class, two beans: the template is the event.
 * The row is cancelled when the comment was retracted or hidden, the recipient can no longer open the kind, or they turned the event off.
 */
public final class CommentItemRenderer implements ItemRenderer {

    private final String template;
    private final ThreadStore threads;
    private final Principals principals;
    private final Entitlements entitlements;
    private final CommentRenderer comments;
    private final MailContentPolicy policy;
    private final NotifyPrefs prefs;
    private final PanelTitles titles;
    private final LinkBuilder links;

    @SuppressWarnings("java:S107")
    public CommentItemRenderer(String template, ThreadStore threads, Principals principals, Entitlements entitlements, CommentRenderer comments,
            MailContentPolicy policy, NotifyPrefs prefs, PanelTitles titles, LinkBuilder links) {
        this.template = template;
        this.threads = threads;
        this.principals = principals;
        this.entitlements = entitlements;
        this.comments = comments;
        this.policy = policy;
        this.prefs = prefs;
        this.titles = titles;
        this.links = links;
    }

    @Override
    public String template() {
        return template;
    }

    @Override
    public MailRenderer.Content content(OutboxItem item, Principal who) {
        Comment c = threads.comment(item.refId()).orElseThrow(() -> new Skip("the comment no longer exists"));
        if (!Comment.LIVE.equals(c.state())) {
            throw new Skip("the comment was retracted or hidden");
        }
        CommentThread t = threads.thread(c.threadId()).orElseThrow(() -> new Skip("the discussion no longer exists"));
        if (!entitlements.mayReach(who, t.kind()) || t.gateKind() != null && !entitlements.mayReach(who, t.gateKind())) {
            throw new Skip("the recipient can no longer open " + t.kind() + " views");
        }
        if (!prefs.emailOn(item.recipient(), template)) {
            throw new Skip("the recipient turned " + template + " mail off");
        }
        String mode = policy.modeFor(t.kind());
        boolean linkOnly = MailContentPolicy.LINK_ONLY.equals(mode);
        String author = principals.user(c.author()).map(User::displayName).filter(n -> n != null && !n.isBlank()).orElse(c.author());
        String note = MailContentPolicy.COMMENT.equals(mode)
                ? CommentRenderer.text(comments.parts(t.kind(), t.entityId(), c.pin(), c.body(), c.maskedSpans(), Set.of(), who)) : null;
        String when = linkOnly || c.pin() == null ? null
                : c.pin().live() || c.pin().businessDate() == null ? "live when written" : c.pin().businessDate().toString();
        String headline = author + ("mention".equals(template) ? " mentioned you in a comment" : " replied in a discussion you follow");
        return new MailRenderer.Content(template, headline, linkOnly ? null : ShareItemRenderer.label(t.kind()), linkOnly ? null : t.entityId(),
                linkOnly ? null : titles.title(t.kind(), t.entityId(), t.panelId(), who), when, note,
                links.view(t.kind(), t.entityId(), c.pin(), t.panelId()));
    }
}
