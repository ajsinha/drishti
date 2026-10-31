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
package com.ash.drishti.server.var;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.collab.Notice;
import com.ash.drishti.identity.content.ContentDocs;
import com.ash.drishti.server.collab.InboxHub;
import com.ash.drishti.server.collab.InboxService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Deleting a saved basket (an ad-hoc scope kept by name, VAR_GUIDE.md section 3.5): its owner deletes their own, an administrator anyone's.
 * Each delete is one audit entry, {@code var-basket-delete} on {@code <owner>/<name>}, that says who deleted it ("deleted by its owner" or
 * "deleted by administrator <user>"), the version, whom it was shared with and the reason when one was given. When an administrator deletes
 * someone's basket, its owner gets an inbox row (and the bell) saying so. Captures that used the basket are never touched.
 */
final class VarBasketDelete {

    /** The inbox row's type: a one-line notice with the console path it opens (rendered as is, like an alert). */
    static final String NOTICE = "notice";
    static final String KIND = "var-basket";
    /** The console page an owner's notice opens: the VaR explorer, where their remaining baskets are listed. */
    static final String LINK = "/var";
    private static final int REASON_MAX = 500;

    private final VarService svc;
    private final Entitlements entitlements;
    private final Supplier<InboxService> inbox;
    private final Supplier<InboxHub> hub;

    VarBasketDelete(VarService svc, Entitlements entitlements, Supplier<InboxService> inbox, Supplier<InboxHub> hub) {
        this.svc = svc;
        this.entitlements = entitlements;
        this.inbox = inbox;
        this.hub = hub;
    }

    /** Deletes {@code owner/name} as {@code p}: theirs, or anyone's for an administrator; anyone else gets 404 (as if there were none). */
    void delete(Principal p, String owner, String name, String reason, Integer version) {
        boolean own = owner.equals(ContentDocs.name(p.user()));
        boolean admin = !own && entitlements.isAdmin(p);
        String id = owner + "/" + name;
        if (!own && !admin) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no basket " + id + " of yours");
        }
        String why = reason == null || reason.isBlank() ? null : reason.strip().replaceAll("\\s+", " ");
        if (why != null && why.length() > REASON_MAX) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a reason is at most " + REASON_MAX + " characters");
        }
        Map<String, Object> gone = svc.saved().deleteBasket(owner, name, version)
                .orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no basket " + id + (own ? " of yours" : "")));
        String by = own ? "deleted by its owner" : "deleted by administrator " + p.user();
        svc.audit(p.user(), "var-basket-delete", String.valueOf(gone.getOrDefault("id", id)), by + " version=" + gone.get("version")
                + " sharedWith=" + gone.get("sharedWith") + (why == null ? "" : " reason=" + why));
        if (admin) {
            tell(String.valueOf(gone.get("owner")), p.user(), String.valueOf(gone.getOrDefault("name", name)), String.valueOf(gone.get("id")), why);
        }
    }

    /** The owner's inbox row (best effort: a missing inbox or a failure never undoes the delete). */
    private void tell(String owner, String admin, String title, String id, String why) {
        InboxService in = inbox.get();
        if (in == null || owner == null || owner.isBlank() || "null".equals(owner)) {
            return;
        }
        String line = "Your VaR basket \"" + title + "\" (BASKET:" + id + ") was deleted by administrator " + admin
                + (why == null ? "" : ": " + why);
        try {
            Notice stored = in.add(new Notice(0, owner, Instant.now(), NOTICE, KIND, LINK, line, null, null, null, admin, null));
            InboxHub h = hub.get();
            if (h != null) {
                h.publish(stored);
            }
        } catch (RuntimeException e) {
            // best effort: the audit entry already says who deleted it
        }
    }
}
