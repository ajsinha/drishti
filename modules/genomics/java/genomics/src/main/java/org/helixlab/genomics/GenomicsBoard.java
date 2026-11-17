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

import static org.helixlab.genomics.ShowcaseData.integer;
import static org.helixlab.genomics.ShowcaseData.num;
import static org.helixlab.genomics.ShowcaseData.round;
import static org.helixlab.genomics.ShowcaseData.str;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;

/**
 * The genomics board's answers (docs/architecture/GENOMICS_SHOWCASE.md sections 4, 5 and 9): the figures, the association plot, the cohort
 * matrix, the map of samples, the lollipop coloured by the scan, and the Explain of a variant, a site and a cluster. Every figure is made
 * here from the rows of the packs (filter, join, count, sort, sample); the console draws the answer and computes nothing. A selection of
 * samples is a filter of the call ({@link Selection}), never a recount in the page.
 */
public final class GenomicsBoard {

    /** The genome-wide line (p 5e-8) and the suggestive one (p 1e-5), as −log10 p. */
    static final double GENOME_WIDE = 7.30103, SUGGESTIVE = 5.0;
    /** Every site below p 1e-4 is drawn; the rest are sampled to about this many points in all. */
    static final int DRAWN = 5400;
    /** A scan score at or below this is damaging, at or below the second intermediate; else tolerated (0 is the natural protein). */
    static final double DAMAGING = -1.0, INTERMEDIATE = -0.4;
    static final List<String> ALTERATIONS = List.of("truncating", "splice", "missense", "inframe", "amplification", "deep deletion");

    private final ShowcaseData data;

    public GenomicsBoard(ShowcaseData data) {
        this.data = data;
    }

    /** What is loaded: the rows of each kind and the kinds with none. */
    public Map<String, Object> status() {
        ShowcaseData.Tables t = data.now();
        Map<String, Object> counts = new LinkedHashMap<>();
        t.rows().forEach((k, v) -> counts.put(k, v.size()));
        List<String> missing = new ArrayList<>(t.missing(ShowcaseData.GENOMICS_KINDS));
        missing.addAll(t.missing(ShowcaseData.PROTEIN_KINDS));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attached", data.attached());
        out.put("rows", counts);
        out.put("missing", missing);
        out.put("synthetic", true);
        return out;
    }

    // ---- the index of a snapshot -------------------------------------------------------------------------------------------------

    /** The rows of a snapshot by their keys, made once per snapshot. */
    record Index(Map<String, Map<String, Object>> samples, Map<String, Map<String, Object>> variants, Map<String, Map<String, Object>> genes,
            Map<String, List<Map<String, Object>>> callsByVariant, Map<String, Set<String>> altered, Map<String, Map<String, String>> cells) {
    }

    Index index() {
        return data.cached("genomics.index", () -> {
            ShowcaseData.Tables t = data.now();
            Map<String, Map<String, Object>> samples = new LinkedHashMap<>(), variants = new HashMap<>(), genes = new LinkedHashMap<>();
            t.of("study-sample").forEach(r -> samples.put(str(r.get("SAMPLE_ID")), r));
            t.of("study-variant").forEach(r -> variants.put(str(r.get("VARIANT_ID")), r));
            t.of("study-gene").forEach(r -> genes.put(str(r.get("GENE")), r));
            Map<String, List<Map<String, Object>>> byVariant = new HashMap<>();
            Map<String, Map<String, String>> cells = new HashMap<>();          // sample -> gene -> the alteration shown (the worst)
            for (Map<String, Object> c : t.of("variant-call")) {
                String vid = str(c.get("VARIANT_ID")), sid = str(c.get("SAMPLE_ID"));
                byVariant.computeIfAbsent(vid, k -> new ArrayList<>()).add(c);
                Map<String, Object> v = variants.get(vid);
                String alt = v == null ? null : alteration(str(v.get("CONSEQUENCE")));
                if (alt != null) {
                    cells.computeIfAbsent(sid, k -> new HashMap<>()).merge(str(v.get("GENE")), alt, GenomicsBoard::worse);
                }
            }
            Map<String, List<Map<String, Object>>> genesByChrom = new HashMap<>();
            genes.values().forEach(g -> genesByChrom.computeIfAbsent(str(g.get("CHROM")), k -> new ArrayList<>()).add(g));
            for (Map<String, Object> s : t.of("cnv-segment")) {
                String call = copyCall(s);
                if (call == null) {
                    continue;
                }
                double a = num(s.get("START")), b = num(s.get("END"));
                for (Map<String, Object> g : genesByChrom.getOrDefault(str(s.get("CHROM")), List.of())) {
                    if (num(g.get("START")) <= b && num(g.get("END")) >= a) {
                        cells.computeIfAbsent(str(s.get("SAMPLE_ID")), k -> new HashMap<>()).merge(str(g.get("GENE")), call, GenomicsBoard::worse);
                    }
                }
            }
            Map<String, Set<String>> altered = new HashMap<>();             // gene -> the samples with an alteration in it
            cells.forEach((sid, m) -> m.keySet().forEach(g -> altered.computeIfAbsent(g, k -> new HashSet<>()).add(sid)));
            return new Index(samples, variants, genes, byVariant, altered, cells);
        });
    }

