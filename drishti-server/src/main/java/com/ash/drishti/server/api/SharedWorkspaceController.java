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
package com.ash.drishti.server.api;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Workspaces other people shared with the caller: listed, and opened read-only as their owner keeps them. */
@RestController
@RequestMapping("/api/v1/workspaces/shared")
public class SharedWorkspaceController {

    private final PreferenceStore store;
    private final Entitlements entitlements;

    public SharedWorkspaceController(PreferenceStore store, Entitlements entitlements) {
        this.store = store;
        this.entitlements = entitlements;
    }

    /** The workspaces shared with the caller (not the caller's own), by owner and name. */
    @GetMapping
    public List<Map<String, Object>> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String key : store.keys(WorkspaceController.SHARES_OWNER, WorkspaceController.SHARES)) {
            store.get(WorkspaceController.SHARES_OWNER, WorkspaceController.SHARES, key).filter(s -> visible(s, p))
                    .filter(s -> !s.path("owner").asText().equals(p.user()))
                    .filter(s -> store.get(s.path("owner").asText(), WorkspaceController.NS, s.path("name").asText()).isPresent())
                    .ifPresent(s -> out.add(Map.of("owner", s.path("owner").asText(), "name", s.path("name").asText(),
                            "sharedAt", s.path("sharedAt").asText())));
        }
        out.sort(java.util.Comparator.comparing((Map<String, Object> m) -> (String) m.get("owner")).thenComparing(m -> (String) m.get("name")));
        return out;
    }

    /** The workspace as its owner keeps it now; panes on kinds the caller may not open come back hidden. */
    @GetMapping("/{owner}/{name}")
    public JsonNode get(@PathVariable String owner, @PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        JsonNode share = store.get(WorkspaceController.SHARES_OWNER, WorkspaceController.SHARES, WorkspaceController.shareKey(owner, name))
                .filter(s -> visible(s, p) || owner.equals(p.user()))
                .orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no workspace '" + name + "' shared by " + owner));
        ObjectNode ws = store.get(owner, WorkspaceController.NS, name).map(JsonNode::deepCopy).map(ObjectNode.class::cast)
                .orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no workspace '" + name + "' shared by " + owner));
        ws.withArray("panes").forEach(pane -> {
            JsonNode ref = pane.path("ref");
            if (ref.isObject() && !entitlements.mayOpen(p, ref.path("kind").asText())) {
                ((ObjectNode) pane).putNull("ref").put("hidden", true);
            }
        });
        ws.put("owner", owner).put("name", name).put("readOnly", !owner.equals(p.user())).put("sharedAt", share.path("sharedAt").asText());
        return ws;
    }

    private static boolean visible(JsonNode share, Principal p) {
        if (share.path("everyone").asBoolean(false)) {
            return true;
        }
        for (JsonNode u : share.path("users")) {
            if (u.asText().equals(p.user())) {
                return true;
            }
        }
        for (JsonNode r : share.path("roles")) {
            if (p.roles().contains(r.asText())) {
                return true;
            }
        }
        return false;
    }
}
