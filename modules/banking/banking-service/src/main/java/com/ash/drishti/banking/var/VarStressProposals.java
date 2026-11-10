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
package com.ash.drishti.banking.var;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.content.ContentDocs;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.kernel.api.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The stress window selection's frozen analyses (VAR_GUIDE.md section 18.9): <b>Freeze and propose</b> keeps the analysis of a node with
 * its inputs (the positions date, the history's data version, the window length, the confidence, the stress set and its window) and
 * the proposed window, as a record in the content store ({@code var/stress-window/proposals/SWP-<n>.json}); a second person approves or
 * rejects it (maker-checker: the proposer may not decide). An approved window is the instruction for the pricing system's next stress
 * scenario set; once a date's stress set covers it, the tool names the approval beside the current window. Every step audited.
 */
final class VarStressProposals {

    static final String FOLDER = "var/stress-window/proposals/";
    static final String SEQUENCE = "var/stress-window/sequence";
    /** The owner name when security is off (no principal). */
    static final String ANONYMOUS = "anonymous";

    private final VarStressWindow tool;
    private final ContentDocs docs;
    private final AuditLog audit;

    VarStressProposals(VarStressWindow tool, ContentStore store, AuditLog audit) {
        this.tool = tool;
        this.docs = new ContentDocs(store);
        this.audit = audit;
    }

    private static String who(Principal p) {
        return p == null ? ANONYMOUS : p.user();
    }

    /**
     * Freezes the analysis of a node and proposes a window: {@code first} its first day (none: the worst window); refused in words when
     * the day starts no window of the history.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> propose(Principal p, String scope, String hierarchy, LocalDate date, Integer length, Double confidence, String first,
            String note) {
        Map<String, Object> a = tool.analyse(p, scope, hierarchy, date, length, confidence);
        Map<String, Object> proposed = (Map<String, Object>) a.get("worst");
        if (first != null && !first.isBlank()) {
            proposed = null;
            Map<String, Object> series = (Map<String, Object>) a.get("series");
            List<String> starts = (List<String>) series.get("starts");
            int at = starts.indexOf(first.strip());
            if (at < 0) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "no window of the history starts on " + first.strip()
                        + ": give a business day between " + starts.get(0) + " and " + starts.get(starts.size() - 1));
            }
            for (Map<String, Object> c : (List<Map<String, Object>>) a.get("candidates")) {
                if (first.strip().equals(c.get("first"))) {
                    proposed = c;
                }
            }
            if (proposed == null) {
                double v = ((double[]) series.get("var"))[at];
                proposed = new LinkedHashMap<>();
                proposed.put("first", first.strip());
                proposed.put("var", v);
                proposed.put("var10", v * Math.sqrt(10));
                proposed.put("scenarios", a.get("length"));
            }
        }
        if (proposed == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "nothing to propose: the history has no window of " + a.get("length") + " days");
        }
        long n = ContentDocs.next(docs.store(), SEQUENCE);
        String id = "SWP-" + ContentDocs.pad(n);
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("id", id);
        rec.put("status", "PROPOSED");
        rec.put("date", a.get("date"));
        rec.put("node", a.get("node"));
        rec.put("length", a.get("length"));
        rec.put("confidence", a.get("confidence"));
        rec.put("history", a.get("history"));
        rec.put("stressSet", a.get("stressSet"));
        rec.put("current", strip(a.get("current")));
        rec.put("proposed", strip(proposed));
        List<Object> cands = new ArrayList<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) a.get("candidates")) {
            cands.add(strip(c));
        }
        rec.put("candidates", cands);
        rec.put("alert", a.get("alert"));
        rec.put("note", note == null ? "" : note);
        rec.put("proposedBy", who(p));
        rec.put("proposedAt", Instant.now().toString());
        docs.create(FOLDER + id + ".json", rec);
        audit(p, "var-stress-window-propose", id, "node=" + ((Map<String, Object>) a.get("node")).get("id") + " window="
                + ((Map<String, Object>) rec.get("proposed")).get("first"));
        return rec;
    }

    /** A window's fields without its comparison helpers. */
    @SuppressWarnings("unchecked")
    private static Object strip(Object w) {
        if (!(w instanceof Map<?, ?> m)) {
            return w;
        }
        Map<String, Object> out = new LinkedHashMap<>((Map<String, Object>) m);
        out.remove("vsCurrent");
        out.remove("worst");
        out.remove("rank");
        return out;
    }

    /** Every proposal, the newest first (at most {@code limit}). */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list(Principal p, int limit) {
        tool.check(p);
        List<String> keys = new ArrayList<>(docs.store().list(FOLDER));
        keys.sort(java.util.Comparator.reverseOrder());
        List<Map<String, Object>> out = new ArrayList<>();
        for (String k : keys) {
            if (out.size() >= Math.max(1, limit)) {
                break;
            }
            docs.value(k, Map.class).ifPresent(m -> out.add((Map<String, Object>) m));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> get(Principal p, String id) {
        tool.check(p);
        return docs.value(FOLDER + id + ".json", Map.class).map(m -> (Map<String, Object>) m)
                .orElseThrow(() -> new DrishtiException(ErrorCode.PROPOSAL_NOT_FOUND, "no stress window proposal " + id));
    }

    /**
     * Approves or rejects a proposal: only one still proposed, never by its proposer (with security on), with a note.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> decide(Principal p, String id, String decision, String note) {
        tool.check(p);
        String d = decision == null ? "" : decision.strip().toUpperCase(Locale.ROOT);
        String status = switch (d) {
            case "APPROVE", "APPROVED" -> "APPROVED";
            case "REJECT", "REJECTED" -> "REJECTED";
            default -> throw new DrishtiException(ErrorCode.BAD_REQUEST, "decision is approve or reject");
        };
        String key = FOLDER + id + ".json";
        if (docs.value(key, Map.class).isEmpty()) {
            throw new DrishtiException(ErrorCode.PROPOSAL_NOT_FOUND, "no stress window proposal " + id);
        }
        Map<String, Object> out = docs.update(key, Map.class, m -> {
            Map<String, Object> rec = (Map<String, Object>) m;
            if (!"PROPOSED".equals(rec.get("status"))) {
                throw new DrishtiException(ErrorCode.PROPOSAL_CONFLICT, "the proposal " + id + " is " + rec.get("status") + " already");
            }
            if (p != null && who(p).equals(rec.get("proposedBy"))) {
                throw new DrishtiException(ErrorCode.FOUR_EYES, "the proposer may not decide the proposal " + id + ": another person "
                        + "approves or rejects it (maker-checker)");
            }
            rec.put("status", status);
            rec.put("decidedBy", who(p));
            rec.put("decidedAt", Instant.now().toString());
            rec.put("decisionNote", note == null ? "" : note);
            return rec;
        });
        audit(p, "var-stress-window-" + status.toLowerCase(Locale.ROOT), id, "");
        return out;
    }

    /** The newest approved proposal whose window starts on {@code first} for a node, if any. */
    @SuppressWarnings("unchecked")
    Optional<Map<String, Object>> approved(String node, String first) {
        if (first == null) {
            return Optional.empty();
        }
        List<String> keys = new ArrayList<>(docs.store().list(FOLDER));
        keys.sort(java.util.Comparator.reverseOrder());
        for (String k : keys) {
            Optional<Map<String, Object>> m = docs.value(k, Map.class).map(x -> (Map<String, Object>) x);
            if (m.isPresent() && "APPROVED".equals(m.get().get("status")) && m.get().get("proposed") instanceof Map<?, ?> w
                    && first.equals(w.get("first")) && m.get().get("node") instanceof Map<?, ?> nd && node.equals(nd.get("id"))) {
                return m;
            }
        }
        return Optional.empty();
    }

    private void audit(Principal p, String action, String id, String extra) {
        if (audit == null) {
            return;
        }
        try {
            audit.record(who(p), action, id, extra);
        } catch (RuntimeException e) {
            // best effort: the record itself is the evidence
        }
    }
}