    /** The alteration a consequence shows in the matrix (none for a silent or non-coding one). */
    static String alteration(String consequence) {
        String c = consequence.toLowerCase(Locale.ROOT);
        if (c.contains("stop_gained") || c.contains("frameshift") || c.contains("nonsense") || c.contains("truncat") || c.contains("start_lost")) {
            return "truncating";
        }
        if (c.contains("splice")) {
            return "splice";
        }
        if (c.contains("missense")) {
            return "missense";
        }
        if (c.contains("inframe")) {
            return "inframe";
        }
        return null;
    }

    /** A copy-number segment's call in the matrix: amplification (AMP, or five copies and more) or deep deletion (DEL with no copy left). */
    static String copyCall(Map<String, Object> s) {
        String call = str(s.get("CALL")).toUpperCase(Locale.ROOT);
        double cn = num(s.get("COPY_NUMBER"));
        if (call.startsWith("AMP") || (!Double.isNaN(cn) && cn >= 5)) {
            return "amplification";
        }
        if ((call.startsWith("DEL") || call.contains("DEEP")) && (Double.isNaN(cn) || cn < 0.5)) {
            return "deep deletion";
        }
        return null;
    }

    private static String worse(String a, String b) {
        return ALTERATIONS.indexOf(a) <= ALTERATIONS.indexOf(b) ? a : b;
    }

    private Set<String> members(Selection sel) {
        return sel.members(data.now().of("study-sample"));
    }

    private void needs(String... kinds) {
        List<String> missing = data.now().missing(List.of(kinds));
        if (!missing.isEmpty()) {
            throw new NoSuchElementException("the showcase's data is not loaded here: no rows of " + String.join(", ", missing)
                    + " (load the packs genomics-showcase and protein-engineering: drishti.py lake demo)");
        }
    }

    // ---- the figures ---------------------------------------------------------------------------------------------------------------

