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
package com.ash.drishti.identity.collab;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * One share with a note: the record of sending. The note is stored as written (the record); the {@code maskedSpans} are the
 * character ranges of it that copy a masked field's value, shown as the mask to readers without {@code raw}. Data is never copied in.
 *
 * @param id {@code sh_} and a 26-character time-ordered id
 * @param sender the username
 * @param kind entity kind
 * @param entityId entity id
 * @param panelId a shared panel, or null for the whole view
 * @param gateKind the kind a shared panel's source names (readers must open it too), or null
 * @param pin which data it is about
 * @param body the note
 * @param maskedSpans ranges of {@code body} to scrub for readers without raw
 * @param channels {@code in-app}, with {@code email} and {@code picture} when asked for (comma-separated)
 * @param threadId the discussion thread it was also posted to, or null
 * @param hash SHA-256 over the fields above, so a stored row can be checked
 */
public record Share(String id, String sender, Instant createdAt, String kind, String entityId, String panelId, String gateKind, Pin pin,
        String body, List<Span> maskedSpans, String channels, String threadId, String hash) {

    /** A half-open character range {@code [start, end)} of the note. */
    public record Span(int start, int end) {}

    public Share {
        maskedSpans = maskedSpans == null ? List.of() : List.copyOf(maskedSpans);
        channels = channels == null ? "in-app" : channels;
    }

    /** True when the sender asked for a watermarked picture ({@code picture} among the channels). */
    public boolean picture() {
        return java.util.Arrays.asList(channels.split(",")).contains("picture");
    }

    /** This share with its hash computed. */
    public Share signed() {
        return new Share(id, sender, createdAt, kind, entityId, panelId, gateKind, pin, body, maskedSpans, channels, threadId, hashOf());
    }

    /** True when the stored hash matches the fields. */
    public boolean intact() {
        return hash != null && hash.equals(hashOf());
    }

    private String hashOf() {
        StringBuilder b = new StringBuilder();
        for (Object o : new Object[] {id, sender, createdAt, kind, entityId, panelId, gateKind, pin == null ? null : pin.businessDate(),
                pin != null && pin.live(), pin == null ? null : pin.knownAt(), pin == null ? 0 : pin.generation(),
                pin == null ? null : pin.source(), body, spansToJson(maskedSpans), channels, threadId}) {
            b.append(o == null ? "" : o).append('\u001f');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code [[1,4],[9,12]]}. */
    public static String spansToJson(List<Span> spans) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < spans.size(); i++) {
            b.append(i == 0 ? "" : ",").append('[').append(spans.get(i).start()).append(',').append(spans.get(i).end()).append(']');
        }
        return b.append(']').toString();
    }

    public static List<Span> spansFromJson(String json) {
        List<Span> out = new ArrayList<>();
        if (json == null) {
            return out;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[(\\d+),(\\d+)]").matcher(json);
        while (m.find()) {
            out.add(new Span(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
        }
        return out;
    }
}
