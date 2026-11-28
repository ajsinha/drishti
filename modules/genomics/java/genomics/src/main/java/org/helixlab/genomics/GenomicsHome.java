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
package org.helixlab.genomics;

import com.ash.drishti.kernel.api.home.HomeData;
import com.ash.drishti.kernel.api.home.HomeTable;
import com.ash.drishti.kernel.api.home.MeasureSource;
import com.ash.drishti.kernel.api.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * What the genomics module gives the genomicist's Home (docs/architecture/ROLE_HOME_SUTRAS.md): the figures (a {@link MeasureSource} named
 * {@code genomics}: samples, variants and genome-wide hits, and the variants of each cohort the Home follows), and its own home tables
 * {@code genomics_gwas_top}, {@code genomics_manhattan}, {@code genomics_clinvar}, {@code genomics_loads} and {@code genomics_needs}. Nothing is
 * computed beyond counting and ordering rows the lake holds; the lake tables are read by their symbolic kinds ({@code association} of the
 * showcase pack, {@code genomics.clinvar-variant} and {@code genomics.ensembl-gene} of the public feeds), never by a path, and a kind that is not loaded is a table
 * with no rows that says so. The feed rows are read at most once a minute.
 */
public final class GenomicsHome {

    /** The Home these tables and figures are for. */
    public static final String HOME = "genomicist";
    /** The p-value line of a genome-wide hit: 5e-8, as -log10. */
    public static final double GENOME_WIDE = 7.301;
    /** The public feeds' tables by their symbolic names (config/lakes/genomicslake.yaml; their rows carry GENE, VARIANT_ID, SIGNIFICANCE, UPDATED and BUSINESS_DATE). */
    public static final String CLINVAR = "genomics.clinvar-variant";
    public static final String ENSEMBL = "genomics.ensembl-gene";
    public static final Map<String, String> FEEDS = Map.of(CLINVAR, "ClinVar", ENSEMBL, "Ensembl");
    private static final long FEED_TTL_MS = 60_000;

    private final GenomicsData data;
    private final ShowcaseData showcase;
    private volatile ShowcaseData.Source source;
    private volatile Feeds feeds = new Feeds(Map.of(), 0);

    /** One read of the feeds: for each kind its row count, latest business date and the rows of the followed genes. */
    private record Feeds(Map<String, List<Map<String, Object>>> rows, long at) {
    }

    public GenomicsHome(GenomicsData data, ShowcaseData showcase) {
        this.data = data;
        this.showcase = showcase;
    }

    /** Where the feed rows come from: the kernel's bindings once the module has started, a fixture in a test. */
    public void attach(ShowcaseData.Source s) {
        this.source = s;
        this.feeds = new Feeds(Map.of(), 0);
    }

    /** Forgets what was read (the module stopping). */
    public void clear() {
        this.source = null;
        this.feeds = new Feeds(Map.of(), 0);
    }

    // ---- the figures --------------------------------------------------------------------------------------------------------------

    /** The module's measure source: engine {@code genomics}. */
    public MeasureSource measures() {
        return new MeasureSource() {
            @Override
            public String engine() {
                return "genomics";
            }

            @Override
            public boolean on() {
                return !data.now().samples().isEmpty() || !data.now().variants().isEmpty();
            }

            @Override
            public OptionalDouble kpi(Principal p, String measure, Map<String, Object> args, LocalDate date) {
                return switch (measure) {
                    case "SAMPLES" -> OptionalDouble.of(data.now().samples().size());
                    case "VARIANTS" -> OptionalDouble.of(data.now().variants().size());
                    case "HITS" -> OptionalDouble.of(hits());
                    default -> OptionalDouble.empty();
                };
            }

            @Override
            public List<HomeData.MeasureValue> values(Principal p, String measure, String subjectKind, LocalDate date) {
                List<HomeData.MeasureValue> out = new ArrayList<>();
                if ("cohort".equals(subjectKind) && ("VARIANTS".equals(measure) || "SAMPLES".equals(measure))) {
                    data.cohorts().forEach((id, c) -> out.add(new HomeData.MeasureValue(subjectKind, id, measure, ((Number) c.get(measure)).doubleValue())));
                }
                return out;
            }

            @Override
            public List<HomeData.LimitRow> limits(Principal p, String measure, String subjectKind, LocalDate date) {
                return List.of();
            }
        };
    }

    /** The variants that pass the genome-wide line, one per variant over every trait. */
    double hits() {
        return associations().stream().filter(r -> num(r.get("NEG_LOG10_P")) >= GENOME_WIDE).map(r -> String.valueOf(r.get("VARIANT_ID"))).distinct().count();
    }

