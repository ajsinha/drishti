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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * What the module knows, read once and kept as one immutable snapshot (swapped whole on a reload, so two people never see half of one):
 * the variants and samples of every VCF file (the module's synthetic samples, {@code <module>/data/samples/*.vcf}, and a site's own files
 * in {@code <data>/modules/genomics/vcf/}, plain or gzipped), the genes (JSONL, {@code data/samples/gene.jsonl}) and the cohorts, computed
 * from the samples' {@code ##SAMPLE} lines.
 */
public final class GenomicsData {

    /** One read of every file. */
    public record Snapshot(Map<String, Vcf.Variant> variants, Map<String, Vcf.Sample> samples, Map<String, Map<String, Object>> genes,
            List<String> files, List<String> problems) {

        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Map.of(), List.of(), List.of());
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    /** Reads every VCF and gene file under the folders (a missing folder holds nothing); the snapshot is replaced whole. */
    public Snapshot load(List<Path> folders) {
        Map<String, Vcf.Variant> variants = new TreeMap<>();
        Map<String, Vcf.Sample> samples = new TreeMap<>();
        Map<String, Map<String, Object>> genes = new TreeMap<>();
        List<String> files = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Path folder : folders) {
            if (folder == null || !Files.isDirectory(folder)) {
                continue;
            }
            try (Stream<Path> s = Files.list(folder)) {
                for (Path f : s.sorted(Comparator.comparing(Path::toString)).toList()) {
                    String n = f.getFileName().toString();
                    if (n.endsWith(".vcf") || n.endsWith(".vcf.gz")) {
                        try (BufferedReader in = reader(f)) {
                            Vcf.File v = Vcf.read(n, in);
                            v.variants().forEach(x -> variants.put(x.id(), x));
                            v.samples().forEach(x -> samples.put(x.id(), x));
                            problems.addAll(v.problems());
                            files.add(n);
                        }
                    } else if (n.equals("gene.jsonl") || n.equals("gene.jsonl.gz")) {
                        try (BufferedReader in = reader(f)) {
                            for (String line = in.readLine(); line != null; line = in.readLine()) {
                                if (!line.isBlank()) {
                                    JsonNode j = JSON.readTree(line);
                                    Map<String, Object> row = JSON.convertValue(j, JSON.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
                                    genes.put(j.path("GENE").asText(), row);
                                }
                            }
                            files.add(n);
                        }
                    }
                }
            } catch (IOException e) {
                problems.add(folder.getFileName() + ": " + e.getMessage());
            }
        }
        Snapshot s = new Snapshot(Map.copyOf(variants), Map.copyOf(samples), Map.copyOf(genes), List.copyOf(files), List.copyOf(problems));
        snapshot = s;
        return s;
    }

    /** The data as last read. */
    public Snapshot now() {
        return snapshot;
    }

    /** Forgets everything (the module stopping). */
    public void clear() {
        snapshot = Snapshot.EMPTY;
    }

    // ---- the rows of each kind ----------------------------------------------------------------------------------------------------

    /** A variant's row: VARIANT_ID, CHROM, POS, REF, ALT, QUAL, FILTER, GENE, CONSEQUENCE, IMPACT, PROTEIN_POS, AF, CARRIERS, SAMPLES. */
    public static Map<String, Object> variantRow(Vcf.Variant v) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("VARIANT_ID", v.id());
        r.put("CHROM", v.chrom());
        r.put("POS", v.pos());
        r.put("REF", v.ref());
        r.put("ALT", v.alt());
        r.put("QUAL", v.qual());
        r.put("FILTER", v.filter());
        r.put("GENE", v.info().getOrDefault("GENE", ""));
        r.put("CONSEQUENCE", v.info().getOrDefault("CSQ", ""));
        r.put("IMPACT", v.info().getOrDefault("IMPACT", ""));
        r.put("PROTEIN_POS", number(v.info().get("AAPOS")));
        List<String> carriers = v.carriers();
        r.put("CARRIERS", carriers.size());
        r.put("AF", v.genotypes().isEmpty() ? null : Math.round(1e4 * carriers.size() / (double) v.genotypes().size()) / 1e4);
        r.put("SAMPLES", carriers);
        return r;
    }

    /** A sample's row: SAMPLE_ID, COHORT, SEX, POPULATION, VARIANTS (the variants it carries). */
    public Map<String, Object> sampleRow(Vcf.Sample s) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("SAMPLE_ID", s.id());
        r.put("COHORT", s.meta().getOrDefault("COHORT", ""));
        r.put("SEX", s.meta().getOrDefault("SEX", ""));
        r.put("POPULATION", s.meta().getOrDefault("POPULATION", ""));
        r.put("VARIANTS", snapshot.variants().values().stream().filter(v -> v.carriers().contains(s.id())).count());
        return r;
    }

    /** A gene's row: the gene file's fields and VARIANTS (how many variants fall in it). */
    public Map<String, Object> geneRow(String gene) {
        Map<String, Object> g = snapshot.genes().get(gene);
        if (g == null) {
            return null;
        }
        Map<String, Object> r = new LinkedHashMap<>(g);
        r.put("VARIANTS", snapshot.variants().values().stream().filter(v -> gene.equals(v.info().get("GENE"))).count());
        return r;
    }

    /** The cohorts, computed: COHORT_ID, SAMPLES, VARIANTS (carried by any of its samples), GENES (touched by those variants). */
    public Map<String, Map<String, Object>> cohorts() {
        Map<String, List<String>> members = new TreeMap<>();
        snapshot.samples().values().forEach(s -> members.computeIfAbsent(s.meta().getOrDefault("COHORT", "UNASSIGNED"), k -> new ArrayList<>()).add(s.id()));
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        members.forEach((c, ids) -> {
            List<Vcf.Variant> carried = snapshot.variants().values().stream().filter(v -> v.carriers().stream().anyMatch(ids::contains)).toList();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("COHORT_ID", c);
            r.put("SAMPLES", ids.size());
            r.put("MEMBERS", List.copyOf(ids));
            r.put("VARIANTS", carried.size());
            r.put("GENES", carried.stream().map(v -> v.info().getOrDefault("GENE", "")).filter(x -> !x.isEmpty()).distinct().sorted().toList());
            out.put(c, r);
        });
        return out;
    }

    private static Integer number(String s) {
        try {
            return s == null ? null : Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BufferedReader reader(Path f) throws IOException {
        InputStream in = Files.newInputStream(f);
        if (f.getFileName().toString().endsWith(".gz")) {
            in = new GZIPInputStream(in);
        }
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
}
