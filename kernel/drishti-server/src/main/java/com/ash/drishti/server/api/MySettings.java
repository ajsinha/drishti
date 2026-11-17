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

import com.ash.drishti.identity.PreferenceStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * A person's own settings, each theirs or its default: the body of {@code GET /api/v1/me/settings}, called by {@link SettingsController}
 * and by the kernel's typed {@code PersonSettings.of} alike (KERNEL_AND_MODULES.md section 2.3).
 */
@Component
public class MySettings {

    private final PreferenceStore store;
    private final ObjectMapper json = new ObjectMapper();

    public MySettings(PreferenceStore store) {
        this.store = store;
    }

    /** The person's settings, each theirs or its default. */
    public ObjectNode of(String user) {
        return withDefaults(stored(user));
    }

    /** What the person stored, as stored (an empty object for nothing). */
    public ObjectNode stored(String user) {
        return store.get(user, SettingsController.NS, SettingsController.NS).filter(JsonNode::isObject).map(n -> (ObjectNode) n)
                .orElseGet(json::createObjectNode);
    }

    /** Every setting: the stored one, else its default. */
    public ObjectNode withDefaults(ObjectNode s) {
        ObjectNode out = json.createObjectNode();
        out.set("theme", s.path("theme").isMissingNode() ? json.nullNode() : s.get("theme"));
        out.put("landing", s.path("landing").asText("/t"));
        out.set("clockZone", s.path("clockZone").isMissingNode() ? json.nullNode() : s.get("clockZone"));
        out.set("locale", s.path("locale").isMissingNode() ? json.nullNode() : s.get("locale"));
        out.put("density", s.path("density").asText("comfortable"));
        out.put("designerMode", s.path("designerMode").asText("customize"));       // the Sutra designer's mode a person opens first
        out.put("flash", s.path("flash").asBoolean(true));
        out.put("searchLimit", s.path("searchLimit").asInt(100));
        ObjectNode email = json.createObjectNode();
        com.ash.drishti.server.collab.mail.NotifyPrefs.EVENTS.forEach(e -> email.put(e, s.path("notify").path("email").path(e).asBoolean(true)));
        out.putObject("notify").set("email", email);
        out.set("refresh", s.path("refresh").isObject() ? s.get("refresh") : json.createObjectNode());
        out.set("pinned", s.path("pinned").isArray() ? s.get("pinned") : json.createArrayNode());
        out.set("savedSearches", s.path("savedSearches").isArray() ? s.get("savedSearches") : json.createArrayNode());
        out.set("lens", s.path("lens").isTextual() ? s.get("lens") : json.nullNode());                  // LENSES.md 8: the console reads them back
        out.set("homeLanding", s.path("homeLanding").isTextual() ? s.get("homeLanding") : json.nullNode());
        out.set("tradingView", s.path("tradingView").isTextual() ? s.get("tradingView") : json.nullNode());   // Drishti Trading's View as
        out.set("varView", s.path("varView").isObject() ? s.get("varView") : json.nullNode());        // the VaR page's lens (2.9.7)
        return out;
    }
}
