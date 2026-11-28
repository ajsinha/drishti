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
package com.ash.drishti.server.home;

import com.ash.drishti.kernel.api.home.HomeData;
import com.ash.drishti.kernel.api.home.MeasureSource;
import com.ash.drishti.kernel.api.home.HomeTable;
import com.ash.drishti.kernel.api.home.HomeDefinition;

import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.server.loads.ExpectationService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The home tables (ROLE_HOME_SUTRAS.md 2): each Home's rows for one person, from the stored snapshots and the engines' own answers. Nothing
 * is computed here beyond a difference against the cut-off snapshot (a figure minus its earlier figure); the engines own every number and
 * {@code changes} owns the comparison. Every call reads as the person, so row rules and masks apply; a missing engine or day gives no rows.
 */
public final class HomeTables {

    /** The tables the server itself serves (a module adds more with a {@link HomeTable} bean). */
    public static final Set<String> BUILT_IN = Set.copyOf(List.of("home_meta", "home_kpis", "home_kpi_history", "home_exceptions", "home_changes", "home_desk_limits",
                "home_exposure", "home_digest", "home_stress", "home_mnemonics", "home_calendar", "home_news", "home_loads", "home_held_changes", "home_model_runs",
                "home_margin_calls", "home_backtest", "home_indicators", "home_indicator_history", "home_positions", "home_pnl_days", "home_market_tiles"));

    private final HomeStore store;
    private final HomeClock clock;
    private final Map<String, MeasureSource> sources = new LinkedHashMap<>();
    private final Map<String, HomeTable> extra = new LinkedHashMap<>();
    private final ChangeEngineHost changes;
    private final Supplier<List<ExpectationService.State>> loadStates;
    private final HomeProperties props;

    public HomeTables(HomeProperties props, HomeStore store, HomeClock clock, List<MeasureSource> sources, List<HomeTable> extra, ChangeEngineHost changes,
            Supplier<List<ExpectationService.State>> loadStates) {
        this.props = props;
        this.store = store;
        this.clock = clock;
        sources.forEach(s -> this.sources.put(s.engine(), s));
        extra.forEach(t -> this.extra.put(t.name(), t));
        this.changes = changes;
        this.loadStates = loadStates;
    }

    /** Every table a Home may read. */
    public Set<String> names() {
        Set<String> out = new java.util.TreeSet<>(BUILT_IN);
        out.addAll(extra.keySet());
        com.ash.drishti.common.Extensions.of(HomeTable.class).forEach(t -> out.add(t.name()));       // a loaded module's own tables
        return out;
    }

    public Optional<List<Map<String, Object>>> rows(String table, Principal p, HomeDefinition h) {
        return rows(table, p, h, null);
    }

    /** One table's rows; {@code since} (null: the Home's cut-off) is where a window-reading table such as {@code home_digest} starts. */
    public Optional<List<Map<String, Object>>> rows(String table, Principal p, HomeDefinition h, Instant since) {
        HomeTable.Context c = new HomeTable.Context(p, h, clock.today(), clock.previous(), since);
        HomeTable own = HomeSources.table(extra, table);
        if (own != null) {
            return own.homes().isEmpty() || own.homes().contains(h.name()) ? Optional.of(own.rows(c)) : Optional.empty();   // a module's table is its Homes' only
        }
        return switch (table) {
            case "home_digest" -> Optional.of(digest(c));
            case "home_meta" -> Optional.of(meta(c));
            case "home_kpis" -> Optional.of(kpis(c));
            case "home_kpi_history" -> Optional.of(history(c));
            case "home_exceptions" -> Optional.of(exceptions(c));
            case "home_changes" -> Optional.of(changes(c));
            case "home_desk_limits" -> Optional.of(limits(c, "var", "VAR", "desk", "DESK", "VAR"));
            case "home_exposure" -> Optional.of(limits(c, "exposure", "EXPOSURE", "counterparty", "CPTY", "EXPOSURE"));
            case "home_stress" -> Optional.of(List.of());      // TODO(R2): computed stress results (computed-risk-measures rule), no engine view yet
            case "home_mnemonics" -> Optional.of(mnemonics(c));
            case "home_loads" -> Optional.of(loads());
            case "home_calendar", "home_news" -> Optional.of(List.of());   // TODO(R2): the economy module's calendar and headline feeds
            // TODO(R2): each is served by the module that owns the data (a HomeTable bean): the edit module's held changes, the model-run ledger,
            // the banking module's margin calls (the collateral engine) and backtest (the VaR history: banking VarHomeBacktest, done), the economy
            // module's indicators; until then the panel shows its own empty words
            case "home_held_changes", "home_model_runs", "home_margin_calls", "home_backtest", "home_indicators", "home_indicator_history", "home_positions",
                "home_pnl_days", "home_market_tiles" ->
                Optional.of(List.of());
            default -> Optional.empty();
        };
    }

