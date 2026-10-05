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
package com.ash.drishti.server.collab.thread;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.identity.collab.Pin;
import com.ash.drishti.identity.collab.Share;
import com.ash.drishti.server.collab.NoteText;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the words a person wrote into what <em>this reader</em> may see: the ranges that copy a masked field's value become the mask
 * (for a reader without {@code raw}), {@code @name} tokens that were resolved at write time become mention parts, and
 * {@code {$.path}} value quotes are filled from the reader's own view of the document at the comment's pin ({@code •••} when the
 * reader's view masks the field, {@code —} when the path is gone). Text is plain: the parts carry no markup, and the console draws
 * them with {@code textContent}.
 */
public final class CommentRenderer {

    /** One piece of rendered text: {@code text}, {@code mention} ({@code target} = user:x or role:x) or {@code quote} ({@code path}). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Part(String t, String v, String target, String path) {
        static Part text(String v) {
            return new Part("text", v, null, null);
        }
    }

    static final String GONE = "—";
    private static final Pattern TOKEN = Pattern.compile("(?<![A-Za-z0-9._-])@([A-Za-z0-9_](?:[A-Za-z0-9._-]*[A-Za-z0-9_])?)|\\{(\\$[A-Za-z0-9_.\\[\\]'\"-]*)}");

    private final Entitlements entitlements;
    private final PinnedDocs docs;

    public CommentRenderer(Entitlements entitlements, PinnedDocs docs) {
        this.entitlements = entitlements;
        this.docs = docs;
    }

    /** The parts of {@code body} for {@code reader}; {@code targets} are the mentions resolved when it was written. */
    public List<Part> parts(String kind, String entityId, Pin pin, String body, List<Share.Span> spans, Set<String> targets, Principal reader) {
        String scrubbed = NoteText.render(body, spans, entitlements.masks(reader));
        List<Part> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(scrubbed);
        int at = 0;
        Optional<DataNode> quoted = null;
        while (m.find()) {
            String user = targets.contains("user:" + m.group(1)) ? "user:" + m.group(1) : null;
            String target = m.group(1) == null ? null : user != null ? user : targets.contains("role:" + m.group(1)) ? "role:" + m.group(1) : null;
            if (m.group(1) != null && target == null) {
                continue;                                              // an @ that names no one is plain text
            }
            if (m.start() > at) {
                out.add(Part.text(scrubbed.substring(at, m.start())));
            }
            if (target != null) {
                out.add(new Part("mention", m.group(0), target, null));
            } else {
                if (quoted == null) {
                    quoted = docs.at(kind, entityId, pin);
                }
                out.add(new Part("quote", quote(quoted, m.group(2), reader), null, m.group(2)));
            }
            at = m.end();
        }
        if (at < scrubbed.length()) {
            out.add(Part.text(scrubbed.substring(at)));
        }
        return out;
    }

    /** The plain text of the parts. */
    public static String text(List<Part> parts) {
        StringBuilder b = new StringBuilder();
        parts.forEach(p -> b.append(p.v()));
        return b.toString();
    }

    private String quote(Optional<DataNode> doc, String path, Principal reader) {
        if (doc.isEmpty()) {
            return GONE;
        }
        DataNode node = entitlements.redact(reader, doc.get()).at(path);
        if (node instanceof DataNode.Val v) {
            return v.isMasked() ? DataNode.MASK : v.isNull() ? GONE : v.asText();
        }
        return GONE;
    }
}
