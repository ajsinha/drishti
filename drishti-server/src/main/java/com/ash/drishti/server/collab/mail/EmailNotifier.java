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
import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.identity.collab.Notice;
import com.ash.drishti.identity.collab.OutboxItem;
import com.ash.drishti.identity.collab.OutboxStore;
import com.ash.drishti.identity.collab.Recipient;
import com.ash.drishti.server.collab.Notifier;
import com.ash.drishti.server.collab.Principals;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The email channel. It sends nothing itself: for each recipient who has an address and wants share mail it writes a row to the
 * outbox in the share's own transaction (so a rolled-back share sends nothing and a crash loses nothing), and the
 * {@link OutboxDispatcher} sends it. Off until {@code drishti.collab.email.enabled}, {@code spring.mail.host} and
 * {@code drishti.collab.console-url} are all set.
 */
public final class EmailNotifier implements Notifier {

    private static final Logger LOG = LoggerFactory.getLogger(EmailNotifier.class);

    private final CollabProperties props;
    private final OutboxStore outbox;
    private final Principals principals;
    private final NotifyPrefs prefs;
    private final boolean transportPresent;

    public EmailNotifier(CollabProperties props, OutboxStore outbox, Principals principals, NotifyPrefs prefs, boolean transportPresent) {
        this.props = props;
        this.outbox = outbox;
        this.principals = principals;
        this.prefs = prefs;
        this.transportPresent = transportPresent;
    }

    @Override
    public String channel() {
        return "email";
    }

    @Override
    public boolean available() {
        return props.email().enabled() && transportPresent && !props.consoleUrl().isBlank();
    }

    /** The newest delivery that failed and is still being retried (or gave up): its last error, so a sender is told the mail may not arrive. */
    @Override
    public String trouble() {
        if (!available()) {
            return null;
        }
        return outbox.list(null, 50).stream().filter(i -> "email".equals(i.channel()) && i.lastError() != null && !i.lastError().isBlank()
                && (OutboxItem.PENDING.equals(i.state()) || OutboxItem.DEAD.equals(i.state()))).map(OutboxItem::lastError).findFirst().orElse(null);
    }

    @Override
    public List<Notice> onShare(ShareEvent e) {
        for (Recipient r : e.reached()) {
            queue(r.username(), "share", e.share().id());
        }
        return List.of();
    }

    /** A mention or a reply ({@code CommentEvent.type}) becomes one email per person, if they want it. */
    @Override
    public List<Notice> onComment(CommentEvent e) {
        for (String who : e.recipients()) {
            queue(who, e.type(), e.comment().id());
        }
        return List.of();
    }

    /** One pending email for the person: skipped for no address, the event turned off, or the hourly cap. */
    private void queue(String who, String event, String ref) {
        Instant now = Instant.now();
        int cap = props.limits().mailsPerRecipientPerHour();
        if (principals.user(who).map(User::email).filter(a -> a != null && !a.isBlank()).isEmpty() || !prefs.emailOn(who, event)) {
            return;
        }
        if (outbox.countSince(who, now.minus(Duration.ofHours(1))) >= cap) {
            LOG.warn("mail to {} not queued: {} a hour reached (drishti.collab.limits.mails-per-recipient-per-hour)", who, cap);
            return;
        }
        Instant due = now;
        if (props.email().coalesces()) {
            // notices inside the window share one send time, so the dispatcher finds them together and sends one digest
            due = outbox.list(OutboxItem.PENDING, 500).stream().filter(i -> "email".equals(i.channel()) && who.equals(i.recipient())
                    && i.nextAt().isAfter(now)).map(OutboxItem::nextAt).min(Instant::compareTo).orElse(now.plus(props.email().coalesceWindow()));
        }
        outbox.add(OutboxItem.pending("email", who, event, ref, now).with(OutboxItem.PENDING, 0, due, null, null, null, null));
    }
}
