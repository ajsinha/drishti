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
package com.ash.drishti.kernel.api.approvals;

import com.ash.drishti.kernel.api.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One kind of four-eyes item feeding the one Approvals queue (docs/architecture/SHARED_SCREENS.md section 2): Sutra proposals, corrections
 * (write-back), BI items, compute requirements, pivot functions, stress windows, regulatory report sign-offs. A source keeps its own rules
 * (who may decide, four eyes, what a decision does); the queue gathers the items, orders them, keeps the questions and is the only API.
 *
 * <p>Every method answers for one person and keeps nothing per person: what a source returns is computed from the principal it is given.
 */
public interface ApprovalSource {

    /** The word every source uses for an item that waits. */
    String PENDING = "pending";

    /** The kind's id in the queue's addresses ({@code sutra}, {@code change}, {@code bi}, ...): letters, digits and dashes. */
    String kind();

    /** The filter it falls under: {@code data}, {@code screens}, {@code risk} or {@code access}. */
    String group();

    /** The kind in words, for the list ({@code Sutra}, {@code Correction}, ...). */
    String label();

    /** False when the feature is switched off on this server: the queue then shows none of its items. */
    default boolean enabled() {
        return true;
    }

    /**
     * The items this person may see, newest first: waiting for them, sent by them, and recently decided ones they may see. At most
     * {@code limit}. Never throws for a person who may see none (an empty list).
     */
    List<Item> items(Principal p, int limit);

    /** One item as this person may see it, with what changes, its effect and the rules; {@code DRS-2005} when there is none for them. */
    Detail detail(String id, Principal p);

    /**
     * Decides: {@code approve}, {@code reject} (a reason is required) or {@code withdraw} (the maker). {@code params} carries what a kind
     * needs besides the comment (a pivot function's sharing scope, a run's version). Answers the item as it now stands.
     */
    Item decide(String id, String action, String comment, Map<String, Object> params, Principal p);

    /**
     * An item in the queue.
     *
     * @param kind the source's kind
     * @param id the item's id within its kind
     * @param title one line naming it
     * @param maker who asked for it
     * @param at when it was asked for
     * @param status {@code pending} while it waits; otherwise the source's own word ({@code approved}, {@code rejected}, {@code withdrawn}, ...)
     * @param reason the maker's reason or note
     * @param due a date it must be decided by, or null
     * @param mayDecide the person may approve or reject it now (the kind's rules: never the maker while four eyes apply)
     * @param mine the person is its maker
     * @param mayWithdraw the person may withdraw it
     * @param link the console address of the feature page that owns it
     * @param decidedBy who decided it, or null
     * @param decidedAt when, or null
     * @param stage where it stands in a chain of several approvals ({@code checker}, {@code 1 of 2}), or null
     */
    record Item(String kind, String id, String title, String maker, Instant at, String status, String reason, LocalDate due, boolean mayDecide,
            boolean mine, boolean mayWithdraw, String link, String decidedBy, Instant decidedAt, String stage) {

        public boolean pending() {
            return PENDING.equals(status);
        }
    }

    /**
     * One item opened: what changes, the effect if approved, and the rules.
     *
     * @param item the item
     * @param diff what changes: {@code {format: lines, rows: [{op, left, right}]}} or {@code {format: fields, rows: [{field, before, after}]}}
     * @param effect what changes downstream if approved, in lines (the impact service's answer, the scopes and reports that refresh)
     * @param rules who may decide and on what grounds, in lines
     * @param facts other facts the kind shows (checks, evidence, versions), by name
     */
    record Detail(Item item, Map<String, Object> diff, List<String> effect, List<String> rules, Map<String, Object> facts) {}

    /** A field-by-field diff: each row {@code {field, before, after}}. */
    static Map<String, Object> fieldDiff(List<Map<String, Object>> rows) {
        return Map.of("format", "fields", "rows", rows);
    }

    /** One diff row. */
    static Map<String, Object> row(String field, Object before, Object after) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("field", field);
        m.put("before", before);
        m.put("after", after);
        return m;
    }

    /** The actions a person has on an item: approve and reject while it waits for them, withdraw for its maker. */
    static List<String> actions(Item i) {
        List<String> a = new ArrayList<>();
        if (i.pending() && i.mayDecide()) {                              // the kind says no for the maker (four eyes), unless its policy allows
            a.add("approve");
            a.add("reject");
        }
        if (i.pending() && i.mayWithdraw()) {
            a.add("withdraw");
        }
        return a;
    }

    /** An instant from a text (ISO-8601), or null. */
    static Instant instant(Object o) {
        if (o instanceof Instant i) {
            return i;
        }
        if (o == null || o.toString().isBlank()) {
            return null;
        }
        try {
            return Instant.parse(o.toString());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** A text or empty. */
    static String text(Object o) {
        return o == null ? "" : o.toString();
    }
}
