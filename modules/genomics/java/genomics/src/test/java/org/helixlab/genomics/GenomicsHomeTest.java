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

import com.ash.drishti.kernel.api.home.HomeData;
import com.ash.drishti.kernel.api.home.HomeTable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The genomicist's Home (GenomicsHome): the figures and the five home tables over the module's own sample files, a small association table of the
 * showcase pack and the public feeds by their symbolic kinds; a kind that is not loaded is a table that says so, a VCF that fails validation is
 * a Needs-you row, and the tables are served to the genomicist's Home only.
 */
class GenomicsHomeTest {

    static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static GenomicsHome home(Path folder, boolean feeds) {
        GenomicsData data = new GenomicsData();
        data.load(List.of(Path.of("../../data/samples"), folder));
        ShowcaseData showcase = new ShowcaseData();
        List<Map<String, Object>> assoc = List.of(
                row("TRAIT", "RESPONSE", "VARIANT_ID", "HX-1", "CHROM", "7", "POS", 92_703_082L, "GENE", "HLXK7", "NEG_LOG10_P", 28.04),
                row("TRAIT", "TOXICITY", "VARIANT_ID", "HX-1", "CHROM", "7", "POS", 92_703_082L, "GENE", "HLXK7", "NEG_LOG10_P", 9.5),
                row("TRAIT", "RESPONSE", "VARIANT_ID", "HX-2", "CHROM", "17", "POS", 7_670_000L, "GENE", "TP53", "NEG_LOG10_P", 3.1),
                row("TRAIT", "RESPONSE", "VARIANT_ID", "HX-3", "CHROM", "X", "POS", 1_000_000L, "GENE", "HLXAA", "NEG_LOG10_P", 8.2));
        List<Map<String, Object>> clinvar = List.of(
                row("GENE", "TP53", "VARIANT_ID", "CV-9", "SIGNIFICANCE", "Pathogenic", "UPDATED", "2026-11-26", "BUSINESS_DATE", "2026-11-27"),
                row("GENE", "OTHER", "VARIANT_ID", "CV-1", "SIGNIFICANCE", "Benign", "UPDATED", "2026-11-26", "BUSINESS_DATE", "2026-11-27"));
        showcase.attach((kind, sink) -> {
            if (kind.equals("association")) {
                assoc.forEach(sink);
                return true;
            }
            return false;
        });
        GenomicsHome h = new GenomicsHome(data, showcase);
        h.attach((kind, sink) -> {
            if (feeds && kind.equals(GenomicsHome.CLINVAR)) {
                clinvar.forEach(sink);
                return true;
            }
            return false;
        });
        return h;
    }

    @Test
    void theFiguresAreCountsOfWhatIsLoadedAndTheCohortsAreSubjects(@TempDir Path tmp) {
        GenomicsHome h = home(tmp, true);
        var m = h.measures();
        assertThat(m.engine()).isEqualTo("genomics");
        assertThat(m.on()).isTrue();
        assertThat(m.kpi(null, "SAMPLES", Map.of(), LocalDate.now())).isEqualTo(OptionalDouble.of(h.measures().values(null, "SAMPLES", "cohort", null).stream().mapToDouble(HomeData.MeasureValue::value).sum()));
        assertThat(m.kpi(null, "HITS", Map.of(), LocalDate.now())).isEqualTo(OptionalDouble.of(2));            // HX-1 (two traits, once) and HX-3
        assertThat(m.kpi(null, "NOPE", Map.of(), LocalDate.now())).isEmpty();
        assertThat(m.values(null, "VARIANTS", "cohort", null)).isNotEmpty().allMatch(v -> v.subjectKind().equals("cohort") && v.value() > 0);
        assertThat(m.values(null, "VARIANTS", "gene", null)).isEmpty();
    }

    @Test
    void theAssociationTablesRankTheStrongestPerGeneAndDrawOnePointPerBin(@TempDir Path tmp) {
        GenomicsHome h = home(tmp, true);
        List<Map<String, Object>> top = h.gwasTop();
        assertThat(top).extracting(r -> r.get("GENE")).containsExactly("HLXK7", "HLXAA", "TP53");
        assertThat(top.get(0)).containsEntry("TRAIT", "RESPONSE").containsEntry("NEG_LOG10_P", 28.04);
        List<Map<String, Object>> plot = h.manhattan();
        assertThat(plot).hasSize(3);                                                                       // the chromosome 7 pair share a bin: its tallest stays
        assertThat(plot.stream().filter(p -> p.get("CHROM").equals("7")).findFirst().orElseThrow()).containsEntry("Y", 28.04);
        assertThat(plot).allMatch(p -> ((Number) p.get("X")).doubleValue() > 0);
    }

    @Test
    void clinvarShowsTheFollowedGenesOnlyAndALoadsTableSaysWhatIsNotLoaded(@TempDir Path tmp) {
        GenomicsHome h = home(tmp, true);
        assertThat(h.followed()).contains("TP53");
        assertThat(h.clinvar()).extracting(r -> r.get("VARIANT_ID")).containsExactly("CV-9");
        assertThat(h.loads()).anySatisfy(r -> assertThat(r).containsEntry("SOURCE", "ClinVar").containsEntry("DATE", "2026-11-27").containsEntry("STATE", "loaded, 2 rows"))
                .anySatisfy(r -> assertThat(r).containsEntry("SOURCE", "Ensembl").containsEntry("STATE", "not loaded"))
                .anySatisfy(r -> assertThat(r).containsEntry("SOURCE", "Own file").containsEntry("WHAT", "cohort-a.vcf").containsEntry("STATE", "read"));
        GenomicsHome none = home(tmp, false);
        assertThat(none.clinvar()).isEmpty();
        assertThat(none.loads()).anySatisfy(r -> assertThat(r).containsEntry("SOURCE", "ClinVar").containsEntry("STATE", "not loaded"));
    }

    @Test
    void aVcfThatFailsValidationIsANeedsYouRowNamingTheFile(@TempDir Path tmp) throws Exception {
        assertThat(home(tmp, false).needs()).isEmpty();
        Files.writeString(tmp.resolve("run7.vcf"), """
                ##fileformat=VCFv4.3
                ##SAMPLE=<ID=S1,Cohort=C1>
                #CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1
                17\t100\trs1\tA\tG\t50\tPASS\tGENE=TP53\tGT\t0/1
                bad line
                also bad
                """);
        GenomicsHome h = home(tmp, false);
        assertThat(h.needs()).hasSize(1);
        assertThat(h.needs().get(0).get("WHAT")).isEqualTo("VCF failed validation: run7.vcf");
        assertThat((String) h.needs().get(0).get("DETAIL")).startsWith("run7.vcf:").contains("and 1 more");
        assertThat(h.loads()).anySatisfy(r -> assertThat(r).containsEntry("WHAT", "run7.vcf").containsEntry("STATE", "failed validation"));
    }

    @Test
    void theTablesAreServedToTheGenomicistsHomeOnly(@TempDir Path tmp) {
        List<GenomicsHome.Table> tables = home(tmp, true).tables();
        assertThat(tables).extracting(HomeTable::name).containsExactly("genomics_gwas_top", "genomics_manhattan", "genomics_clinvar", "genomics_loads", "genomics_needs");
        assertThat(tables).allMatch(t -> t.homes().equals(List.of("genomicist")));
    }
}
