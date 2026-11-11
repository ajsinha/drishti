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
package com.ash.drishti.server.collab.mail;

import com.ash.drishti.identity.PreferenceStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * A person's choice of which events are emailed ({@code notify.email.share}, {@code .mention}, {@code .reply} in {@code /me/settings}).
 * All default to on; a person who turns one off gets nothing by email for it (the bell still rings). Stored with the other
 * personal settings.
 */
public final class NotifyPrefs {

    public static final String NAMESPACE = "settings";
    public static final List<String> EVENTS = List.of("share", "mention", "reply");

    private final PreferenceStore store;

    public NotifyPrefs(PreferenceStore store) {
        this.store = store;
    }

    /** True unless the user turned this event off. */
    public boolean emailOn(String user, String event) {
        JsonNode v = store.get(user, NAMESPACE, NAMESPACE).map(s -> s.path("notify").path("email").path(event)).orElse(null);
        return v == null || v.isMissingNode() || v.isNull() || v.asBoolean(true);
    }
}
