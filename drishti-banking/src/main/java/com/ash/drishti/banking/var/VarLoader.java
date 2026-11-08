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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.banking.compute.var.PositionRules;
import com.ash.drishti.banking.compute.var.VarBook;
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

    /** The hierarchies a load builds now (the setting, else a pack's block, else none). */
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
     * The rows of a date per shard key value (DATA_ARCHITECTURE.md section 13), read as columns (no vector is read): every kind of {@code kinds}
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

    /** The system baskets' VaR limits of a date (USD), read again for a date put together from its shards. */
    Map<String, Double> portfolioLimits(LocalDate date) {
        return portfolioLimits(AsOf.of(date));
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
        String hierKey = props.rootNamesKey() + hs.digest() + namesKey(date, hs)
                + (hs.limitKinds().isEmpty() ? "" : "|limits=" + versions.getOrDefault(k.limit(), ""));
        String setKey = versions.get(k.scenarioSet()) + (stressSet != null ? "|stress=" + stressSet : props.window() > 0 ? "|window=" + props.window() : "");
        return VarTier.digest(date, inputsKey, hierKey, setKey, pr.digest());
    }

    /**
     * The digest a shard's book built now would have (DATA_ARCHITECTURE.md section 13): what a member that puts the date together expects of the
     * shard's skeleton, computed from its own reads of the inputs' versions (empty when the trades' source cannot say its version).
     */
    String shardDigest(LocalDate date, VarShardScope shard) {
        return digest(date, versions(date), effective(date, hierarchies.get()), rules.get(), shard);
    }

    /**
     * One shard of a sharded date (DATA_ARCHITECTURE.md section 13): the date's inputs read for the rows of the shard's key range only (the
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
        // the hierarchies, checked against the sources' promoted columns: a level column a source does not promote is a warning (its rows
        // sit under (Blank) at that level), one promoted by no source refuses the date (DRS-5213)
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
        // the hierarchies' cells and nodes, from the trades' promoted columns; their roots named by drishti.var.root-name(s) when set, each
        // node by its reference entity (desk, book, legal-entity) when there is one
        b.columnHierarchies(hs.specs().stream().map(s -> s.withRootName(props.rootName(s.id(), null, s.rootName()))).toList(), limits,
                names(asOf, hs));
        try {
            VarIntake intake = intake(date, asOf, b, pr, setId, scenarioDates, fullSet.size(), hs, shard, stress ? keepOnly(setId)
                    : keepNormal(sets.stressIds()), fxSet);
            List<LocalDate> days = scenarioDates;
            VarIntake.Summary sum = intake.summary();
            long tVectors = System.nanoTime();
            Map<String, Double> portfolios = portfolioLimits(asOf);       // the system baskets' limits (their definitions: the basket store)
            versions.put("scenarioSet", setId);
            b.batchId(sum.batchIds().isEmpty() ? "" : sum.batchIds().get(0));
            VarBook book;
            try {
                book = b.meta(meta(sum, versions, intake.fx())).build(sums);
            } catch (IllegalStateException e) {
                throw new DrishtiException(ErrorCode.VAR_HIERARCHY_INVALID, "the VaR inputs of " + date + " are not loaded: " + e.getMessage());
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
            limitsWithoutNodes(date, book, hs);
            blanks(date, book, hs, sum);
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
     * What a date's nodes are built from (a load, or a sharded date put together, DATA_ARCHITECTURE.md#13-var-sharding-how-it-is-built):
     * the hierarchies, every one from the trades' promoted columns, their roots named as configured, with the var-limit rows and the
     * reference entities' names.
     */
    com.ash.drishti.banking.compute.var.ShardAssembly.Nodes nodeRows(AsOf asOf, Hierarchies hs, Map<String, Double> limits) {
        List<com.ash.drishti.banking.compute.var.HierarchySpec> specs = hs.specs().stream()
                .map(s -> s.withRootName(props.rootName(s.id(), null, s.rootName()))).toList();
        return new com.ash.drishti.banking.compute.var.ShardAssembly.Nodes(List.of(), Map.of(), Map.of(), specs, limits, names(asOf, hs));
    }

    /**
     * The display names of a date's nodes (VAR_GUIDE.md section 15.14): a level's name is the reference kind ({@code desk}, {@code book},
     * {@code legal-entity}) and the value its id; the entity's {@code NAME} when a connector serves it, else none (the node is named by
     * its value). Each kind's names are read once per load, as one column when its source keeps {@code NAME} as a column, else entity by
     * entity (at most {@link #NAME_READS} a kind, then by value).
     */
    com.ash.drishti.banking.compute.var.Cells.Names names(AsOf asOf, Hierarchies hs) {
        java.util.Set<String> levels = new java.util.HashSet<>();
        hs.specs().forEach(s -> levels.addAll(s.levelNames()));
        Map<String, Map<String, String>> byKind = new java.util.concurrent.ConcurrentHashMap<>();
        Map<String, java.util.concurrent.atomic.AtomicInteger> reads = new java.util.concurrent.ConcurrentHashMap<>();
        return (level, value) -> {
            if (!levels.contains(level) || value == null) {
                return null;
            }
            Map<String, String> names = byKind.computeIfAbsent(level, k -> nameColumn(k, asOf));
            String got = names.get(value);
            if (got == null && !names.containsKey(ALL_READ) && reads.computeIfAbsent(level, k -> new java.util.concurrent.atomic.AtomicInteger())
                    .incrementAndGet() <= NAME_READS) {
                got = nameOf(level, value, asOf);
                names.put(value, got);
            }
            return got == null || got.isEmpty() ? null : got;
        };
    }

    /** The most entities of one kind whose names a load reads one by one (a kind whose source keeps no NAME column). */
    static final int NAME_READS = 200;
    private static final String ALL_READ = "\u0000all";

    /** A kind's names as one column ({@code NAME} kept as a column by its source), marked complete; else an empty map to fill. */
    private Map<String, String> nameColumn(String kind, AsOf asOf) {
        Map<String, String> out = new java.util.concurrent.ConcurrentHashMap<>();
        try {
            router.columns(kind, List.of("NAME"), asOf, java.time.Duration.ofSeconds(30)).filter(c -> c.has("NAME")).ifPresent(c -> {
                for (int i = 0; i < c.size(); i++) {
                    Object n = c.value("NAME", i);
                    if (c.ids()[i] != null && n != null && !String.valueOf(n).isBlank()) {
                        out.put(c.ids()[i], String.valueOf(n).strip());
                    }
                }
                out.put(ALL_READ, "");
            });
        } catch (RuntimeException e) {
            out.put(ALL_READ, "");                                     // no connector serves the kind: every node named by its value
        }
        return out;
    }

    /** One entity's {@code NAME} ("" when there is none). */
    private String nameOf(String kind, String id, AsOf asOf) {
        try {
            com.ash.drishti.api.EntityRef ref = new com.ash.drishti.api.EntityRef(kind, id);
            EntityDocument d = router.fetchAll(List.of(ref), java.time.Duration.ofSeconds(10), asOf).get(ref);
            String n = d == null || d.data() == null ? null : text(d.data(), "NAME");
            return n == null || n.isBlank() ? "" : n.strip();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** What the reference entities that name the nodes add to a hot file's digest: each level kind's data version. */
    private String namesKey(LocalDate date, Hierarchies hs) {
        java.util.TreeSet<String> kinds = new java.util.TreeSet<>();
        hs.specs().forEach(s -> kinds.addAll(s.levelNames()));
        StringBuilder b = new StringBuilder();
        for (String k : kinds) {
            String v;
            try {
                v = router.dataVersion(k, AsOf.of(date)).orElse("");
            } catch (RuntimeException e) {
                v = "";
            }
            if (!v.isEmpty() && !v.equals("none")) {
                b.append("|names.").append(k).append('=').append(v);
            }
        }
        return b.toString();
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
                .inputs(new VarInputs(props::input)).setLength(setLength).pnlFx(() -> fx(date, scenarioDates, fxSet));
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
        return new VarDate(vd.date(), vd.book(), vd.portfolioLimits(), vd.version() + "|set=" + setId, vd.loadedAt(), vd.loadMs(), vd.vectorMs(),
                vd.intake(), v, vd.warnings(), vd.currency(), vd.fx(), null);
    }

    /** Per business date, its hierarchy warnings: level columns not promoted, (Blank) nodes and their counts (VAR_GUIDE.md section 15.14). */
    private final Map<LocalDate, List<String>> blankNotes = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The hierarchies a date is built with, checked against the promoted columns of the sources of its trades (the P&amp;L vectors' and the
     * scenario PVs' tables, when a connector can say its columns): a level column a source does not promote is a warning (its rows sit
     * under (Blank) at that level, said in Admin → Health and the log); a level column no source promotes refuses the date (DRS-5213: a
     * typo in {@code levels:}).
     */
    Hierarchies effective(LocalDate date, Hierarchies all) {
        VarProperties.Kinds k = props.kinds();
        Map<String, java.util.Set<String>> kept = new LinkedHashMap<>();
        kept.put(k.vector(), router.columnar(k.vector()));
        kept.put(k.scenarioPv(), router.columnar(k.scenarioPv()));
        Map<String, String> why = all.unpromoted(kept, true);
        Map<String, String> before = Hierarchies.warnings().entrySet().stream().filter(e -> e.getKey().startsWith("var "))
                .collect(java.util.stream.Collectors.toMap(e -> e.getKey().substring(4), Map.Entry::getValue));
        Hierarchies.warnings("var", why);
        if (!why.equals(before)) {
            why.values().forEach(w -> org.slf4j.LoggerFactory.getLogger(VarLoader.class).warn("VaR: {}", w));
        }
        return all;
    }

    /**
     * The (Blank) nodes of a loaded date (VAR_GUIDE.md section 15.14), each with its count of trades by source kind, kept with the date for
     * its answers' provenance (a warning, never a reason to refuse the date).
     */
    private void blanks(LocalDate date, VarBook book, Hierarchies hs, VarIntake.Summary sum) {
        List<String> out = new ArrayList<>(Hierarchies.warnings().entrySet().stream().filter(e -> e.getKey().startsWith("var "))
                .map(Map.Entry::getValue).toList());
        for (VarBook.Node n : book.nodes().values()) {
            if (!com.ash.drishti.banking.compute.var.HierarchySpec.blankNode(n.id()) || !n.children().isEmpty() && hasBlankParent(book, n)) {
                continue;
            }
            int[] byKind = new int[2];
            n.members().forEach(o -> byKind[sum != null && sum.fromScenarioPv(book.tradeId(o)) ? 1 : 0]++);
            com.ash.drishti.banking.compute.var.HierarchySpec s = hs.spec(n.hierarchy());
            int i = s == null ? -1 : s.levelNames().indexOf(n.level());
            String col = i < 0 ? n.level() : s.levels().get(i);
            out.add(n.name() + " " + n.id() + " (" + n.hierarchy() + ", level " + n.level() + "): " + (byKind[0] + byKind[1])
                    + " trades with no " + col + " value (" + props.kinds().vector() + " " + byKind[0] + ", " + props.kinds().scenarioPv() + " "
                    + byKind[1] + "); fix: send " + col + " on every row and promote it in the connector's layout.<kind>.columns");
        }
        if (blankNotes.size() > 64) {
            blankNotes.clear();
        }
        blankNotes.put(date, List.copyOf(out));
    }

    /** True when a (Blank) node's parent is a (Blank) node too (the rows are said once, at the highest empty level). */
    private static boolean hasBlankParent(VarBook book, VarBook.Node n) {
        return n.parent() != null && com.ash.drishti.banking.compute.var.HierarchySpec.blankNode(n.parent());
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
     * The portfolios' VaR limits (2.9.7, DATA_ARCHITECTURE.md section 12: every VaR limit lives in {@code var-limit}, with its currency):
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

        boolean matches(com.ash.drishti.banking.compute.var.VarLens lens) {
            // a VaR limit is set against VaR on the normal scenario set: stressed VaR is never compared with it
            return !lens.stressed() && lens.days() == days && Math.abs(lens.confidence() - confidence) < 1e-9;
        }

        /** {@code 1-day 99%}. */
        String label() {
            return days + "-day " + com.ash.drishti.banking.compute.var.VarLens.pct(confidence);
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
                d = com.ash.drishti.banking.compute.var.VarLens.parseHorizon(horizon);
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

    /**
     * Per business date, the var-limit rows naming no node of the date (kept with the date; a date is never refused for them): a node its
     * hierarchy can build but with no positions on the date ({@code limit, no positions}), or one it cannot build (DRS-5212, the reason).
     */
    private final Map<LocalDate, LimitsWithoutNodes> limitsWithoutNodes = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The var-limit rows of a date without a node.
     *
     * @param noPositions node id to its hierarchy: a node of a configured hierarchy with no positions on the date
     * @param unbuildable node id to why it cannot be a node (DRS-5212)
     */
    record LimitsWithoutNodes(Map<String, String> noPositions, Map<String, String> unbuildable) {
        static final LimitsWithoutNodes NONE = new LimitsWithoutNodes(Map.of(), Map.of());
    }

    private void limitsWithoutNodes(LocalDate date, VarBook book, Hierarchies hs) {
        if (limitsWithoutNodes.size() > 64) {
            limitsWithoutNodes.clear();
        }
        try {
            limitsWithoutNodes.put(date, limitsWithoutNodes(AsOf.of(date), book, hs));
        } catch (RuntimeException e) {
            limitsWithoutNodes.put(date, LimitsWithoutNodes.NONE);   // the limits are a warning only
        }
    }

    /** The var-limit rows of a date that name no node of its book, told apart: no positions, or not a node of its hierarchy (DRS-5212). */
    LimitsWithoutNodes limitsWithoutNodes(AsOf asOf, VarBook book, Hierarchies hs) {
        Map<String, String> none = new java.util.TreeMap<>();
        Map<String, String> bad = new java.util.TreeMap<>();
        Map<String, String> kinds = hs.limitKinds();
        for (String kind : new java.util.LinkedHashSet<>(kinds.values())) {
            for (DataNode d : optional(kind, asOf).values()) {
                String h = text(d, "HIERARCHY");
                String node = text(d, "NODE_ID");
                if (h == null || node == null || "PORTFOLIO".equalsIgnoreCase(h) || book.node(node) != null) {
                    continue;
                }
                String why = hs.unbuildable(h.toUpperCase(java.util.Locale.ROOT), node);
                if (why == null) {
                    none.put(node, h.toUpperCase(java.util.Locale.ROOT));
                } else {
                    bad.put(node, why);
                }
            }
        }
        return new LimitsWithoutNodes(Map.copyOf(none), Map.copyOf(bad));
    }

    /** The var-limit rows of a date without a node (empty: none, or the date not loaded yet). */
    LimitsWithoutNodes limitsWithoutNodes(LocalDate date) {
        return limitsWithoutNodes.getOrDefault(date, LimitsWithoutNodes.NONE);
    }

    /**
     * What the answers of a date say about its hierarchies, in the provenance's warnings: level columns not promoted and (Blank) nodes,
     * positions split at a cell's edge, limits without a node. Data-quality notes: never a reason to answer another date or skip the
     * official capture.
     */
    List<String> hierarchyNotes(VarDate d) {
        List<String> out = new ArrayList<>(blankNotes.getOrDefault(d.date(), List.of()));
        out.addAll(warnings(d.book(), limitsWithoutNodes(d.date())));
        return out;
    }

    /** What the answers of a date say about its hierarchies: positions split at a cell's edge, limits without a node. */
    static List<String> warnings(VarBook book, LimitsWithoutNodes limits) {
        List<String> out = new ArrayList<>();
        if (book.splitPositions() > 0) {
            out.add(book.splitPositions() + " position" + (book.splitPositions() == 1 ? "" : "s") + " had trades under two nodes of a hierarchy "
                    + "and " + (book.splitPositions() == 1 ? "is" : "are") + " split, one part per node (<position>@<value>)");
        }
        if (!limits.noPositions().isEmpty()) {
            List<String> ids = new ArrayList<>(limits.noPositions().keySet());
            out.add(ids.size() + " VaR limit" + (ids.size() == 1 ? "" : "s") + " with no positions on the date (limit, no positions): "
                    + String.join(", ", ids.subList(0, Math.min(5, ids.size()))) + (ids.size() > 5 ? ", ..." : ""));
        }
        if (!limits.unbuildable().isEmpty()) {
            List<String> ids = new ArrayList<>(limits.unbuildable().keySet());
            out.add(ErrorCode.VAR_LIMIT_UNKNOWN_NODE.code() + " " + ids.size() + " VaR limit" + (ids.size() == 1 ? " names" : "s name")
                    + " no node its hierarchy can build, unused: " + String.join("; ", ids.subList(0, Math.min(5, ids.size())).stream()
                    .map(i -> i + " (" + limits.unbuildable().get(i) + ")").toList()) + (ids.size() > 5 ? "; ..." : ""));
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
        Map<String, Double> portfolios = portfolioLimits(AsOf.of(date));
        tier.opened(book);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        VarDate vd = new VarDate(date, book, portfolios, version(date), java.time.Instant.now(), ms, 0, sum, v, List.of(), VarDate.USD, fx);
        if (!withDay) {
            return vd;                                                 // a shard: the morning run only
        }
        Hierarchies hs = hierarchies.get();
        limitsWithoutNodes(date, book, hs);
        blanks(date, book, hs, sum);
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
        List<com.ash.drishti.banking.compute.var.VarIntraday.Update> ups = new ArrayList<>();
        for (VarIntradayRows.Row r : rows) {
            last = VarIntradayRows.compare(r.computedAt(), last) > 0 ? r.computedAt() : last;
            ups.add(r.usd());
        }
        com.ash.drishti.banking.compute.var.ScenarioFx fx = fx(date, days);
        return com.ash.drishti.banking.compute.var.VarIntraday.apply(book, ups, exact(date, book.morning(), sum, days), c -> fx.rates(c).orElse(null),
                VarIntradayRows.batchAt(rows), last);
    }

    /**
     * A morning trade's USD P&amp;L exactly as its node aggregates were summed from it (float64): on the heap, its row; with the tiered
     * memory (float32 rows), its morning row read back from the lake by its id ({@code PNL-<trade>-<set>}, 2.9.5, or the earlier
     * {@code PV-<trade>-<set>}; {@code SPV-<trade>-<set>}), its float32 row when that cannot be read.
     */
    com.ash.drishti.banking.compute.var.VarIntraday.Morning exact(LocalDate date, VarBook morning, VarIntake.Summary sum, List<LocalDate> days) {
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
        com.ash.drishti.banking.compute.var.ScenarioFx.Rates r = fx(date, days).rates(text(d.data(), "CURRENCY") == null ? "USD" : text(d.data(), "CURRENCY"))
                .orElse(null);
        double base = number(text(d.data(), "BASE_PV"));
        if (r == null || Double.isNaN(base)) {
            return null;
        }
        double[] out = new double[width];
        com.ash.drishti.banking.compute.var.ScenarioFx.pnlFromPv(base, v, width, r, out);
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
    private final Map<String, com.ash.drishti.banking.compute.var.ScenarioFx> fxCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The date's FX against USD for its scenarios (VAR_GUIDE.md section 15.9): per currency, the batch's supplied scenario rates (the
     * {@code fx-scenario} kind, of the date's scenario set) when it sent them, else rates derived from the FX history of the
     * {@code fx-spot} kind (today's rate moved by each scenario day's 1-day move); pairs against USD only (every cross goes through
     * USD). Kept per date and input versions.
     */
    com.ash.drishti.banking.compute.var.ScenarioFx fx(LocalDate date, List<LocalDate> scenarioDates) {
        return fx(date, scenarioDates, null);
    }

    /**
     * The same for a scenario set: {@code stressSet} the date's stress set (its own supplied scenario FX only), or null for the normal set
     * (the stress set's supplied rows left out). The stress days' FX is never derived from a history the lake does not hold: a currency
     * the stress set's batch did not supply is refused for its trades ({@code DRS-5207}), as for the normal set.
     */
    com.ash.drishti.banking.compute.var.ScenarioFx fx(LocalDate date, List<LocalDate> scenarioDates, String stressSet) {
        VarProperties.Kinds k = props.kinds();
        AsOf asOf = AsOf.of(date);
        String key = date + "|" + router.dataVersion(k.fxScenario(), asOf).orElse("") + "|" + router.dataVersion(k.fxSpot(), asOf).orElse("")
                + "|" + scenarioDates.size() + "|" + scenarioDates.get(scenarioDates.size() - 1) + "|" + stressSet;
        com.ash.drishti.banking.compute.var.ScenarioFx hit = fxCache.get(key);
        if (hit != null) {
            return hit;
        }
        java.util.Set<String> stressIds = stressSet == null ? stressIds(date) : java.util.Set.of();
        List<com.ash.drishti.banking.compute.var.ScenarioFx.Rates> supplied = new ArrayList<>();
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
            supplied.add(new com.ash.drishti.banking.compute.var.ScenarioFx.Rates(ccy, number(text(d, "RATE")), r, "supplied"));
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
        com.ash.drishti.banking.compute.var.ScenarioFx fx = com.ash.drishti.banking.compute.var.ScenarioFx.assemble(scenarioDates, supplied, today, history);
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
