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
package com.ash.drishti.server.var;

import com.ash.drishti.compute.var.PositionRules;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The VaR engine (VAR_GUIDE.md): value at risk for any set of trades from the stored P&amp;L vectors, in the server.
 *
 * @param enabled the engine and its API are on
 * @param confidence VaR's confidence (0.99)
 * @param esConfidence ES's confidence (0.975)
 * @param rankRule {@code kth-worst} (k = ceil(N (1 - a)): the 6th worst of 509) or {@code interpolated}
 * @param componentWindow the scenarios around the VaR rank component VaR averages over (5)
 * @param componentScaleToVar components scaled so they add up to the VaR exactly (true), else to the smoothed VaR
 * @param threads the cores the vector sums use (0: every core)
 * @param parallelRows the fewest rows worth splitting across threads
 * @param memoryBudgetMb the most memory one date's vectors may take (0: a quarter of the heap); a date that would exceed it is refused
 *     with the reason (503, DRS-5204), never loaded in part
 * @param rowsPerChunk rows per chunk of the vector store
 * @param hotDates business dates kept loaded at once (the newest first; older ones are dropped)
 * @param historyDays the history and backtest window (250 business days)
 * @param histogramBins the bins of the P&amp;L distribution sent to the screen
 * @param maxParts the most parts a contribution table computes at once (more: the largest by gross size, and said)
 * @param kinds the kinds the engine reads (vectors, hierarchy, scenario set, portfolios) and serves (positions)
 * @param access per role, the hierarchy nodes it may see and how: {@code MGMT~DESK-RATES: positions} (its numbers, children, positions
 *     and trades) or {@code aggregate} (numbers and children, never a position); a role without an entry sees every node at
 *     {@code positions} level when it may open the vector kind, else nothing
 * @param pathPage rows of the P&amp;L path table per page
 * @param positions how positions are derived from the trades when no loaded pack says (a pack's {@code positions:} section wins)
 * @param official the official end-of-day captures and their published results
 * @param tier where a loaded date's vectors are kept: the memory-mapped hot file (default) or the heap (VAR_GUIDE.md section 26)
 * @param currencies the reporting currencies built ahead (VAR_GUIDE.md section 15.11): after a date's USD book loads, each listed
 *     currency's book is built in the background from it and kept as its own hot file, so a VaR in a listed currency never waits on its
 *     first ask; USD always first; default USD, EUR, CAD, JPY, GBP; any other currency with FX is still built on first ask
 * @param currencyRetentionDates the newest business dates whose listed currencies are built ahead and kept on disk (5, one business
     *     week); an older date's listed-currency files are deleted when the next date is built ahead (on first ask they are built again)
 * @param rootName the name of every hierarchy's root node (the top of the VaR tree, "Drishti Bank" in the demo data); blank keeps the
 *     hierarchy data's own {@code NAME}
 * @param rootNames the name of one hierarchy's root (hierarchy id to name, e.g. MGMT, LEGAL); wins over {@code rootName} for it
 * @param intraday the intraday updates' settings: the PV topic's window (VAR_GUIDE.md section 8.4, Kafka)
 * @param inputs an optional pin per source system of what its records are (VAR_GUIDE.md section 15.13): the form ({@code pv}, {@code pnl}
 *     or {@code any}) and the grain ({@code trade}, {@code position} or {@code any}); {@code default} for the systems not listed. Every
 *     record says what it is ({@code VALUE_TYPE}, {@code GRAIN}); a pin is a cross-check: a record that disagrees is refused
 *     ({@code DRS-5209}). Read at start: a change applies after a restart, from the next date loaded
 * @param scenarioWindow how many scenarios the engine uses (VAR_GUIDE.md section 18.5): {@code all} (the whole scenario set, its length
 *     509 in the demo) or a number N, the newest N scenarios of each date's set (250: one year, about 500: two); at least 20; a set
 *     shorter than N is used whole, with a warning in the provenance
 * @param hierarchies the hierarchies (2.9.5, VAR_GUIDE.md section 16): id to its settings ({@code title}, {@code source:
 *     risk-hierarchy|columns}, {@code levels: "LIMIT_LETTER > DESK"}, {@code level-names}, {@code root}, {@code ids: path|leaf},
 *     {@code word}, {@code limits}); when set, wins over a loaded pack's {@code hierarchies:} block; neither: MGMT and LEGAL from the
 *     {@code risk-hierarchy} rows, as before 2.9.5
 */
@ConfigurationProperties("drishti.var")
public record VarProperties(Boolean enabled, Double confidence, Double esConfidence, String rankRule, Integer componentWindow,
        Boolean componentScaleToVar, Integer threads, Integer parallelRows, Long memoryBudgetMb, Integer rowsPerChunk, Integer hotDates,
        Integer historyDays, Integer histogramBins, Integer maxParts, Kinds kinds, Map<String, Map<String, String>> access, Integer pathPage,
        Positions positions, Official official, Tier tier, List<String> currencies, Integer currencyRetentionDates, String rootName,
        Map<String, String> rootNames, Intraday intraday, Map<String, Input> inputs, String scenarioWindow,
        Map<String, Map<String, Object>> hierarchies) {

    /** The smallest scenario window: fewer scenarios than this make a tail of one or two days. */
    public static final int MIN_WINDOW = 20;

    /** The scenario window: 0 for the whole set, else N (the newest N scenarios of each date's set). */
    public int window() {
        return scenarioWindow.equals("all") ? 0 : Integer.parseInt(scenarioWindow);
    }

    /** Checks a confidence (VaR's, ES's, or one asked per request): in (0.5, 1). */
    public static double checkConfidence(Double c, String what) {
        if (c == null || !Double.isFinite(c) || c <= 0.5 || c >= 1) {
            throw new IllegalArgumentException(what + " " + c + " is not in (0.5, 1): 0.99 or 0.95 for VaR, 0.975 for ES");
        }
        return c;
    }

    /** Checks a scenario window (the setting, or one asked per request): all, or a number of at least {@link #MIN_WINDOW}. */
    public static String checkWindow(String w, String what) {
        String v = w == null || w.isBlank() ? "all" : w.strip().toLowerCase(Locale.ROOT);
        if (v.equals("all")) {
            return v;
        }
        int n;
        try {
            n = Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " " + w + " is neither all nor a number of scenarios");
        }
        if (n < MIN_WINDOW) {
            throw new IllegalArgumentException(what + " " + n + " is below " + MIN_WINDOW + " scenarios: too few for a tail (250 is one year)");
        }
        return Integer.toString(n);
    }

    /**
     * What a source system sends (VAR_GUIDE.md section 15.13): the form and the grain, each {@code any} when not pinned.
     *
     * @param form {@code pv} (scenario PVs, {@code VALUE_TYPE: PV}), {@code pnl} (P&amp;L vectors, {@code PNL}) or {@code any}
     * @param grain {@code trade} ({@code GRAIN: TRADE}), {@code position} ({@code POSITION}) or {@code any}
     */
    public record Input(String form, String grain) {
        public Input {
            form = norm(form, List.of("PV", "PNL"), "form");
            grain = norm(grain, List.of("TRADE", "POSITION"), "grain");
        }

        private static String norm(String v, List<String> allowed, String what) {
            String u = v == null || v.isBlank() ? "ANY" : v.strip().toUpperCase(Locale.ROOT);
            if (!u.equals("ANY") && !allowed.contains(u)) {
                throw new IllegalArgumentException("drishti.var.inputs: " + what + " " + v + " is not one of " + allowed + " or any");
            }
            return u;
        }

        /** Says it in words: {@code pv, position}. */
        @Override
        public String toString() {
            return form.toLowerCase(Locale.ROOT) + ", " + grain.toLowerCase(Locale.ROOT);
        }
    }

    /** A source system's pin: its own, else {@code default}, else any (system names in any case). */
    public Input input(String system) {
        Input own = system == null ? null : inputs.get(system.strip().toUpperCase(Locale.ROOT));
        return own != null ? own : inputs.getOrDefault("DEFAULT", new Input(null, null));
    }

    /**
     * The intraday updates (VAR_GUIDE.md section 8.4).
     *
     * @param kafka the PV topic's micro-batches (the {@code var-pv-stream} connector)
     */
    public record Intraday(Kafka kafka) {
        public Intraday {
            kafka = kafka == null ? new Kafka(null, null) : kafka;
        }
    }

    /**
     * The PV topic's micro-batches: everything received in a window is appended to the lake as one intraday batch and applied.
     *
     * @param window how long a window collects ({@code 5m}: every five minutes; {@code 30s}, {@code 1h})
     * @param maxTrades a window is applied earlier once this many trades wait (500)
     */
    public record Kafka(String window, Integer maxTrades) {
        public Kafka {
            window = window == null || window.isBlank() ? "5m" : window.strip();
            maxTrades = maxTrades == null || maxTrades < 1 ? 500 : maxTrades;
        }

        /** The window as a duration. */
        public java.time.Duration windowDuration() {
            return java.time.Duration.ofMillis((long) (VarPvStreamPlugin.seconds(window) * 1000));
        }
    }

    /**
     * The name a hierarchy's root node shows: {@code root-names} for the hierarchy, else {@code root-name}, else the data's (VAR_GUIDE.md
     * section 20, settings). Null for a node that is not a root.
     */
    public String rootName(String hierarchy, String parentId, String dataName) {
        if (parentId != null && !parentId.isBlank()) {
            return dataName;
        }
        String own = hierarchy == null ? null : rootNames.get(hierarchy.strip().toUpperCase(Locale.ROOT));
        if (own != null && !own.isBlank()) {
            return own.strip();
        }
        return rootName.isBlank() ? dataName : rootName.strip();
    }

    /** What the configured root names add to a hot file's digest (empty when none is set: the files of earlier releases stay valid). */
    public String rootNamesKey() {
        return rootName.isBlank() && rootNames.isEmpty() ? "" : "|root=" + rootName.strip() + new java.util.TreeMap<>(rootNames);
    }

    /**
     * The kinds the engine reads, as the market-risk pack names them; {@code position} is the kind its derived positions are served as.
     * A bank sends its trades' scenario P&amp;L as P&amp;L vectors ({@code vector}, USD) or as scenario PVs in each trade's currency
     * ({@code scenarioPv}, 2.9.4), or both for different trades; the FX of the scenarios is {@code fxScenario} when the batch supplies
     * it, else derived from the FX history of {@code fxSpot} (the market-data pack's). The nodes' VaR limits by hierarchy and node id
     * are {@code limit} (2.9.5, {@code var-limit}), read for the hierarchies whose {@code limits} names it.
     */
    public record Kinds(String vector, String position, String hierarchy, String scenarioSet, String portfolio, String scenarioPv,
            String fxScenario, String fxSpot, String limit) {
        public Kinds {
            vector = vector == null ? "pnl-vector" : vector;
            position = position == null ? "position" : position;
            hierarchy = hierarchy == null ? "risk-hierarchy" : hierarchy;
            scenarioSet = scenarioSet == null ? "scenario-set" : scenarioSet;
            portfolio = portfolio == null ? "portfolio" : portfolio;
            scenarioPv = scenarioPv == null ? "scenario-pv" : scenarioPv;
            fxScenario = fxScenario == null ? "fx-scenario" : fxScenario;
            fxSpot = fxSpot == null ? "fx-spot" : fxSpot;
            limit = limit == null ? "var-limit" : limit;
        }

        public List<String> all() {
            return List.of(vector, position, hierarchy, scenarioSet, portfolio, scenarioPv, fxScenario, fxSpot, limit);
        }

        /**
         * The kinds whose load changes a date's answers (the data-load signal reloads the date): the inputs. The FX history
         * ({@code fxSpot}) is not one: a market-data load does not reload VaR; a date reads the history it finds when it loads.
         */
        public List<String> inputs() {
            return List.of(vector, hierarchy, scenarioSet, portfolio, scenarioPv, fxScenario, limit);
        }
    }

    /**
     * The server's own position rules (VAR_GUIDE.md section 15.6), used when no loaded pack has a {@code positions:} section.
     *
     * @param liveStatuses the trade statuses that count on their date (default NEW, VALIDATED, AMENDED)
     * @param rules first match wins: {@code name}, {@code groups} (PRODUCT_GROUP), {@code types} (PRODUCT_TYPE), {@code key} (the fields
     *     whose first present value keys the position); none: every trade is a position of its own
     */
    public record Positions(List<String> liveStatuses, List<Rule> rules) {
        public record Rule(String name, List<String> groups, List<String> types, List<String> key) {}

        public PositionRules toRules() {
            List<PositionRules.Rule> out = new ArrayList<>();
            int n = 0;
            for (Rule r : rules == null ? List.<Rule>of() : rules) {
                n++;
                out.add(new PositionRules.Rule(r.name() == null ? "rule-" + n : r.name(), set(r.groups()), set(r.types()),
                        r.key() == null ? List.of() : r.key()));
            }
            return new PositionRules(liveStatuses, out);
        }

        private static java.util.Set<String> set(List<String> l) {
            return l == null ? java.util.Set.of() : new LinkedHashSet<>(l);
        }
    }

    /**
     * The official end-of-day captures (VAR_GUIDE.md section 19).
     *
     * @param enabled capture every node of every hierarchy when a date's inputs are complete (after the data-load signal)
     * @param publish also write them to {@code <root>/risk-results/var-official} for other systems
     * @param root the Delta root the results are published under (the risk lake's root)
     * @param retentionYears the years the published table keeps its log and removed files (time travel), 7 by default
     * @param onStart at start, capture the newest date when it has no official record yet (a lake built while the server was down)
     * @param currencies the reporting currencies captured officially (USD always, first and published; others kept in the content
     *     store beside it, VAR_GUIDE.md section 19)
     * @param jsonl also write each published date as JSON Lines beside the table (2.9.4, true by default)
     */
    public record Official(Boolean enabled, Boolean publish, String root, Integer retentionYears, Boolean onStart, List<String> currencies,
            Boolean jsonl) {
        public Official {
            jsonl = jsonl == null || jsonl;                 // 2.9.4: each published date also as JSON Lines beside the table
            enabled = enabled == null || enabled;
            publish = publish == null || publish;
            root = root == null || root.isBlank() ? null : root;
            retentionYears = retentionYears == null || retentionYears < 1 ? 7 : retentionYears;
            onStart = onStart == null || onStart;
            currencies = currencyList(currencies == null ? List.of() : currencies);
        }
    }

    /**
     * The tiered memory (VAR_GUIDE.md section 26): where a loaded date's vectors live.
     *
     * @param mode {@code mapped} (default): each loaded date becomes a local hot file (float32 position rows in hierarchy order, the
     *     aggregates in float64) mapped into memory, the heap keeping the aggregates, columns and links; {@code heap}: every trade's vector
     *     in float64 on the heap (as before 2.8.0)
     * @param dir the hot files' folder ({@code <data folder>/var/hot}), one folder per business date
     * @param diskDates the most hot files kept on disk (the least recently used beyond it are deleted); a date not open is mapped
     *     again from its file when asked, without reading the lake
     * @param keepOnShutdown keep the hot files when the server stops (false: they are deleted at shutdown, and at start any left over)
     * @param closeGraceSeconds how long an evicted date stays mapped for the requests still reading it before it is unmapped
     */
    public record Tier(String mode, String dir, Integer diskDates, Boolean keepOnShutdown, Integer closeGraceSeconds) {
        public Tier {
            mode = mode == null || mode.isBlank() ? "mapped" : mode.trim().toLowerCase(Locale.ROOT);
            mode = mode.equals("heap") ? "heap" : "mapped";
            dir = dir == null || dir.isBlank() ? com.ash.drishti.api.DataDir.under("var/hot") : dir;
            diskDates = diskDates == null || diskDates < 1 ? 60 : diskDates;
            keepOnShutdown = keepOnShutdown != null && keepOnShutdown;
            closeGraceSeconds = closeGraceSeconds == null || closeGraceSeconds < 0 ? 60 : closeGraceSeconds;
        }

        public boolean mapped() {
            return mode.equals("mapped");
        }
    }

    public VarProperties {
        enabled = enabled == null || enabled;
        // refused at start when out of range (never silently replaced): VaR at 99% or 95%, ES at 97.5% (VAR_GUIDE.md section 18.5)
        confidence = confidence == null ? 0.99 : checkConfidence(confidence, "drishti.var.confidence");
        esConfidence = esConfidence == null ? 0.975 : checkConfidence(esConfidence, "drishti.var.es-confidence");
        scenarioWindow = checkWindow(scenarioWindow, "drishti.var.scenario-window");
        rankRule = rankRule == null || rankRule.isBlank() ? "kth-worst" : rankRule.trim().toLowerCase(Locale.ROOT);
        componentWindow = componentWindow == null || componentWindow < 1 ? 5 : componentWindow;
        componentScaleToVar = componentScaleToVar == null || componentScaleToVar;
        threads = threads == null || threads <= 0 ? Runtime.getRuntime().availableProcessors() : threads;
        parallelRows = parallelRows == null || parallelRows <= 0 ? 4096 : parallelRows;
        memoryBudgetMb = memoryBudgetMb == null || memoryBudgetMb <= 0 ? Runtime.getRuntime().maxMemory() / 4 / (1024 * 1024) : memoryBudgetMb;
        rowsPerChunk = rowsPerChunk == null || rowsPerChunk <= 0 ? 65_536 : rowsPerChunk;
        hotDates = hotDates == null || hotDates <= 0 ? 2 : hotDates;
        historyDays = historyDays == null || historyDays <= 1 ? 250 : historyDays;
        histogramBins = histogramBins == null || histogramBins < 5 ? 42 : histogramBins;
        maxParts = maxParts == null || maxParts <= 0 ? 5000 : maxParts;
        kinds = kinds == null ? new Kinds(null, null, null, null, null, null, null, null, null) : kinds;
        hierarchies = hierarchies == null ? Map.of() : hierarchies;
        access = access == null ? Map.of() : access;
        pathPage = pathPage == null || pathPage <= 0 ? 60 : pathPage;
        positions = positions == null ? new Positions(null, null) : positions;
        official = official == null ? new Official(null, null, null, null, null, null, null) : official;
        tier = tier == null ? new Tier(null, null, null, null, null) : tier;
        currencies = currencyList(currencies == null ? List.of("USD", "EUR", "CAD", "JPY", "GBP") : currencies);
        currencyRetentionDates = currencyRetentionDates == null || currencyRetentionDates < 1 ? 5 : currencyRetentionDates;
        rootName = rootName == null ? "" : rootName;
        intraday = intraday == null ? new Intraday(null) : intraday;
        Map<String, Input> in = new java.util.LinkedHashMap<>();
        if (inputs != null) {
            inputs.forEach((sys, v) -> in.put(sys.strip().toUpperCase(Locale.ROOT), v == null ? new Input(null, null) : v));
        }
        inputs = Map.copyOf(in);
        Map<String, String> rn = new java.util.LinkedHashMap<>();
        if (rootNames != null) {
            rootNames.forEach((h, n) -> {
                if (h != null && n != null && !n.isBlank()) {
                    rn.put(h.strip().toUpperCase(Locale.ROOT), n.strip());
                }
            });
        }
        rootNames = Map.copyOf(rn);
    }

    /** ISO codes in upper case, USD first and once, the rest in the order given (duplicates and blanks dropped). */
    static List<String> currencyList(List<String> given) {
        List<String> c = new ArrayList<>(List.of("USD"));
        for (String x : given) {
            String u = x == null ? "" : x.trim().toUpperCase(Locale.ROOT);
            if (u.matches("[A-Z]{3}") && !c.contains(u)) {
                c.add(u);
            }
        }
        return List.copyOf(c);
    }

    public com.ash.drishti.compute.var.VarMath.RankRule rule() {
        return rankRule.startsWith("interp") ? com.ash.drishti.compute.var.VarMath.RankRule.INTERPOLATED
                : com.ash.drishti.compute.var.VarMath.RankRule.KTH_WORST;
    }

    public com.ash.drishti.compute.var.VarCalc calc() {
        return new com.ash.drishti.compute.var.VarCalc(confidence, esConfidence, rule(), componentWindow, componentScaleToVar);
    }
}
