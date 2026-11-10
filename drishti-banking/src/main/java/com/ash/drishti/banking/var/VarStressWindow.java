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

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.banking.compute.var.StressWindows;
import com.ash.drishti.banking.compute.var.VarMath;
import com.ash.drishti.kernel.api.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The stress window selection (VAR_GUIDE.md section 18.9): every window of {@code length} consecutive days of a node's long-history P&amp;L
 * of today's portfolio (the {@code pnl-history} kind) valued as a stressed VaR, the worst against the window of the date's stress
 * scenario set, the top candidates that do not overlap, each node's own worst window, and the alert when another window is worse by
 * more than the threshold. Computed when asked from the rows of the date, never stored; the rows read once per date and data version.
 * The freeze, the proposal and the decision are {@link VarStressProposals}'.
 */
final class VarStressWindow {

    /** One node's history: its values in date order. */
    record History(String node, String name, String level, String hierarchy, String[] dates, double[] pnl, boolean synthetic,
            String description, String batch) {}

    private final VarService svc;
    private final StressWindowProperties props;
    /** A date's histories by node id, per date and data version (a few dates kept). */
    private final com.github.benmanes.caffeine.cache.Cache<String, Map<String, History>> rows = com.github.benmanes.caffeine.cache.Caffeine
            .newBuilder().maximumWeight(256L << 20).weigher(VarStressWindow::weight).expireAfterAccess(java.time.Duration.ofHours(1)).build();

    /** A date's histories in bytes: each node's P&L (8 a day) and dates (about 56 a day as strings), plus its texts. */
    private static int weight(String key, Map<String, History> histories) {
        long b = 256L + 2L * key.length();
        for (History h : histories.values()) {
            b += 512L + 8L * (h.pnl() == null ? 0 : h.pnl().length) + 56L * (h.dates() == null ? 0 : h.dates().length);
        }
        return (int) Math.min(Integer.MAX_VALUE, b);
    }
    /** Each node's rolling VaR per date, version, length and confidence (bounded). */
    private final com.github.benmanes.caffeine.cache.Cache<String, double[]> rolling = com.github.benmanes.caffeine.cache.Caffeine
            .newBuilder().maximumWeight(64L << 20).weigher((String k, double[] v) -> 64 + 2 * k.length() + 8 * v.length)
            .expireAfterAccess(java.time.Duration.ofHours(1)).build();

    VarStressWindow(VarService svc, StressWindowProperties props) {
        this.svc = svc;
        this.props = props;
    }

    StressWindowProperties props() {
        return props;
    }

