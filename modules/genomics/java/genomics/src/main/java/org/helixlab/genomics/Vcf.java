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

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A small reader of VCF 4.x text (the Variant Call Format): the {@code ##} meta lines (the {@code ##SAMPLE=<ID=..,Cohort=..>} lines name
 * each sample's cohort), the {@code #CHROM} header with the sample columns, then one record per line: CHROM, POS, ID, REF, ALT, QUAL,
 * FILTER, INFO ({@code KEY=VALUE;FLAG}), FORMAT and one genotype column per sample ({@code GT} first: {@code 0/1}, {@code 1|1}, {@code ./.}).
 * Multi-allelic records give one variant per ALT allele. Immutable results; no state between reads.
 */
public final class Vcf {

    /** One variant: a position and one alternate allele, with the samples that carry it. */
    public record Variant(String id, String chrom, long pos, String ref, String alt, Double qual, String filter, Map<String, String> info,
            Map<String, String> genotypes) {

        /** The samples carrying the allele (a genotype with a non-reference call). */
        public List<String> carriers() {
            List<String> out = new ArrayList<>();
            genotypes.forEach((s, gt) -> {
                if (gt.chars().anyMatch(c -> c >= '1' && c <= '9')) {
                    out.add(s);
                }
            });
            return out;
        }
    }

    /** One sample column and what its {@code ##SAMPLE} line says of it. */
    public record Sample(String id, Map<String, String> meta) {
    }

    /** One file read. */
    public record File(String name, List<Sample> samples, List<Variant> variants, List<String> problems) {
    }

    private Vcf() {
    }

    /** Reads a whole VCF; a line that does not read is skipped and named in {@code problems}. */
    public static File read(String name, BufferedReader in) throws IOException {
        List<String> columns = List.of();
        Map<String, Map<String, String>> sampleMeta = new LinkedHashMap<>();
        List<Variant> variants = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int lineNo = 0;
        for (String line = in.readLine(); line != null; line = in.readLine()) {
            lineNo++;
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("##SAMPLE=<") && line.endsWith(">")) {
                Map<String, String> m = pairs(line.substring("##SAMPLE=<".length(), line.length() - 1), ',');
                sampleMeta.put(m.getOrDefault("ID", ""), m);
            } else if (line.startsWith("#CHROM")) {
                columns = List.of(line.substring(1).split("\t"));
            } else if (!line.startsWith("#")) {
                String[] f = line.split("\t");
                if (f.length < 8 || columns.isEmpty()) {
                    problems.add(name + ":" + lineNo + ": a record needs the #CHROM header and 8 columns");
                    continue;
                }
                try {
                    variants.addAll(record(f, columns));
                } catch (RuntimeException e) {
                    problems.add(name + ":" + lineNo + ": " + e.getMessage());
                }
            }
        }
        List<Sample> samples = new ArrayList<>();
        for (int i = 9; i < columns.size(); i++) {
            samples.add(new Sample(columns.get(i), sampleMeta.getOrDefault(columns.get(i), Map.of())));
        }
        return new File(name, List.copyOf(samples), List.copyOf(variants), List.copyOf(problems));
    }

    private static List<Variant> record(String[] f, List<String> columns) {
        String chrom = f[0];
        long pos = Long.parseLong(f[1]);
        Double qual = ".".equals(f[5]) ? null : Double.valueOf(f[5]);
        Map<String, String> info = pairs(f[7], ';');
        String[] format = f.length > 8 ? f[8].split(":") : new String[0];
        Map<String, String> gts = new LinkedHashMap<>();
        for (int i = 9; i < f.length && i < columns.size(); i++) {
            String[] v = f[i].split(":");
            gts.put(columns.get(i), format.length > 0 && "GT".equals(format[0]) ? v[0] : ".");
        }
        String[] alts = f[4].split(",");
        List<Variant> out = new ArrayList<>();
        for (int a = 0; a < alts.length; a++) {
            String id = ".".equals(f[2]) ? chrom + "-" + pos + "-" + f[3] + "-" + alts[a] : (alts.length > 1 ? f[2] + "-" + alts[a] : f[2]);
            Map<String, String> gtOfAllele = new LinkedHashMap<>();
            int allele = a + 1;
            gts.forEach((s, gt) -> gtOfAllele.put(s, carries(gt, allele) ? gt : gt.replaceAll("[1-9]", "0")));
            out.add(new Variant(id, chrom, pos, f[3], alts[a], qual, f[6], info, gtOfAllele));
        }
        return out;
    }

    private static boolean carries(String gt, int allele) {
        for (String x : gt.split("[/|]")) {
            if (x.equals(String.valueOf(allele))) {
                return true;
            }
        }
        return false;
    }

    /** {@code A=1;B=x;FLAG} (or comma separated) as a map; a flag maps to {@code "true"}; keys upper case. */
    static Map<String, String> pairs(String text, char sep) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null || text.isBlank() || ".".equals(text)) {
            return out;
        }
        for (String p : text.split(java.util.regex.Pattern.quote(String.valueOf(sep)))) {
            int eq = p.indexOf('=');
            String k = (eq < 0 ? p : p.substring(0, eq)).strip().toUpperCase(Locale.ROOT);
            if (!k.isEmpty()) {
                out.put(k, eq < 0 ? "true" : p.substring(eq + 1).strip());
            }
        }
        return out;
    }
}
