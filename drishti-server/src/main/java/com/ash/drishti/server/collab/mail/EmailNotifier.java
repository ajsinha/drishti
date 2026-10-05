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

    @Override
    public List<Notice> onShare(ShareEvent e) {
        Instant now = Instant.now();
        int cap = props.limits().mailsPerRecipientPerHour();
        for (Recipient r : e.reached()) {
            String who = r.username();
            if (principals.user(who).map(User::email).filter(a -> a != null && !a.isBlank()).isEmpty()) {
                continue;
            }
            if (!prefs.emailOn(who, "share")) {
                continue;
            }
            if (outbox.countSince(who, now.minus(Duration.ofHours(1))) >= cap) {
                LOG.warn("mail to {} not queued: {} a hour reached (drishti.collab.limits.mails-per-recipient-per-hour)", who, cap);
                continue;
            }
            outbox.add(OutboxItem.pending("email", who, "share", e.share().id(), now));
        }
        return List.of();
    }
}
