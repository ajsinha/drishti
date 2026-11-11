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
package com.ash.drishti.server.bi;

import com.ash.drishti.identity.User;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.identity.collab.Notice;
import com.ash.drishti.server.collab.InboxHub;
import com.ash.drishti.server.collab.InboxService;
import com.ash.drishti.server.collab.Principals;
import com.ash.drishti.server.security.Entitlements;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tells people about governance in BI through the inbox and the bell that shares and comments already use: a review was asked for (to the reviewers), a
 * review was decided (to the author), a certification was revoked (to the owner and whoever certified). A row is a pointer with a one-line summary (names
 * and counts, never a data value); {@code kind} is {@code bi} and {@code entityId} the item. A failure to tell is logged and never fails the change.
 */
public final class BiNotifier {

    public static final String REVIEW = "bi-review";
    public static final String DECISION = "bi-decision";
    public static final String CERTIFICATION = "bi-certification";
    public static final String KIND = "bi";
    private static final Logger LOG = LoggerFactory.getLogger(BiNotifier.class);

    private final BiGovProperties props;
    private final InboxService inbox;
    private final InboxHub hub;
    private final UserService users;
    private final Principals principals;
    private final BiAccess access;
    private final Entitlements entitlements;
    private final BiStore store;

    public BiNotifier(BiGovProperties props, InboxService inbox, InboxHub hub, UserService users, Principals principals, BiAccess access, Entitlements entitlements,
            BiStore store) {
        this.props = props;
        this.inbox = inbox;
        this.hub = hub;
        this.users = users;
        this.principals = principals;
        this.access = access;
        this.entitlements = entitlements;
        this.store = store;
    }

    /** Who reviews a proposal: the stewards and the members of its workspace who hold the approve power; never the author. */
    public Set<String> reviewers(BiStore.Item i, String author) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> members = new LinkedHashSet<>();
        store.members(i.workspace()).stream().filter(m -> BiAccess.rank(m.role()) >= BiAccess.rank("member")).forEach(m -> members.add(m.username()));
        for (User u : users.list("")) {
            if (!u.enabled() || u.username().equals(author)) {
                continue;
            }
            var p = principals.of(u.username());
            if (access.has(p, BiAccess.STEWARD) || members.contains(u.username()) && entitlements.mayApprove(p)) {
                out.add(u.username());
            }
        }
        return out;
    }

    /** One inbox row each; returns how many people were told. */
    public int tell(Collection<String> to, String type, String itemId, String summary, String actor) {
        if (!props.tellPeople()) {
            return 0;
        }
        int n = 0;
        for (String who : to) {
            try {
                Notice stored = inbox.add(new Notice(0, who, Instant.now(), type, KIND, itemId, summary, null, null, null, actor, null));
                hub.publish(stored);
                n++;
            } catch (RuntimeException e) {
                LOG.warn("could not tell {} about {}: {}", who, itemId, e.getMessage());
            }
        }
        return n;
    }
}
