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
package com.ash.drishti.server.edit;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.Branding;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.compute.reactive.ReactiveConnector;
import com.ash.drishti.compute.reactive.ReactiveHub;
import com.ash.drishti.identity.User;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The review and confirm sheet (WRITE_BACK.md › The design and its reasons, section 18.2): before anything is submitted the person sees, in plain words, per change:
 * the difference (what they saw, what is there now, their change; a field that moved underneath is flagged before submit, not after),
 * where it goes ({@code Correction kept in Drishti (overlay)}, {@code Amend in <connector> (command amend-notional)}), who must approve
 * (not them), what it will recompute (the reactive connectors whose {@code write-back} triggers name the kind, read from their
 * triggers without computing anything), and whether a reason is required. Nothing is written; a change that would be refused says why.
 */
public final class EditPreview {

    private final EditService edits;
    private final EditHandler handler;
    private final Entitlements entitlements;
    private final UserService users;
    private final Supplier<List<ReactiveConnector>> reactive;

    public EditPreview(EditService edits, EditHandler handler, Entitlements entitlements, UserService users) {
        this(edits, handler, entitlements, users, () -> ReactiveHub.get().connectors());
    }

    EditPreview(EditService edits, EditHandler handler, Entitlements entitlements, UserService users, Supplier<List<ReactiveConnector>> reactive) {
        this.edits = edits;
        this.handler = handler;
        this.entitlements = entitlements;
        this.users = users;
        this.reactive = reactive;
    }

    public Map<String, Object> preview(Principal p, JsonNode body) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Change> changes;
        boolean noReason = body == null || !body.hasNonNull("reason") || body.path("reason").asText().isBlank();
        JsonNode asked = body;
        if (noReason && body instanceof com.fasterxml.jackson.databind.node.ObjectNode o) {
            asked = o.deepCopy().put("reason", "(review)");                // the sheet asks for the reason; everything else is checked now
        }
        try {
            changes = edits.changes(p, asked);
        } catch (DrishtiException e) {
            out.put("ok", false);
            out.put("code", e.errorCode().code());
            out.put("problem", e.getMessage());
            return out;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Change c : changes) {
            list.add(one(p, c));
        }
        out.put("ok", true);
        out.put("changes", list);
        return out;
    }

    private Map<String, Object> one(Principal p, Change c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", c.kind());
        m.put("entity", c.entity());
        m.put("target", c.target());
        m.put("where", where(c));
        DataNode now = handler.current(c);
        List<Map<String, Object>> diff = new ArrayList<>();
        for (Change.Item it : c.items()) {
            boolean hidden = EditHandler.fieldsOf(it.path()).stream().anyMatch(f -> entitlements.masked(p, f));
            DataNode n = now == null ? DataNode.missing() : EditPaths.get(now, it.path());
            Object current = n.isMissing() ? null : n.unwrap();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("field", EditPaths.field(it.path()));
            d.put("path", it.path());
            d.put("saw", hidden ? DataNode.MASK : it.from());
            d.put("now", hidden ? DataNode.MASK : current);
            d.put("wanted", hidden ? DataNode.MASK : it.to());
            d.put("moved", now != null && !"new".equals(c.origin("mode")) && !EditHandler.same(current, it.from()));
            diff.add(d);
        }
        m.put("diff", diff);
        m.put("approvals", c.approvals());
        if (c.approvals() > 0) {
            m.put("approvers", approvers(p));
        }
        m.put("recompute", recompute(c.kind()));
        m.put("reasonRequired", edits.fields().find(c.kind(), EditPaths.field(c.items().get(0).path()), c.origin("sutra"), c.origin("panel"))
                .map(r -> r.save().reason()).orElse(false));
        return m;
    }

    static String where(Change c) {
        return switch (c.target()) {
            case "source" -> "Amend in " + c.connector() + " (command " + c.command() + "), shown here as pending until the source confirms it";
            case "owned" -> "Kept by " + Branding.name("the server") + " as the record itself (it is the record's system of record)";
            default -> "Correction kept by " + Branding.name("the server") + " (overlay): the source keeps its value, the page shows yours and says so";
        };
    }

    /** Up to five people who may approve, never the submitter. */
    private List<String> approvers(Principal p) {
        List<String> out = new ArrayList<>();
        for (User u : users.list(null)) {
            if (out.size() >= 5) {
                break;
            }
            if (!u.enabled() || u.username().equals(p.user())) {
                continue;
            }
            if (edits.approver(new Principal(u.username(), List.copyOf(u.roles())))) {
                out.add(u.displayName() == null || u.displayName().isBlank() ? u.username() : u.displayName());
            }
        }
        return out;
    }

    /** The reactive connectors whose write-back triggers fire for the kind, and whether they run. */
    List<Map<String, Object>> recompute(String kind) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReactiveConnector rc : reactive.get()) {
            Map<String, Object> d = rc.describe();
            Object triggers = d.get("triggers");
            if (!(triggers instanceof List<?> l)) {
                continue;
            }
            boolean fires = l.stream().map(String::valueOf).anyMatch(t -> t.startsWith("write-back") && (t.equals("write-back") || t.contains(kind)));
            if (fires) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("connector", rc.name());
                r.put("kinds", d.get("kinds") instanceof Map<?, ?> k ? new ArrayList<>(k.keySet()) : List.of());
                r.put("available", d.get("available"));
                out.add(r);
            }
        }
        return out;
    }
}