    /** The figures of a selection: samples, response rate, and with a gene the response if altered and if not. */
    public Map<String, Object> summary(Selection sel, String gene) {
        needs("study-sample", "study-variant", "variant-call");
        Index ix = index();
        Set<String> m = members(sel);
        Set<String> all = sel.narrowed() ? new Selection(sel.cohort(), Set.of(), null, Set.of()).members(data.now().of("study-sample")) : m;
        long resp = m.stream().filter(s -> Selection.responded(ix.samples().get(s).get("RESPONSE"))).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cohort", sel.cohort());
        out.put("selection", sel.words());
        out.put("cohortSamples", all.size());
        out.put("samples", m.size());
        out.put("responders", resp);
        out.put("responseRate", m.isEmpty() ? null : round(resp / (double) m.size(), 4));
        Explained ex = new Explained("The figures of " + sel.words(), m.size() + " samples").scope(sel.words())
                .step("The samples of the selection", "study-sample", m.size()).step("Responders among them", "study-sample", resp);
        if (gene != null && !gene.isBlank()) {
            Set<String> alt = ix.altered().getOrDefault(gene, Set.of());
            List<String> yes = m.stream().filter(alt::contains).toList(), no = m.stream().filter(s -> !alt.contains(s)).toList();
            long ry = yes.stream().filter(s -> Selection.responded(ix.samples().get(s).get("RESPONSE"))).count();
            long rn = no.stream().filter(s -> Selection.responded(ix.samples().get(s).get("RESPONSE"))).count();
            out.put("gene", gene);
            out.put("altered", yes.size());
            out.put("alteredRate", m.isEmpty() ? null : round(yes.size() / (double) m.size(), 4));
            out.put("responseIfAltered", yes.isEmpty() ? null : round(ry / (double) yes.size(), 4));
            out.put("responseIfNot", no.isEmpty() ? null : round(rn / (double) no.size(), 4));
            ex.step("Samples with " + gene + " altered (variant-call joined to study-variant, and cnv-segment over the gene)", "variant-call", yes.size())
                    .sentence(yes.isEmpty() ? "No sample of " + sel.words() + " has " + gene + " altered."
                            : String.format(Locale.ROOT, "%d of %d samples carry a change in %s; %d%% of them respond, against %d%% of the others.", yes.size(), m.size(), gene,
                                    Math.round(100.0 * ry / yes.size()), no.isEmpty() ? 0 : Math.round(100.0 * rn / no.size())));
        } else {
            ex.sentence(String.format(Locale.ROOT, "%d samples, %d respond.", m.size(), resp));
        }
        out.put("explain", ex.build());
        return out;
    }

    // ---- the association plot ------------------------------------------------------------------------------------------------------

    /** The chromosomes in genome order (1 to 22, X, Y, then the rest), each with its offset on one axis. */
    List<Map<String, Object>> chromosomes() {
        Map<String, Double> len = new HashMap<>();
        data.now().of("study-gene").forEach(g -> len.merge(str(g.get("CHROM")), num(g.get("END")), Math::max));
        data.now().of("study-variant").forEach(v -> len.merge(str(v.get("CHROM")), num(v.get("POS")), Math::max));
        List<String> names = new ArrayList<>(len.keySet());
        names.sort(Comparator.comparingInt(GenomicsBoard::chromRank).thenComparing(c -> c));
        List<Map<String, Object>> out = new ArrayList<>();
        double at = 0;
        for (String c : names) {
            double l = Math.max(1, len.get(c)) * 1.02;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("CHROM", c);
            r.put("FROM", at);
            r.put("TO", at + l);
            out.add(r);
            at += l;
        }
        return out;
    }

    static int chromRank(String c) {
        String s = c.toUpperCase(Locale.ROOT).replace("CHR", "");
        if (s.equals("X")) {
            return 23;
        }
        if (s.equals("Y")) {
            return 24;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 99;
        }
    }

    /** The traits tested (RESPONSE first when there is one). */
    List<String> traits() {
        Set<String> t = new java.util.TreeSet<>();
        data.now().of("association").forEach(a -> t.add(str(a.get("TRAIT"))));
        List<String> out = new ArrayList<>(t);
        out.sort(Comparator.comparing((String s) -> !s.equalsIgnoreCase("RESPONSE")));
        return out;
    }

