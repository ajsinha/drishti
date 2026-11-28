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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * The screens answer from the lake: the module's variants, genes and cohorts are the rows of the kinds variant, sample, gene (and cohort)
 * the kernel's bindings give, here the demo lake's own rows (data/testdata/genomics: what {@code lake demo} builds into genomicslake), and the
 * module reads no file of any kind: nothing in its main code opens a path or a VCF.
 */
class GenomicsDataTest {

    /** The base folder of the Maven project (surefire sets basedir). */
    static Path base() {
        return Path.of(System.getProperty("basedir", ".")).toAbsolutePath();
    }

    /** The demo lake's rows of each kind, as the kernel's bindings give them (the committed test data stands for the lake). */
    static ShowcaseData.Source demoLake() {
        Path root = base().resolve("../../../../data/testdata/genomics").normalize();
        ObjectMapper json = new ObjectMapper();
        return (kind, sink) -> {
            Path folder = root.resolve(kind);
            if (!Files.isDirectory(folder)) {
                return false;
            }
            try (Stream<Path> files = Files.list(folder)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".jsonl.gz")).sorted().toList()) {
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(f)), StandardCharsets.UTF_8))) {
                        for (String line = in.readLine(); line != null; line = in.readLine()) {
                            if (!line.isBlank()) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> row = json.readValue(line, LinkedHashMap.class);
                                sink.accept(row);
                            }
                        }
                    }
                }
                return true;
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
    }

    static GenomicsData data() {
        GenomicsData d = new GenomicsData();
        d.attach(demoLake());
        return d;
    }

    @Test
    void theDemoLakesRowsAreTheModulesVariantsSamplesGenesAndTwoCohorts() {
        GenomicsData d = data();
        GenomicsData.Snapshot s = d.now();
        assertThat(s.missing()).isEmpty();
        assertThat(s.variants()).hasSize(120).containsKey("HX-A001");
        assertThat(s.samples()).hasSize(24);
        assertThat(s.genes()).containsKeys("TP53", "BRCA1");
        assertThat(d.cohorts()).containsOnlyKeys("COH-A", "COH-B");
        assertThat(d.cohorts().get("COH-A")).containsEntry("SAMPLES", 12);
        assertThat(d.geneRow("TP53")).containsKeys("DOMAINS", "PROTEIN_LENGTH").containsKey("VARIANTS");
        assertThat(GenomicsFunctions.afClass(0.004)).isEqualTo("rare");
    }

    @Test
    void theApiAnswersFromTheLakeRows() {
        GenomicsController api = new GenomicsController(data());
        Map<String, Object> all = api.variants(null, null);
        assertThat(all).containsEntry("count", 120);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) all.get("rows");
        assertThat(rows).allSatisfy(r -> assertThat(r).containsKeys("VARIANT_ID", "CHROM", "POS", "GENE", "CARRIERS", "SAMPLES"));
        assertThat(rows.stream().map(r -> String.valueOf(r.get("CHROM"))).toList()).isSorted();
        assertThat((int) api.variants("tp53", null).get("count")).isPositive().isLessThan(120);
        Map<String, Object> coh = api.variants(null, "COH-A");
        assertThat((int) coh.get("count")).isPositive().isLessThanOrEqualTo(120);
        assertThat(api.variant("HX-A001").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(api.variant("nope").getStatusCode().value()).isEqualTo(404);
        assertThat(api.gene("TP53").getBody()).containsKey("variants");
        assertThat(api.cohort("COH-B").getBody()).containsEntry("SAMPLES", 12);
        assertThat(api.genes()).containsEntry("count", 8);
        assertThat(api.cohorts()).containsEntry("count", 2);
    }

    @Test
    void aLoadedCohortRowAddsToTheComputedOneAndAKindWithNoRowsIsSaid() {
        GenomicsData d = new GenomicsData();
        ShowcaseData.Source lake = demoLake();
        d.attach((kind, sink) -> {
            if (kind.equals("cohort")) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("COHORT_ID", "COH-A");
                r.put("TITLE", "loaded");
                sink.accept(r);
                return true;
            }
            return !kind.equals("gene") && lake.rows(kind, sink);
        });
        assertThat(d.cohorts().get("COH-A")).containsEntry("TITLE", "loaded").containsEntry("SAMPLES", 12);
        assertThat(d.now().missing()).containsExactly("gene");
        assertThat(new GenomicsData().now().missing()).containsExactly("variant", "sample", "gene");
    }

    @Test
    void nothingInTheModulesCodeReadsAFileOrTheOldVcfFolder() throws Exception {
        Path main = base().resolve("src/main/java/org/helixlab/genomics");
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> s = Files.list(main)) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
        }
        assertThat(sources).isNotEmpty();
        for (Path f : sources) {
            String text = Files.readString(f);
            assertThat(text).as(f.getFileName() + " reads no file").doesNotContain("java.nio.file").doesNotContain("java.io.File")
                    .doesNotContain("GZIPInputStream");
            assertThat(text).as(f.getFileName() + " knows no vcf folder").doesNotContain("modules/genomics/vcf").doesNotContain("\"vcf\"")
                    .doesNotContain("SourcePlugin");
        }
        assertThat(main.resolve("Vcf.java")).doesNotExist();
        assertThat(main.resolve("VcfConnector.java")).doesNotExist();
    }
}
