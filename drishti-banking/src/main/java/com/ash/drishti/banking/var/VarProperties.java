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

import com.ash.drishti.banking.compute.var.PositionRules;
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
 * @param memoryBudgetMb the heap tier ({@code tier.mode: heap}): the most memory one date's vectors may take (0: a quarter of the heap);
 *     a date that would exceed it is refused with the reason (503, DRS-5204), never loaded in part. The mapped tier (the default) is not
 *     bounded by it: its rows are on disk, so it is bounded by the disk (a hard limit) and the page cache (advisory)
 * @param rowsPerChunk rows per chunk of the vector store
 * @param hotDates business dates kept open at once (10): the least recently used leaves first; the date just used, the newest business
 *     date and the dates pinned in memory always stay (VAR_GUIDE.md section 26.8)
 * @param historyDays the history and backtest window (250 business days)
 * @param histogramBins the bins of the P&amp;L distribution sent to the screen
 * @param maxParts the most parts a contribution table computes at once (more: the largest by gross size, and said)
 * @param kinds the kinds the engine reads (vectors, scenario set, portfolios, limits) and serves (positions)
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
 * @param rootName the name of every hierarchy's root node (the top of the VaR tree); blank keeps the hierarchy's own {@code root.name}
 *     (its title when it sets none)
 * @param rootNames the name of one hierarchy's root (hierarchy id to name, e.g. MGMT, LEGAL); wins over {@code rootName} for it
 * @param intraday the intraday updates' settings: the PV topic's window (VAR_GUIDE.md section 8.4, Kafka)
 * @param inputs an optional pin per source system of what its records are (VAR_GUIDE.md section 15.13): the form ({@code pv}, {@code pnl}
 *     or {@code any}) and the grain ({@code trade}, {@code position} or {@code any}); {@code default} for the systems not listed. Every
 *     record says what it is ({@code VALUE_TYPE}, {@code GRAIN}); a pin is a cross-check: a record that disagrees is refused
 *     ({@code DRS-5209}). Read at start: a change applies after a restart, from the next date loaded
 * @param scenarioWindow how many scenarios the engine uses (VAR_GUIDE.md section 18.5): {@code all} (the whole scenario set, its length
 *     509 in the demo) or a number N, the newest N scenarios of each date's set (250: one year, about 500: two); at least 20; a set
 *     shorter than N is used whole, with a warning in the provenance
 * @param hierarchies the hierarchies (VAR_GUIDE.md section 15.14), any number, each built from the trades' promoted columns: id to
 *     its settings ({@code title}, {@code levels: "DIVISION > DESK > TT_BOOK"}, {@code level-names}, {@code root}, {@code ids: path|leaf},
 *     {@code word}, {@code limits}, {@code empty-label}); when set, wins over a loaded pack's {@code hierarchies:} block (the market-risk
 *     pack's MGMT, LEGAL and LL); neither: no hierarchy
 * @param hierarchy what every hierarchy shares: the name of a (Blank) node ({@code empty-label}, default {@code (Blank)})
 * @param viewCacheMb the most memory the viewers' views (bitsets per date, version, currency and role set) may take (64); idle ones go
 *     after an hour and a date's go with it (2.9.7, CLUSTER_GUIDE.md section 5.3)
 * @param versionCheck how often an open date compares its data version with the lake's (30 s; 0 switches the watch off): a changed date
 *     reloads in the background without a data-load signal (2.9.7, CLUSTER_GUIDE.md section 4.4)
 * @param loadThreads the threads that load business dates from the lake (2), off the common pool (2.9.7)
 * @param tenDayMethod how a 10-day VaR is made when the method is not named (2.9.7, VAR_GUIDE.md section 18.6): {@code sqrt10} (the
 *     default: the 1-day figures × √10) or {@code sum} (each 10-day scenario the sum of 10 consecutive 1-day scenario P&amp;Ls)
 * @param confidences the VaR confidences a page and a command may choose (2.9.7; default 0.95, 0.975, 0.99, 0.995); {@code confidence}
 *     is the default and is always offered
 * @param esConfidences the ES confidences that may be chosen (default 0.95, 0.975, 0.99); {@code es-confidence} is the default and is
 *     always offered
 * @param stress stressed VaR (VAR_GUIDE.md section 18.8): how a date's stress scenario set is told from its normal one, and the period of
 *     the stressed VaR average
 * @param refusalTtlSeconds how long a date refused for room (capacity, disk) is remembered (300): asked again meanwhile, it is refused at
 *     once with {@code Retry-After}, without a lake read; forgotten sooner when room is freed or the date's inputs change; 0: not remembered
 * @param openHeapMb the byte safety net over every open book's heap (0: automatic, 0.4 x heap - 512 MB - load-threads x 1.6 GB, at
 *     least a tenth of the heap); over it the least recently used dates' currency and stress books leave first, then whole dates
 * @param fiscalYearEnd the bank's fiscal year-end, {@code MM-DD} (for example {@code 10-31}); blank: none (the {@code fiscal-year-end}
 *     pin is then off)
 * @param pinned the pinned dates (VAR_GUIDE.md section 26.8): month-, quarter-, year- and fiscal-year-ends, the stress window and named
 *     dates, each kept on disk (default) or in memory
 */
