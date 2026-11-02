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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.var.PositionRules;
import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.compute.vector.ParallelSums;
import com.ash.drishti.engine.source.SourceRouter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Reads one business date of the VaR engine's inputs through the connectors the kinds are routed to (the market-risk pack's
 * {@code risk-store} in the demo; the bank's lake in production) and builds the {@link VarBook}: the trades' vectors streamed from the
 * vector column straight into the store (no document is parsed for them), each row checked and given its position by the configured
 * rules ({@link VarIntake}), then the hierarchies with their limits, the scenario set, and the portfolios' definitions. Positions are
 * Drishti's: derived here, from the trades and the rules, each time the date is loaded (VAR_GUIDE.md section 15.6). A date whose inputs are
 * not one run (several batch ids, another scenario set, two scenario sets) is refused ({@code DRS-5203}). Reads as the server; what a
 * viewer may see is applied per request.
 *
 * <p>With the tiered memory ({@code drishti.var.tier.mode: mapped}, VAR_GUIDE.md section 26) the rows stream to a spill file and the date is
 * written to its hot file and mapped; a date whose hot file was built from the same inputs (the same data versions of its vectors,
 * hierarchy and scenario set, the same position rules) is opened from that file, without reading its vectors again.
 */
final class VarLoader {

    private final SourceRouter router;
    private final VarProperties props;
    private final ParallelSums sums;
    private final Supplier<PositionRules> rules;
    private final VarTier tier;
    private final Supplier<Hierarchies> hierarchies;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    VarLoader(SourceRouter router, VarProperties props, ParallelSums sums, Supplier<PositionRules> rules) {
        this(router, props, sums, rules, new VarTier(props.tier()));
    }

    VarLoader(SourceRouter router, VarProperties props, ParallelSums sums, Supplier<PositionRules> rules, VarTier tier) {
        this(router, props, sums, rules, tier, Hierarchies.of(props, null));
    }

    VarLoader(SourceRouter router, VarProperties props, ParallelSums sums, Supplier<PositionRules> rules, VarTier tier,
            Supplier<Hierarchies> hierarchies) {
        this.router = router;
        this.props = props;
        this.sums = sums;
        this.rules = rules;
        this.tier = tier;
        this.hierarchies = hierarchies;
    }

    /** How the vector files of a load decode: several at once, delivered in file order ({@code drishti.var.read}, VAR_GUIDE.md 27a.6). */
    private volatile com.ash.drishti.api.VectorReads.Options reads = VarReadProperties.defaults().options();

    /** Sets how a load's vector files decode ({@link com.ash.drishti.api.VectorReads.Options#serial()}: one after the other). */
    VarLoader reads(com.ash.drishti.api.VectorReads.Options options) {
        this.reads = options == null ? com.ash.drishti.api.VectorReads.Options.serial() : options;
        return this;
    }

    /** The hierarchies a load builds now (the setting, a pack's block, or MGMT and LEGAL from the rows). */
    Hierarchies hierarchies() {
        return hierarchies.get();
    }

    VarTier tier() {
        return tier;
    }

    /** The newest business date that has trades' scenario P&amp;L (P&amp;L vectors or scenario PVs), or null. */
    LocalDate latest() {
        LocalDate v = router.latestDate(props.kinds().vector()).orElse(null);
        LocalDate pv = router.latestDate(props.kinds().scenarioPv()).orElse(null);
        return v == null ? pv : pv == null || !pv.isAfter(v) ? v : pv;
    }

    /** The newest date with vectors or scenario PVs before {@code date}, or null (a date that cannot be loaded is answered by the one before). */
    LocalDate previous(LocalDate date) {
        LocalDate v = router.heldDates(props.kinds().vector()).map(s -> s.lower(date)).orElse(null);
        LocalDate pv = router.heldDates(props.kinds().scenarioPv()).map(s -> s.lower(date)).orElse(null);
        return v == null ? pv : pv == null || !pv.isAfter(v) ? v : pv;
    }

    /**
     * The data version of a date's trade inputs: the vector table's, and the scenario-PV table's when a connector keeps that kind (a
     * refused date is not read again while it is unchanged; the version stamps every answer of the date).
     */
    String version(LocalDate date) {
        String v = router.dataVersion(props.kinds().vector(), AsOf.of(date)).orElse("");
        String pv = router.dataVersion(props.kinds().scenarioPv(), AsOf.of(date)).orElse("");
        return pv.isEmpty() || pv.equals("none") ? v : v + "|" + props.kinds().scenarioPv() + ":" + pv;
    }

    /**
     * The rows of a date per shard key value (VAR_SHARDING.md section 3), read as columns (no vector is read): every kind of {@code kinds}
     * that a connector holds counted together. Empty when a kind's source cannot answer the key as columns (the date is then not cut).
     */
    java.util.Optional<java.util.SortedMap<List<String>, Long>> keyCounts(LocalDate date, List<String> key, List<String> kinds) {
        AsOf asOf = AsOf.of(date);
        java.util.SortedMap<List<String>, Long> out = com.ash.drishti.compute.shard.ShardMap.counts();
        for (String kind : kinds) {
            if (holdsNothing(router.dataVersion(kind, asOf).orElse(""))) {
                continue;                                              // no connector holds the kind on the date
            }
            if (!router.columnar(kind).containsAll(key)) {
                return java.util.Optional.empty();
            }
            java.util.Optional<com.ash.drishti.api.ColumnSet> cs = router.columns(kind, key, asOf, java.time.Duration.ofMinutes(5));
            if (cs.isEmpty()) {
                return java.util.Optional.empty();
            }
            String[][] cols = key.stream().map(c -> cs.get().texts().get(c)).toArray(String[][]::new);
            for (int i = 0; i < cs.get().size(); i++) {
                List<String> k = new ArrayList<>(key.size());
                for (String[] c : cols) {
                    String x = c == null ? null : c[i];
                    k.add(x == null ? "" : x);
                }
                out.merge(List.copyOf(k), 1L, Long::sum);
            }
        }
        return java.util.Optional.of(out);
    }

    /**
     * True when a kind's data version says no connector holds it: none at all, or each source's part {@code <source>=none} (the router
     * names the source: {@code risk-store=none} is a connector without the table), so a lake without a {@code scenario-pv} table is sharded
     * on its P&amp;L vectors alone.
     */
    static boolean holdsNothing(String version) {
        if (version == null || version.isEmpty()) {
            return true;
        }
        for (String part : version.split("\\|")) {
            if (!part.equals("none") && !part.endsWith("=none")) {
                return false;
            }
        }
        return true;
    }

