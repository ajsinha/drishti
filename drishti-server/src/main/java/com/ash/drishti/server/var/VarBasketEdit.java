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
import com.ash.drishti.identity.content.ContentDocs;
import com.ash.drishti.server.collab.InboxHub;
import com.ash.drishti.server.collab.InboxService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Saving a basket, and editing one (VAR_GUIDE.md section 3.5): its owner saves their own (the same name is the next version); an
 * administrator edits anyone's at any time ({@code owner} in the body names whose), saved under its owner, with one audit entry
 * {@code var-basket-edit} ("edited by administrator <user>") and an inbox row to the owner, as a delete does ({@link VarBasketDelete}).
 * Anyone else naming another person's basket gets {@code 404}, as if there were none. A portfolio is the bank's (its feed): it is never
 * saved here; the sheet's <i>Edit a copy</i> saves it as the viewer's own basket under a name of their choosing.
 */
final class VarBasketEdit {

    /** The longest scope text an audit entry keeps; a sheet's entry gives the number of its parts instead. */
    private static final int AUDIT_SCOPE = 500;

    private final VarService svc;
    private final Entitlements entitlements;
    private final Supplier<InboxService> inbox;
    private final Supplier<InboxHub> hub;

    VarBasketEdit(VarService svc, Entitlements entitlements, Supplier<InboxService> inbox, Supplier<InboxHub> hub) {
        this.svc = svc;
        this.entitlements = entitlements;
        this.inbox = inbox;
        this.hub = hub;
    }

    /**
     * Saves {@code body}: {@code {name, title, scope | sheet, sharedWith, owner?, version?}}. {@code version}, when given, is the version
     * the editor opened (a basket changed since: {@code 409}).
     */
    Map<String, Object> save(Principal p, Map<String, Object> body) {
        String me = ContentDocs.name(p.user());
        String owner = body.get("owner") == null || String.valueOf(body.get("owner")).isBlank() ? me : ContentDocs.name(String.valueOf(body.get("owner")));
        Integer version = body.get("version") instanceof Number n ? Integer.valueOf(n.intValue())
                : body.get("version") != null && String.valueOf(body.get("version")).matches("\\d{1,9}") ? Integer.valueOf(String.valueOf(body.get("version"))) : null;
        if (owner.equals(me)) {
            Map<String, Object> b = svc.saved().saveBasket(p.user(), without(body, "editedBy"), version);
            svc.audit(p.user(), "var-basket-save", String.valueOf(b.get("id")), "version=" + b.get("version") + what(b) + " sharedWith=" + b.get("sharedWith"));
            return b;
        }
        String name = VarSaved.slug(String.valueOf(body.getOrDefault("name", "")));
        String id = owner + "/" + name;
        if (!entitlements.isAdmin(p)) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no basket " + id + " of yours");
        }
        Map<String, Object> was = svc.saved().rawBasket(owner, name)
                .orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no basket " + id + ": an administrator edits a basket that exists"));
        if (Boolean.TRUE.equals(was.get("official"))) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "the basket " + id + " is official: it is never edited");
        }
        Map<String, Object> b2 = new LinkedHashMap<>(body);
        b2.put("editedBy", p.user());
        if (!b2.containsKey("sharedWith")) {
            b2.put("sharedWith", was.getOrDefault("sharedWith", List.of()));
        }
        String ownerUser = String.valueOf(was.getOrDefault("owner", owner));
        Map<String, Object> b = svc.saved().saveBasket(ownerUser, b2, version);
        svc.audit(p.user(), "var-basket-edit", String.valueOf(b.get("id")), "edited by administrator " + p.user() + " for " + ownerUser
                + " version=" + b.get("version") + what(b) + " sharedWith=" + b.get("sharedWith"));
        VarBasketDelete.tell(inbox.get(), hub.get(), ownerUser, p.user(), "Your VaR basket \"" + b.get("name") + "\" (BASKET:" + b.get("id")
                + ") was edited by administrator " + p.user() + ": now version " + b.get("version"));
        return b;
    }

    private static Map<String, Object> without(Map<String, Object> body, String key) {
        if (!body.containsKey(key)) {
            return body;
        }
        Map<String, Object> m = new LinkedHashMap<>(body);
        m.remove(key);
        return m;
    }

    /** What the audit keeps of a basket: a sheet's number of parts, or the scope text (cut). */
    private static String what(Map<String, Object> b) {
        if (b.get("sheet") != null) {
            return " parts=" + b.get("parts") + " wholeBank=" + (b.get("sheet") instanceof Map<?, ?> s && Boolean.TRUE.equals(s.get("wholeBank")));
        }
        String s = String.valueOf(b.get("scope"));
        return " scope=" + (s.length() > AUDIT_SCOPE ? s.substring(0, AUDIT_SCOPE) + "…" : s);
    }
}