    private LocalTime cutoff(HomeDefinition h) {
        return h.measures().cutoff() == null ? props.cutoffTime() : LocalTime.parse(h.measures().cutoff());
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private List<Map<String, Object>> meta(HomeTable.Context c) {
        return List.of(row("BUSINESS_DATE", c.today().toString(), "SINCE", clock.words(cutoff(c.home()))));
    }

    private List<Map<String, Object>> kpis(HomeTable.Context c) {
        Map<String, Double> was = byLabel(store.kpis(c.previous()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (HomeDefinition.Kpi k : c.home().measures().kpis()) {
            MeasureSource s = HomeSources.find(sources, k.engine());
            if (s == null || !s.on()) {
                continue;                                       // an engine that is off drops its figures (engine switch)
            }
            s.kpi(c.person(), k.measure(), k.args(), c.today()).ifPresent(v -> {
                Double w = was.get(k.label());
                out.add(row("LABEL", k.label(), "VALUE", v, "DELTA", w == null ? null : v - w, "CAPTION", w == null ? "no earlier figure" : "since " + c.previous(),
                        "OPEN", k.open()));
            });
        }
        return out;
    }

    private List<Map<String, Object>> history(HomeTable.Context c) {
        Set<String> mine = new HashSet<>();
        c.home().measures().kpis().forEach(k -> mine.add(k.label()));
        int most = c.home().measures().kpis().stream().mapToInt(HomeDefinition.Kpi::history).max().orElse(0);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = most; i >= 1; i--) {
            LocalDate d = c.today().minusDays(i);
            for (HomeData.KpiPoint k : store.kpis(d)) {
                if (mine.contains(k.label())) {
                    out.add(row("LABEL", k.label(), "DATE", d.toString(), "VALUE", k.value()));
                }
            }
        }
        return out;
    }

    private List<Map<String, Object>> limits(HomeTable.Context c, String engine, String measure, String kind, String nameCol, String valueCol) {
        MeasureSource s = HomeSources.find(sources, engine);
        if (s == null || !s.on()) {
            return List.of();
        }
        Map<String, Double> was = new LinkedHashMap<>();
        store.snapshot(c.previous()).stream().filter(v -> v.measure().equals(measure) && v.subjectKind().equals(kind)).forEach(v -> was.put(v.subject(), v.value()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (HomeData.LimitRow r : s.limits(c.person(), measure, kind, c.today())) {
            Double w = was.get(r.name());
            out.add(row(nameCol, r.name(), valueCol, r.value(), "LIMIT", r.limit(), "CHANGE", w == null ? null : r.value() - w));
        }
        out.sort(Comparator.comparingDouble((Map<String, Object> m) -> use(m)).reversed());
        return out;
    }

    private static double use(Map<String, Object> m) {
        Object v = m.values().stream().skip(1).findFirst().orElse(0), l = m.get("LIMIT");
        return v instanceof Number a && l instanceof Number b && b.doubleValue() != 0 ? a.doubleValue() / b.doubleValue() : 0;
    }

    // ----------------------------------------------------------------------------------------------------------- exceptions

    private List<Map<String, Object>> exceptions(HomeTable.Context c) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (HomeDefinition.Rule r : c.home().measures().exceptions()) {
            String[] cmp = r.when().trim().split("\\s+");
            if (cmp.length == 3 && cmp[1].equals(">=")) {
                measureRule(c, r, cmp[0], Double.parseDouble(cmp[2]), out);
            } else if (r.when().equals("load-failed") || r.when().equals("feed-late")) {
                stateRule(c, r, out);
            }                                                   // TODO(R2): order-rejected and other trading states, from the trading module
        }
        List<String> order = List.of("high", "medium", "low");
        out.sort(Comparator.comparingInt(m -> order.indexOf(m.get("SEVERITY"))));
        return out;
    }

    private void measureRule(HomeTable.Context c, HomeDefinition.Rule r, String measure, double threshold, List<Map<String, Object>> out) {
        for (HomeDefinition.Follow f : c.home().measures().follow()) {
            MeasureSource s = HomeSources.find(sources, f.engine());
            if (!f.measure().equals(measure) || s == null || !s.on()) {
                continue;
            }
            for (HomeData.MeasureValue v : s.values(c.person(), measure, f.subjects(), c.today())) {
                if (v.value() >= threshold && !already(out, r, v.subject())) {
                    out.add(row("SEVERITY", r.severity(), "MESSAGE", text(r.text(), v.subject(), measure, measure.equals("LIM_USE") ? Math.round(v.value() * 100) + "%" : compact(v.value()), ""),
                            "SUBJECT", v.subject(), "COMMAND", r.open().replace("{subject}", v.subject())));
                }
            }
        }
    }

    /** A subject that already has a higher rule's row does not get the lower one (95% is high, not also medium). */
    private static boolean already(List<Map<String, Object>> out, HomeDefinition.Rule r, String subject) {
        return out.stream().anyMatch(m -> subject.equals(m.get("SUBJECT")));
    }

    private void stateRule(HomeTable.Context c, HomeDefinition.Rule r, List<Map<String, Object>> out) {
        for (ExpectationService.State s : loadStates.get()) {
            boolean failed = ExpectationService.FAILED.equals(s.state());
            boolean late = ExpectationService.LATE.equals(s.state()) || ExpectationService.MISSING.equals(s.state());
            if (r.when().equals("load-failed") ? !failed : !late) {
                continue;
            }
            String subject = s.pack() + "/" + s.kind();
            long minutes = s.deadline() == null ? 0 : Math.max(0, Duration.between(s.deadline(), clock.now()).toMinutes());
            out.add(row("SEVERITY", r.severity(), "MESSAGE", text(r.text(), subject, "", "", String.valueOf(minutes)), "SUBJECT", subject, "COMMAND", r.open()));
        }
    }

    private static String text(String t, String subject, String measure, String value, String minutes) {
        return t.replace("{subject}", subject).replace("{measure}", measure).replace("{value}", value).replace("{minutes}", minutes);
    }

    private static String compact(double v) {
        double a = Math.abs(v);
        return a >= 1e9 ? String.format("%.1fB", v / 1e9) : a >= 1e6 ? String.format("%.1fM", v / 1e6) : a >= 1e3 ? String.format("%.0fK", v / 1e3) : String.format("%.0f", v);
    }

    /** The loads that need a look (late, missing, failed), as the loader's expectations state them. */
    private List<Map<String, Object>> loads() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExpectationService.State s : loadStates.get()) {
            out.add(row("PACK", s.pack(), "KIND", s.kind(), "BUSINESS_DATE", String.valueOf(s.businessDate()), "STATE", s.state(), "DEADLINE",
                    s.deadline() == null ? "" : s.deadline().toString()));
        }
        return out;
    }

    // ----------------------------------------------------------------------------------------------------------- changes

    private List<Map<String, Object>> changes(HomeTable.Context c) {
        if (!changes.available()) {
            return List.of();                                   // the panel says why: GET /api/v1/homes/{home}/status
        }
        Set<String> visible = new HashSet<>();
        List<HomeData.MeasureValue> now = new ArrayList<>();
        for (HomeDefinition.Follow f : c.home().measures().follow()) {
            MeasureSource s = HomeSources.find(sources, f.engine());
            if (s != null && s.on()) {
                List<HomeData.MeasureValue> v = s.values(c.person(), f.measure(), f.subjects(), c.today());
                now.addAll(v);
                v.forEach(x -> visible.add(x.subjectKind() + "|" + x.subject()));
                s.values(c.person(), f.measure(), f.subjects(), c.previous()).forEach(x -> visible.add(x.subjectKind() + "|" + x.subject()));
            }
        }
        List<HomeData.MeasureValue> then = store.snapshot(c.previous()).stream().filter(v -> visible.contains(v.subjectKind() + "|" + v.subject())).toList();
        java.time.Instant since = clock.cutoff(c.previous(), cutoff(c.home()));
        List<HomeData.Event> events = new ArrayList<>();
        for (LocalDate d : List.of(c.previous(), c.today())) {
            for (HomeData.Event e : store.events(d, since)) {
                if (c.home().measures().events().contains(e.kind()) && (e.subjectKind().equals("feed") || visible.contains(e.subjectKind() + "|" + e.subject()))) {
                    events.add(e);                              // TODO(R2): trade events by the trade's row rules, not by the measures' subjects
                }
            }
        }
        List<HomeData.Driver> drivers = store.drivers(c.today()).stream().filter(d -> visible.contains(d.subjectKind() + "|" + d.subject())).toList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (HomeData.Change ch : changes.changes(c.person(), then, now, c.home().measures().follow(), events, drivers, 200)) {
            out.add(row("SUBJECT", ch.subject(), "MEASURE", ch.measure(), "KIND", ch.kind(), "THEN", ch.then(), "NOW", ch.now(), "CHANGE", ch.change(), "WHY",
                    ch.why(), "COMMAND", ch.command()));
        }
        return out;
    }

    /**
     * What the server recorded since {@code c.since()} (the Home's cut-off when none), for "since your last visit": the events of the Home's kinds
     * (loads, limit moves and the like) and each followed measure that has moved since the snapshot of that day, for the subjects the person may
     * see. Newest first; the figures are the engines' own, only compared here.
     */
    private List<Map<String, Object>> digest(HomeTable.Context c) {
        Instant since = c.since() != null ? c.since() : clock.cutoff(c.previous(), cutoff(c.home()));
        LocalDate first = since.atZone(clock.zone()).toLocalDate();
        if (first.isBefore(c.today().minusDays(30))) {
            first = c.today().minusDays(30);
        }
        Set<String> visible = new HashSet<>();
        List<HomeData.MeasureValue> now = new ArrayList<>();
        for (HomeDefinition.Follow f : c.home().measures().follow()) {
            MeasureSource s = HomeSources.find(sources, f.engine());
            if (s != null && s.on()) {
                List<HomeData.MeasureValue> v = s.values(c.person(), f.measure(), f.subjects(), c.today());
                now.addAll(v);
                v.forEach(x -> visible.add(x.subjectKind() + "|" + x.subject()));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(c.today()); d = d.plusDays(1)) {
            for (HomeData.Event e : store.events(d, since)) {
                if (c.home().measures().events().contains(e.kind()) && (e.subjectKind().equals("feed") || visible.contains(e.subjectKind() + "|" + e.subject()))) {
                    out.add(row("AT", e.at().toString(), "KIND", e.kind(), "SUBJECT", e.subject(), "TEXT", e.text()));
                }
            }
        }
        LocalDate ref = first.isAfter(c.previous()) ? c.previous() : first;          // the snapshot the window starts from: the day's, else the nearest earlier
        while (!store.hasSnapshot(ref) && ref.isAfter(c.today().minusDays(30))) {
            ref = ref.minusDays(1);
        }
        Map<String, Double> then = new LinkedHashMap<>();
        store.snapshot(ref).forEach(v -> then.put(v.subjectKind() + "|" + v.subject() + "|" + v.measure(), v.value()));
        for (HomeData.MeasureValue v : now) {
            Double was = then.get(v.subjectKind() + "|" + v.subject() + "|" + v.measure());
            HomeDefinition.Follow f = c.home().measures().follow().stream().filter(x -> x.measure().equals(v.measure())).findFirst().orElse(null);
            if (was == null || f == null) {
                continue;
            }
            double diff = v.value() - was;
            if (Math.abs(diff) > 0 && Math.abs(diff) >= f.minAbs() && (was == 0 || Math.abs(diff / was) >= f.minRel())) {
                out.add(row("AT", clock.now().toString(), "KIND", "move", "SUBJECT", v.subject(), "TEXT",
                        v.subject() + " " + v.measure() + " " + compact(was) + " to " + compact(v.value()) + " (" + (diff > 0 ? "+" : "") + compact(diff) + ")"));
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("AT"))).reversed());
        return out.size() > 50 ? new ArrayList<>(out.subList(0, 50)) : out;
    }

    /** The Home's own quick list (its measures file); TODO(R2): the person's most used mnemonics from their settings go first. */
    private List<Map<String, Object>> mnemonics(HomeTable.Context c) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, String> m : c.home().measures().mnemonics()) {
            out.add(row("MNEMONIC", m.get("mnemonic"), "ABOUT", m.getOrDefault("about", "")));
        }
        return out;
    }

    private static Map<String, Double> byLabel(List<HomeData.KpiPoint> points) {
        Map<String, Double> m = new LinkedHashMap<>();
        points.forEach(p -> m.put(p.label(), p.value()));
        return m;
    }
}
