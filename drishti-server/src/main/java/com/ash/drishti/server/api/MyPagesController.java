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
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "My version of this page" (SCREEN_DESIGNER.md section 31): per user, which of their Designs draws the page of a kind
 * ({@code {"trade": "<design id>"}}). A flag of its own, so renaming the Design never breaks it; kept in the
 * {@link PreferenceStore} as one small document. {@code PUT /{kind}} sets it, {@code DELETE /{kind}} clears it.
 */
@RestController
@RequestMapping("/api/v1/me/pages")
public class MyPagesController {

    static final String NS = "designer-pages";
    private static final Pattern KIND = Pattern.compile("^[a-z0-9-]{1,40}$");
    private static final Pattern DESIGN = Pattern.compile("^[\\w-]{1,64}$");
    private static final int MAX = 200;

    private final PreferenceStore store;
    private final ObjectMapper json = new ObjectMapper();

    public MyPagesController(PreferenceStore store) {
        this.store = store;
    }

    @GetMapping
    public ObjectNode all(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return stored(p.user());
    }

    @PutMapping("/{kind}")
    public ObjectNode set(@PathVariable String kind, @RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        String design = body == null ? "" : body.path("design").asText("");
        if (!KIND.matcher(kind).matches() || !DESIGN.matcher(design).matches()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a page is a kind (a-z, 0-9, -) and the id of one of your designs");
        }
        ObjectNode next = stored(p.user());
        if (!next.has(kind) && next.size() >= MAX) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "at most " + MAX + " pages of your own: reset one first");
        }
        next.put(kind, design);
        store.put(p.user(), NS, NS, next);
        return next;
    }

    @DeleteMapping("/{kind}")
    public ObjectNode clear(@PathVariable String kind, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        ObjectNode next = stored(p.user());
        if (next.remove(kind) != null) {
            store.put(p.user(), NS, NS, next);
        }
        return next;
    }

    private ObjectNode stored(String user) {
        return store.get(user, NS, NS).filter(JsonNode::isObject).map(n -> ((ObjectNode) n).deepCopy()).orElseGet(json::createObjectNode);
    }
}