    /** The association plot of a trait: every site below p 1e-4 and an even sample of the rest; the peaks; the chromosomes. */
    public Map<String, Object> associations(String trait) {
        needs("study-variant", "association");
        List<String> traits = traits();
        String tr = trait == null || trait.isBlank() ? traits.get(0) : trait;
        if (traits.stream().noneMatch(x -> x.equalsIgnoreCase(tr))) {
            throw new NoSuchElementException("no trait " + tr + " (there are " + String.join(", ", traits) + ")");
        }
        return data.cached("associations|" + tr.toUpperCase(Locale.ROOT), () -> {
            Index ix = index();
            List<Map<String, Object>> chroms = chromosomes();
            Map<String, Double> offset = new HashMap<>();
            chroms.forEach(c -> offset.put(str(c.get("CHROM")), num(c.get("FROM"))));
            List<Map<String, Object>> strong = new ArrayList<>(), rest = new ArrayList<>();
            int total = 0;
            for (Map<String, Object> a : data.now().of("association")) {
                if (!str(a.get("TRAIT")).equalsIgnoreCase(tr)) {
                    continue;
                }
                Map<String, Object> v = ix.variants().get(str(a.get("VARIANT_ID")));
                if (v == null) {
                    continue;
                }
                total++;
                double lp = num(a.get("NEG_LOG10_P"));
                if (Double.isNaN(lp)) {
                    lp = -Math.log10(Math.max(1e-300, num(a.get("P"))));
                }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("VARIANT_ID", v.get("VARIANT_ID"));
                r.put("CHROM", v.get("CHROM"));
                r.put("POS", v.get("POS"));
                r.put("X", offset.getOrDefault(str(v.get("CHROM")), 0.0) + num(v.get("POS")));
                r.put("NEG_LOG10_P", round(lp, 3));
                r.put("GENE", v.get("GENE"));
                r.put("BETA", a.get("BETA"));
                (lp >= 4 ? strong : rest).add(r);
            }
            rest.sort(Comparator.comparingDouble(r -> num(r.get("X"))));
            int room = Math.max(0, DRAWN - strong.size()), every = rest.isEmpty() || room >= rest.size() ? 1 : (int) Math.ceil(rest.size() / (double) Math.max(1, room));
            List<Map<String, Object>> rows = new ArrayList<>(strong);
            for (int i = 0; i < rest.size(); i += every) {
                rows.add(rest.get(i));
            }
            rows.sort(Comparator.comparingDouble(r -> num(r.get("X"))));
            Map<String, Map<String, Object>> best = new TreeMap<>();
            strong.stream().filter(r -> num(r.get("NEG_LOG10_P")) >= SUGGESTIVE && !str(r.get("GENE")).isEmpty())
                    .forEach(r -> best.merge(str(r.get("GENE")), r, (a, b) -> num(a.get("NEG_LOG10_P")) >= num(b.get("NEG_LOG10_P")) ? a : b));
            List<Map<String, Object>> peaks = best.values().stream().sorted(Comparator.comparingDouble(r -> -num(r.get("NEG_LOG10_P")))).limit(8).toList();
            long past = strong.stream().filter(r -> num(r.get("NEG_LOG10_P")) >= GENOME_WIDE).count();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("trait", tr);
            out.put("traits", traits);
            out.put("total", total);
            out.put("drawn", rows.size());
            out.put("threshold", GENOME_WIDE);
            out.put("suggestive", SUGGESTIVE);
            out.put("chromosomes", chroms);
            out.put("rows", rows);
            out.put("peaks", peaks);
            out.put("explain", new Explained("Association with " + tr, total + " sites").scope("trait " + tr)
                    .step("Sites tested against " + tr, "association", total).step("Sites below p 1e-4, all drawn", "association", strong.size())
                    .step("The rest, sampled evenly along the genome", "association", rows.size() - strong.size())
                    .sentence(past == 0 ? "No site passes the genome-wide line (p 5e-8)." : String.format(Locale.ROOT, "%d sites pass the genome-wide line; the tallest peak sits on %s.", past,
                            peaks.isEmpty() ? "no named gene" : str(peaks.get(0).get("GENE")))).build());
            return out;
        });
    }

    // ---- the cohort matrix ---------------------------------------------------------------------------------------------------------

