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
package com.ash.drishti.server.bi.runs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The run object of the API (docs/architecture/BI_RUNS.md section 14.3), the contract between the server and the console: every field is
 * always present (null when unknown), fields are never removed, and {@code can} is computed here for the caller so the console never decides
 * rights. No data value is ever written: names, counts, states and the values the reader typed into filters.
 */
public final class RunJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RunJson() {}

    /**
     * Who is looking and what the server knows beside the row.
     *
     * @param caller      the user the object is written for
     * @param admin       the caller holds bi-admin (sees state, may cancel; never results of others)
     * @param author      the caller holds bi-author (may promote)
     * @param recipient   the run was shared with the caller
     * @param promote     {@code promote.enabled}
     * @param sourceTitle the item's title (report, board, model), else null
     * @param dataWords   the data version in words ({@code trade_endur delta v412}), else null
     * @param dataTables  one entry per table read: {@code {table, connector, versions}}
     * @param shares      the run's shares as the runner sees them: {@code {recipient, mode, recipientRun}}
     * @param hasResult   the stored result is there (not expired, not removed)
     */
    public record View(String caller, boolean admin, boolean author, boolean recipient, boolean promote, String sourceTitle, String dataWords,
                       List<Map<String, Object>> dataTables, List<Map<String, Object>> shares, boolean hasResult) {

        public static View of(String caller) {
            return new View(caller, false, false, false, true, null, null, List.of(), List.of(), false);
        }

        public View withResult(boolean has) {
            return new View(caller, admin, author, recipient, promote, sourceTitle, dataWords, dataTables, shares, has);
        }

        public View withShares(List<Map<String, Object>> s) {
            return new View(caller, admin, author, recipient, promote, sourceTitle, dataWords, dataTables, s, hasResult);
        }

        public View withPowers(boolean isAdmin, boolean isAuthor) {
            return new View(caller, isAdmin, isAuthor, recipient, promote, sourceTitle, dataWords, dataTables, shares, hasResult);
        }

        public View asRecipient(boolean r) {
            return new View(caller, admin, author, r, promote, sourceTitle, dataWords, dataTables, shares, hasResult);
        }

        public View withData(String words, List<Map<String, Object>> tables) {
            return new View(caller, admin, author, recipient, promote, sourceTitle, words, tables, shares, hasResult);
        }

        public View withSourceTitle(String t) {
            return new View(caller, admin, author, recipient, promote, t, dataWords, dataTables, shares, hasResult);
        }

        public View withPromote(boolean p) {
            return new View(caller, admin, author, recipient, p, sourceTitle, dataWords, dataTables, shares, hasResult);
        }
    }

    /** The run object for {@code view.caller()} at {@code now}. */
    public static Map<String, Object> of(Run r, View view, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("title", r.title() == null || r.title().isBlank() ? defaultTitle(r) : r.title());
        m.put("source", source(r, view));
        m.put("runner", r.runner());
        m.put("owner", r.owner());
        m.put("workspace", r.workspace());
        m.put("params", parse(r.params()));
        m.put("asOf", asOf(r.asOf()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", r.dataVersion());
        data.put("words", view.dataWords());
        data.put("tables", view.dataTables() == null ? List.of() : view.dataTables());
        m.put("data", data);
        m.put("tier", r.tier().wire());
        m.put("engine", r.engine());
        m.put("class", r.tier() == Tier.AD_HOC_HEAVY ? "background" : "interactive");
        m.put("state", r.state().wire());
        m.put("progress", r.progress());
        m.put("phase", r.phase());
        m.put("estimate", parse(r.estimate()));
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("rowsRead", r.rowsRead());
        actual.put("rowsOut", r.rowsOut());
        actual.put("bytesOut", r.bytesOut());
        actual.put("seconds", seconds(r, now));
        m.put("actual", actual);
        m.put("code", r.code());
        m.put("reason", r.reason());
        m.put("node", r.node());
        m.put("attempt", r.attempt());
        m.put("submittedAt", text(r.submittedAt()));
        m.put("startedAt", text(r.startedAt()));
        m.put("finishedAt", text(r.finishedAt()));
        m.put("expiresAt", text(r.expiresAt()));
        if (r.reuseOf() == null) {
            m.put("reuse", null);
        } else {
            Map<String, Object> reuse = new LinkedHashMap<>();
            reuse.put("of", r.reuseOf());
            reuse.put("mode", r.sharedFrom() != null && r.sharedFrom().equals(r.reuseOf()) ? "re-masked" : "same");
            m.put("reuse", reuse);
        }
        Map<String, Object> shared = new LinkedHashMap<>();
        shared.put("from", r.sharedFrom());
        boolean own = isOwner(r, view);
        shared.put("with", own && view.shares() != null ? view.shares() : List.of());
        m.put("shared", shared);
        m.put("group", r.groupId());
        m.put("rerunOf", r.rerunOf());
        m.put("promotedTo", r.promotedTo());
        m.put("can", can(r, view));
        String self = "/api/v1/bi/runs/" + r.id();
        m.put("links", Map.of("self", self, "result", self + "/result", "events", "/api/v1/bi/runs/events"));
        return m;
    }

    static boolean isOwner(Run r, View v) {
        return v.caller() != null && (v.caller().equals(r.owner()) || v.caller().equals(r.runner()));
    }

    /** What the caller may do with the run now (section 7.3's table). */
    static Map<String, Object> can(Run r, View v) {
        boolean own = isOwner(r, v);
        boolean seeResult = own || v.recipient();
        boolean finishedOk = r.state() == RunState.DONE || r.state() == RunState.REUSED;
        boolean active = r.state().active() || r.state() == RunState.AWAITING_CONFIRM || r.state() == RunState.PLANNED;
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("open", seeResult && finishedOk && v.hasResult());
        c.put("cancel", active && (own || v.admin()));
        c.put("rerun", own && !active);
        c.put("export", seeResult && finishedOk && v.hasResult());
        c.put("share", own && finishedOk && v.hasResult());
        c.put("promote", own && finishedOk && v.hasResult() && v.author() && v.promote());
        c.put("schedule", own);
        c.put("keep", own && finishedOk && v.hasResult());
        c.put("delete", (own || v.admin()) && finishedOk && v.hasResult());
        c.put("confirm", own && r.state() == RunState.AWAITING_CONFIRM);
        return c;
    }

    private static Map<String, Object> source(Run r, View v) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", r.sourceType());
        s.put("id", r.sourceId());
        s.put("title", v.sourceTitle());
        String page = null;
        String visual = null;
        if (r.sourceRef() != null && !r.sourceRef().isBlank()) {
            String[] parts = r.sourceRef().split("/", 2);
            page = parts[0].isBlank() ? null : parts[0];
            visual = parts.length > 1 && !parts[1].isBlank() ? parts[1] : null;
        }
        s.put("page", page);
        s.put("visual", visual);
        String href;
        if (("report".equals(r.sourceType()) || "board".equals(r.sourceType())) && r.sourceId() != null) {
            href = "/bi/r/" + r.sourceId() + (page == null ? "" : "/" + page) + "?run=" + r.id();
        } else {
            href = "/bi/runs/" + r.id();
        }
        s.put("href", href);
        return s;
    }

    private static String defaultTitle(Run r) {
        return switch (r.sourceType()) {
            case "sql" -> "SQL question";
            case "mq" -> "Model question";
            case "statement" -> "Statement";
            case "subscription" -> "Subscription send";
            case "schedule" -> "Scheduled run";
            default -> r.sourceId() == null ? "Report question" : r.sourceId();
        };
    }

    /** {@code from..to}, a JSON list of dates, or one date, as {@code {from, to, dates}}; null when none. */
    static Map<String, Object> asOf(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        String t = text.strip();
        List<String> dates = new ArrayList<>();
        if (t.startsWith("[")) {
            Object v = parse(t);
            if (v instanceof List<?> l) {
                l.forEach(x -> dates.add(String.valueOf(x)));
            }
            dates.sort(String::compareTo);
            m.put("from", dates.isEmpty() ? null : dates.get(0));
            m.put("to", dates.isEmpty() ? null : dates.get(dates.size() - 1));
            m.put("dates", dates.size());
        } else if (t.contains("..")) {
            String[] p = t.split("\\.\\.", 2);
            m.put("from", p[0]);
            m.put("to", p[1]);
            m.put("dates", null);
        } else {
            m.put("from", t);
            m.put("to", t);
            m.put("dates", 1);
        }
        return m;
    }

    /** The as-of text of a run row for these dates: one date, or a JSON list. */
    public static String asOfText(List<LocalDate> dates) {
        if (dates == null || dates.isEmpty()) {
            return null;
        }
        if (dates.size() == 1) {
            return dates.get(0).toString();
        }
        return write(dates.stream().map(LocalDate::toString).sorted().toList());
    }

    private static Long seconds(Run r, Instant now) {
        if (r.startedAt() == null) {
            return null;
        }
        Instant end = r.finishedAt() == null ? now : r.finishedAt();
        return Math.max(0, Duration.between(r.startedAt(), end).toSeconds());
    }

    private static String text(Instant i) {
        return i == null ? null : i.toString();
    }

    /** JSON text to plain maps and lists; text that is not JSON comes back as it is. */
    public static Object parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(text, new TypeReference<Object>() {});
        } catch (Exception e) {
            return text;
        }
    }

    public static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot write JSON: " + e.getMessage(), e);
        }
    }
}