    private List<Map<String, Object>> associations() {
        return showcase == null ? List.of() : showcase.now().of("association");
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ---- the tables ---------------------------------------------------------------------------------------------------------------

    /** The strongest association of each gene, strongest first: GENE, VARIANT_ID, TRAIT, NEG_LOG10_P. */
    public List<Map<String, Object>> gwasTop() {
        Map<String, Map<String, Object>> best = new LinkedHashMap<>();
        for (Map<String, Object> r : associations()) {
            double y = num(r.get("NEG_LOG10_P"));
            String g = String.valueOf(r.get("GENE"));
            if (!Double.isNaN(y) && (!best.containsKey(g) || y > num(best.get(g).get("NEG_LOG10_P")))) {
                best.put(g, row("GENE", g, "VARIANT_ID", r.get("VARIANT_ID"), "TRAIT", r.get("TRAIT"), "NEG_LOG10_P", y));
            }
        }
        return best.values().stream().sorted(Comparator.comparingDouble((Map<String, Object> m) -> num(m.get("NEG_LOG10_P"))).reversed()).limit(10).toList();
    }

    /** The association map as a small plot: X (position along the genome, in Mb, chromosomes end to end), Y (-log10 p), GENE, CHROM; the tallest of each 10 Mb. */
    public List<Map<String, Object>> manhattan() {
        Map<String, Map<String, Object>> bins = new TreeMap<>();
        for (Map<String, Object> r : associations()) {
            double y = num(r.get("NEG_LOG10_P")), pos = num(r.get("POS"));
            int chrom = chromosome(String.valueOf(r.get("CHROM")));
            if (Double.isNaN(y) || Double.isNaN(pos) || chrom == 0) {
                continue;
            }
            String bin = String.format("%02d:%05d", chrom, (long) (pos / 1e7));
            Map<String, Object> was = bins.get(bin);
            if (was == null || y > num(was.get("Y"))) {
                bins.put(bin, row("X", Math.round((chrom * 300 + pos / 1e6) * 10) / 10.0, "Y", y, "GENE", r.get("GENE"), "CHROM", String.valueOf(r.get("CHROM"))));
            }
        }
        return new ArrayList<>(bins.values());
    }

    private static int chromosome(String c) {
        String s = c.startsWith("chr") ? c.substring(3) : c;
        return switch (s) {
            case "X" -> 23;
            case "Y" -> 24;
            case "M", "MT" -> 25;
            default -> {
                try {
                    int n = Integer.parseInt(s);
                    yield n >= 1 && n <= 22 ? n : 0;
                } catch (NumberFormatException e) {
                    yield 0;
                }
            }
        };
    }

    /** The genes the person's cohorts carry (the genes followed). */
    Set<String> followed() {
        Set<String> out = new TreeSet<>();
        data.now().variants().values().forEach(v -> {
            String g = GenomicsData.text(v, "GENE");
            if (!g.isEmpty()) {
                out.add(g);
            }
        });
        return out;
    }

    private Map<String, List<Map<String, Object>>> feedRows() {
        Feeds f = feeds;
        ShowcaseData.Source s = source;
        if (s == null || System.currentTimeMillis() - f.at() < FEED_TTL_MS) {
            return f.rows();
        }
        synchronized (this) {
            if (feeds != f) {
                return feeds.rows();
            }
            Map<String, List<Map<String, Object>>> all = new LinkedHashMap<>();
            for (String kind : FEEDS.keySet()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                try {
                    s.rows(kind, rows::add);
                } catch (RuntimeException e) {
                    rows.clear();                   // a feed that does not read is a feed not loaded: the loads table says so
                }
                all.put(kind, rows);
            }
            feeds = new Feeds(all, System.currentTimeMillis());
            return all;
        }
    }

    /** The latest ClinVar changes for the followed genes, newest first: GENE, VARIANT_ID, SIGNIFICANCE, UPDATED. */
    public List<Map<String, Object>> clinvar() {
        Set<String> genes = followed();
        return feedRows().getOrDefault(CLINVAR, List.of()).stream().filter(r -> genes.contains(String.valueOf(r.get("GENE"))))
                .sorted(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("UPDATED"))).reversed()).limit(10)
                .map(r -> row("GENE", r.get("GENE"), "VARIANT_ID", r.get("VARIANT_ID"), "SIGNIFICANCE", r.get("SIGNIFICANCE"), "UPDATED", r.get("UPDATED"))).toList();
    }

    /** The module's data loads: SOURCE, WHAT, DATE, STATE for each public feed and each of the module's own tables (loaded with its rows, or not loaded). */
    public List<Map<String, Object>> loads() {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, List<Map<String, Object>>> feeds = feedRows();
        for (Map.Entry<String, String> f : new TreeMap<>(FEEDS).entrySet()) {
            out.add(load(f.getValue(), f.getKey(), feeds.getOrDefault(f.getKey(), List.of())));
        }
        GenomicsData.Snapshot s = data.now();
        out.add(load("Own files", GenomicsData.VARIANT, s.variants().values()));
        out.add(load("Own files", GenomicsData.SAMPLE, s.samples().values()));
        out.add(load("Own files", GenomicsData.GENE, s.genes().values()));
        return out;
    }

    private static Map<String, Object> load(String source, String what, java.util.Collection<Map<String, Object>> rows) {
        String date = rows.stream().map(r -> String.valueOf(r.get("BUSINESS_DATE"))).filter(d -> !d.equals("null")).max(String::compareTo).orElse("");
        return row("SOURCE", source, "WHAT", what, "DATE", date, "STATE", rows.isEmpty() ? "not loaded" : "loaded, " + rows.size() + " rows");
    }

    /** What needs the genomicist: WHAT and DETAIL of each of the module's own tables with no rows (the files go through the loader into the lake). */
    public List<Map<String, Object>> needs() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String kind : data.now().missing()) {
            out.add(row("WHAT", "No " + kind + " rows in the genomics lake", "DETAIL",
                    "Drop your VCF, gene and sample files in the inbox and run tools/loaders/genomics/genomics_run.py (docs: load-genomics-files)"));
        }
        return out;
    }

    // ---- the home tables as beans ---------------------------------------------------------------------------------------------------

    /** One home table of the module, served to the genomicist's Home only. */
    public record Table(String name, Function<HomeTable.Context, List<Map<String, Object>>> read) implements HomeTable {

        @Override
        public List<String> homes() {
            return List.of(HOME);
        }

        @Override
        public List<Map<String, Object>> rows(Context c) {
            return read.apply(c);
        }
    }

    /** Every table the module serves. */
    public List<Table> tables() {
        return List.of(new Table("genomics_gwas_top", c -> gwasTop()), new Table("genomics_manhattan", c -> manhattan()),
                new Table("genomics_clinvar", c -> clinvar()), new Table("genomics_loads", c -> loads()), new Table("genomics_needs", c -> needs()));
    }
}
