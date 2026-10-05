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
package com.ash.drishti.server.collab;

import com.ash.drishti.identity.collab.Pin;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** The links that go into emails and bridge posts: a share's page, or a view as it was pinned (the console's own link form). */
public final class LinkBuilder {

    private final String base;

    public LinkBuilder(String consoleUrl) {
        String c = consoleUrl == null ? "" : consoleUrl.strip();
        this.base = c.endsWith("/") ? c.substring(0, c.length() - 1) : c;
    }

    public String share(String shareId) {
        return base + "/share/" + enc(shareId);
    }

    /** {@code /v/<kind>/<id>} at the pin's date, "known at" (whole seconds) and generation, scrolled to the panel. */
    public String view(String kind, String entityId, Pin pin, String panelId) {
        StringBuilder q = new StringBuilder();
        if (pin != null && !pin.live() && pin.businessDate() != null) {
            q.append("asOf=").append(enc(pin.businessDate().toString()));
            Instant known = pin.knownAt();
            if (known != null) {
                q.append("&knownAt=").append(enc(known.truncatedTo(ChronoUnit.SECONDS).toString()));
            }
        }
        if (pin != null && pin.generation() > 0) {
            q.append(q.length() > 0 ? "&" : "").append("gen=").append(pin.generation());
        }
        return base + "/v/" + enc(kind) + "/" + enc(entityId) + (q.length() > 0 ? "?" + q : "")
                + (panelId == null || panelId.isBlank() ? "" : "#p-" + enc(panelId));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
