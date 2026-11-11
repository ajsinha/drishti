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

import com.ash.drishti.identity.collab.CollabProperties;
import com.ash.drishti.server.security.PackAccess;
import java.util.Map;

/**
 * How much an email says, per kind: {@code comment} (the id, the panel, the date and the note as the recipient sees it), {@code title}
 * (the id, no note) or {@code link-only} (no id, no note). The default is {@code drishti.collab.email.content}; a pack whose
 * identifiers are themselves sensitive sets {@code drishti.collab.packs.<pack>.email.content: link-only}. Pack names live in
 * configuration only.
 */
public final class MailContentPolicy {

    public static final String LINK_ONLY = "link-only";
    public static final String TITLE = "title";
    public static final String COMMENT = "comment";

    private final CollabProperties props;
    private final PackAccess packs;

    public MailContentPolicy(CollabProperties props, PackAccess packs) {
        this.props = props;
        this.packs = packs;
    }

    /** The mode for entities of this kind: the owning pack's setting, else the global one. */
    public String modeFor(String kind) {
        String owner = kind == null ? null : packs.ownerOf(kind);
        Object email = owner == null ? null : props.packs().getOrDefault(owner, Map.of()).get("email");
        if (email instanceof Map<?, ?> m && m.get("content") != null) {
            String v = String.valueOf(m.get("content")).trim().toLowerCase();
            if (LINK_ONLY.equals(v) || TITLE.equals(v) || COMMENT.equals(v)) {
                return v;
            }
        }
        return props.email().content();
    }
}
