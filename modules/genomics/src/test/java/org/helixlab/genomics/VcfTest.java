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

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VcfTest {

    @Test
    void readsRecordsSamplesAndOneVariantPerAllele() throws Exception {
        String text = """
                ##fileformat=VCFv4.3
                ##SAMPLE=<ID=S1,Cohort=C1,Sex=F>
                ##SAMPLE=<ID=S2,Cohort=C1,Sex=M>
                #CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS1\tS2
                17\t100\trs1\tA\tG,T\t50\tPASS\tGENE=TP53;CSQ=missense_variant;IMPACT=MODERATE;AAPOS=12\tGT:DP\t0/1:9\t0/2:7
                17\t200\t.\tC\tT\t.\tPASS\tGENE=TP53\tGT\t0/0\t./.
                bad line
                """;
        Vcf.File f = Vcf.read("t.vcf", new BufferedReader(new StringReader(text)));
        assertThat(f.samples()).extracting(Vcf.Sample::id).containsExactly("S1", "S2");
        assertThat(f.samples().get(0).meta()).containsEntry("COHORT", "C1");
        assertThat(f.variants()).extracting(Vcf.Variant::id).containsExactly("rs1-G", "rs1-T", "17-200-C-T");
        assertThat(f.variants().get(0).carriers()).containsExactly("S1");
        assertThat(f.variants().get(1).carriers()).containsExactly("S2");
        assertThat(f.variants().get(2).carriers()).isEmpty();
        assertThat(f.problems()).hasSize(1);
        Map<String, Object> row = GenomicsData.variantRow(f.variants().get(0));
        assertThat(row).containsEntry("GENE", "TP53").containsEntry("PROTEIN_POS", 12).containsEntry("CARRIERS", 1).containsEntry("AF", 0.5);
    }

    @Test
    void theShippedSamplesReadIntoVariantsGenesAndTwoCohorts() {
        GenomicsData d = new GenomicsData();
        GenomicsData.Snapshot s = d.load(List.of(Path.of("src/dmod/data/samples")));
        assertThat(s.problems()).isEmpty();
        assertThat(s.variants()).hasSize(120).containsKey("HX-A001");
        assertThat(s.samples()).hasSize(24);
        assertThat(s.genes()).containsKeys("TP53", "BRCA1");
        assertThat(d.cohorts()).containsOnlyKeys("COH-A", "COH-B");
        assertThat(d.cohorts().get("COH-A")).containsEntry("SAMPLES", 12);
        assertThat(GenomicsFunctions.afClass(0.004)).isEqualTo("rare");
        assertThat(GenomicsFunctions.afClass("0.2")).isEqualTo("common");
    }
}
