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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's saved workspaces: a layout and up to four panes, each showing an entity and optionally
 * following another pane's selection. Stored per user; validated so a workspace always renders.
 */
@RestController
@RequestMapping("/api/v1/me/workspaces")
public class WorkspaceController {

    static final String NS = "workspaces";
    /** Shares are kept under an owner no user can have (user names start with a letter or digit). */
    static final String SHARES_OWNER = "_shared";
    static final String SHARES = "workspace-shares";

    /** A share's key: owner and workspace name (user names have no spaces, so the first space separates them). */
    static String shareKey(String owner, String name) {
        return owner + " " + name;
    }
    static final Set<String> LAYOUTS = Set.of("2col", "3col", "2x2", "1+2");

    private final PreferenceStore store;
    private final Entitlements entitlements;
    private final ObjectMapper json = new ObjectMapper();

    public WorkspaceController(PreferenceStore store, Entitlements entitlements) {
        this.store = store;
        this.entitlements = entitlements;
    }

    @GetMapping
    public List<String> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return store.keys(p.user(), NS);
    }

    @GetMapping("/{name}")
    public JsonNode get(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return store.get(p.user(), NS, name).orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no workspace '" + name + "'"));
    }

    @PutMapping("/{name}")
    public JsonNode put(@PathVariable String name, @RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        ObjectNode ws = validate(body, p);
        store.put(p.user(), NS, name, ws);
        return ws;
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (!store.delete(p.user(), NS, name)) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no workspace '" + name + "'");
        }
        store.delete(SHARES_OWNER, SHARES, shareKey(p.user(), name));           // a deleted workspace is no longer shared
    }

    /** Who a workspace is shared with, or 404 when it is not shared. */
    @GetMapping("/{name}/share")
    public JsonNode share(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return store.get(SHARES_OWNER, SHARES, shareKey(p.user(), name))
                .orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "workspace '" + name + "' is not shared"));
    }

    /**
     * Shares a workspace: {@code {"everyone": true}} or {@code {"roles": ["risk"], "users": ["ravi"]}}. Those it is
     * shared with see it as it is now and as the owner changes it (read-only; they may copy it into their own). Panes
     * on kinds a reader may not open stay hidden from that reader.
     */
    @PutMapping("/{name}/share")
    public JsonNode share(@PathVariable String name, @RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        get(name, p);                                                          // it must exist
        ObjectNode s = json.createObjectNode().put("owner", p.user()).put("name", name)
                .put("everyone", body.path("everyone").asBoolean(false)).put("sharedAt", java.time.Instant.now().toString());
        s.set("roles", names(body.path("roles"), "roles"));
        s.set("users", names(body.path("users"), "users"));
        if (!s.path("everyone").asBoolean() && s.path("roles").isEmpty() && s.path("users").isEmpty()) {
            throw bad("share with everyone, or name roles or users");
        }
        store.put(SHARES_OWNER, SHARES, shareKey(p.user(), name), s);
        return s;
    }

    @DeleteMapping("/{name}/share")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unshare(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (!store.delete(SHARES_OWNER, SHARES, shareKey(p.user(), name))) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "workspace '" + name + "' is not shared");
        }
    }

    private com.fasterxml.jackson.databind.node.ArrayNode names(JsonNode list, String what) {
        var out = json.createArrayNode();
        if (list.isArray()) {
            if (list.size() > 50) {
                throw bad("at most 50 " + what);
            }
            list.forEach(n -> {
                String v = n.asText("").trim();
                if (!v.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
                    throw bad("'" + v + "' is not a valid name in " + what);
                }
                out.add(v);
            });
        }
        return out;
    }

    /** A workspace with a known layout, 1-4 panes, entities the caller may open, and acyclic follows. */
    ObjectNode validate(JsonNode body, Principal p) {
        String layout = body.path("layout").asText("2col");
        if (!LAYOUTS.contains(layout)) {
            throw bad("layout must be one of " + LAYOUTS);
        }
        JsonNode panes = body.path("panes");
        if (!panes.isArray() || panes.isEmpty() || panes.size() > 4) {
            throw bad("a workspace has 1 to 4 panes");
        }
        ObjectNode out = json.createObjectNode().put("layout", layout);
        var arr = out.putArray("panes");
        int[] follows = new int[panes.size()];
        for (int i = 0; i < panes.size(); i++) {
            JsonNode pane = panes.get(i);
            ObjectNode o = arr.addObject();
            JsonNode ref = pane.path("ref");
            if (ref.isObject() && !ref.path("kind").asText().isBlank() && !ref.path("id").asText().isBlank()) {
                String kind = ref.path("kind").asText();
                if (!entitlements.mayOpen(p, kind)) {
                    throw new DrishtiException(ErrorCode.FORBIDDEN, p.user() + " may not open " + kind + " entities");
                }
                o.putObject("ref").put("kind", kind).put("id", ref.path("id").asText());
            } else {
                o.putNull("ref");
            }
            follows[i] = pane.path("follows").isInt() ? pane.path("follows").asInt() : -1;
            if (follows[i] >= panes.size() || follows[i] == i || follows[i] < -1) {
                throw bad("pane " + (i + 1) + " follows a pane that does not exist or itself");
            }
            if (follows[i] >= 0) {
                o.put("follows", follows[i]);
            } else {
                o.putNull("follows");
            }
            String title = pane.path("title").asText("");
            o.put("title", title.length() > 60 ? title.substring(0, 60) : title);
        }
        for (int i = 0; i < follows.length; i++) {
            Set<Integer> seen = new HashSet<>();
            for (int j = i; j >= 0; j = follows[j]) {
                if (!seen.add(j)) {
                    throw bad("panes follow each other in a circle");
                }
            }
        }
        return out;
    }

    private static DrishtiException bad(String message) {
        return new DrishtiException(ErrorCode.BAD_REQUEST, message);
    }
}
