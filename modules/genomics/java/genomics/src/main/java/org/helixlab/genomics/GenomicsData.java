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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What the module knows, read from the lake and kept as one immutable snapshot (swapped whole on a reload, so two people never see half of
 * one): the rows of the kinds {@code variant}, {@code sample}, {@code gene} and, when someone loaded them, {@code cohort} of the genomics pack,
 * which a site binds to the genomics lake's tables ({@code genomics.variant} ... of {@code config/lakes/genomicslake.yaml}). Never a file and
 * never a path: the files a person has (VCF, gene, sample and cohort tables) reach the lake through the genomics loader
 * ({@code tools/loaders/genomics}). The first call reads the kinds; a read that found a kind empty is tried again after a minute (a lake still
 * loading). The cohorts are computed from the samples and the variants they carry; a cohort row someone loaded adds to or replaces the
 * computed one of its id.
 */
public final class GenomicsData {

    public static final String VARIANT = "variant";
    public static final String SAMPLE = "sample";
    public static final String GENE = "gene";
    public static final String COHORT = "cohort";
    /** The kinds read, in order. */
    public static final List<String> KINDS = List.of(VARIANT, SAMPLE, GENE, COHORT);

    private static final long RETRY_MS = 60_000;

    /** One read of the lake: each kind's rows by key, and the kinds that had none. */
    public record Snapshot(Map<String, Map<String, Object>> variants, Map<String, Map<String, Object>> samples, Map<String, Map<String, Object>> genes,
            Map<String, Map<String, Object>> cohortRows, List<String> missing, long readAt) {

        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Map.of(), Map.of(), List.of(VARIANT, SAMPLE, GENE), 0);
    }

    private volatile ShowcaseData.Source source;
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile Map<String, Map<String, Object>> cohorts;

    /** Where the rows come from: the kernel's bindings once the module has started, a fixture in a test. */
    public void attach(ShowcaseData.Source s) {
        this.source = s;
        this.snapshot = Snapshot.EMPTY;
        this.cohorts = null;
    }

    /** Whether a source is attached. */
    public boolean attached() {
        return source != null;
    }

    /** The data now: read on first use, and again when a kind was empty a minute ago. */
    public Snapshot now() {
        Snapshot s = snapshot;
        if (source != null && (s.readAt() == 0 || !s.missing().isEmpty() && System.currentTimeMillis() - s.readAt() > RETRY_MS)) {
            synchronized (this) {
                if (snapshot == s) {
                    snapshot = read(source);
                    cohorts = null;
                }
                s = snapshot;
            }
        }
        return s;
    }

    /** Reads every kind again (the module's reload). */
    public synchronized Snapshot reload() {
        if (source != null) {
            snapshot = read(source);
            cohorts = null;
        }
        return snapshot;
    }

    /** Forgets everything (the module stopping). */
    public void clear() {
        source = null;
        snapshot = Snapshot.EMPTY;
        cohorts = null;
    }

    private static Snapshot read(ShowcaseData.Source s) {
        Map<String, Map<String, Map<String, Object>>> all = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        String[] keys = {"VARIANT_ID", "SAMPLE_ID", "GENE", "COHORT_ID"};
        for (int i = 0; i < KINDS.size(); i++) {
            Map<String, Map<String, Object>> rows = new TreeMap<>();
            String key = keys[i];
            try {
                s.rows(KINDS.get(i), r -> {
                    String id = ShowcaseData.str(r.get(key));
                    if (!id.isEmpty()) {
                        Map<String, Object> row = new LinkedHashMap<>(r);
                        row.computeIfPresent("SAMPLES", (k, v) -> v instanceof List<?> ? v : List.of());
                        rows.put(id, Collections.unmodifiableMap(row));
                    }
                });
            } catch (RuntimeException e) {
                rows.clear();                    // a kind that does not read is a kind with no rows: the screens say which is missing
            }
            if (rows.isEmpty() && !COHORT.equals(KINDS.get(i))) {
                missing.add(KINDS.get(i));
            }
            all.put(KINDS.get(i), Collections.unmodifiableMap(rows));
        }
        return new Snapshot(all.get(VARIANT), all.get(SAMPLE), all.get(GENE), all.get(COHORT), List.copyOf(missing), System.currentTimeMillis());
    }

    // ---- the rows of each kind ----------------------------------------------------------------------------------------------------

    /** The ids of the samples that carry a variant. */
    public static List<String> carriers(Map<String, Object> variant) {
        List<String> out = new ArrayList<>();
        if (variant.get("SAMPLES") instanceof List<?> l) {
            l.forEach(x -> out.add(String.valueOf(x)));
        }
        return out;
    }

    /** A variant's text field ("" for none). */
    public static String text(Map<String, Object> row, String field) {
        return ShowcaseData.str(row.get(field));
    }

    /** A variant's row: VARIANT_ID, CHROM, POS, REF, ALT, QUAL, FILTER, GENE, CONSEQUENCE, IMPACT, PROTEIN_POS, AF, CARRIERS, SAMPLES. */
    public static Map<String, Object> variantRow(Map<String, Object> v) {
        Map<String, Object> r = new LinkedHashMap<>(v);
        r.putIfAbsent("CARRIERS", carriers(v).size());
        r.putIfAbsent("SAMPLES", carriers(v));
        return r;
    }

    /** A gene's row: the gene table's fields and VARIANTS (how many variants fall in it). */
    public Map<String, Object> geneRow(String gene) {
        Snapshot s = now();
        Map<String, Object> g = s.genes().get(gene);
        if (g == null) {
            return null;
        }
        Map<String, Object> r = new LinkedHashMap<>(g);
        r.put("VARIANTS", s.variants().values().stream().filter(v -> gene.equals(text(v, "GENE"))).count());
        return r;
    }

    /** The cohorts, computed: COHORT_ID, SAMPLES, MEMBERS, VARIANTS (carried by any of its samples), GENES (touched by those variants); a loaded cohort row wins. */
    public Map<String, Map<String, Object>> cohorts() {
        Map<String, Map<String, Object>> done = cohorts;
        if (done != null) {
            return done;
        }
        Snapshot s = now();
        Map<String, List<String>> members = new TreeMap<>();
        s.samples().values().forEach(x -> {
            String c = text(x, "COHORT");
            members.computeIfAbsent(c.isEmpty() ? "UNASSIGNED" : c, k -> new ArrayList<>()).add(text(x, "SAMPLE_ID"));
        });
        Map<String, Map<String, Object>> out = new TreeMap<>();
        members.forEach((c, ids) -> {
            List<Map<String, Object>> carried = s.variants().values().stream().filter(v -> carriers(v).stream().anyMatch(ids::contains)).toList();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("COHORT_ID", c);
            r.put("SAMPLES", ids.size());
            r.put("MEMBERS", List.copyOf(ids));
            r.put("VARIANTS", carried.size());
            r.put("GENES", List.copyOf(new TreeSet<>(carried.stream().map(v -> text(v, "GENE")).filter(x -> !x.isEmpty()).toList())));
            out.put(c, r);
        });
        s.cohortRows().forEach((id, row) -> {
            Map<String, Object> merged = new LinkedHashMap<>(out.getOrDefault(id, Map.of()));
            row.forEach((k, v) -> {
                if (v != null) {
                    merged.put(k, v);
                }
            });
            out.put(id, merged);
        });
        cohorts = out;
        return out;
    }

    /** The variants by position (chromosome as text, then position). */
    public static Comparator<Map<String, Object>> byPosition() {
        return Comparator.<Map<String, Object>, String>comparing(v -> text(v, "CHROM")).thenComparingDouble(v -> ShowcaseData.num(v.get("POS")));
    }
}
