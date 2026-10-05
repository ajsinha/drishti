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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.identity.User;
import com.ash.drishti.identity.collab.OutboxItem;
import com.ash.drishti.identity.collab.Share;
import com.ash.drishti.identity.collab.ShareStore;
import com.ash.drishti.server.collab.PanelTitles;
import com.ash.drishti.server.collab.Principals;
import com.ash.drishti.server.collab.snapshot.SnapshotService;
import com.ash.drishti.server.collab.thread.CommentRenderer;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;

/**
 * The email for a share, built when it is sent: the sender's name, the kind and id, the panel, the date of the pin, and the note
 * as this recipient would read it: the copies of masked values replaced by the mask unless they hold {@code raw}, and value quotes
 * ({@code {$.path}}) filled from the recipient's own view (masked fields read as the mask). Under {@code link-only} no note at all.
 * Per-kind content mode from {@link MailContentPolicy}. A share whose recipient may no longer reach it, or who turned share mail
 * off, is skipped.
 */
public final class ShareItemRenderer implements ItemRenderer {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ShareItemRenderer.class);

    private final ShareStore shares;
    private final Principals principals;
    private final Entitlements entitlements;
    private final MailContentPolicy policy;
    private final NotifyPrefs prefs;
    private final String consoleUrl;
    private final PanelTitles titles;
    private final SnapshotService snapshots;
    private final CommentRenderer comments;

    public ShareItemRenderer(ShareStore shares, Principals principals, Entitlements entitlements, MailContentPolicy policy, NotifyPrefs prefs,
            String consoleUrl, PanelTitles titles, CommentRenderer comments) {
        this(shares, principals, entitlements, policy, prefs, consoleUrl, titles, comments, null);
    }

    @SuppressWarnings("java:S107")
    public ShareItemRenderer(ShareStore shares, Principals principals, Entitlements entitlements, MailContentPolicy policy, NotifyPrefs prefs,
            String consoleUrl, PanelTitles titles, CommentRenderer comments, SnapshotService snapshots) {
        this.comments = comments;
        this.snapshots = snapshots;
        this.shares = shares;
        this.principals = principals;
        this.entitlements = entitlements;
        this.policy = policy;
        this.prefs = prefs;
        this.consoleUrl = consoleUrl;
        this.titles = titles;
    }

    @Override
    public String template() {
        return "share";
    }

    @Override
    public MailRenderer.Content content(OutboxItem item, Principal who) {
        Share s = shares.find(item.refId()).orElseThrow(() -> new Skip("the share no longer exists"));
        if (!entitlements.mayReach(who, s.kind())) {
            throw new Skip("the recipient can no longer open " + s.kind() + " views");
        }
        if (!prefs.emailOn(item.recipient(), "share")) {
            throw new Skip("the recipient turned share mail off");
        }
        String mode = policy.modeFor(s.kind());
        boolean linkOnly = MailContentPolicy.LINK_ONLY.equals(mode);
        String sender = principals.user(s.sender()).map(User::displayName).filter(n -> n != null && !n.isBlank()).orElse(s.sender());
        String note = MailContentPolicy.COMMENT.equals(mode)
                ? CommentRenderer.text(comments.parts(s.kind(), s.entityId(), s.pin(), s.body(), s.maskedSpans(), java.util.Set.of(), who)) : null;
        String when = linkOnly ? null : s.pin().live() || s.pin().businessDate() == null ? "live when shared" : s.pin().businessDate().toString();
        return new MailRenderer.Content("share", sender + " shared a view with you", linkOnly ? null : label(s.kind()),
                linkOnly ? null : s.entityId(), linkOnly ? null : titles.title(s.kind(), s.entityId(), s.panelId(), who), when, note, link(s.id()),
                MailContentPolicy.COMMENT.equals(mode) ? picture(s) : null);
    }

    /** The share's watermarked picture, only under {@code email.content: comment}; a picture that cannot be made never holds the mail back. */
    private byte[] picture(Share s) {
        if (snapshots == null || !s.picture() || !snapshots.allowed(s.kind(), s.gateKind())) {
            return null;
        }
        try {
            return snapshots.forShare(s, s.sender()).png();
        } catch (DrishtiException e) {
            LOG.info("share {} goes without its picture: {}", s.id(), e.getMessage());
            return null;
        }
    }

    private String link(String id) {
        return (consoleUrl.endsWith("/") ? consoleUrl.substring(0, consoleUrl.length() - 1) : consoleUrl) + "/share/" + id;
    }

    /** {@code counterparty-limit} becomes {@code Counterparty limit}. */
    static String label(String kind) {
        String t = kind.replace('-', ' ').replace('_', ' ').strip();
        return t.isEmpty() ? kind : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }
}