@ConfigurationProperties("drishti.var")
public record VarProperties(Boolean enabled, Double confidence, Double esConfidence, String rankRule, Integer componentWindow,
        Boolean componentScaleToVar, Integer threads, Integer parallelRows, Long memoryBudgetMb, Integer rowsPerChunk, Integer hotDates,
        Integer historyDays, Integer histogramBins, Integer maxParts, Kinds kinds, Map<String, Map<String, String>> access, Integer pathPage,
        Positions positions, Official official, Tier tier, List<String> currencies, Integer currencyRetentionDates, String rootName,
        Map<String, String> rootNames, Intraday intraday, Map<String, Input> inputs, String scenarioWindow,
        Map<String, Map<String, Object>> hierarchies, Integer viewCacheMb, java.time.Duration versionCheck, Integer loadThreads,
        String tenDayMethod, List<Double> confidences, List<Double> esConfidences, Stress stress, Integer refusalTtlSeconds, Long openHeapMb,
        String fiscalYearEnd, com.ash.drishti.server.caches.PinnedDates.Settings pinned, Hierarchy hierarchy) {

    /**
     * What every hierarchy shares (VAR_GUIDE.md section 15.14).
     *
     * @param emptyLabel the name of a (Blank) node: the node a row whose level column is empty (or not promoted by its source) sits under,
     *     at that level ({@code (Blank)}, as Power BI names it); a hierarchy's own {@code empty-label} wins
     */
    public record Hierarchy(String emptyLabel) {
        public Hierarchy {
            emptyLabel = emptyLabel == null || emptyLabel.isBlank() ? com.ash.drishti.banking.compute.var.HierarchySpec.DEFAULT_EMPTY_LABEL
                    : emptyLabel.strip();
        }
    }

    /**
     * Stressed VaR (VAR_GUIDE.md section 18.8): VaR and ES on a date's <b>stress scenario set</b>, a second scenario set of the date (the
     * same feed and contracts as the normal set) holding today's trades revalued under a 12-month stress window.
     *
     * @param enabled stressed VaR is offered (a date's stress set read when asked); off: the stress set's rows are still kept apart
     *     from the normal set's, and a stressed request is refused in words
     * @param idPrefix a scenario set is the date's stress set when its {@code SET_TYPE} is {@code STRESS}, or (a set that does not say)
     *     when its {@code SCENARIO_SET_ID} starts with this ({@code HS-STRESS-})
     * @param averageWeeks the period of the stressed VaR average over the official records (12 weeks, the US capital rule's)
     */
    public record Stress(Boolean enabled, String idPrefix, Integer averageWeeks) {
        public Stress {
            enabled = enabled == null || enabled;
            idPrefix = idPrefix == null || idPrefix.isBlank() ? "HS-STRESS-" : idPrefix.strip();
            averageWeeks = averageWeeks == null || averageWeeks < 1 ? 12 : averageWeeks;
        }

        /** True when a scenario set (its {@code SET_TYPE}, its id) is a stress set. */
        public boolean isStress(String setType, String id) {
            if (setType != null && !setType.isBlank()) {
                return setType.strip().equalsIgnoreCase("STRESS") || setType.strip().equalsIgnoreCase("STRESSED");
            }
            return id != null && id.startsWith(idPrefix);
        }
    }

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
     * are {@code limit} (2.9.5, {@code var-limit}), read for the hierarchies whose {@code limits} names it. VaR by a trade column the
     * vectors do not carry ({@code by=attribute:TRADER_ID}) joins the trade valuations, {@code valuation}, by trade id (2.9.11).
     */
    public record Kinds(String vector, String position, String scenarioSet, String portfolio, String scenarioPv,
            String fxScenario, String fxSpot, String limit, String valuation) {
        public Kinds {
            vector = vector == null ? "pnl-vector" : vector;
            position = position == null ? "position" : position;
            scenarioSet = scenarioSet == null ? "scenario-set" : scenarioSet;
            portfolio = portfolio == null ? "portfolio" : portfolio;
            scenarioPv = scenarioPv == null ? "scenario-pv" : scenarioPv;
            fxScenario = fxScenario == null ? "fx-scenario" : fxScenario;
            fxSpot = fxSpot == null ? "fx-spot" : fxSpot;
            limit = limit == null ? "var-limit" : limit;
            valuation = valuation == null ? "trade-valuation" : valuation;
        }

        public List<String> all() {
            return List.of(vector, position, scenarioSet, portfolio, scenarioPv, fxScenario, fxSpot, limit);
        }

        /**
         * The kinds whose load changes a date's answers (the data-load signal reloads the date): the inputs. The FX history
         * ({@code fxSpot}) is not one: a market-data load does not reload VaR; a date reads the history it finds when it loads.
         */
        public List<String> inputs() {
            return List.of(vector, scenarioSet, portfolio, scenarioPv, fxScenario, limit);
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
     * @param format the published table's format, {@code delta} (the default) or {@code iceberg} (2.9.5)
     * @param storage the object store's settings for an {@code s3://} root ({@code s3.endpoint}, {@code s3.region}, {@code s3.access-key},
     *     {@code s3.secret-key}; none: the AWS default chain, a task role)
     */
    public record Official(Boolean enabled, Boolean publish, String root, Integer retentionYears, Boolean onStart, List<String> currencies,
            Boolean jsonl, String format, Map<String, String> storage) {

        public Official {
            jsonl = jsonl == null || jsonl;                 // 2.9.4: each published date also as JSON Lines beside the table
            enabled = enabled == null || enabled;
            publish = publish == null || publish;
            root = root == null || root.isBlank() ? null : root;
            retentionYears = retentionYears == null || retentionYears < 1 ? 7 : retentionYears;
            onStart = onStart == null || onStart;
            currencies = currencyList(currencies == null ? List.of() : currencies);
            format = format == null || format.isBlank() ? "delta" : format.strip().toLowerCase(java.util.Locale.ROOT);
            if (!format.equals("delta") && !format.equals("iceberg")) {
                throw new IllegalArgumentException("drishti.var.official.format is delta or iceberg, not '" + format + "'");
            }
            storage = storage == null ? Map.of() : Map.copyOf(storage);
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
     * @param maxDiskGb the most disk the hot files (dates, listed currencies, shard books) may take (0: half the disk's size); the least
     *     recently used beyond are deleted, never an open one (2.9.7, CLUSTER_GUIDE.md section 7.2)
     */
    public record Tier(String mode, String dir, Integer diskDates, Boolean keepOnShutdown, Integer closeGraceSeconds, Double maxDiskGb) {

        public Tier {
            mode = mode == null || mode.isBlank() ? "mapped" : mode.trim().toLowerCase(Locale.ROOT);
            mode = mode.equals("heap") ? "heap" : "mapped";
            dir = dir == null || dir.isBlank() ? com.ash.drishti.api.DataDir.under("var/hot") : dir;
            diskDates = diskDates == null || diskDates < 1 ? 60 : diskDates;
            keepOnShutdown = keepOnShutdown != null && keepOnShutdown;
            closeGraceSeconds = closeGraceSeconds == null || closeGraceSeconds < 0 ? 60 : closeGraceSeconds;
            maxDiskGb = maxDiskGb == null || maxDiskGb < 0 ? 0.0 : maxDiskGb;
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
        hotDates = hotDates == null || hotDates <= 0 ? 10 : hotDates;
        historyDays = historyDays == null || historyDays <= 1 ? 250 : historyDays;
        histogramBins = histogramBins == null || histogramBins < 5 ? 42 : histogramBins;
        maxParts = maxParts == null || maxParts <= 0 ? 5000 : maxParts;
        kinds = kinds == null ? new Kinds(null, null, null, null, null, null, null, null, null) : kinds;
        hierarchies = hierarchies == null ? Map.of() : hierarchies;
        access = access == null ? Map.of() : access;
        pathPage = pathPage == null || pathPage <= 0 ? 60 : pathPage;
        positions = positions == null ? new Positions(null, null) : positions;
        official = official == null ? new Official(null, null, null, null, null, null, null, null, null) : official;
        tier = tier == null ? new Tier(null, null, null, null, null, null) : tier;
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
        viewCacheMb = viewCacheMb == null || viewCacheMb <= 0 ? 64 : viewCacheMb;
        versionCheck = versionCheck == null || versionCheck.isNegative() ? java.time.Duration.ofSeconds(30) : versionCheck;
        loadThreads = loadThreads == null || loadThreads <= 0 ? 2 : loadThreads;
        tenDayMethod = (tenDayMethod == null || tenDayMethod.isBlank() ? com.ash.drishti.banking.compute.var.VarLens.Method.SQRT
                : com.ash.drishti.banking.compute.var.VarLens.Method.parse(tenDayMethod)).word();
        confidences = confidenceList(confidences, List.of(0.95, 0.975, 0.99, 0.995), confidence, "drishti.var.confidences");
        esConfidences = confidenceList(esConfidences, List.of(0.95, 0.975, 0.99), esConfidence, "drishti.var.es-confidences");
        stress = stress == null ? new Stress(null, null, null) : stress;
        refusalTtlSeconds = refusalTtlSeconds == null || refusalTtlSeconds < 0 ? 300 : refusalTtlSeconds;
        openHeapMb = openHeapMb == null || openHeapMb < 0 ? 0L : openHeapMb;
        fiscalYearEnd = fiscalYearEnd == null ? "" : fiscalYearEnd.trim();
        com.ash.drishti.server.caches.PinnedDates.fiscalYearEnd(fiscalYearEnd);       // a bad value refuses the start
        pinned = pinned == null ? com.ash.drishti.server.caches.PinnedDates.Settings.none() : pinned;
        hierarchy = hierarchy == null ? new Hierarchy(null) : hierarchy;
    }

    /** The heap the open books may take before the safety net closes some (bytes): {@code open-heap-mb}, or the automatic net. */
    public long openHeapBytes() {
        return openHeapMb > 0 ? openHeapMb << 20
                : com.ash.drishti.server.caches.OpenDates.automaticNet(Runtime.getRuntime().maxMemory(), loadThreads, PER_LOAD_BYTES);
    }

    /** The heap one load holds while it builds a date (its aggregates and currency totals before they are written): 1.6 GB at 1M. */
    static final long PER_LOAD_BYTES = 1600L << 20;

    /** The configured list (or {@code dflt}), each checked, the default value always in, sorted and once each. */
    static List<Double> confidenceList(List<Double> given, List<Double> dflt, double always, String what) {
        java.util.TreeSet<Double> out = new java.util.TreeSet<>();
        for (Double c : given == null || given.isEmpty() ? dflt : given) {
            out.add(checkConfidence(c, what));
        }
        out.add(always);
        return List.copyOf(out);
    }

    /** The default 10-day method. */
    public com.ash.drishti.banking.compute.var.VarLens.Method defaultTenDayMethod() {
        return com.ash.drishti.banking.compute.var.VarLens.Method.parse(tenDayMethod);
    }

    /** The default lens: 1 day at the configured confidences (what official records and every answer without a choice use). */
    public com.ash.drishti.banking.compute.var.VarLens defaultLens() {
        return com.ash.drishti.banking.compute.var.VarLens.oneDay(confidence, esConfidence);
    }

    /**
     * A lens asked for (any part null: the default's): {@code horizon} 1d or 10d, {@code method} sqrt10 or sum (10d only; none: the
     * {@code ten-day-method}), the confidences from the configured lists. Throws IllegalArgumentException in words when not allowed.
     */
    public com.ash.drishti.banking.compute.var.VarLens lens(String horizon, String method, Double conf, Double esConf) {
        return lens(horizon, method, conf, esConf, null);
    }

    /**
     * The same on a scenario set: {@code set} {@code normal} (null, blank) or {@code stressed} ({@code stress}, {@code svar}); refused in
     * words when it is neither, or stressed VaR is switched off.
     */
    public com.ash.drishti.banking.compute.var.VarLens lens(String horizon, String method, Double conf, Double esConf, String set) {
        boolean stressed = stressed(set);
        if (stressed && !stress.enabled()) {
            throw new IllegalArgumentException("stressed VaR is switched off on this server (drishti.var.stress.enabled)");
        }
        return lens1(horizon, method, conf, esConf).withStressed(stressed);
    }

    /** Reads a scenario set's word: {@code normal} (null, blank) or {@code stressed}; anything else is refused in words. */
    public static boolean stressed(String set) {
        String v = set == null ? "" : set.strip().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "", "normal", "n" -> false;
            case "stressed", "stress", "svar", "s" -> true;
            default -> throw new IllegalArgumentException("set '" + set + "' is normal or stressed");
        };
    }

    private com.ash.drishti.banking.compute.var.VarLens lens1(String horizon, String method, Double conf, Double esConf) {
        int days = com.ash.drishti.banking.compute.var.VarLens.parseHorizon(horizon);
        com.ash.drishti.banking.compute.var.VarLens.Method m = com.ash.drishti.banking.compute.var.VarLens.Method.NONE;
        if (method != null && !method.isBlank()) {
            m = com.ash.drishti.banking.compute.var.VarLens.Method.parse(method);
            if (days == 1 && (horizon == null || horizon.isBlank())) {
                days = com.ash.drishti.banking.compute.var.VarLens.TEN;             // a method alone implies 10 days
            } else if (days == 1) {
                throw new IllegalArgumentException("method " + method + " is for the 10-day horizon (horizon=10d)");
            }
        } else if (days != 1) {
            m = defaultTenDayMethod();
        }
        double c = conf == null ? confidence : allowed(conf, confidences, "confidence");
        double ec = esConf == null ? esConfidence : allowed(esConf, esConfidences, "esConfidence");
        return new com.ash.drishti.banking.compute.var.VarLens(days, m, c, ec);
    }

    /** {@code c} (a fraction, or a percentage above 1: 99 is 0.99) when it is one of {@code list}, else refused in words. */
    static double allowed(double c, List<Double> list, String what) {
        double f = c > 1 ? c / 100.0 : c;
        for (double x : list) {
            if (Math.abs(x - f) < 1e-9) {
                return x;
            }
        }
        throw new IllegalArgumentException(what + " " + c + " is not offered; the configured ones: " + list.stream()
                .map(com.ash.drishti.banking.compute.var.VarLens::pct).toList());
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

    public com.ash.drishti.banking.compute.var.VarMath.RankRule rule() {
        return rankRule.startsWith("interp") ? com.ash.drishti.banking.compute.var.VarMath.RankRule.INTERPOLATED
                : com.ash.drishti.banking.compute.var.VarMath.RankRule.KTH_WORST;
    }

    public com.ash.drishti.banking.compute.var.VarCalc calc() {
        return new com.ash.drishti.banking.compute.var.VarCalc(confidence, esConfidence, rule(), componentWindow, componentScaleToVar);
    }
}
