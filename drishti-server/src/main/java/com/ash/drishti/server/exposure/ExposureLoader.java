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
package com.ash.drishti.server.exposure;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.ccr.CsaTerms;
import com.ash.drishti.compute.ccr.ExposureBook;
import com.ash.drishti.compute.ccr.ExposureGrid;
import com.ash.drishti.engine.source.SourceRouter;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads one business date of the exposure engine's inputs through the connectors the kinds are routed to (the counterparty-risk pack's
 * {@code exposure-store} in the demo; the bank's risk lake in production) and builds the {@link ExposureBook} (EXPOSURE_GUIDE.md section 26.3): the
 * run of the date (the newest complete {@code simulation-run}), checked; its {@code exposure-path} rows streamed from the vector column
 * {@code MTM} straight into the netting sets' value matrices ({@link ExposureIntake}); each set's CSA terms (thresholds, minimum transfer
 * amounts, independent amount, rounding, margin period of risk) and what it holds today ({@code collateral-balance}); then collateral,
 * exposure and negative exposure per path. A date whose run is not one run (its grid, two batch ids, a duplicate row, a trade in two
 * sets) is refused ({@code DRS-5441}). Reads as the server; what a viewer may see is applied per request.
 *
 * <p>With the hot tier ({@code drishti.ccr.exposure.tier: mapped}) the date is written once to its hot file and mapped; a date whose
 * file was built from the same inputs (their data versions, the run, the collateral and IM models) is opened from it, without the lake.
 */
final class ExposureLoader {

    /** The columns the paths' read asks for, after TRADE_ID (those the table keeps). */
    static final List<String> COLUMNS = List.of("NETTING_SET", "COUNTERPARTY", "LEGAL_ENTITY", "DESK", "TT_BOOK", "ASSET_CLASS", "PRODUCT",
            "SOURCE_SYSTEM", "MATURITY_DATE", "NOTIONAL", "GRID_INDEX", "GRID_DATE", "RUN_ID", "BATCH_ID", "STATUS");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private final SourceRouter router;
    private final ExposureProperties props;
    private final ExposureTier tier;
    /** The hierarchies (2.9.5, shared with VaR): the level columns of the column hierarchies are read from the paths' rows. */
    private java.util.function.Supplier<com.ash.drishti.server.var.Hierarchies> hierarchies = com.ash.drishti.server.var.Hierarchies::defaults;

    ExposureLoader(SourceRouter router, ExposureProperties props, ExposureTier tier) {
        this.router = router;
        this.props = props;
        this.tier = tier;
    }

    ExposureTier tier() {
        return tier;
    }

    /** The hierarchies VaR builds (the setting, a pack's block, or the default), so exposure's nodes are the same. */
    ExposureLoader hierarchies(java.util.function.Supplier<com.ash.drishti.server.var.Hierarchies> h) {
        this.hierarchies = h;
        return this;
    }

    com.ash.drishti.server.var.Hierarchies hierarchies() {
        com.ash.drishti.server.var.Hierarchies all;
        try {
            all = hierarchies.get();
        } catch (RuntimeException e) {
            return com.ash.drishti.server.var.Hierarchies.defaults();
        }
        // a column hierarchy whose level column the paths' table does not keep is skipped, said (DRS-5211 as a warning); the rest load
        if (all.columnSpecs().isEmpty()) {
            skipped = Map.of();
            return all;
        }
        Map<String, String> why = all.missingColumns(Map.of(props.kinds().paths(), router.columnar(props.kinds().paths())), "");
        for (com.ash.drishti.compute.var.HierarchySpec s : all.columnSpecs()) {
            com.ash.drishti.server.var.Hierarchies.skipped("exposure", s.id(), why.get(s.id()));
        }
        if (!why.equals(skipped)) {
            why.values().forEach(w -> org.slf4j.LoggerFactory.getLogger(ExposureLoader.class).warn("exposure: {}", w));
        }
        skipped = why;
        return all.without(why.keySet());
    }

    /** The column hierarchies skipped now (their level column not in the paths' table), id to why (2.9.5). */
    private volatile Map<String, String> skipped = Map.of();

    Map<String, String> skipped() {
        hierarchies();
        return skipped;
    }

    /** The configured hierarchies (the column ones' words name nodes in a scope). */
    List<com.ash.drishti.compute.var.HierarchySpec> hierarchySpecs() {
        return hierarchies().specs();
    }

