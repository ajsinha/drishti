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

import com.ash.drishti.kernel.api.module.ModuleApi;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The module's REST API under {@code /api/v1/genomics/}: what its pages draw (rows, never a layout). */
@RestController
@ModuleApi("genomics")
public class GenomicsController {

    private final GenomicsData data;

    public GenomicsController(GenomicsData data) {
        this.data = data;
    }

    /** The variants, optionally of one gene or carried in one cohort, by position. */
    @GetMapping("/variants")
    public Map<String, Object> variants(@RequestParam(name = "gene", required = false) String gene,
            @RequestParam(name = "cohort", required = false) String cohort) {
        List<String> members = cohort == null ? null : members(cohort);
        List<Map<String, Object>> rows = new ArrayList<>();
        data.now().variants().values().stream()
                .filter(v -> gene == null || gene.equalsIgnoreCase(v.info().getOrDefault("GENE", "")))
                .filter(v -> members == null || v.carriers().stream().anyMatch(members::contains))
                .sorted(Comparator.comparing(Vcf.Variant::chrom).thenComparingLong(Vcf.Variant::pos))
                .forEach(v -> rows.add(GenomicsData.variantRow(v)));
        return Map.of("rows", rows, "count", rows.size());
    }

    @GetMapping("/variants/{id}")
    public ResponseEntity<Map<String, Object>> variant(@PathVariable("id") String id) {
        Vcf.Variant v = data.now().variants().get(id);
        return v == null ? missing("variant", id) : ResponseEntity.ok(GenomicsData.variantRow(v));
    }

    @GetMapping("/genes")
    public Map<String, Object> genes() {
        List<Map<String, Object>> rows = data.now().genes().keySet().stream().sorted().map(data::geneRow).toList();
        return Map.of("rows", rows, "count", rows.size());
    }

    /** A gene with its variants (the lollipop's rows). */
    @GetMapping("/genes/{id}")
    public ResponseEntity<Map<String, Object>> gene(@PathVariable("id") String id) {
        Map<String, Object> g = data.geneRow(id);
        if (g == null) {
            return missing("gene", id);
        }
        Map<String, Object> out = new LinkedHashMap<>(g);
        out.put("variants", variants(id, null).get("rows"));
        return ResponseEntity.ok(out);
    }

    @GetMapping("/cohorts")
    public Map<String, Object> cohorts() {
        List<Map<String, Object>> rows = List.copyOf(data.cohorts().values());
        return Map.of("rows", rows, "count", rows.size());
    }

    @GetMapping("/cohorts/{id}")
    public ResponseEntity<Map<String, Object>> cohort(@PathVariable("id") String id) {
        Map<String, Object> c = data.cohorts().get(id);
        if (c == null) {
            return missing("cohort", id);
        }
        Map<String, Object> out = new LinkedHashMap<>(c);
        out.put("variants", variants(null, id).get("rows"));
        return ResponseEntity.ok(out);
    }

    private List<String> members(String cohort) {
        Map<String, Object> c = data.cohorts().get(cohort);
        List<String> out = new ArrayList<>();
        if (c != null && c.get("MEMBERS") instanceof List<?> l) {
            l.forEach(x -> out.add(String.valueOf(x)));
        }
        return out;
    }

    private static ResponseEntity<Map<String, Object>> missing(String kind, String id) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "DRS-1001", "detail", "no " + kind + " " + id));
    }
}