    /** The tool is on and the caller may read node-level P&amp;L of the whole bank; a 403 or 404 in words otherwise. */
    void check(Principal p) {
        if (!props.enabled() || !svc.props().enabled()) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the stress window selection is switched off on this server "
                    + "(drishti.var.stress-window.enabled)");
        }
        if (!svc.access().seesAll(p)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "the stress window selection reads the long-history P&L of every node: it needs "
                    + "a role that sees every node of the VaR hierarchies");
        }
    }

    /** The date asked, else the newest date with long-history rows. */
    LocalDate date(LocalDate asked) {
        if (asked != null) {
            return asked;
        }
        return svc.loader().latestOf(props.kind()).orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND,
                "no long-history P&L (kind " + props.kind() + ") on any date: the stress window selection needs the monthly batch's "
                        + "rows (VAR_GUIDE.md section 18.9)"));
    }

    /** A date's data version of the history kind. */
    String version(LocalDate date) {
        return svc.loader().dataVersionOf(props.kind(), date);
    }

    /** A date's histories by node id; a date without rows is said in words, never zero. */
    Map<String, History> histories(LocalDate date) {
        String key = date + "|" + version(date);
        return rows.get(key, k -> read(date));
    }

    private Map<String, History> read(LocalDate date) {
        Map<String, History> out = new TreeMap<>();
        for (DataNode d : svc.loader().documentsOf(props.kind(), date).values()) {
            String node = VarLoader.text(d, "NODE_ID");
            DataNode ds = d.get("HISTORY_DATES");
            DataNode pv = d.get("PNL");
            if (node == null || ds == null || pv == null || ds.size() == 0 || ds.size() != pv.size()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "the long-history P&L row of " + node + " on " + date + " is refused: "
                        + "HISTORY_DATES and PNL must have the same, non-zero length (contract pnl-history)");
            }
            String[] dates = new String[ds.size()];
            double[] pnl = new double[pv.size()];
            for (int i = 0; i < dates.length; i++) {
                dates[i] = ds.get(i).asText();
                pnl[i] = pv.get(i).asDouble();
                if (i > 0 && dates[i].compareTo(dates[i - 1]) <= 0) {
                    throw new DrishtiException(ErrorCode.BAD_REQUEST, "the long-history P&L row of " + node + " on " + date + " is refused: "
                            + "HISTORY_DATES must increase (" + dates[i - 1] + " then " + dates[i] + ")");
                }
            }
            DataNode syn = d.get("SYNTHETIC");
            out.put(node, new History(node, VarLoader.text(d, "NAME"), VarLoader.text(d, "LEVEL"), VarLoader.text(d, "HIERARCHY"), dates,
                    pnl, syn != null && !syn.isMissing() && !syn.isNull() && syn.asBoolean(), VarLoader.text(d, "DESCRIPTION"),
                    VarLoader.text(d, "SOURCE_BATCH")));
        }
        if (out.isEmpty()) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no long-history P&L (kind " + props.kind() + ") on " + date
                    + ": the stress window selection is not available for this date");
        }
        return out;
    }

    /** The node a scope names in a hierarchy: {@code BANK}, {@code DESK:DESK-RATES} or {@code DESK:RATES}, {@code MGMT~DESK-RATES}. */
    static String node(Map<String, History> hs, String hierarchy, String scope) {
        String s = scope == null || scope.isBlank() ? "BANK" : scope.strip();
        if (s.contains("~")) {
            if (hs.containsKey(s)) {
                return s;
            }
        } else {
            String h = hierarchy.toUpperCase(Locale.ROOT);
            String up = s.toUpperCase(Locale.ROOT);
            int colon = up.indexOf(':');
            List<String> tries = new ArrayList<>();
            if (colon < 0) {
                tries.add(h + "~" + up);
            } else {
                String word = up.substring(0, colon);
                String id = up.substring(colon + 1);
                tries.add(h + "~" + id);
                tries.add(h + "~" + word + "-" + id);
            }
            for (String t : tries) {
                if (hs.containsKey(t)) {
                    return t;
                }
            }
        }
        throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no long-history P&L for " + s + " in hierarchy " + hierarchy
                + ": the rows name " + String.join(", ", hs.keySet()));
    }

    double[] rolling(LocalDate date, History h, int length, double alpha) {
        String key = date + "|" + version(date) + "|" + h.node() + "|" + length + "|" + alpha;
        return rolling.get(key, k -> StressWindows.rolling(h.pnl(), length, alpha, svc.props().rule()));
    }

    /** The date's stress scenario set (its id, first and last day, size, synthetic), or null when it has none. */
    Map<String, Object> stressSet(LocalDate date) {
        try {
            VarLoader.Sets sets = svc.loader().sets(date, com.ash.drishti.api.AsOf.of(date));
            if (sets.stress().size() != 1) {
                return null;
            }
            DataNode s = sets.stress().values().iterator().next();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", VarLoader.text(s, "SCENARIO_SET_ID"));
            m.put("first", VarLoader.text(s, "WINDOW_START"));
            m.put("last", VarLoader.text(s, "WINDOW_END"));
            m.put("scenarios", s.get("SCENARIO_COUNT") == null || s.get("SCENARIO_COUNT").isMissing() ? null
                    : (int) s.get("SCENARIO_COUNT").asDouble());
            DataNode syn = s.get("SYNTHETIC");
            m.put("synthetic", syn != null && !syn.isMissing() && !syn.isNull() && syn.asBoolean());
            if (m.get("first") == null) {
                DataNode sd = s.get("SCENARIO_DATES");
                if (sd != null && sd.size() > 0) {
                    m.put("first", sd.get(0).asText());
                    m.put("last", sd.get(sd.size() - 1).asText());
                }
            }
            return m;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The number of days of the date's normal scenario set as the engine uses it (the scenario window when set), or 0. */
    int normalDays(LocalDate date) {
        try {
            DataNode n = svc.loader().sets(date, com.ash.drishti.api.AsOf.of(date)).normal(date);
            DataNode sd = n.get("SCENARIO_DATES");
            int count = sd == null ? 0 : sd.size();
            int w = svc.props().window();
            return w > 0 ? Math.min(w, count) : count;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** One window of a history as the answer gives it. */
    static Map<String, Object> window(History h, double[] r, int s, int length, double current) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("first", h.dates()[s]);
        m.put("last", h.dates()[s + length - 1]);
        m.put("var", r[s]);
        m.put("var10", r[s] * Math.sqrt(10));
        int wd = StressWindows.worstDay(h.pnl(), s, length);
        m.put("worstDay", h.dates()[wd]);
        m.put("worstPnl", h.pnl()[wd]);
        m.put("scenarios", length);
        m.put("vsCurrent", Double.isNaN(current) || current == 0 ? null : r[s] / current - 1);
        return m;
    }

    /**
     * The analysis of one node: the history's facts, the rolling stressed VaR of every window start, the worst window, the current one
     * (the stress set's), today's VaR from the last days, the candidates, the alert, and every node of the hierarchy's own worst window.
     */
    Map<String, Object> analyse(Principal p, String scope, String hierarchy, LocalDate asked, Integer lengthAsked, Double confidence) {
        check(p);
        LocalDate date = date(asked);
        Map<String, History> hs = histories(date);
        String hier = hierarchy == null || hierarchy.isBlank() ? "MGMT" : hierarchy.strip().toUpperCase(Locale.ROOT);
        String node = node(hs, hier, scope);
        History h = hs.get(node);
        hier = h.hierarchy() == null ? hier : h.hierarchy();
        int length = lengthAsked == null ? props.length() : lengthAsked;
        if (length < StressWindowProperties.MIN_LENGTH || length > h.pnl().length) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a window of " + length + " days is refused: between "
                    + StressWindowProperties.MIN_LENGTH + " and the history's " + h.pnl().length + " days");
        }
        double alpha = confidence == null ? svc.props().confidence() : VarProperties.checkConfidence(confidence > 1 ? confidence / 100 : confidence,
                "confidence");
        double[] r = rolling(date, h, length, alpha);
        Map<String, Object> set = stressSet(date);
        int cur = -1;
        String currentNote = null;
        if (set == null) {
            currentNote = "no stress scenario set on " + date + ": there is no current window to compare with";
        } else {
            int at = set.get("first") == null ? -1 : StressWindows.indexOf(h.dates(), (String) set.get("first"));
            if (at < 0 || !h.dates()[at].equals(set.get("first")) || at + length > h.pnl().length) {
                currentNote = "the stress set's window (" + set.get("first") + " to " + set.get("last") + ") is not inside the history ("
                        + h.dates()[0] + " to " + h.dates()[h.dates().length - 1] + ")";
            } else {
                cur = at;
            }
        }
        double current = cur < 0 ? Double.NaN : r[cur];
        int[] picks = StressWindows.candidates(r, length, props.top());
        int worst = picks.length == 0 ? -1 : picks[0];
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date.toString());
        out.put("node", Map.of("id", node, "name", h.name() == null ? node : h.name(), "level", h.level() == null ? "" : h.level(),
                "hierarchy", hier));
        out.put("hierarchies", hs.values().stream().map(History::hierarchy).filter(x -> x != null).distinct().sorted().toList());
        final String hh = hier;
        out.put("nodes", hs.values().stream().filter(x -> hh.equals(x.hierarchy()))
                .map(x -> Map.of("id", x.node(), "name", x.name() == null ? x.node() : x.name(), "level", x.level() == null ? "" : x.level()))
                .toList());
        Map<String, Object> hist = new LinkedHashMap<>();
        hist.put("kind", props.kind());
        hist.put("first", h.dates()[0]);
        hist.put("last", h.dates()[h.dates().length - 1]);
        hist.put("days", h.pnl().length);
        hist.put("synthetic", h.synthetic());
        hist.put("description", h.description());
        hist.put("batch", h.batch());
        hist.put("dataVersion", version(date));
        out.put("history", hist);
        out.put("length", length);
        out.put("confidence", alpha);
        out.put("rank", VarMath.rank(length, alpha));
        out.put("stressSet", set);
        out.put("current", cur < 0 ? null : window(h, r, cur, length, current));
        out.put("currentNote", currentNote);
        out.put("worst", worst < 0 ? null : window(h, r, worst, length, current));
        int days = normalDays(date);
        double today = days > 0 ? StressWindows.tail(h.pnl(), days, alpha, svc.props().rule()) : Double.NaN;
        out.put("today", Double.isNaN(today) ? null : Map.of("var", today, "days", days));
        Map<String, Object> alert = new LinkedHashMap<>();
        double gap = worst < 0 || cur < 0 ? Double.NaN : r[worst] / current - 1;
        alert.put("raised", !Double.isNaN(gap) && gap > props.alertThreshold() && worst != cur);
        alert.put("gap", Double.isNaN(gap) ? null : gap);
        alert.put("threshold", props.alertThreshold());
        out.put("alert", alert);
        List<Map<String, Object>> cands = new ArrayList<>();
        boolean currentListed = false;
        for (int i = 0; i < picks.length; i++) {
            Map<String, Object> w = window(h, r, picks[i], length, current);
            w.put("current", picks[i] == cur);
            w.put("worst", i == 0);
            currentListed |= picks[i] == cur;
            cands.add(w);
        }
        if (!currentListed && cur >= 0) {                                 // the current window always shown, in its place by VaR
            Map<String, Object> w = window(h, r, cur, length, current);
            w.put("current", true);
            w.put("worst", false);
            int at = 0;
            while (at < cands.size() && (double) cands.get(at).get("var") >= current) {
                at++;
            }
            cands.add(at, w);
        }
        for (int i = 0; i < cands.size(); i++) {
            cands.get(i).put("rank", i + 1);
        }
        out.put("candidates", cands);
        // the chart: every window start and its VaR
        out.put("series", Map.of("starts", java.util.Arrays.asList(h.dates()).subList(0, r.length), "var", r));
        out.put("byNode", byNode(date, hs, hier, length, alpha, set));
        return out;
    }

    /** Every node of the hierarchy: its VaR in the current window, its own worst window and the gap. */
    private List<Map<String, Object>> byNode(LocalDate date, Map<String, History> hs, String hier, int length, double alpha,
            Map<String, Object> set) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (History x : hs.values()) {
            if (!hier.equals(x.hierarchy()) || x.pnl().length < length) {
                continue;
            }
            double[] r = rolling(date, x, length, alpha);
            int[] w = StressWindows.candidates(r, length, 1);
            int cur = -1;
            if (set != null && set.get("first") != null) {
                int at = StressWindows.indexOf(x.dates(), (String) set.get("first"));
                cur = at >= 0 && x.dates()[at].equals(set.get("first")) && at + length <= x.pnl().length ? at : -1;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", x.node());
            m.put("name", x.name() == null ? x.node() : x.name());
            m.put("level", x.level() == null ? "" : x.level());
            m.put("current", cur < 0 ? null : r[cur]);
            m.put("worst", w.length == 0 ? null : r[w[0]]);
            m.put("worstFirst", w.length == 0 ? null : x.dates()[w[0]]);
            m.put("gap", cur < 0 || w.length == 0 || r[cur] == 0 ? null : r[w[0]] / r[cur] - 1);
            out.add(m);
        }
        out.sort(java.util.Comparator.comparingInt((Map<String, Object> m) -> levelOrder((String) m.get("level")))
                .thenComparing(m -> (String) m.get("id")));
        return out;
    }

    static int levelOrder(String level) {
        return switch (level == null ? "" : level) {
            case "bank" -> 0;
            case "division", "legal-entity" -> 1;
            case "desk" -> 2;
            default -> 3;
        };
    }
}
