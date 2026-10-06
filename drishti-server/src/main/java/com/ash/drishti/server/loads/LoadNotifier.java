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
package com.ash.drishti.server.loads;

import com.ash.drishti.identity.User;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.identity.collab.Notice;
import com.ash.drishti.server.collab.InboxHub;
import com.ash.drishti.server.collab.InboxService;
import com.ash.drishti.server.collab.Principals;
import com.ash.drishti.server.collab.mail.EmailNotifier;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tells the people a pack names about a load (or late data): one inbox row each (and the bell), and an email through the outbox
 * when the pack asks for email and the email channel is on. A row holds the one-line summary only (counts and names, never a data
 * value), and is shown to the reader only while they may open the kind.
 */
public final class LoadNotifier {

    public static final String TYPE = "load";
    public static final String TYPE_LATE = "load-late";
    private static final Logger LOG = LoggerFactory.getLogger(LoadNotifier.class);

    private final UserService users;
    private final Principals principals;
    private final Entitlements entitlements;
    private final InboxService inbox;
    private final InboxHub hub;
    private final EmailNotifier email;
    private final Clock clock;

    public LoadNotifier(UserService users, Principals principals, Entitlements entitlements, InboxService inbox, InboxHub hub,
            EmailNotifier email, Clock clock) {
        this.users = users;
        this.principals = principals;
        this.entitlements = entitlements;
        this.inbox = inbox;
        this.hub = hub;
        this.email = email;
        this.clock = clock;
    }

    /** Who is told: users with a named role or named themselves, enabled, and able to open the kind. */
    public Set<String> recipients(LoadsConfig cfg, String kind) {
        Set<String> out = new LinkedHashSet<>();
        for (User u : users.list("")) {
            boolean named = cfg.notifyUsers().contains(u.username()) || u.roles().stream().anyMatch(cfg.notifyRoles()::contains);
            if (named && u.enabled()) {
                Principal p = principals.of(u.username());
                if (entitlements.mayOpen(p, kind)) {
                    out.add(u.username());
                }
            }
        }
        return out;
    }

    /**
     * @param type {@link #TYPE} or {@link #TYPE_LATE}
     * @param emailRef the outbox reference the mail renderer resolves ({@code load:<pack>:<id>} or {@code late:<pack>:<kind>:<date>})
     * @return how many people were told
     */
    public int tell(LoadsConfig cfg, String pack, String kind, LocalDate date, String type, String summary, String emailRef, String actor) {
        int n = 0;
        for (String who : recipients(cfg, kind)) {
            try {
                Notice stored = inbox.add(new Notice(0, who, clock.instant(), type, kind, date.toString(), summary, null, pack, null, actor, null));
                hub.publish(stored);
                n++;
                if (cfg.email() && email != null && email.available()) {
                    email.queue(who, TYPE, emailRef);
                }
            } catch (RuntimeException e) {
                LOG.warn("could not tell {} about the {} {} load: {}", who, pack, kind, e.getMessage());
            }
        }
        return n;
    }
}