    /** The cohort matrix: the most altered genes (and the one asked for), the selection's samples in the mutual-exclusivity order, the tracks. */
    public Map<String, Object> oncoprint(Selection sel, int genesWanted, String gene) {
        needs("study-sample", "study-variant", "variant-call");
        Index ix = index();
        Set<String> m = members(sel);
        int n = Math.max(1, Math.min(60, genesWanted));
        List<String> genes = new ArrayList<>(ix.altered().keySet().stream()
                .sorted(Comparator.comparingLong((String g) -> -ix.altered().get(g).stream().filter(m::contains).count()).thenComparing(g -> g)).limit(n).toList());
        if (gene != null && !gene.isBlank() && !genes.contains(gene) && ix.genes().containsKey(gene)) {
            genes.add(0, gene);
        }
        List<String> samples = new ArrayList<>(m);
        Map<String, Long> mask = new HashMap<>();
        for (String s : samples) {
            long k = 0;
            Map<String, String> row = ix.cells().getOrDefault(s, Map.of());
            for (int i = 0; i < Math.min(62, genes.size()); i++) {
                if (row.containsKey(genes.get(i))) {
                    k |= 1L << (62 - i);
                }
            }
            mask.put(s, k);
        }
        samples.sort(Comparator.comparingLong((String s) -> -mask.get(s)).thenComparing(s -> s));
        List<Map<String, Object>> cells = new ArrayList<>();
        for (String s : samples) {
            Map<String, String> row = ix.cells().getOrDefault(s, Map.of());
            for (String g : genes) {
                String a = row.get(g);
                if (a != null) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("SAMPLE_ID", s);
                    c.put("GENE", g);
                    c.put("ALTERATION", a);
                    cells.add(c);
                }
            }
        }
        List<Map<String, Object>> altered = new ArrayList<>();
        for (String g : genes) {
            long k = ix.altered().getOrDefault(g, Set.of()).stream().filter(m::contains).count();
            altered.add(Map.of("GENE", g, "SAMPLES", k, "PCT", m.isEmpty() ? 0.0 : round(k / (double) m.size(), 4)));
        }
        List<Map<String, Object>> tracks = new ArrayList<>();
        tracks.add(track("Response", samples, s -> Selection.responded(ix.samples().get(s).get("RESPONSE")) ? "responder" : "non-responder"));
        tracks.add(track("Tumour type", samples, s -> str(ix.samples().get(s).get("TUMOUR_TYPE"))));
        tracks.add(track("Cluster", samples, s -> str(ix.samples().get(s).get("CLUSTER"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("selection", sel.words());
        out.put("genes", genes);
        out.put("samples", samples);
        out.put("rows", cells);
        out.put("altered", altered);
        out.put("tracks", tracks);
        out.put("explain", new Explained("Who carries what: " + sel.words(), samples.size() + " samples × " + genes.size() + " genes").scope(sel.words())
                .step("The samples of the selection", "study-sample", samples.size())
                .step("Their calls joined to the variants (a coding change shows)", "variant-call", data.now().of("variant-call").stream().filter(c -> m.contains(str(c.get("SAMPLE_ID")))).count())
                .step("Copy-number segments over the genes", "cnv-segment", data.now().of("cnv-segment").stream().filter(c -> m.contains(str(c.get("SAMPLE_ID")))).count())
                .step("The genes most often altered, the samples sorted so each gene's carriers sit together", "study-variant", genes.size())
                .sentence(cells.size() + " alterations in " + genes.size() + " genes over " + samples.size() + " samples.").build());
        return out;
    }

    private static Map<String, Object> track(String title, List<String> samples, java.util.function.Function<String, String> of) {
        Map<String, Object> values = new LinkedHashMap<>();
        samples.forEach(s -> values.put(s, of.apply(s)));
        return Map.of("title", title, "values", values);
    }

    // ---- the map of samples --------------------------------------------------------------------------------------------------------

    /** The map of samples: every sample of the cohort with its precomputed position, which are selected, and each cluster's figures. */
    public Map<String, Object> sampleMap(Selection sel) {
        needs("study-sample");
        Selection whole = new Selection(sel.cohort(), Set.of(), null, Set.of());
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, double[]> clusters = new TreeMap<>();             // n, responders, sum x, sum y
        for (Map<String, Object> s : data.now().of("study-sample")) {
            if (!whole.has(s)) {
                continue;
            }
            rows.add(Explained.pick(s, List.of("SAMPLE_ID", "EMB_X", "EMB_Y", "CLUSTER", "RESPONSE", "TUMOUR_TYPE")));
            double[] c = clusters.computeIfAbsent(str(s.get("CLUSTER")), k -> new double[4]);
            c[0]++;
            c[1] += Selection.responded(s.get("RESPONSE")) ? 1 : 0;
            c[2] += num(s.get("EMB_X"));
            c[3] += num(s.get("EMB_Y"));
        }
        List<Map<String, Object>> cl = new ArrayList<>();
        clusters.forEach((k, c) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("CLUSTER", k);
            r.put("SAMPLES", (int) c[0]);
            r.put("RESPONSE_RATE", round(c[1] / c[0], 4));
            r.put("CX", round(c[2] / c[0], 3));
            r.put("CY", round(c[3] / c[0], 3));
            cl.add(r);
        });
        Set<String> m = members(sel);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("clusters", cl);
        out.put("selected", sel.narrowed() ? List.copyOf(m) : List.of());
        out.put("selection", sel.words());
        out.put("explain", new Explained("The map of samples", rows.size() + " samples").scope(sel.words()).step("The samples of the cohort with their precomputed positions", "study-sample", rows.size())
                .step("Selected", "study-sample", m.size()).sentence("Each point is a sample, placed by its expression; the clusters were found before the data was loaded.").build());
        return out;
    }

    // ---- the lollipop --------------------------------------------------------------------------------------------------------------

    /** The protein row of a gene (or of a protein's own name), or null. */
    Map<String, Object> proteinOf(String id) {
        return data.now().of("protein").stream().filter(p -> str(p.get("GENE")).equalsIgnoreCase(id) || str(p.get("PROTEIN")).equalsIgnoreCase(id)).findFirst().orElse(null);
    }

    /** The scan's assays of a protein (ACTIVITY first when there is one). */
    List<String> assays(String protein) {
        Set<String> a = new java.util.TreeSet<>();
        data.now().of("dms-score").stream().filter(d -> str(d.get("PROTEIN")).equals(protein)).forEach(d -> a.add(str(d.get("ASSAY"))));
        List<String> out = new ArrayList<>(a);
        out.sort(Comparator.comparing((String s) -> !s.equalsIgnoreCase("ACTIVITY")));
        return out;
    }

    /** The scan's score of one change ("PROTEIN|ASSAY|POS|AA"), made once per snapshot. */
    Map<String, Map<String, Object>> scanIndex() {
        return data.cached("genomics.scan", () -> {
            Map<String, Map<String, Object>> m = new HashMap<>();
            data.now().of("dms-score").forEach(d -> m.put(str(d.get("PROTEIN")) + "|" + str(d.get("ASSAY")) + "|" + integer(d.get("POS"), -1) + "|" + str(d.get("AA")), d));
            return m;
        });
    }

    /** What a scan score says: damaging, intermediate, tolerated, or not measured. */
    static String effect(Double score) {
        if (score == null || score.isNaN()) {
            return "not measured";
        }
        return score <= DAMAGING ? "damaging" : score <= INTERMEDIATE ? "intermediate" : "tolerated";
    }

    /** A gene's variants along its protein: carriers in the selection, the scan's score of each exact change, the domains. */
    public Map<String, Object> lollipop(String gene, Selection sel, String assay) {
        needs("study-variant", "variant-call", "study-sample");
        Index ix = index();
        Map<String, Object> g = ix.genes().get(gene);
        Map<String, Object> p = proteinOf(gene);
        if (g == null && p == null) {
            throw new NoSuchElementException("no gene " + gene);
        }
        Set<String> m = members(sel);
        String protein = p == null ? null : str(p.get("PROTEIN"));
        String as = protein == null ? null : assay != null && !assay.isBlank() ? assay : assays(protein).stream().findFirst().orElse(null);
        Map<String, Map<String, Object>> scan = scanIndex();
        List<Map<String, Object>> rows = new ArrayList<>();
        long calls = 0;
        for (Map<String, Object> v : ix.variants().values()) {
            if (!str(v.get("GENE")).equals(gene) || Double.isNaN(num(v.get("PROTEIN_POS")))) {
                continue;
            }
            long carriers = ix.callsByVariant().getOrDefault(str(v.get("VARIANT_ID")), List.of()).stream().filter(c -> m.contains(str(c.get("SAMPLE_ID")))).count();
            if (carriers == 0) {
                continue;
            }
            calls += carriers;
            Map<String, Object> d = protein == null ? null : scan.get(protein + "|" + as + "|" + integer(v.get("PROTEIN_POS"), -1) + "|" + str(v.get("AA_ALT")));
            Double score = d == null ? null : num(d.get("SCORE"));
            Map<String, Object> r = Explained.pick(v, List.of("VARIANT_ID", "PROTEIN_POS", "AA_REF", "AA_ALT", "CONSEQUENCE", "IMPACT"));
            r.put("CHANGE", str(v.get("AA_REF")) + integer(v.get("PROTEIN_POS"), 0) + str(v.get("AA_ALT")));
            r.put("CARRIERS", carriers);
            r.put("SCORE", score == null || score.isNaN() ? null : round(score, 3));
            r.put("EFFECT", effect(score));
            rows.add(r);
        }
        rows.sort(Comparator.comparingDouble(r -> num(r.get("PROTEIN_POS"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gene", gene);
        out.put("protein", protein);
        out.put("assay", as);
        out.put("length", p != null ? p.get("LENGTH") : g.get("PROTEIN_LENGTH"));
        out.put("domains", protein == null ? List.of() : domains(protein));
        out.put("rows", rows);
        out.put("selection", sel.words());
        long damaging = rows.stream().filter(r -> "damaging".equals(r.get("EFFECT"))).count();
        out.put("explain", new Explained("The variants of " + gene + " along its protein", rows.size() + " variants").scope(sel.words())
                .step("The variants of " + gene + " with a protein position", "study-variant", rows.size()).step("Their carriers in the selection", "variant-call", calls)
                .step("Each exact change joined to the scan (" + (as == null ? "no scan" : as) + ")", "dms-score", rows.stream().filter(r -> r.get("SCORE") != null).count())
                .sentence(rows.isEmpty() ? "No variant of " + gene + " is carried in " + sel.words() + "."
                        : damaging + " of the " + rows.size() + " changes carried here break the protein in the laboratory scan.")
                .link("Open " + gene + " in the protein workbench", "/proteins/" + gene).build());
        return out;
    }

    /** The domains of a protein: the runs of residues with one DOMAIN. */
    List<Map<String, Object>> domains(String protein) {
        List<Map<String, Object>> res = data.now().of("residue").stream().filter(r -> str(r.get("PROTEIN")).equals(protein))
                .sorted(Comparator.comparingDouble(r -> num(r.get("POS")))).toList();
        List<Map<String, Object>> out = new ArrayList<>();
        String cur = null;
        int from = 0, to = 0;
        for (Map<String, Object> r : res) {
            String d = str(r.get("DOMAIN"));
            int pos = integer(r.get("POS"), 0);
            if (!d.equals(cur)) {
                if (cur != null && !cur.isEmpty()) {
                    out.add(Map.of("NAME", cur, "FROM", from, "TO", to));
                }
                cur = d;
                from = pos;
            }
            to = pos;
        }
        if (cur != null && !cur.isEmpty()) {
            out.add(Map.of("NAME", cur, "FROM", from, "TO", to));
        }
        return out;
    }

    // ---- Explain -------------------------------------------------------------------------------------------------------------------

    /** A variant: its carriers in the selection with their figures, joined to the scan's score of that exact change. */
    public Map<String, Object> explainVariant(String id, Selection sel, String assay) {
        needs("study-variant", "variant-call", "study-sample");
        Index ix = index();
        Map<String, Object> v = ix.variants().get(id);
        if (v == null) {
            throw new NoSuchElementException("no variant " + id);
        }
        Set<String> m = members(sel);
        List<Map<String, Object>> carriers = new ArrayList<>();
        for (Map<String, Object> c : ix.callsByVariant().getOrDefault(id, List.of())) {
            String sid = str(c.get("SAMPLE_ID"));
            if (!m.contains(sid)) {
                continue;
            }
            Map<String, Object> s = ix.samples().getOrDefault(sid, Map.of());
            Map<String, Object> r = Explained.pick(c, List.of("SAMPLE_ID", "GENOTYPE", "VAF", "DEPTH"));
            r.putAll(Explained.pick(s, List.of("CLUSTER", "TUMOUR_TYPE", "STAGE", "RESPONSE")));
            carriers.add(r);
        }
        Map<String, Object> p = proteinOf(str(v.get("GENE")));
        String protein = p == null ? null : str(p.get("PROTEIN"));
        String as = protein == null ? null : assay != null && !assay.isBlank() ? assay : assays(protein).stream().findFirst().orElse(null);
        Map<String, Object> d = protein == null ? null : scanIndex().get(protein + "|" + as + "|" + integer(v.get("PROTEIN_POS"), -1) + "|" + str(v.get("AA_ALT")));
        Double score = d == null ? null : num(d.get("SCORE"));
        String change = str(v.get("AA_REF")) + integer(v.get("PROTEIN_POS"), 0) + str(v.get("AA_ALT"));
        long resp = carriers.stream().filter(r -> Selection.responded(r.get("RESPONSE"))).count();
        Explained ex = new Explained(id + " · " + str(v.get("GENE")) + " p." + change, carriers.size() + " carriers").scope(sel.words())
                .step("The variant", "study-variant", 1).step("Its carriers in the selection", "variant-call", carriers.size())
                .step("Joined to the samples", "study-sample", carriers.size()).step("The scan's score of " + change + (as == null ? "" : " (" + as + ")"), "dms-score", d == null ? 0 : 1)
                .sentence(String.format(Locale.ROOT, "%s (%s p.%s) is carried by %d samples of %s, %d of whom respond; %s", id, str(v.get("GENE")), change, carriers.size(), sel.words(), resp,
                        score == null || score.isNaN() ? "the scan did not measure this change." : "the laboratory scan measures this exact change at " + round(score, 2) + " (" + effect(score) + ")."))
                .records(List.of("SAMPLE_ID", "GENOTYPE", "VAF", "DEPTH", "CLUSTER", "TUMOUR_TYPE", "STAGE", "RESPONSE"), carriers);
        if (p != null) {
            ex.link("This change in the protein workbench", "/proteins/" + str(v.get("GENE")) + "?residue=" + integer(v.get("PROTEIN_POS"), 0));
        }
        return ex.build();
    }

    /** A site of the association plot: its test against every trait. */
    public Map<String, Object> explainSite(String id) {
        needs("study-variant", "association");
        Map<String, Object> v = index().variants().get(id);
        if (v == null) {
            throw new NoSuchElementException("no variant " + id);
        }
        List<Map<String, Object>> tests = data.now().of("association").stream().filter(a -> str(a.get("VARIANT_ID")).equals(id))
                .map(a -> Explained.pick(a, List.of("TRAIT", "P", "NEG_LOG10_P", "BETA", "SE", "R2_LEAD"))).toList();
        return new Explained(id + " on " + str(v.get("GENE")), tests.size() + " tests").scope("chromosome " + str(v.get("CHROM")) + " at " + str(v.get("POS")))
                .step("The site", "study-variant", 1).step("Its tests against each trait", "association", tests.size())
                .sentence("The association was computed from the calls and the response of every sample (a test per site); these are its figures.")
                .records(List.of("TRAIT", "P", "NEG_LOG10_P", "BETA", "SE", "R2_LEAD"), tests).build();
    }

    /** A cluster of the map of samples: its samples and response. */
    public Map<String, Object> explainCluster(String cluster, String cohort) {
        needs("study-sample");
        Selection sel = new Selection(cohort == null ? "" : cohort, Set.of(cluster), null, Set.of());
        List<Map<String, Object>> rows = data.now().of("study-sample").stream().filter(sel::has)
                .map(s -> Explained.pick(s, List.of("SAMPLE_ID", "TUMOUR_TYPE", "STAGE", "AGE_BAND", "RESPONSE"))).toList();
        long resp = rows.stream().filter(r -> Selection.responded(r.get("RESPONSE"))).count();
        return new Explained("Cluster " + cluster, rows.size() + " samples").scope(sel.words()).step("The samples of the cluster", "study-sample", rows.size())
                .step("Responders", "study-sample", resp).sentence(String.format(Locale.ROOT, "%d samples; %d%% respond.", rows.size(), rows.isEmpty() ? 0 : Math.round(100.0 * resp / rows.size())))
                .records(List.of("SAMPLE_ID", "TUMOUR_TYPE", "STAGE", "AGE_BAND", "RESPONSE"), rows).build();
    }
}
