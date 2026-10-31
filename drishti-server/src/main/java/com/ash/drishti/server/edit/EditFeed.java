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

import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Write-back's events on every console's stream ({@code edit} on {@code /api/v1/me/alerts/stream}; WRITE_BACK.md › The design and its reasons, sections 10 and 18.5),
 * shaped for each reader when sent ({@link #forReader}): the submitter hears every step of their own change (queued, awaiting approval,
 * applied, refused or conflicted with the reason, the recomputes it triggered); an approver hears of a change that awaits them; anyone who
 * may open the record's kind hears that it changed, so an open page of it updates; the submitter's name only for a reader who holds the
 * edit power or administers (who may see the edit history). Values are never sent: the page reads the record again, with its own masks.
 */
public final class EditFeed {

    /** One event, before it is shaped for a reader. */
    public record Event(Map<String, Object> body) {}

    private final List<Consumer<Object>> listeners = new CopyOnWriteArrayList<>();
    private final Entitlements entitlements;
    private final Predicate<Principal> approver;

    public EditFeed(Entitlements entitlements, Predicate<Principal> approver) {
        this.entitlements = entitlements;
        this.approver = approver;
    }

    public AutoCloseable listen(Consumer<Object> l) {
        listeners.add(l);
        return () -> listeners.remove(l);
    }

    public void publish(Map<String, Object> notice) {
        Event e = new Event(Map.copyOf(notice.entrySet().stream().filter(x -> x.getValue() != null)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))));
        listeners.forEach(l -> l.accept(e));
    }

    /** What this reader may hear of the event, or null when it is not for them. */
    public Map<String, Object> forReader(Event e, Principal p) {
        Map<String, Object> b = e.body();
        String type = String.valueOf(b.get("type"));
        String by = String.valueOf(b.get("by"));
        String kind = String.valueOf(b.get("kind"));
        boolean mine = by.equals(p.user());
        boolean viewer = EditLog.APPLIED.equals(type) || "withdrawn-overlay".equals(type) || "confirmed".equals(type) || "refused-at-source".equals(type);
        boolean awaiting = ("submitted".equals(type) || "approval".equals(type)) && !mine && approver.test(p);
        if (!mine && !awaiting && !(viewer && entitlements.mayOpen(p, kind))) {
            return null;
        }
        if (!mine && !entitlements.mayOpen(p, kind)) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>(b);
        out.put("mine", mine);
        if (!mine) {
            out.remove("reason");
            out.remove("recompute");
            out.remove("draft");
            if (!entitlements.mayEdit(p) && !entitlements.isAdmin(p)) {
                out.put("by", "");                           // the name only for who may see the edit history
            }
        }
        return out;
    }
}
