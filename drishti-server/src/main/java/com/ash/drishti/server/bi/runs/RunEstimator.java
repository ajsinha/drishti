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

import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.runs.RunEstimate.Basis;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The planner's estimate maths (docs/architecture/BI_RUNS.md section 4), as pure functions over metadata the caller has already read
 * ({@code Layers.peek}, {@code SourcePlugin.rowEstimate}, {@code RunHistory}, {@code RunRates}). Nothing here reads data or a file.
 *
 * <pre>
 * rows(t, d)   = peek | rowEstimate | history | unknown                        (the caller fills {@link DateRows})
 * readRows(P)  = Σ covered d rollupRows(d) + Σ t Σ uncovered d rows(t, d)      (all covered: rollup rows only)
 * buildRows(P) = Σ t Σ uncovered d, not built rows(t, d)
 * seconds(P)   = Σ t buildRows(t) / rate.build(connector, path) / fragments(t) + readRows / rate.scan + overhead
 * bytes(P)     = Σ rows read × bytesPerRow of what was read
 * rowsOut(P)   = min(limit, Π grain cardinalities, readRows)                   (no cardinalities: readRows)
 * range        = seconds × [low, high]                                         (the factors of {@link #factors})
 * </pre>
 *
 * Then the decisions of 4.3 and 5.1: the tier, whether to confirm, and refusals ({@code DRS-9086} past every engine, {@code DRS-9089} for an
 * interaction that would need a background run).
 */
public final class RunEstimator {

    private RunEstimator() {}

    /** How a table's missing partitions are built: a connector's column scan, or record by record. */
    public enum BuildPath { SCAN, RECORD }

    /**
     * One date of one table as the metadata knows it.
     *
     * @param rows  the row count; null when unknown
     * @param basis where the count came from ({@code LAYERS} only for a valid built partition)
     * @param built the partition is valid in the BI store (nothing to build)
     */
    public record DateRows(LocalDate date, Long rows, Basis basis, boolean built) {
        public DateRows {
            if (rows == null && basis != Basis.UNKNOWN) {
                throw new IllegalArgumentException("a date without rows has basis unknown");
            }
            if (rows != null && rows < 0) {
                throw new IllegalArgumentException("rows are zero or more");
            }
        }

        public static DateRows built(LocalDate d, long rows) {
            return new DateRows(d, rows, Basis.LAYERS, true);
        }

        public static DateRows toBuild(LocalDate d, long rows, Basis basis) {
            return new DateRows(d, rows, basis, false);
        }

        public static DateRows unknown(LocalDate d) {
            return new DateRows(d, null, Basis.UNKNOWN, false);
        }
    }

    /**
     * One table the plan reads.
     *
     * @param fragments   the fragments a build of it may use at once ({@code max-fragments × fragments-share}, at least 1)
     * @param bytesPerRow bytes per row of the columns read (Parquet footers, else type widths); 0: the settings' default
     */
    public record Table(String name, String connector, BuildPath path, List<DateRows> dates, int fragments, int bytesPerRow) {
        public Table {
            dates = List.copyOf(dates);
            fragments = Math.max(1, fragments);
        }
    }

    /** The rollup (or S4 product) the optimiser would choose, with its valid built partitions' rows by date. */
    public record Rollup(String name, Map<LocalDate, Long> rowsByDate, int bytesPerRow) {
        public Rollup {
            rowsByDate = Map.copyOf(rowsByDate);
        }
    }

    /**
     * The plan as the estimate sees it.
     *
     * @param rollup            the optimiser's rollup; empty when none matches the grain
     * @param grainWords        the grain in words ({@code desk × month}), for {@code why}
     * @param grainCardinalities the cardinality of each grain column (from the dimensions' layers); empty when not known
     * @param limit             the question's limit; null when none
     * @param live              a live table is in the plan
     */
    public record Plan(List<Table> tables, Optional<Rollup> rollup, String grainWords, List<Long> grainCardinalities, Long limit, boolean live) {
        public Plan {
            tables = List.copyOf(tables);
            rollup = rollup == null ? Optional.empty() : rollup;
            grainCardinalities = grainCardinalities == null ? List.of() : List.copyOf(grainCardinalities);
        }
    }

    /**
     * The rates in force: build rows per second per {@code connector/path} (measured, else the settings' seeds) and the engine's scan rate.
     */
    public record Rates(Map<String, Double> build, double scan) {
        public Rates {
            build = build == null ? Map.of() : Map.copyOf(build);
        }

        public static Rates seeds(PlannerSettings s) {
            return new Rates(Map.of(), s.scanRowsPerSecond());
        }

        double build(Table t, PlannerSettings s) {
            Double r = build.get(t.connector() + "/" + t.path().name().toLowerCase(Locale.ROOT));
            return r != null && r > 0 ? r : t.path() == BuildPath.RECORD ? s.recordRowsPerSecond() : s.buildRowsPerSecond();
        }
    }

    // ---- the estimate ---------------------------------------------------------------------------------------------

    /** The estimate of {@code p} under the given rates and range factors ({@link #factors}). */
    public static RunEstimate estimate(Plan p, Rates rates, double[] factors, PlannerSettings s) {
        TreeSet<LocalDate> dates = new TreeSet<>();
        p.tables().forEach(t -> t.dates().forEach(d -> dates.add(d.date())));
        Map<LocalDate, Long> rollupRows = p.rollup().map(Rollup::rowsByDate).orElse(Map.of());
        TreeSet<LocalDate> coveredDates = new TreeSet<>();
        for (LocalDate d : dates) {
            if (rollupRows.containsKey(d)) {
                coveredDates.add(d);
            }
        }
        boolean covered = !dates.isEmpty() && coveredDates.size() == dates.size();
        Part part = part(p, coveredDates, rates, s);
        long rollupRead = 0;
        long rollupBytes = 0;
        for (LocalDate d : coveredDates) {
            long r = rollupRows.get(d);
            rollupRead = sat(rollupRead + r);
            rollupBytes = sat(rollupBytes + mul(r, bytesPerRow(p.rollup().get().bytesPerRow(), s)));
        }
        Basis basis = basis(part.counts);
        String why = why(p, dates.size(), part, coveredDates.size(), covered);
        if (part.unknown) {
            return new RunEstimate(null, null, null, null, null, null, Basis.UNKNOWN, dates.size(), part.datesToBuild, covered, coveredDates.size(), why);
        }
        long readRows = sat(rollupRead + part.readRows);
        double seconds = part.buildSeconds + readRows / rates.scan() + s.overheadSeconds();
        return new RunEstimate(readRows, part.buildRows, sat(rollupBytes + part.bytes), rowsOut(p, readRows), round(seconds * factors[0]),
                round(seconds * factors[1]), basis, dates.size(), part.datesToBuild, covered, coveredDates.size(), why);
    }

    /** The raw (uncovered) part of a plan: rows read and built, seconds to build, and where the counts came from. */
    private static final class Part {
        long readRows;
        long buildRows;
        long bytes;
        double buildSeconds;
        int datesToBuild;
        boolean unknown;
        String unknownTable;
        final Map<Basis, Long> counts = new EnumMap<>(Basis.class);
        final Map<String, long[]> perTable = new java.util.LinkedHashMap<>();   // name -> {rows, dates}
    }

    private static Part part(Plan p, java.util.Set<LocalDate> skip, Rates rates, PlannerSettings s) {
        Part out = new Part();
        TreeSet<LocalDate> toBuild = new TreeSet<>();
        for (Table t : p.tables()) {
            long tableBuild = 0;
            for (DateRows d : t.dates()) {
                if (skip.contains(d.date())) {
                    continue;
                }
                if (d.rows() == null) {
                    out.unknown = true;
                    out.unknownTable = out.unknownTable == null ? t.name() : out.unknownTable;
                    if (!d.built()) {
                        toBuild.add(d.date());
                    }
                    continue;
                }
                out.readRows = sat(out.readRows + d.rows());
                out.bytes = sat(out.bytes + mul(d.rows(), bytesPerRow(t.bytesPerRow(), s)));
                out.counts.merge(d.basis(), d.rows(), RunEstimator::satAdd);
                long[] pt = out.perTable.computeIfAbsent(t.name(), k -> new long[2]);
                pt[0] = sat(pt[0] + d.rows());
                pt[1]++;
                if (!d.built()) {
                    toBuild.add(d.date());
                    tableBuild = sat(tableBuild + d.rows());
                }
            }
            out.buildRows = sat(out.buildRows + tableBuild);
            out.buildSeconds += tableBuild / rates.build(t, s) / t.fragments();
        }
        out.datesToBuild = toBuild.size();
        return out;
    }

    /** {@code unknown} when any count is unknown (handled by the caller); {@code layers} when every count is a built partition (or a rollup);
     *  otherwise the non-layers basis behind the most rows. */
    private static Basis basis(Map<Basis, Long> counts) {
        Basis best = null;
        long most = -1;
        for (Map.Entry<Basis, Long> e : counts.entrySet()) {
            if (e.getKey() != Basis.LAYERS && e.getValue() > most) {
                best = e.getKey();
                most = e.getValue();
            }
        }
        return best == null ? Basis.LAYERS : best;
    }

    private static Long rowsOut(Plan p, long readRows) {
        long out = readRows;
        if (!p.grainCardinalities().isEmpty()) {
            long product = 1;
            for (long c : p.grainCardinalities()) {
                product = mul(product, Math.max(c, 0));
            }
            out = Math.min(out, product);
        }
        return p.limit() == null ? out : Math.min(out, p.limit());
    }

    private static String why(Plan p, int dates, Part part, int coveredDates, boolean covered) {
        StringBuilder b = new StringBuilder();
        b.append(dates).append(dates == 1 ? " business date of " : " business dates of ");
        List<String> tables = new ArrayList<>();
        p.tables().forEach(t -> tables.add(t.name()));
        b.append(String.join(", ", tables));
        if (part.unknown) {
            b.append(" (the size of ").append(part.unknownTable).append(" is not known)");
        } else if (!covered && !part.perTable.isEmpty()) {
            long[] first = part.perTable.values().iterator().next();
            b.append(" (about ").append(words(first[1] == 0 ? 0 : first[0] / first[1])).append(" rows a day");
            if (part.datesToBuild > 0) {
                b.append("; ").append(part.datesToBuild).append(part.datesToBuild == 1 ? " date" : " dates").append(" not built yet");
            }
            b.append(')');
        }
        String rollup = p.rollup().map(Rollup::name).orElse(null);
        if (covered) {
            b.append("; answered from rollup ").append(rollup);
        } else if (coveredDates > 0) {
            int missing = dates - coveredDates;
            b.append("; rollup ").append(rollup).append(" lacks ").append(missing).append(missing == 1 ? " date" : " dates");
        } else if (p.grainWords() != null && !p.grainWords().isBlank()) {
            b.append("; no rollup has grain ").append(p.grainWords());
        }
        return b.toString();
    }

    /** 10.1 M, 410 K, 950: a row count in words for {@code why}. */
    static String words(long n) {
        if (n >= 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.1f B", n / 1e9);
        }
        if (n >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.1f M", n / 1e6);
        }
        if (n >= 10_000L) {
            return String.format(Locale.ROOT, "%.0f K", n / 1e3);
        }
        return Long.toString(n);
    }

    // ---- the range factors ----------------------------------------------------------------------------------------

    /**
     * {@code [low, high]}: the settings' factors until there are {@code minRatioSamples} measured actual ÷ estimate ratios for the engine, then
     * their 10th and 90th percentiles (section 4.4).
     */
    public static double[] factors(List<Double> ratios, PlannerSettings s) {
        List<Double> ok = ratios == null ? List.of() : ratios.stream().filter(r -> r != null && r > 0 && Double.isFinite(r)).sorted().toList();
        if (ok.size() < s.minRatioSamples()) {
            return new double[] {s.lowFactor(), s.highFactor()};
        }
        return new double[] {percentile(ok, 0.10), percentile(ok, 0.90)};
    }

    static double percentile(List<Double> sorted, double q) {
        double at = q * (sorted.size() - 1);
        int lo = (int) Math.floor(at);
        int hi = (int) Math.ceil(at);
        return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (at - lo);
    }

    // ---- the decisions --------------------------------------------------------------------------------------------

    /**
     * The planner's decision.
     *
     * @param refusal DRS-9086 (past every engine) or DRS-9089 (an interaction that would need a run); empty when it may run
     */
    public record Decision(Tier tier, boolean confirm, Optional<ErrorCode> refusal) {}

    /**
     * The tier (section 5.1), confirmation (4.3) and refusals: a live plan is interactive; a covered plan is known heavy when its raw rows pass
     * the interactive bound, else interactive; a mixed plan (5.2) is interactive when its uncovered dates fit the interactive bounds; an
     * uncovered plan is interactive when the high end, the rows read and the dates to build all fit; anything else, and every unknown estimate,
     * is ad-hoc heavy.
     *
     * @param rawReadRows        the rows the plan would read without the rollup (for known heavy); null when unknown
     * @param uncovered          the estimate of the uncovered dates alone (for a mixed plan); the estimate itself when nothing is covered
     * @param interaction        the question comes from a slicer, cross-filter or drill
     * @param biggerEngine       a split or external engine is configured that accepts the plan
     */
    public static Decision decide(Plan p, RunEstimate e, RunEstimate uncovered, Long rawReadRows, boolean interaction, boolean biggerEngine,
                                  PlannerSettings s) {
        Tier tier;
        if (p.live()) {
            tier = Tier.INTERACTIVE;
        } else if (e.covered()) {
            tier = rawReadRows == null || rawReadRows > s.interactiveMaxRows() ? Tier.KNOWN_HEAVY : Tier.INTERACTIVE;
        } else {
            tier = fitsInteractive(uncovered, s) ? Tier.INTERACTIVE : Tier.AD_HOC_HEAVY;
        }
        if (!biggerEngine && e.readRows() != null && e.readRows() > s.engineMaxReadRows()) {
            return new Decision(tier, false, Optional.of(ErrorCode.RUN_TOO_LARGE));
        }
        if (interaction && tier == Tier.AD_HOC_HEAVY) {
            return new Decision(tier, false, Optional.of(ErrorCode.RUN_NEEDED));
        }
        boolean confirm = tier == Tier.AD_HOC_HEAVY && (!e.known() ? s.confirmUnknown()
                : e.secondsHigh() > s.confirmAboveSeconds() || e.readRows() > s.confirmAboveRows());
        return new Decision(tier, confirm, Optional.empty());
    }

    /** The estimate of a plan alone, then its decision: the common case of a plan with no rollup or a partial one. */
    public static Decision decide(Plan p, Rates rates, double[] factors, boolean interaction, boolean biggerEngine, PlannerSettings s) {
        RunEstimate e = estimate(p, rates, factors, s);
        Plan raw = new Plan(p.tables(), Optional.empty(), p.grainWords(), p.grainCardinalities(), p.limit(), p.live());
        RunEstimate rawEstimate = estimate(raw, rates, factors, s);
        RunEstimate uncovered = e;
        if (e.coveredDates() > 0 && !e.covered()) {
            java.util.Set<LocalDate> coveredDates = p.rollup().get().rowsByDate().keySet();
            List<Table> rest = p.tables().stream().map(t -> new Table(t.name(), t.connector(), t.path(),
                    t.dates().stream().filter(d -> !coveredDates.contains(d.date())).toList(), t.fragments(), t.bytesPerRow())).toList();
            uncovered = estimate(new Plan(rest, Optional.empty(), p.grainWords(), p.grainCardinalities(), p.limit(), p.live()), rates, factors, s);
        }
        return decide(p, e, uncovered, rawEstimate.readRows(), interaction, biggerEngine, s);
    }

    static boolean fitsInteractive(RunEstimate e, PlannerSettings s) {
        return e.known() && e.secondsHigh() <= s.interactiveMaxSeconds() && e.readRows() <= s.interactiveMaxRows()
                && e.datesToBuild() <= s.interactiveMaxBuildDates();
    }

    // ---- arithmetic -----------------------------------------------------------------------------------------------

    private static int bytesPerRow(int given, PlannerSettings s) {
        return given > 0 ? given : s.defaultBytesPerRow();
    }

    /** Saturating: a sum that overflows is Long.MAX_VALUE, never negative. */
    private static long sat(long v) {
        return v < 0 ? Long.MAX_VALUE : v;
    }

    /** Saturating product of two counts that are zero or more. */
    private static long mul(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static Long satAdd(Long a, Long b) {
        return sat(a + b);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
