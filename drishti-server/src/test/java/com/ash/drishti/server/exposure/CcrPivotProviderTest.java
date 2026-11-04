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
package com.ash.drishti.server.exposure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.compute.ccr.ExposureMath;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The CCR cube's provider without a server: the tenor buckets (decision 14's three by default, (from, to] in years, a bad one refused in
 * words), a profile's peak inside a bucket, the cell's scope in the exposure engine's grammar, and COUNTERPARTY_ENGINES.md case X2: a group
 * of two netting sets whose PFE 75% is the quantile of the paths summed (3), not the sum of the members' peaks (2), the statistics the
 * provider reads from the exposure engine for every cell.
 */
class CcrPivotProviderTest {

    @Test
    void theDefaultBucketsAreTheThreeOfDecision14() {
        List<CcrCubeProperties.Bucket> b = CcrCubeProperties.defaults().parsed();
        assertThat(b).extracting(CcrCubeProperties.Bucket::label).containsExactly("0-1Y", "1-5Y", "5Y+");
        assertThat(b.get(0).holds(1.0)).isTrue();
        assertThat(b.get(1).holds(1.0)).isFalse();
        assertThat(b.get(1).holds(5.0)).isTrue();
        assertThat(b.get(2).holds(30.0)).isTrue();
        assertThat(b.get(0).holds(0.0)).as("(from, to]: today is in no bucket").isFalse();
        assertThat(CcrCubeProperties.defaults().warningAt()).isEqualTo(0.8);
    }

    @Test
    void aBadBucketIsRefusedInWords() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ODD", "5-1");
        assertThatThrownBy(() -> new CcrCubeProperties(m, null, null, null).parsed()).hasMessageContaining("drishti.cube.ccr.buckets.ODD")
                .hasMessageContaining("from-to in years");
    }

    @Test
    void aProfilesPeakInsideABucket() {
        double[] pfe = {1, 3, 1};
        assertThat(CcrPivotProvider.peak(pfe, null)).isEqualTo(3);
        assertThat(CcrPivotProvider.peak(pfe, new int[] {0})).isEqualTo(1);
        assertThat(CcrPivotProvider.peak(pfe, new int[] {1, 2})).isEqualTo(3);
        assertThat(CcrPivotProvider.peak(pfe, new int[] {})).isEqualTo(0);
    }

    @Test
    void aCellsScopeIsTheExposureEnginesWord() {
        assertThat(FvaPivotProvider.scope(Map.of())).isEqualTo("BANK");
        assertThat(FvaPivotProvider.scope(Map.of("COUNTERPARTY", "CP-1"))).isEqualTo("CPTY:CP-1");
        assertThat(FvaPivotProvider.scope(Map.of("GROUP", "GRP-1"))).isEqualTo("GROUP:GRP-1");
        assertThat(FvaPivotProvider.scope(Map.of("NETTING_SET", "NS-X", "COUNTERPARTY", "CP-1"))).isNull();
    }

    @Test
    void caseX2AGroupsPfeIsTheQuantileOfItsSummedPathsNotTheSumOfPeaks() {
        double[] g1 = {4, 0, 1, 0};
        double[] g2 = {0, 3, 1, 0};
        double[] sum = new double[4];
        for (int j = 0; j < 4; j++) {
            sum[j] = g1[j] + g2[j];
        }
        double[] times = {1.0};
        double[] qs = {0.75};
        ExposureMath.Stats s1 = ExposureMath.statistics(g1, new double[4], null, times, 4, 1.0, qs, 0, ExposureMath.Rule.KTH);
        ExposureMath.Stats s2 = ExposureMath.statistics(g2, new double[4], null, times, 4, 1.0, qs, 0, ExposureMath.Rule.KTH);
        ExposureMath.Stats g = ExposureMath.statistics(sum, new double[4], null, times, 4, 1.0, qs, 0, ExposureMath.Rule.KTH);
        assertThat(s1.peakPfe[0] + s2.peakPfe[0]).isEqualTo(2);
        assertThat(g.peakPfe[0]).isEqualTo(3);
        assertThat(g.ee[0]).isEqualTo(s1.ee[0] + s2.ee[0]);                                       // EE adds: 2.25
    }
}