    /** The scope words of the configured column hierarchies. */
    java.util.Set<String> words() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        hierarchies().columnSpecs().forEach(s -> {
            if (s.word() != null) {
                out.add(s.word());
            }
        });
        return out;
    }

    /** The level columns the trades' rows must carry: the column hierarchies' (but those the engine reads anyway). */
    List<String> levels() {
        List<String> out = new ArrayList<>();
        for (String c : hierarchies().levelColumns()) {
            if (!List.of("COUNTERPARTY", "LEGAL_ENTITY", "DESK", "TT_BOOK", "ASSET_CLASS", "PRODUCT", "SOURCE_SYSTEM").contains(c)) {
                out.add(c);
            }
        }
        return out;
    }

    SourceRouter router() {
        return router;
    }

    /** The newest business date that has exposure paths, or null. */
    LocalDate latest() {
        return router.latestDate(props.kinds().paths()).orElse(null);
    }

    LocalDate previous(LocalDate date) {
        return router.heldDates(props.kinds().paths()).map(s -> s.lower(date)).orElse(null);
    }

    /** The dates that have exposure paths (the retention plan and the status read them). */
    java.util.NavigableSet<LocalDate> held() {
        return router.heldDates(props.kinds().paths()).orElse(new java.util.TreeSet<>());
    }

    String version(LocalDate date) {
        return router.dataVersion(props.kinds().paths(), AsOf.of(date)).orElse("");
    }

    ExposureDate load(LocalDate date) {
        long t0 = System.nanoTime();
        ExposureProperties.Kinds k = props.kinds();
        AsOf asOf = AsOf.of(date);
        Map<String, String> versions = new LinkedHashMap<>();
        for (String kind : k.inputs()) {
            versions.put(kind, router.dataVersion(kind, asOf).orElse(""));
        }
        DataNode run = run(date, documents(k.run(), asOf));
        Reference ref = reference(date, asOf, run);
        String runId = text(run, "RUN_ID");
        List<String> levels = levels();
        String digest = ExposureTier.digest(date, versions, runId, props.collateralModel(), props.imModel()
                + (levels.isEmpty() ? "" : "|levels=" + levels));
        Optional<Path> hot = props.mapped() ? tier.existing(date, digest) : Optional.empty();
        ExposureBook book = null;
        if (hot.isPresent()) {
            try {
                book = tier.open(hot.get());
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(ExposureLoader.class).warn("exposure: the hot file {} is not usable ({}); the date is read from the lake",
                        hot.get(), e.getMessage());
            }
        }
        if (book == null) {
            book = build(date, asOf, run, ref, digest);
        }
        tier.opened(book);
        versions.put("runId", runId);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return new ExposureDate(date, book, ref.known, ref.names, ref.groups, ref.limits, versions.get(k.paths()), java.time.Instant.now(), ms, book.intake(),
                versions, List.of());
    }

    /** Streams the run's rows into a new book, then writes its hot file (or keeps it on the heap). */
    private ExposureBook build(LocalDate date, AsOf asOf, DataNode run, Reference ref, String digest) {
        ExposureGrid grid = grid(date, run);
        int paths = (int) run.get("PATHS").asDouble();
        if (paths <= 0) {
            throw inconsistent(date, "the run " + text(run, "RUN_ID") + " has no PATHS");
        }
        long budget = (long) (props.memoryBudgetGb() * (1L << 30));
        ExposureBook.Builder b = new ExposureBook.Builder(grid, paths, text(run, "RUN_ID"), text(run, "BATCH_ID"), runRecord(run), props.model(), budget);
        List<String> levels = levels();
        b.attributeColumns(levels);                                      // the column hierarchies' level columns, kept per trade (2.9.5)
        ExposureIntake intake = new ExposureIntake(b, grid, date, text(run, "RUN_ID"), text(run, "BATCH_ID"), ref.memberOf, props.t0Tolerance())
                .levels(levels);
        List<String> columns = columns();

        boolean read;
        try {
            read = router.vectors(props.kinds().paths(), "MTM", columns, asOf, (id, values, len, row) -> intake.row(id, values, len, row, columns));
        } catch (ExposureBook.BudgetExceeded e) {
            throw new DrishtiException(ErrorCode.EXPOSURE_CAPACITY, e.getMessage() + ": raise drishti.ccr.exposure.memory-budget-gb or the heap");
        } catch (IllegalArgumentException e) {
            throw inconsistent(date, e.getMessage());
        } catch (DrishtiException e) {
            throw e;
        } catch (Exception e) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "the exposure paths of " + date + " could not be read: " + e.getMessage());
        }
        if (!read) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "no connector keeps " + props.kinds().paths() + ".MTM as a vector column with "
                    + "the columns " + columns + " (the pack's layout." + props.kinds().paths() + ".columns and .vectors)");
        }
        Map<String, Object> summary = intake.summary(ref);
        try {
            return b.build(ref, props.mapped() ? tier.target(date, digest) : null, digest, summary);
        } catch (java.io.IOException e) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "the exposure hot file of " + date + " could not be written: " + e.getMessage());
        }
    }

    private List<String> columns() {
        java.util.Set<String> kept = router.columnar(props.kinds().paths());
        List<String> out = new ArrayList<>(List.of("TRADE_ID"));
        for (String c : COLUMNS) {
            if (kept.isEmpty() ? List.of("NETTING_SET", "COUNTERPARTY", "LEGAL_ENTITY", "GRID_INDEX", "RUN_ID").contains(c) : kept.contains(c)) {
                out.add(c);
            }
        }
        for (String c : levels()) {
            if (!out.contains(c)) {
                out.add(c);
            }
        }
        return out;
    }

    /** The run of the date: the newest complete one (by COMPUTED_AT); checked for what the engine needs. */
    private DataNode run(LocalDate date, Map<String, DataNode> runs) {
        DataNode best = null;
        for (DataNode r : runs.values()) {
            String status = text(r, "STATUS");
            if (status != null && !status.equalsIgnoreCase("complete")) {
                continue;
            }
            if (best == null || String.valueOf(text(r, "COMPUTED_AT")).compareTo(String.valueOf(text(best, "COMPUTED_AT"))) > 0) {
                best = r;
            }
        }
        if (best == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no complete simulation run of " + date + " (kind " + props.kinds().run() + ")");
        }
        String ccy = text(best, "CURRENCY");
        if (ccy != null && !ccy.equalsIgnoreCase("USD")) {
            throw inconsistent(date, "the run " + text(best, "RUN_ID") + " is in " + ccy + "; exposure paths are in USD");
        }
        String precision = text(best, "PRECISION");
        if (precision != null && !precision.equals("float32") && !precision.equals("float64")) {
            throw inconsistent(date, "the run's PRECISION is " + precision + ": float32 or float64");
        }
        return best;
    }

    private static ExposureGrid grid(LocalDate date, DataNode run) {
        List<LocalDate> dates = new ArrayList<>();
        List<String> roles = new ArrayList<>();
        List<Integer> lb = new ArrayList<>();
        List<String> tenors = new ArrayList<>();
        try {
            for (int i = 0; i < run.get("GRID_DATES").size(); i++) {
                dates.add(LocalDate.parse(run.get("GRID_DATES").get(i).asText().substring(0, 10)));
            }
            for (int i = 0; i < run.get("GRID_ROLE").size(); i++) {
                roles.add(run.get("GRID_ROLE").get(i).asText());
            }
            for (int i = 0; i < run.get("LOOKBACK_OF").size(); i++) {
                lb.add((int) run.get("LOOKBACK_OF").get(i).asDouble());
            }
            DataNode tn = run.get("GRID_TENORS");
            for (int i = 0; i < tn.size(); i++) {
                tenors.add(tn.get(i).isNull() ? null : tn.get(i).asText());
            }
            return ExposureGrid.of(date, dates, roles, lb, tenors.isEmpty() ? null : tenors);
        } catch (IllegalArgumentException | java.time.DateTimeException | StringIndexOutOfBoundsException e) {
            throw inconsistent(date, "the run " + text(run, "RUN_ID") + "'s grid: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> runRecord(DataNode run) {
        Object u = run.unwrap();
        return u instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : Map.of();
    }

    /** What the book needs beside the rows, from the date's netting sets, CSAs, collateral holdings and groups. */
    static final class Reference implements ExposureBook.Reference {
        final Map<String, ExposureDate.Known> known = new LinkedHashMap<>();
        final Map<String, String> memberOf = new HashMap<>();
        final Map<String, Double> mtm0 = new HashMap<>();
        final Map<String, CsaTerms> terms = new HashMap<>();
        final Map<String, List<String>> flags = new HashMap<>();
        final Map<String, String> names = new HashMap<>();
        final Map<String, List<String>> groups = new LinkedHashMap<>();
        final Map<String, List<String>> liveMembers = new HashMap<>();
        final Map<String, ExposureDate.Limit> limits = new HashMap<>();

        @Override
        public CsaTerms terms(String set) {
            return terms.getOrDefault(set, CsaTerms.NONE);
        }

        @Override
        public double mtm0(String trade) {
            Double d = mtm0.get(trade);
            return d == null ? Double.NaN : d;
        }

        @Override
        public List<String> flags(String set) {
            return flags.getOrDefault(set, List.of());
        }
    }

    /** The trade statuses that count (COMPUTED_ATTRIBUTES.md section 3, Live: with a maturity date not before the business date). */
    static final java.util.Set<String> LIVE_STATUSES = java.util.Set.of("NEW", "VALIDATED", "AMENDED");

    /** A member of a netting set on the date: its id, its MtM (NaN when it has none), its maturity date (null when it has none). */
    record Member(String id, double mtm, String maturity) {}

    /**
     * Each netting set's members on the date (2.9.6, E0 of COMPUTED_ATTRIBUTES.md): the trade valuations of a live status
     * ({@link #LIVE_STATUSES}) naming the set, with their MtM; the fundamental source, never the netting set's stored copies. A date
     * without trade valuations (a bank that sends none, a test lake) reads the sets' stored {@code TRADES[]} as before, and says so.
     */
    private Map<String, List<Member>> members(AsOf asOf, Map<String, DataNode> sets) {
        Map<String, DataNode> valuations = documents(props.kinds().valuation(), asOf);
        if (valuations.isEmpty() && !sets.isEmpty()) {
            org.slf4j.LoggerFactory.getLogger(ExposureLoader.class).info("exposure: no {} on {}: the netting sets' stored members and MtM are read",
                    props.kinds().valuation(), asOf.businessDate());
        }
        return members(valuations, sets);
    }

    /** {@link #members(AsOf, Map)} over documents already read: the valuations' live members by set, else the sets' stored members. */
    static Map<String, List<Member>> members(Map<String, DataNode> valuations, Map<String, DataNode> sets) {
        Map<String, List<Member>> out = new LinkedHashMap<>();
        for (DataNode t : valuations.values()) {
            String ns = text(t, "NETTING_SET");
            String tid = text(t, "TRADE_ID");
            String status = text(t, "TRADE_STATUS");
            if (ns == null || ns.isBlank() || tid == null || status != null && !LIVE_STATUSES.contains(status.toUpperCase(java.util.Locale.ROOT))) {
                continue;
            }
            out.computeIfAbsent(ns, x -> new ArrayList<>()).add(new Member(tid, t.get("MTM").asDouble(), text(t, "MATURITY_DATE")));
        }
        if (!out.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, DataNode> e : sets.entrySet()) {
            DataNode trades = e.getValue().get("TRADES");
            List<Member> ms = new ArrayList<>();
            for (int i = 0; i < trades.size(); i++) {
                DataNode t = trades.get(i);
                String tid = text(t, "TRADE_ID");
                if (tid != null) {
                    ms.add(new Member(tid, t.get("MTM").asDouble(), text(t, "MATURITY")));
                }
            }
            out.put(e.getKey(), ms);
        }
        return out;
    }

    private Reference reference(LocalDate date, AsOf asOf, DataNode run) {
        ExposureProperties.Kinds k = props.kinds();
        Reference r = new Reference();
        Map<String, DataNode> csas = documents(k.csa(), asOf);
        Map<String, DataNode> balances = documents(k.collateral(), asOf);
        int lookback = (int) run.get("LOOKBACK_DAYS").asDouble();
        Map<String, DataNode> sets = documents(k.nettingSet(), asOf);
        Map<String, List<Member>> members = members(asOf, sets);
        for (Map.Entry<String, DataNode> e : sets.entrySet()) {
            DataNode s = e.getValue();
            String id = e.getKey();
            List<String> live = new ArrayList<>();
            int past = 0;
            for (Member m : members.getOrDefault(id, List.of())) {
                r.memberOf.put(m.id(), id);
                if (!Double.isNaN(m.mtm())) {
                    r.mtm0.put(m.id(), m.mtm());
                }
                if (m.maturity() == null || m.maturity().isBlank() || m.maturity().substring(0, Math.min(10, m.maturity().length())).compareTo(date.toString()) >= 0) {
                    live.add(m.id());
                } else {
                    past++;                                             // a live status past its maturity date: in no figure, flagged
                }
            }
            r.liveMembers.put(id, live);
            r.known.put(id, new ExposureDate.Known(id, text(s, "COUNTERPARTY"), text(s, "LEGAL_ENTITY"), text(s, "CSA"), live.size()));
            List<String> flags = new ArrayList<>();
            if (past > 0) {
                flags.add("PAST_MATURITY");                              // data quality (decision 3): live status, past maturity, left out
            }
            DataNode csa = text(s, "CSA") == null ? null : csas.get(text(s, "CSA"));
            DataNode bal = text(s, "COLLATERAL_BALANCE") == null ? null : balances.get(text(s, "COLLATERAL_BALANCE"));
            CsaTerms terms = terms(csa, bal, flags, lookback);
            r.terms.put(id, terms);
            r.flags.put(id, flags);
        }
        for (Map.Entry<String, DataNode> e : documents(k.counterparty(), asOf).entrySet()) {
            String n = text(e.getValue(), "NAME");
            if (n != null) {
                r.names.put(e.getKey(), n);
            }
        }
        for (Map.Entry<String, DataNode> e : documents(k.group(), asOf).entrySet()) {
            List<String> ms = new ArrayList<>();
            DataNode m = e.getValue().get("MEMBERS");
            for (int i = 0; i < m.size(); i++) {
                String id = text(m.get(i), "ID");
                if (id != null) {
                    ms.add(id);
                }
            }
            r.groups.put(e.getKey(), ms);
        }
        for (Map.Entry<String, DataNode> e : documents(k.limit(), asOf).entrySet()) {     // the approved limit of the credit-limit record (LIM is parked)
            DataNode l = e.getValue();
            String cp = text(l, "COUNTERPARTY");
            double amount = l.get("LIMIT").asDouble();
            if (cp != null && !Double.isNaN(amount) && amount > 0) {
                r.limits.putIfAbsent(cp, new ExposureDate.Limit(e.getKey(), amount, text(l, "MEASURE")));
            }
        }
        return r;
    }

    /**
     * The collateral a set holds today, after haircut (2.9.6, E0 and F3 of COMPUTED_ATTRIBUTES.md): its holdings' collateral values
     * ({@code POSITIONS[].VALUE}, held positive, posted negative); a balance without positions gives its stored {@code NET}.
     */
    static double heldAfterHaircut(DataNode bal) {
        if (bal == null) {
            return 0;
        }
        DataNode pos = bal.get("POSITIONS");
        if (pos == null || pos.size() == 0) {
            return nz(bal.get("NET").asDouble());
        }
        double held = 0;
        for (int i = 0; i < pos.size(); i++) {
            DataNode p = pos.get(i);
            double v = p.get("VALUE").asDouble();
            if (Double.isNaN(v)) {
                v = p.get("MARKET_VALUE").asDouble() * (1 - nz(p.get("HAIRCUT").asDouble()));
            }
            held += "Posted".equalsIgnoreCase(String.valueOf(text(p, "DIRECTION"))) ? -nz(v) : nz(v);
        }
        return held;
    }

    /** A set's terms in USD from its CSA (null: unmargined) and its holdings; flags for a margin period the run does not look back. */
    private CsaTerms terms(DataNode csa, DataNode bal, List<String> flags, int lookback) {
        double held = heldAfterHaircut(bal);
        double im = bal == null || props.imModel().equals("none") ? 0 : nz(bal.get("IM_HELD").asDouble());
        if (csa == null) {
            return new CsaTerms(null, 0, 0, 0, 0, 0, 0, 0, 1, false, held, im);
        }
        double mta = nz(csa.get("MTA").asDouble());
        double mtaThem = csa.get("MTA_THEM").isMissing() ? mta : nz(csa.get("MTA_THEM").asDouble());
        double mtaUs = csa.get("MTA_US").isMissing() ? mta : nz(csa.get("MTA_US").asDouble());
        int mpor = days(csa.get("MPOR_DAYS").isMissing() ? csa.get("MPOR") : csa.get("MPOR_DAYS"), 10);
        int freq = csa.get("MARGIN_FREQUENCY_DAYS").isMissing() ? frequency(text(csa, "FREQUENCY")) : days(csa.get("MARGIN_FREQUENCY_DAYS"), 1);
        if (lookback > 0 && mpor + freq - 1 != lookback) {
            flags.add("MPOR_MISMATCH");                                 // the run's look-back is used (open question 3, decided)
        }
        String type = String.valueOf(text(csa, "MARGIN_TYPE"));
        return new CsaTerms(text(csa, "CSA_ID"), nz(csa.get("THRESHOLD_THEM").asDouble()), nz(csa.get("THRESHOLD_US").asDouble()), mtaThem, mtaUs,
                nz(csa.get("INDEPENDENT_AMOUNT").asDouble()), nz(csa.get("ROUNDING").asDouble()), mpor, freq, type.contains("VM"), held, im);
    }

    private static int days(DataNode n, int otherwise) {
        if (n == null || n.isNull()) {
            return otherwise;
        }
        double d = n.asDouble();
        if (!Double.isNaN(d)) {
            return (int) d;
        }
        String s = n.asText().trim().split("\\s+")[0];
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    private static int frequency(String f) {
        if (f == null) {
            return 1;
        }
        return switch (f.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "weekly" -> 5;
            case "monthly" -> 21;
            default -> 1;
        };
    }

    private static double nz(double d) {
        return Double.isNaN(d) ? 0 : d;
    }

    static DrishtiException inconsistent(LocalDate date, String why) {
        return new DrishtiException(ErrorCode.EXPOSURE_INCONSISTENT_RUN, "the exposure run of " + date + " is not loaded: " + why);
    }

    Map<String, DataNode> documents(String kind, AsOf asOf) {
        Map<String, DataNode> out = new LinkedHashMap<>();
        try {
            boolean whole = router.documents(kind, asOf, (EntityDocument d) -> out.put(d.ref().id(), d.data()));
            if (!whole) {
                var listing = router.listAcross(kind, 100_000, java.time.Duration.ofSeconds(30), asOf, new com.ash.drishti.engine.source.SourceFailures());
                var refs = listing.hits().stream().map(h -> h.ref()).toList();
                router.fetchAll(refs, java.time.Duration.ofSeconds(30), asOf).forEach((ref, d) -> out.put(ref.id(), d.data()));
            }
        } catch (DrishtiException e) {
            if (e.errorCode() != ErrorCode.NO_SOURCE_FOR_KIND && e.errorCode() != ErrorCode.CONNECTOR_NOT_CONFIGURED) {
                throw e;
            }
        }
        return out;
    }

    /**
     * Reads trades' paths from the lake (contributions by trade, a scope that cuts a netting set, a trade alone): the date's rows of
     * the loaded run streamed once, those of the wanted trades kept ({@code G x P} each; an absent row is zero).
     */
    Map<String, double[]> tradePaths(ExposureDate d, java.util.Collection<String> ids) {
        java.util.Set<String> want = new java.util.HashSet<>(ids);
        ExposureBook b = d.book();
        int p = b.paths();
        int g = b.grid().size();
        Map<String, double[]> out = new HashMap<>();
        List<String> cols = List.of("TRADE_ID", "GRID_INDEX", "RUN_ID");
        try {
            router.vectors(props.kinds().paths(), "MTM", cols, AsOf.of(d.date()), (id, values, len, row) -> {
                String trade = row[0] == null ? id : row[0];
                if (!want.contains(trade) || len != p || row.length < 3 || row[2] != null && !row[2].equals(b.runId())) {
                    return;
                }
                int k = (int) Double.parseDouble(row[1]);
                if (k < 0 || k >= g) {
                    return;
                }
                double[] m = out.computeIfAbsent(trade, x -> new double[g * p]);
                System.arraycopy(values, 0, m, k * p, p);
            });
        } catch (Exception e) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "the trades' exposure paths of " + d.date() + " could not be read: " + e.getMessage());
        }
        return out;
    }

    static String text(DataNode n, String field) {
        if (n == null) {
            return null;
        }
        DataNode v = n.get(field);
        return v == null || v.isMissing() || v.isNull() ? null : v.asText();
    }

    static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return String.valueOf(o);
        }
    }
}