    /** The scenarios a date's vectors keep: its scenario set's length, or the scenario window (0 when the date has no set). */
    int width(LocalDate date) {
        try {
            DataNode set = sets(date, AsOf.of(date)).normal().values().stream().findFirst().orElse(null);
            DataNode sd = set == null ? null : set.get("SCENARIO_DATES");
            int n = sd == null ? 0 : sd.size();
            return props.window() > 0 ? Math.min(n, props.window()) : n;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The data versions of a date's input kinds, as a load records them (the optional kinds only when a connector holds them). */
    Map<String, String> versions(LocalDate date) {
        VarProperties.Kinds k = props.kinds();
        AsOf asOf = AsOf.of(date);
        Map<String, String> versions = new LinkedHashMap<>();
        for (String kind : k.inputs()) {
            String v = router.dataVersion(kind, asOf).orElse("");
            boolean optional = kind.equals(k.scenarioPv()) || kind.equals(k.fxScenario()) || kind.equals(k.limit());
            if (!optional || !v.isEmpty() && !v.equals("none")) {
                versions.put(kind, v);
            }
        }
        return versions;
    }

    /** The stored portfolios of a date (their definitions), read again for a date put together from its shards. */
    Map<String, VarDate.Portfolio> portfolios(LocalDate date) {
        Map<String, VarDate.Portfolio> out = new LinkedHashMap<>();
        Map<String, Double> lim = portfolioLimits(AsOf.of(date));
        documents(props.kinds().portfolio(), AsOf.of(date)).forEach((id, p) -> out.put(id, VarDate.Portfolio.of(id, p, lim)));
        return out;
    }

    /** The position rules a load uses now (a pack's section, else the server's setting). */
    PositionRules rules() {
        return rules.get();
    }

    VarDate load(LocalDate date) {
        return load(date, null);
    }

    /**
     * The digest of the inputs a date (or one of its shards) is built from: the data versions of its trades (and the FX that converts their
     * scenario PVs), hierarchy and scenario set, the hierarchies' configuration and limits, the scenario window, the position rules and (a
     * shard) its place in its shard map. A hot file built from other inputs is not reopened.
     */
    private String digest(LocalDate date, Map<String, String> versions, Hierarchies hs, PositionRules pr, VarShardScope shard) {
        return digest(date, versions, hs, pr, shard, null);
    }

    /** The same for the date's stress scenario set ({@code stressSet}: its id; null: the normal set). */
    private String digest(LocalDate date, Map<String, String> versions, Hierarchies hs, PositionRules pr, VarShardScope shard, String stressSet) {
        VarProperties.Kinds k = props.kinds();
        String trades = version(date);
        // a date with scenario PVs converts them with the date's FX: its hot file is built from those versions too
        String inputsKey = trades.isBlank() || !versions.containsKey(k.scenarioPv()) ? trades
                : trades + "|fx=" + versions.get(k.fxScenario()) + "/" + router.dataVersion(k.fxSpot(), AsOf.of(date)).orElse("");
        if (shard != null && !inputsKey.isBlank()) {
            inputsKey += "|shard=" + shard.key();                         // a shard's file: its range of its map is part of its inputs
        }
        // the hierarchies' configuration (2.9.5) and their limits' version: a file built under another is not reopened
        String hierKey = versions.get(k.hierarchy()) + props.rootNamesKey() + hs.digest()
                + (hs.limitKinds().isEmpty() ? "" : "|limits=" + versions.getOrDefault(k.limit(), ""));
        String setKey = versions.get(k.scenarioSet()) + (stressSet != null ? "|stress=" + stressSet : props.window() > 0 ? "|window=" + props.window() : "");
        return VarTier.digest(date, inputsKey, hierKey, setKey, pr.digest());
    }

    /**
     * The digest a shard's book built now would have (VAR_SHARDING.md section 6): what a member that puts the date together expects of the
     * shard's skeleton, computed from its own reads of the inputs' versions (empty when the trades' source cannot say its version).
     */
    String shardDigest(LocalDate date, VarShardScope shard) {
        return digest(date, versions(date), effective(date, hierarchies.get()), rules.get(), shard);
    }

    /**
     * One shard of a sharded date (VAR_SHARDING.md section 5): the date's inputs read for the rows of the shard's key range only (the
     * plugin skips the files whose statistics hold none of its keys; every row is checked here), built and written as the shard's own hot
     * file (its {@code RANG} section), the morning run only (the day's updates are applied by each member on the date it puts together).
     */
    VarDate loadShard(LocalDate date, VarShardScope shard) {
        return load(date, shard);
    }

    private VarDate load(LocalDate date, VarShardScope shard) {
        return load(date, shard, false);
    }

    /**
     * The date's <b>stress scenario set</b> (stressed VaR, VAR_GUIDE.md section 18.8): its trades' rows of that set only, built as a book of
     * their own (its own hot file, {@code <date>-STRESS}), the set used whole (the scenario window is the normal set's), the morning run
     * alone, in USD. A date without a stress set is answered in words ({@code DRS-1001}), never with zeros.
     */
    VarDate loadStress(LocalDate date) {
        return load(date, null, true);
    }

    /** The hot-file folder label of a date's stress-set book ({@code <date>-STRESS}). */
    static final String STRESS_FOLDER = "STRESS";

    /** The key of {@link VarDate#versions()} that marks a date of the stress scenario set (its id). */
    static final String STRESS_SET = "stressSet";
    /** The keys of {@link VarDate#versions()} with the stress set's own description and its synthetic mark (the demo's set). */
    static final String STRESS_ABOUT = "stressSetDescription";
    static final String STRESS_SYNTHETIC = "stressSetSynthetic";

    /** How many loads began (from a hot file or the lake): a test's count of the reads a remembered refusal saves. */
    private final java.util.concurrent.atomic.AtomicLong loadsBegun = new java.util.concurrent.atomic.AtomicLong();

    long loadsBegun() {
        return loadsBegun.get();
    }

    private VarDate load(LocalDate date, VarShardScope shard, boolean stress) {
        loadsBegun.incrementAndGet();
        long t0 = System.nanoTime();
        VarProperties.Kinds k = props.kinds();
        AsOf asOf = AsOf.of(date);
        Map<String, String> versions = versions(date);                // the 2.9.4 kinds only when a connector holds them (records stay comparable)
        PositionRules pr = rules.get();
        // a column hierarchy whose level column a source does not keep is skipped, said (DRS-5211 as a warning); the rest load (2.9.5)
        Hierarchies hs = effective(date, hierarchies.get());
        String trades = version(date);
        Sets sets = sets(date, asOf);
        DataNode set = stress ? sets.stress(date) : sets.normal(date);
        String setId = text(set, "SCENARIO_SET_ID");
        String folder = stress ? STRESS_FOLDER : VarDate.USD;
        String digest = tier.mapped() ? digest(date, versions, hs, pr, shard, stress ? setId : null) : "";
        java.util.Optional<java.nio.file.Path> hot = !tier.mapped() ? java.util.Optional.empty()
                : shard == null ? tier.existing(date, folder, digest) : tier.existing(date, VarDate.USD, shard.index(), digest);
        if (hot.isPresent()) {
            VarDate reopened = reopen(date, hot.get(), versions, t0, shard == null && !stress);
            if (reopened != null) {
                return stress ? stressDate(reopened, set) : reopened;
            }
        }
        List<LocalDate> fullSet = scenarioDates(date, setId, set);
        // the scenario window (drishti.var.scenario-window, VAR_GUIDE.md section 18.5): the newest N scenarios of the set, every row cut
        // to them at load (P&L vectors and scenario PVs alike); a set shorter than N is used whole (said in the provenance). The stress set
        // is the stress window itself: used whole
        List<LocalDate> scenarioDates = stress ? fullSet : window(fullSet);
        String fxSet = stress ? setId : null;
        // the heap tier's vectors are bounded by memory-budget-mb; the mapped tier's rows are on disk: bounded by the disk (a hard limit,
        // checked before the build and while the rows stream in) and the page cache (advisory), never by a share of the heap
        long budget = tier.mapped() ? Long.MAX_VALUE : props.memoryBudgetMb() * 1024 * 1024;
        if (shard != null && !tier.mapped()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a sharded date needs the tiered memory (drishti.var.tier.mode: mapped)");
        }
        VarBook.Builder b = tier.mapped() ? new VarBook.Builder(date, setId, scenarioDates, budget,
                shard == null ? tier.target(date, folder, digest) : tier.target(date, VarDate.USD, shard.index(), digest))
                : new VarBook.Builder(date, setId, scenarioDates, props.rowsPerChunk(), budget);
        if (shard != null) {
            b.range(shard.json(digest));
        }
        // the build sums every node's PVs per trade currency too (VAR_GUIDE.md section 15.11): the date's FX, read only when a trade needs it
        b.currencyRates(c -> fx(date, scenarioDates, fxSet).rates(c).orElse(null));
        Map<String, Double> limits = limits(asOf, hs);
        // the column hierarchies' cells and nodes (2.9.5); their roots named as the risk-hierarchy roots are (drishti.var.root-name(s))
        b.columnHierarchies(hs.specs().stream().map(s -> s.columns() ? s.withRootName(props.rootName(s.id(), null, s.rootName())) : s).toList(),
                limits);
        try {
            VarIntake intake = intake(date, asOf, b, pr, setId, scenarioDates, fullSet.size(), hs, shard, stress ? keepOnly(setId)
                    : keepNormal(sets.stressIds()), fxSet);
            List<LocalDate> days = scenarioDates;
            VarIntake.Summary sum = intake.summary();
            long tVectors = System.nanoTime();
            com.ash.drishti.compute.var.ShardAssembly.Nodes rows = nodeRows(asOf, hs, limits);
            for (String[] r : rows.rows()) {
                b.node(r[0], r[1], r[2], r[3], r[4], rows.books().get(r[0]), rows.limits().get(r[0]));
            }
            Map<String, VarDate.Portfolio> portfolios = new LinkedHashMap<>();
            Map<String, Double> pfLimits = portfolioLimits(asOf);
            documents(k.portfolio(), asOf).forEach((id, p) -> portfolios.put(id, VarDate.Portfolio.of(id, p, pfLimits)));
            versions.put("scenarioSet", setId);
            List<String> unknownLimits = new ArrayList<>();
            b.batchId(sum.batchIds().isEmpty() ? "" : sum.batchIds().get(0));
            VarBook book;
            try {
                book = b.meta(meta(sum, versions, intake.fx())).build(sums);
            } catch (IllegalStateException e) {
                throw new DrishtiException(ErrorCode.VAR_HIERARCHY_INVALID, "the VaR inputs of " + date + " are not loaded: " + e.getMessage());
            }
            for (String nid : limits.keySet()) {
                if (book.node(nid) == null) {
                    unknownLimits.add(nid);
                }
            }
            tier.opened(book);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            VarDate vd = new VarDate(date, book, portfolios, trades, java.time.Instant.now(), ms, (tVectors - t0) / 1_000_000, sum,
                    versions, List.of(), VarDate.USD, intake.fx());
            if (shard != null) {
                return vd;                                             // a shard: the morning run only
            }
            if (stress) {
                return stressDate(vd, set);                            // the stress set: the morning run only
            }
            unknownLimits(date, unknownLimits);
            return withIntraday(vd, intake, days);
        } catch (RuntimeException e) {
            b.discard();
            throw e;
        }
    }

    /**
     * Streams the date's trades through the intake into the builder, then checks the date is one run: the P&amp;L vectors, then the
     * scenario PVs (converted to USD as they stream, with the date's FX read on the first PV row); a bank may send either or both.
     */
    /** The newest {@code drishti.var.scenario-window} scenarios of a set (all of it for {@code all}, or when the set is shorter). */
    List<LocalDate> window(List<LocalDate> fullSet) {
        int n = props.window();
        return n <= 0 || n >= fullSet.size() ? fullSet : List.copyOf(fullSet.subList(fullSet.size() - n, fullSet.size()));
    }

    /**
     * The hierarchy rows of a date (the {@code risk-hierarchy} kind, for the hierarchies built from rows) with their books and limits (a
     * var-limit row wins over the row's own {@code LIMIT_VAR}), the root names as configured, and the hierarchies built from columns: what
     * a date's nodes are built from (a load, or a sharded date put together, VAR_SHARDING.md).
     */
    com.ash.drishti.compute.var.ShardAssembly.Nodes nodeRows(AsOf asOf, Hierarchies hs, Map<String, Double> limits) {
        List<String[]> rows = new ArrayList<>();
        Map<String, List<String>> books = new java.util.HashMap<>();
        Map<String, Double> lim = new java.util.HashMap<>();
        for (DataNode n : documents(props.kinds().hierarchy(), asOf).values()) {
            if (!hs.fromRows(text(n, "HIERARCHY"))) {
                continue;                                              // a hierarchy built from the columns: its rows are not read
            }
            List<String> bs = new ArrayList<>();
            DataNode list = n.get("BOOKS");
            for (int i = 0; list != null && i < list.size(); i++) {
                bs.add(list.get(i).asText());
            }
            // a root's name from drishti.var.root-name(s) when set (the top of the VaR tree), else the data's
            String name = props.rootName(text(n, "HIERARCHY"), text(n, "PARENT_ID"), text(n, "NAME"));
            String nid = text(n, "NODE_ID");
            rows.add(new String[] {nid, text(n, "HIERARCHY"), text(n, "LEVEL"), name, text(n, "PARENT_ID")});
            books.put(nid, List.copyOf(bs));
            lim.put(nid, limits.getOrDefault(nid, number(text(n, "LIMIT_VAR"))));   // a var-limit row wins over the row's own LIMIT_VAR
        }
        List<com.ash.drishti.compute.var.HierarchySpec> specs = hs.specs().stream().filter(com.ash.drishti.compute.var.HierarchySpec::columns)
                .map(s -> s.withRootName(props.rootName(s.id(), null, s.rootName()))).toList();
        return new com.ash.drishti.compute.var.ShardAssembly.Nodes(rows, books, lim, specs, limits);
    }

    private VarIntake intake(LocalDate date, AsOf asOf, VarBook.Builder b, PositionRules pr, String setId, List<LocalDate> scenarioDates,
            int setLength, Hierarchies hs, VarShardScope shard, java.util.function.Predicate<String> keep, String fxSet) {
        VarProperties.Kinds k = props.kinds();
        List<String> levels = hs.levelColumns();
        List<String> columns = VarIntake.columns(router.columnar(k.vector()), pr, List.of(), levels);
        List<String> pvColumns = VarIntake.columns(router.columnar(k.scenarioPv()), pr, VarIntake.PV_COLUMNS, levels);
        String unmarked = unmarked(columns, pvColumns);
        if (fxSet != null) {
            sayable(columns, pvColumns, setId);                       // the stress set's rows must be told from the normal set's
        }
        VarIntake intake = new VarIntake(b, pr, date, columns.subList(1, columns.size())).scenarioSet(setId)
                .inputs(new VarInputs(props::input)).setLength(setLength).pnlFx(() -> fx(date, scenarioDates, fxSet)).levels(levels);
        boolean read;
        boolean readPv;
        try {
            com.ash.drishti.api.KeyRange range = shard == null ? null : shard.range();
            read = router.vectors(k.vector(), "PNL", columns, asOf, range, com.ash.drishti.api.VectorReads.parallel(
                    ofSet(columns, keep, shardRows(shard, columns, intake::row)), reads));
            intake.scenarioPvs(pvColumns.subList(1, pvColumns.size()), () -> fx(date, scenarioDates, fxSet), scenarioDates);
            readPv = router.vectors(k.scenarioPv(), SCENARIO_PV, pvColumns, asOf, range, com.ash.drishti.api.VectorReads.parallel(
                    ofSet(pvColumns, keep, shardRows(shard, pvColumns, intake::row)), reads));
        } catch (com.ash.drishti.compute.vector.VectorStore.BudgetExceeded e) {
            throw new DrishtiException(ErrorCode.VAR_CAPACITY, e.getMessage() + ": the engine has no room for this date now; raise "
                    + "drishti.var.memory-budget-mb (the heap tier) or the server's heap, or keep fewer dates open (drishti.var.hot-dates)");
        } catch (VarBook.DuplicateTrade e) {
            throw new DrishtiException(ErrorCode.VAR_DUPLICATE_TRADE, "the P&L vectors of " + date + " are not loaded: " + e.getMessage()
                    + (unmarked == null ? "" : "; " + unmarked));
        } catch (DrishtiException e) {
            throw e;
        } catch (Exception e) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "the P&L vectors of " + date + " could not be read: " + e.getMessage());
        }
        if (!read && !readPv) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "no connector keeps " + k.vector() + ".PNL (or " + k.scenarioPv() + "." + SCENARIO_PV
                    + ") as a vector column with the columns " + columns + " (the pack's layout." + k.vector() + ".columns and .vectors)");
        }
        VarIntake.Summary sum = intake.summary();
        if (sum.batchIds().size() > 1) {
            throw inconsistent(date, "the vectors come from " + sum.batchIds().size() + " runs (BATCH_ID " + String.join(", ", sum.batchIds())
                    + "): one run per date");
        }
        List<String> otherSets = sum.scenarioSets().stream().filter(s -> !s.equals(setId)).toList();
        if (!otherSets.isEmpty()) {
            throw inconsistent(date, "vectors of the scenario set " + String.join(", ", otherSets) + " beside the date's set " + setId);
        }
        intake.verify();                                                // one form and one grain per source system (VAR_GUIDE.md 15.13)
        return intake;
    }

    /**
     * The rows a load takes: every row, or (a shard) those whose key is in the shard's range, by the row's own key columns as the read
     * returns them ({@code columns}: TRADE_ID first, as the row's values).
     */
    private static com.ash.drishti.api.VectorSink shardRows(VarShardScope shard, List<String> columns, com.ash.drishti.api.VectorSink to) {
        if (shard == null) {
            return to;
        }
        int[] at = shard.range().columns().stream().mapToInt(columns::indexOf).toArray();
        return (id, values, len, row) -> {
            String[] key = new String[at.length];
            for (int i = 0; i < at.length; i++) {
                String v = at[i] < 0 || at[i] >= row.length ? null : row[at[i]];
                key[i] = v == null ? "" : v;
            }
            if (shard.range().contains(java.util.Arrays.asList(key))) {
                to.row(id, values, len, row);
            }
        };
    }

    // ------------------------------------------------------------------------------------------------------- the scenario sets

    /**
     * A date's scenario sets (VAR_GUIDE.md section 18.8): its normal set (one, as before) and its stress set (none or one), told apart by
     * the set's {@code SET_TYPE} or its id ({@code drishti.var.stress.id-prefix}).
     */
    record Sets(Map<String, DataNode> normal, Map<String, DataNode> stress, String kind) {

        /** The date's normal set; none or two refuse the date as before ({@code DRS-1001}, {@code DRS-5203}). */
        DataNode normal(LocalDate date) {
            if (normal.isEmpty()) {
                throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no scenario set for " + date + " (kind " + kind + ")"
                        + (stress.isEmpty() ? "" : ": only the stress set " + String.join(", ", stress.keySet())));
            }
            if (normal.size() > 1) {
                throw inconsistent(date, normal.size() + " scenario sets (" + String.join(", ", normal.keySet()) + "): one per date");
            }
            return normal.values().iterator().next();
        }

        /** The date's stress set; none is said in words ({@code DRS-1001}: stressed VaR is not available on the date, never zero). */
        DataNode stress(LocalDate date) {
            if (stress.isEmpty()) {
                throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no stress scenario set on " + date + ": stressed VaR is not "
                        + "available for this date (a stress set is a scenario set with SET_TYPE STRESS, VAR_GUIDE.md section 18.8)");
            }
            if (stress.size() > 1) {
                throw inconsistent(date, stress.size() + " stress scenario sets (" + String.join(", ", stress.keySet()) + "): one per date");
            }
            return stress.values().iterator().next();
        }

        /** The ids of the date's stress sets: their rows are left out of the normal set's book. */
        java.util.Set<String> stressIds() {
            java.util.Set<String> out = new java.util.HashSet<>();
            stress.values().forEach(d -> out.add(text(d, "SCENARIO_SET_ID")));
            return out;
        }
    }

    /** A date's scenario sets, the normal and the stress ones apart. */
    Sets sets(LocalDate date, AsOf asOf) {
        Map<String, DataNode> normal = new LinkedHashMap<>();
        Map<String, DataNode> stress = new LinkedHashMap<>();
        documents(props.kinds().scenarioSet(), asOf).forEach((id, d) -> {
            boolean s = props.stress().isStress(text(d, "SET_TYPE"), text(d, "SCENARIO_SET_ID"));
            (s ? stress : normal).put(id, d);
        });
        return new Sets(normal, stress, props.kinds().scenarioSet());
    }

    /** The ids of a date's stress sets (none when it has none, or its sets cannot be read). */
    java.util.Set<String> stressIds(LocalDate date) {
        try {
            return sets(date, AsOf.of(date)).stressIds();
        } catch (RuntimeException e) {
            return java.util.Set.of();
        }
    }

    /** The rows of the normal set: every row but those of a stress set (a row of another set still reaches the one-run check). */
    static java.util.function.Predicate<String> keepNormal(java.util.Set<String> stressIds) {
        return set -> set == null || !stressIds.contains(set);
    }

    /** The rows of one set only. */
    static java.util.function.Predicate<String> keepOnly(String setId) {
        return setId::equals;
    }

    /** A sink that passes on the rows whose {@code SCENARIO_SET_ID} (a column of {@code columns}) {@code keep} takes. */
    static com.ash.drishti.api.VectorSink ofSet(List<String> columns, java.util.function.Predicate<String> keep, com.ash.drishti.api.VectorSink to) {
        int at = columns.indexOf("SCENARIO_SET_ID");
        if (at < 0) {
            return to;                                                 // the connector does not keep it: every row (the normal set's load)
        }
        return (id, values, len, row) -> {
            if (keep.test(at < row.length ? row[at] : null)) {
                to.row(id, values, len, row);
            }
        };
    }

    /** Refuses a stress set's load when a source of the trades does not keep {@code SCENARIO_SET_ID}: its rows could not be told apart. */
    private void sayable(List<String> columns, List<String> pvColumns, String setId) {
        VarProperties.Kinds k = props.kinds();
        List<String> missing = new ArrayList<>();
        if (!router.columnar(k.vector()).isEmpty() && !columns.contains("SCENARIO_SET_ID")) {
            missing.add("layout." + k.vector() + ".columns");
        }
        if (!router.columnar(k.scenarioPv()).isEmpty() && !pvColumns.contains("SCENARIO_SET_ID")) {
            missing.add("layout." + k.scenarioPv() + ".columns");
        }
        if (!missing.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the stress set " + setId + " cannot be read: the connector does not promote "
                    + "SCENARIO_SET_ID (" + String.join(", ", missing) + "), so its rows cannot be told from the normal set's");
        }
    }

    /**
     * A date built from its stress set, marked as such (its version names the set, so no cache takes it for the normal set's), with what
     * the set says of itself ({@code DESCRIPTION}, {@code SYNTHETIC}) for the answers.
     */
    private static VarDate stressDate(VarDate vd, DataNode set) {
        String setId = text(set, "SCENARIO_SET_ID");
        Map<String, String> v = new LinkedHashMap<>(vd.versions());
        v.put(STRESS_SET, setId);
        String about = text(set, "DESCRIPTION");
        if (about != null) {
            v.put(STRESS_ABOUT, about);
        }
        if ("true".equalsIgnoreCase(String.valueOf(text(set, "SYNTHETIC")))) {
            v.put(STRESS_SYNTHETIC, "true");
        }
        return new VarDate(vd.date(), vd.book(), vd.portfolios(), vd.version() + "|set=" + setId, vd.loadedAt(), vd.loadMs(), vd.vectorMs(),
                vd.intake(), v, vd.warnings(), vd.currency(), vd.fx(), null);
    }

    /** Per business date, the column hierarchies skipped on it and why (2.9.5). */
    private final Map<LocalDate, Map<String, String>> skipped = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The hierarchies a date is built with: the configured ones but the column hierarchies whose level column a source of the trades does not
     * keep (the P&amp;L vectors' or the scenario PVs' table, when a connector serves it and can say its columns). Each skipped one is said:
     * in the date's provenance (DRS-5211, a warning: the date and its official capture go on), in Admin → Health and in the log. A connector
     * that cannot say its columns is asked for them; its rows without one are refused one by one, as a row with the column left blank is.
     */
    Hierarchies effective(LocalDate date, Hierarchies all) {
        if (all.columnSpecs().isEmpty()) {
            skipped.remove(date);
            return all;
        }
        VarProperties.Kinds k = props.kinds();
        Map<String, java.util.Set<String>> kept = new LinkedHashMap<>();
        kept.put(k.vector(), router.columnar(k.vector()));
        kept.put(k.scenarioPv(), router.columnar(k.scenarioPv()));
        Map<String, String> why = all.missingColumns(kept, " on " + date);
        Map<String, String> before = skipped.put(date, why);
        for (com.ash.drishti.compute.var.HierarchySpec s : all.columnSpecs()) {
            Hierarchies.skipped("var", s.id(), why.get(s.id()));
        }
        if (!why.equals(before)) {
            why.values().forEach(w -> org.slf4j.LoggerFactory.getLogger(VarLoader.class).warn("VaR: {}", w));
        }
        return all.without(why.keySet());
    }

    /** The column hierarchies skipped on a date, id to why (empty: none, or the date not loaded yet). */
    Map<String, String> skipped(LocalDate date) {
        return skipped.getOrDefault(date, Map.of());
    }

    /**
     * Var limits ({@code var-limit}, 2.9.5): node id to limit in USD, for the hierarchies whose {@code limits} names the kind; the date's
     * rows (a dated kind), none when no connector serves it.
     */
    Map<String, Double> limits(AsOf asOf, Hierarchies hs) {
        Map<String, String> kinds = hs.limitKinds();
        Map<String, Double> out = new LinkedHashMap<>();
        if (kinds.isEmpty()) {
            return out;
        }
        for (String kind : new java.util.LinkedHashSet<>(kinds.values())) {
            for (DataNode d : optional(kind, asOf).values()) {
                String h = text(d, "HIERARCHY");
                String node = text(d, "NODE_ID");
                double limit = number(text(d, "LIMIT_VAR"));
                if (h != null && node != null && kind.equals(kinds.get(h.toUpperCase(java.util.Locale.ROOT))) && Double.isFinite(limit)) {
                    out.put(node, limit);
                    basis(asOf, node, d);
                }
            }
        }
        return out;
    }

    /**
     * The portfolios' VaR limits (2.9.7, decision 8 of COMPUTED_ATTRIBUTES.md: every VaR limit lives in {@code var-limit}, with its currency):
     * the date's var-limit rows of hierarchy {@code PORTFOLIO}, portfolio id to limit in USD; empty when no connector serves the kind.
     */
    Map<String, Double> portfolioLimits(AsOf asOf) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (DataNode d : optional(props.kinds().limit(), asOf).values()) {
            String node = text(d, "NODE_ID");
            double limit = number(text(d, "LIMIT_VAR"));
            if ("PORTFOLIO".equalsIgnoreCase(String.valueOf(text(d, "HIERARCHY"))) && node != null && Double.isFinite(limit)) {
                out.put(node, limit);
                basis(asOf, node, d);
            }
        }
        return out;
    }

    /**
     * What a VaR limit is set for (2.9.7, var-limit's {@code HORIZON} and {@code CONFIDENCE}; 1-day 99% when a row does not say): a VaR is
     * compared with it (limit use) only when the answer's horizon and confidence are the limit's own.
     *
     * @param days 1 or 10
     * @param confidence the VaR confidence (0.99)
     */
    record LimitBasis(int days, double confidence) {

        static final LimitBasis DEFAULT = new LimitBasis(1, 0.99);

        boolean matches(com.ash.drishti.compute.var.VarLens lens) {
            // a VaR limit is set against VaR on the normal scenario set: stressed VaR is never compared with it
            return !lens.stressed() && lens.days() == days && Math.abs(lens.confidence() - confidence) < 1e-9;
        }

        /** {@code 1-day 99%}. */
        String label() {
            return days + "-day " + com.ash.drishti.compute.var.VarLens.pct(confidence);
        }

        Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("horizon", days + "D");
            m.put("confidence", confidence);
            m.put("label", label());
            return m;
        }

        /** A var-limit row's basis: its HORIZON (1D, 10D) and CONFIDENCE (0.99 or 99), each the default when absent or unreadable. */
        static LimitBasis of(String horizon, String confidence) {
            int d = 1;
            try {
                d = com.ash.drishti.compute.var.VarLens.parseHorizon(horizon);
            } catch (IllegalArgumentException e) {
                // unreadable: the default horizon
            }
            double c = number(confidence);
            c = Double.isFinite(c) && c > 1 ? c / 100.0 : c;
            return new LimitBasis(d, Double.isFinite(c) && c > 0.5 && c < 1 ? c : DEFAULT.confidence());
        }
    }

    /** Per business date, the basis of each limit that is not 1-day 99% (node or portfolio id to basis). */
    private final Map<LocalDate, Map<String, LimitBasis>> limitBases = new java.util.concurrent.ConcurrentHashMap<>();

    private void basis(AsOf asOf, String node, DataNode d) {
        LimitBasis b = LimitBasis.of(text(d, "HORIZON"), text(d, "CONFIDENCE"));
        LocalDate date = asOf == null ? null : asOf.businessDate();
        if (date == null) {
            return;
        }
        if (limitBases.size() > 64) {
            limitBases.clear();
        }
        Map<String, LimitBasis> m = limitBases.computeIfAbsent(date, x -> new java.util.concurrent.ConcurrentHashMap<>());
        if (b.equals(LimitBasis.DEFAULT)) {
            m.remove(node);
        } else {
            m.put(node, b);
        }
    }

    /** What the limit of a node or portfolio (null: none) is set for on a date: 1-day 99% unless its var-limit row says otherwise. */
    LimitBasis limitBasis(LocalDate date, String id) {
        if (id == null || date == null) {
            return LimitBasis.DEFAULT;
        }
        return limitBases.getOrDefault(date, Map.of()).getOrDefault(id, LimitBasis.DEFAULT);
    }

    /** Per business date, the var-limit rows its load found naming no node (kept with the date; a date is never refused for them). */
    private final Map<LocalDate, List<String>> unknownLimits = new java.util.concurrent.ConcurrentHashMap<>();

    private void unknownLimits(LocalDate date, List<String> nodes) {
        if (unknownLimits.size() > 64) {
            unknownLimits.clear();
        }
        unknownLimits.put(date, List.copyOf(nodes));
    }

    /**
     * What the answers of a date say about its hierarchies (2.9.5), in the provenance's warnings: positions split at a cell's edge, limits
     * naming no node (DRS-5212). Data-quality notes: never a reason to answer another date or skip the official capture.
     */
    List<String> hierarchyNotes(VarDate d) {
        List<String> out = new ArrayList<>(skipped(d.date()).values());
        out.addAll(warnings(d.book(), unknownLimits.getOrDefault(d.date(), List.of())));
        return out;
    }

    /** What the answers of a date say about its hierarchies: positions split at a cell's edge, limits naming no node (DRS-5212). */
    static List<String> warnings(VarBook book, List<String> unknownLimits) {
        List<String> out = new ArrayList<>();
        if (book.splitPositions() > 0) {
            out.add(book.splitPositions() + " position" + (book.splitPositions() == 1 ? "" : "s") + " had trades under two nodes of a hierarchy "
                    + "built from columns and " + (book.splitPositions() == 1 ? "is" : "are") + " split, one part per node (<position>@<value>)");
        }
        if (!unknownLimits.isEmpty()) {
            out.add(ErrorCode.VAR_LIMIT_UNKNOWN_NODE.code() + " " + unknownLimits.size() + " VaR limit" + (unknownLimits.size() == 1 ? " names" : "s name")
                    + " no node of the date, unused: " + String.join(", ", unknownLimits.subList(0, Math.min(5, unknownLimits.size())))
                    + (unknownLimits.size() > 5 ? ", ..." : ""));
        }
        return out;
    }

    /** The load's own record kept in the hot file: what the intake counted and the inputs' versions (read back by {@link #reopen}). */
    private static String meta(VarIntake.Summary sum, Map<String, String> versions, Map<String, Object> fx) {
        try {
            return JSON.writeValueAsString(Map.of("intake", sum, "versions", versions, "fx", fx));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }

    /** The var-limit rows of a date that name no node of its book (a date reopened from its hot file: its limits read again). */
    private List<String> unknownLimits(LocalDate date, VarBook book) {
        List<String> out = new ArrayList<>();
        try {
            limits(AsOf.of(date), hierarchies.get()).keySet().stream().filter(n -> book.node(n) == null).forEach(out::add);
        } catch (RuntimeException e) {
            // the limits are a warning only
        }
        return out;
    }

    /**
     * A date opened from its hot file (built from the same inputs): mapped, its intake and versions from the file, its portfolios read
     * again (they are not in the file's digest). Null when the file cannot be used (then the date is built from the lake).
     */
    private VarDate reopen(LocalDate date, java.nio.file.Path file, Map<String, String> versions, long t0, boolean withDay) {
        VarBook book;
        try {
            book = tier.open(file, t0);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(VarLoader.class).warn("VaR: the hot file {} is not usable ({}); the date is read from the lake", file,
                    e.getMessage());
            return null;
        }
        VarIntake.Summary sum;
        Map<String, String> v = new LinkedHashMap<>(versions);
        Map<String, Object> fx = new LinkedHashMap<>();
        try {
            com.fasterxml.jackson.databind.JsonNode m = JSON.readTree(book.hot().meta());
            sum = JSON.treeToValue(m.get("intake"), VarIntake.Summary.class);
            m.path("versions").fields().forEachRemaining(e -> v.putIfAbsent(e.getKey(), e.getValue().asText()));
            if (m.path("fx").isObject()) {
                fx.putAll(JSON.convertValue(m.get("fx"), Map.class));
            }
        } catch (Exception e) {
            book.close();
            return null;
        }
        v.put("scenarioSet", book.scenarioSet());
        Map<String, VarDate.Portfolio> portfolios = new LinkedHashMap<>();
        Map<String, Double> pfLimits = portfolioLimits(AsOf.of(date));
        documents(props.kinds().portfolio(), AsOf.of(date)).forEach((id, p) -> portfolios.put(id, VarDate.Portfolio.of(id, p, pfLimits)));
        tier.opened(book);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        VarDate vd = new VarDate(date, book, portfolios, version(date), java.time.Instant.now(), ms, 0, sum, v, List.of(), VarDate.USD, fx);
        if (!withDay) {
            return vd;                                                 // a shard: the morning run only
        }
        unknownLimits(date, unknownLimits(date, book));
        try {                                                          // the hot file holds the morning run: the day's updates read again
            return withIntraday(vd, readIntraday(date, book, java.util.Set.of(), sum), book.scenarioDates());
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(VarLoader.class).warn("VaR: the intraday updates of {} could not be read: {}", date, e.getMessage());
            return vd.warned(intradayUnread(date, e));                 // said on every answer, never a silent morning run
        }
    }

    /** The warning of a date answered without its intraday updates because they could not be read (the next data-load signal reads them). */
    static String intradayUnread(LocalDate date, RuntimeException e) {
        return INTRADAY_UNREAD + date + " could not be read (" + e.getMessage() + "): the morning run alone is answered until the next data-load "
                + "signal of the date reads them";
    }

    /** The start of {@link #intradayUnread}'s warning: dropped once a signal has read the updates. */
    static final String INTRADAY_UNREAD = "the intraday updates of ";

    // ---------------------------------------------------------------------------------------------------------------- intraday

    /**
     * The date's intraday updates (VAR_GUIDE.md section 8.4) applied on its morning book: the latest per trade, those computed before the
     * morning run left out; the date as loaded when there are none.
     */
    VarDate withIntraday(VarDate vd, VarIntake intake, List<LocalDate> days) {
        VarIntradayRows state = new VarIntradayRows().morning(intake.morningAt(), intake.summary().batchIds());
        state.met(intake.intradayBatches());
        state.metParts(intake.intradayParts());
        state.rejected(intake.intradayRejections());
        for (VarIntradayRows.Row r : intake.intraday().rows()) {
            state.offer(r);
        }
        if (state.isEmpty()) {
            return vd.withBook(vd.book(), state);                      // the morning run's COMPUTED_AT kept: the PV topic's guard
        }
        VarBook b = apply(vd.date(), vd.book(), state.rows(), days, vd.intake());
        return vd.withBook(b, state);
    }

    /** Applies updates (USD) on a USD book: each trade's difference into its book, its nodes and the per-trade-currency totals. */
    VarBook apply(LocalDate date, VarBook book, List<VarIntradayRows.Row> rows, List<LocalDate> days, VarIntake.Summary sum) {
        String last = null;
        List<com.ash.drishti.compute.var.VarIntraday.Update> ups = new ArrayList<>();
        for (VarIntradayRows.Row r : rows) {
            last = VarIntradayRows.compare(r.computedAt(), last) > 0 ? r.computedAt() : last;
            ups.add(r.usd());
        }
        com.ash.drishti.compute.var.ScenarioFx fx = fx(date, days);
        return com.ash.drishti.compute.var.VarIntraday.apply(book, ups, exact(date, book.morning(), sum, days), c -> fx.rates(c).orElse(null),
                VarIntradayRows.batchAt(rows), last);
    }

    /**
     * A morning trade's USD P&amp;L exactly as its node aggregates were summed from it (float64): on the heap, its row; with the tiered
     * memory (float32 rows), its morning row read back from the lake by its id ({@code PNL-<trade>-<set>}, 2.9.5, or the earlier
     * {@code PV-<trade>-<set>}; {@code SPV-<trade>-<set>}), its float32 row when that cannot be read.
     */
    com.ash.drishti.compute.var.VarIntraday.Morning exact(LocalDate date, VarBook morning, VarIntake.Summary sum, List<LocalDate> days) {
        if (morning.hot() == null && !morning.sharded()) {
            return morning::tradeVector;
        }
        return o -> {
            String tid = morning.tradeId(o);
            try {
                double[] v = morningRow(date, tid, morning.scenarioSet(), sum != null && sum.fromScenarioPv(tid), days, morning.width());
                if (v != null) {
                    return v;
                }
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(VarLoader.class).debug("VaR: the morning row of {} not read back: {}", tid, e.getMessage());
            }
            return morning.tradeVector(o);
        };
    }

    private double[] morningRow(LocalDate date, String trade, String set, boolean fromPv, List<LocalDate> days, int width) {
        VarProperties.Kinds k = props.kinds();
        EntityDocument d = null;
        for (String prefix : fromPv ? List.of("SPV-") : List.of(PNL_ID, OLD_PNL_ID)) {      // 2.9.5 ids first, then the earlier PV- ids
            com.ash.drishti.api.EntityRef ref = new com.ash.drishti.api.EntityRef(fromPv ? k.scenarioPv() : k.vector(), prefix + trade + "-" + set);
            d = router.fetchAll(List.of(ref), java.time.Duration.ofSeconds(30), AsOf.of(date)).get(ref);
            if (d != null && d.data() != null) {
                break;
            }
        }
        if (d == null || d.data() == null) {
            return null;
        }
        DataNode arr = d.data().get(fromPv ? SCENARIO_PV : "PNL");
        if (arr == null || arr.isMissing() || arr.isNull() || arr.size() != width) {
            return null;
        }
        double[] v = new double[width];
        for (int j = 0; j < width; j++) {
            v[j] = arr.get(j).asDouble();
        }
        if (!fromPv) {
            return v;
        }
        com.ash.drishti.compute.var.ScenarioFx.Rates r = fx(date, days).rates(text(d.data(), "CURRENCY") == null ? "USD" : text(d.data(), "CURRENCY"))
                .orElse(null);
        double base = number(text(d.data(), "BASE_PV"));
        if (r == null || Double.isNaN(base)) {
            return null;
        }
        double[] out = new double[width];
        com.ash.drishti.compute.var.ScenarioFx.pnlFromPv(base, v, width, r, out);
        return out;
    }

    /**
     * The intraday rows of a loaded date (both forms), the batches in {@code applied} skipped: what a data-load signal of an intraday
     * batch brings (VAR_GUIDE.md section 8.4). Reads the date's rows once; keeps only the latest update per trade.
     */
    /**
     * Why intraday rows cannot be told from the morning run's, or null: the connector reads its promoted columns only, and one generated
     * from the pack's template before 2.9.4 ({@code config/connectors/risk-store.yaml}, never overwritten) does not promote
     * {@code RUN_KIND} and {@code COMPUTED_AT}. Logged once; added to a duplicate trade's error (an intraday row read as a second
     * morning row).
     */
    private String unmarked(List<String> columns, List<String> pvColumns) {
        VarProperties.Kinds k = props.kinds();
        List<String> missing = new ArrayList<>();
        if (!router.columnar(k.vector()).isEmpty() && !columns.containsAll(MARKED)) {
            missing.add("layout." + k.vector() + ".columns");
        }
        if (!router.columnar(k.scenarioPv()).isEmpty() && !pvColumns.containsAll(MARKED)) {
            missing.add("layout." + k.scenarioPv() + ".columns");
        }
        if (missing.isEmpty()) {
            return null;
        }
        String why = "the connector does not promote " + String.join(", ", MARKED) + " (" + String.join(", ", missing) + "), so the "
                + "records cannot say what they are (VAR_GUIDE.md sections 8.4 and 15.13): a connector file written from the pack's template "
                + "before 2.9.4 (config/connectors/<connector>.yaml is never overwritten) needs them added to those settings";
        if (UNMARKED_SAID.compareAndSet(false, true)) {
            org.slf4j.LoggerFactory.getLogger(VarLoader.class).warn("VaR: {}", why);
        }
        return why;
    }

    /** The columns a PV record says what it is with (2.9.4): a connector's layout must promote them. */
    private static final List<String> MARKED = List.of("RUN_KIND", "COMPUTED_AT", "VALUE_TYPE", "GRAIN", "SOURCE_SYSTEM", "POSITION_ID");
    private static final java.util.concurrent.atomic.AtomicBoolean UNMARKED_SAID = new java.util.concurrent.atomic.AtomicBoolean();

    VarIntake readIntraday(LocalDate date, VarBook morning, java.util.Set<String> applied, VarIntake.Summary morningRun) {
        VarProperties.Kinds k = props.kinds();
        AsOf asOf = AsOf.of(date);
        PositionRules pr = rules.get();
        List<LocalDate> days = morning.scenarioDates();
        VarBook.Builder none = new VarBook.Builder(date, morning.scenarioSet(), days, 1, Long.MAX_VALUE);   // never given a row
        none.cells(morning.cellColumns());                                 // the morning's cell columns: an update's texts in its order
        List<String> levels = effective(date, hierarchies.get()).levelColumns();
        List<String> columns = VarIntake.columns(router.columnar(k.vector()), pr, List.of(), levels);
        List<String> pvColumns = VarIntake.columns(router.columnar(k.scenarioPv()), pr, VarIntake.PV_COLUMNS, levels);
        unmarked(columns, pvColumns);
        VarInputs in = new VarInputs(props::input);
        in.restore(morningRun == null ? null : morningRun.inputs());     // each system's morning form and grain: an update follows it
        VarIntake intake = new VarIntake(none, pr, date, columns.subList(1, columns.size())).scenarioSet(morning.scenarioSet()).intradayOnly(applied)
                .levels(levels)
                .inputs(in).setLength(morningRun == null || morningRun.setLength() <= 0 ? days.size() : morningRun.setLength())
                .pnlFx(() -> fx(date, days));
        java.util.function.Predicate<String> normal = keepNormal(stressIds(date));     // the stress set's rows are never the normal set's
        try {
            router.vectors(k.vector(), "PNL", columns, asOf, com.ash.drishti.api.VectorReads.parallel(ofSet(columns, normal, intake::row),
                    reads));
            intake.scenarioPvs(pvColumns.subList(1, pvColumns.size()), () -> fx(date, days), days);
            router.vectors(k.scenarioPv(), SCENARIO_PV, pvColumns, asOf, com.ash.drishti.api.VectorReads.parallel(ofSet(pvColumns, normal,
                    intake::row), reads));
        } catch (DrishtiException e) {
            throw e;
        } catch (Exception e) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "the intraday updates of " + date + " could not be read: " + e.getMessage());
        }
        return intake;
    }

    // ---------------------------------------------------------------------------------------------------------------------- FX

    /** The prefix of a P&amp;L vector's id (2.9.5): {@code PNL-<trade or position>-<SCENARIO_SET_ID>}. */
    static final String PNL_ID = "PNL-";
    /** The prefix of a P&amp;L vector's id before 2.9.5 ({@code PV-<trade>-<set>}): still read, for the lakes and captures written then. */
    static final String OLD_PNL_ID = "PV-";

    /** The scenario-PV kind's vector column: each trade's PV under each scenario, in its own currency. */
    static final String SCENARIO_PV = "SCENARIO_PV";
    private final Map<String, com.ash.drishti.compute.var.ScenarioFx> fxCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The date's FX against USD for its scenarios (VAR_GUIDE.md section 15.9): per currency, the batch's supplied scenario rates (the
     * {@code fx-scenario} kind, of the date's scenario set) when it sent them, else rates derived from the FX history of the
     * {@code fx-spot} kind (today's rate moved by each scenario day's 1-day move); pairs against USD only (every cross goes through
     * USD). Kept per date and input versions.
     */
    com.ash.drishti.compute.var.ScenarioFx fx(LocalDate date, List<LocalDate> scenarioDates) {
        return fx(date, scenarioDates, null);
    }

    /**
     * The same for a scenario set: {@code stressSet} the date's stress set (its own supplied scenario FX only), or null for the normal set
     * (the stress set's supplied rows left out). The stress days' FX is never derived from a history the lake does not hold: a currency
     * the stress set's batch did not supply is refused for its trades ({@code DRS-5207}), as for the normal set.
     */
    com.ash.drishti.compute.var.ScenarioFx fx(LocalDate date, List<LocalDate> scenarioDates, String stressSet) {
        VarProperties.Kinds k = props.kinds();
        AsOf asOf = AsOf.of(date);
        String key = date + "|" + router.dataVersion(k.fxScenario(), asOf).orElse("") + "|" + router.dataVersion(k.fxSpot(), asOf).orElse("")
                + "|" + scenarioDates.size() + "|" + scenarioDates.get(scenarioDates.size() - 1) + "|" + stressSet;
        com.ash.drishti.compute.var.ScenarioFx hit = fxCache.get(key);
        if (hit != null) {
            return hit;
        }
        java.util.Set<String> stressIds = stressSet == null ? stressIds(date) : java.util.Set.of();
        List<com.ash.drishti.compute.var.ScenarioFx.Rates> supplied = new ArrayList<>();
        for (DataNode d : optional(k.fxScenario(), asOf).values()) {
            String ccy = text(d, "CURRENCY");
            DataNode rs = d.get("RATES");
            if (ccy == null || rs == null || rs.isMissing() || rs.isNull()) {
                continue;
            }
            String sid = text(d, "SCENARIO_SET_ID");
            if (stressSet != null ? !stressSet.equals(sid) : sid != null && stressIds.contains(sid)) {
                continue;                                              // another set's scenario FX
            }
            int skip = Math.max(0, rs.size() - scenarioDates.size());   // a scenario window: the set's newest scenarios
            double[] r = new double[rs.size() - skip];
            for (int i = 0; i < r.length; i++) {
                r[i] = rs.get(i + skip).isNull() ? Double.NaN : rs.get(i + skip).asDouble();
            }
            supplied.add(new com.ash.drishti.compute.var.ScenarioFx.Rates(ccy, number(text(d, "RATE")), r, "supplied"));
        }
        Map<String, Double> today = new java.util.TreeMap<>();
        Map<String, java.util.TreeMap<LocalDate, Double>> history = new java.util.TreeMap<>();
        for (DataNode d : optional(k.fxSpot(), asOf).values()) {
            DataNode conv = d.get("CONVENTIONS");
            String base = conv == null ? null : text(conv, "BASE");
            String quote = conv == null ? null : text(conv, "QUOTE");
            boolean direct = "USD".equals(quote) && base != null && !base.equals("USD");
            boolean inverse = "USD".equals(base) && quote != null && !quote.equals("USD");
            if (!direct && !inverse) {
                continue;                                              // a cross pair: every cross goes through USD
            }
            String ccy = direct ? base : quote;
            java.util.function.DoubleUnaryOperator usdPer = x -> x > 0 ? (direct ? x : 1 / x) : Double.NaN;
            double mid = number(text(d, "MID"));
            java.util.TreeMap<LocalDate, Double> h = history.computeIfAbsent(ccy, x -> new java.util.TreeMap<>());
            DataNode hs = d.get("HISTORY");
            for (int i = 0; hs != null && i < hs.size(); i++) {
                try {
                    double m = usdPer.applyAsDouble(number(text(hs.get(i), "MID")));
                    if (Double.isFinite(m)) {
                        h.put(LocalDate.parse(text(hs.get(i), "DATE").substring(0, 10)), m);
                    }
                } catch (RuntimeException e) {
                    // a history point that is not a date and a rate is skipped
                }
            }
            if (Double.isFinite(usdPer.applyAsDouble(mid))) {
                today.put(ccy, usdPer.applyAsDouble(mid));
                h.put(date, usdPer.applyAsDouble(mid));
            }
        }
        com.ash.drishti.compute.var.ScenarioFx fx = com.ash.drishti.compute.var.ScenarioFx.assemble(scenarioDates, supplied, today, history);
        if (fxCache.size() > 32) {
            fxCache.clear();
        }
        fxCache.put(key, fx);
        return fx;
    }

    /** The data versions of the date's FX inputs (supplied scenario FX / the FX history), for the hot files of other currencies. */
    String fxVersions(LocalDate date) {
        AsOf asOf = AsOf.of(date);
        return router.dataVersion(props.kinds().fxScenario(), asOf).orElse("") + "/" + router.dataVersion(props.kinds().fxSpot(), asOf).orElse("");
    }

    /** A kind's documents of the date, or none when no connector serves the kind (an optional input). */
    private Map<String, DataNode> optional(String kind, AsOf asOf) {
        try {
            return documents(kind, asOf);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(VarLoader.class).debug("VaR: {} not read on {}: {}", kind, asOf, e.getMessage());
            return Map.of();
        }
    }

    /** The scenario days, checked: strictly increasing and all before the business date (else the date is not one run). */
    private static List<LocalDate> scenarioDates(LocalDate date, String setId, DataNode set) {
        List<LocalDate> out = new ArrayList<>();
        DataNode sd = set.get("SCENARIO_DATES");
        for (int i = 0; sd != null && i < sd.size(); i++) {
            LocalDate d = LocalDate.parse(sd.get(i).asText());
            if (!out.isEmpty() && !d.isAfter(out.get(out.size() - 1))) {
                throw inconsistent(date, "the scenario set " + setId + " is not in date order at " + d);
            }
            if (!d.isBefore(date)) {
                throw inconsistent(date, "the scenario set " + setId + " has the scenario day " + d + ", not before the business date");
            }
            out.add(d);
        }
        if (out.isEmpty()) {
            throw inconsistent(date, "the scenario set " + setId + " has no SCENARIO_DATES");
        }
        return out;
    }

    /** A date's scenario set as the scenario matrix reads it without loading the date: its id, its full days and the window's days. */
    record ScenarioSet(String id, List<LocalDate> full, List<LocalDate> window) {}

    /**
     * The scenario set of a date, read alone (the scenario matrix, VAR_GUIDE.md section 3.9: a date not open in memory read from the lake
     * by id); checked as a load checks it (one set, in date order, before the date), else {@code DRS-5203}; none: {@code DRS-1001}.
     */
    ScenarioSet scenarioSet(LocalDate date) {
        DataNode set = sets(date, AsOf.of(date)).normal(date);                 // the normal set (a stress set beside it is its own)
        String id = text(set, "SCENARIO_SET_ID");
        List<LocalDate> full = scenarioDates(date, id, set);
        return new ScenarioSet(id, full, window(full));
    }

    /** The business dates with trades' scenario P&amp;L (vectors or scenario PVs) on or before {@code upTo}, newest first, at most {@code n}. */
    List<LocalDate> datesUpTo(LocalDate upTo, int n) {
        java.util.TreeSet<LocalDate> all = new java.util.TreeSet<>();
        router.heldDates(props.kinds().vector()).ifPresent(all::addAll);
        router.heldDates(props.kinds().scenarioPv()).ifPresent(all::addAll);
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d : upTo == null ? all.descendingSet() : all.headSet(upTo, true).descendingSet()) {
            if (out.size() >= n) {
                break;
            }
            out.add(d);
        }
        return out;
    }

    /** The source that answers a kind by id (the scenario matrix's lake read of one trade or position): the router. */
    com.ash.drishti.engine.source.SourceRouter router() {
        return router;
    }

    /** The engine's settings (the kinds the matrix reads by id). */
    VarProperties props() {
        return props;
    }

    private static DrishtiException inconsistent(LocalDate date, String why) {
        return new DrishtiException(ErrorCode.VAR_INCONSISTENT_DATE, "the VaR inputs of " + date + " are not loaded: " + why);
    }

    private Map<String, DataNode> documents(String kind, AsOf asOf) {
        Map<String, DataNode> out = new LinkedHashMap<>();
        boolean whole = router.documents(kind, asOf, (EntityDocument d) -> out.put(d.ref().id(), d.data()));
        if (!whole) {
            var listing = router.listAcross(kind, 100_000, java.time.Duration.ofSeconds(30), asOf, new com.ash.drishti.engine.source.SourceFailures());
            var refs = listing.hits().stream().map(h -> h.ref()).toList();
            router.fetchAll(refs, java.time.Duration.ofSeconds(30), asOf).forEach((r, d) -> out.put(r.id(), d.data()));
        }
        return out;
    }

    /**
     * A kind's whole documents of a date by id, vector columns included (the stress window selection's {@code pnl-history}, VAR_GUIDE.md
     * section 18.9): the ids listed, then each fetched whole (a listing leaves a table's vector columns out).
     */
    Map<String, DataNode> documentsOf(String kind, LocalDate date) {
        AsOf asOf = AsOf.of(date);
        List<com.ash.drishti.api.EntityRef> refs = documents(kind, asOf).keySet().stream()
                .map(id -> new com.ash.drishti.api.EntityRef(kind, id)).toList();
        Map<String, DataNode> out = new LinkedHashMap<>();
        if (!refs.isEmpty()) {
            router.fetchAll(refs, java.time.Duration.ofSeconds(30), asOf).forEach((r, d) -> {
                if (d != null && d.data() != null) {
                    out.put(r.id(), d.data());
                }
            });
        }
        return out;
    }

    /** A kind's data version on a date ("" when no connector holds it). */
    String dataVersionOf(String kind, LocalDate date) {
        return router.dataVersion(kind, AsOf.of(date)).orElse("");
    }

    /** The newest date a kind holds, if any. */
    java.util.Optional<LocalDate> latestOf(String kind) {
        return router.latestDate(kind);
    }

    static String text(DataNode n, String field) {
        DataNode v = n.get(field);
        return v == null || v.isMissing() || v.isNull() ? null : v.asText();
    }

    static double number(String s) {
        if (s == null || s.isBlank()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
