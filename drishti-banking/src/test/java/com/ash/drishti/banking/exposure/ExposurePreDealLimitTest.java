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
package com.ash.drishti.banking.exposure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The pre-deal check's limit use without a server (EXPOSURE_GUIDE.md section 18.9): within, near (from the near share of the limit or
 * of a tenor bucket's limit) and breach, with the headroom before and after; and the IMM EAD (alpha x EEPE) where the answer has EEPE.
 */
class ExposurePreDealLimitTest {

    private static final ExposurePreDeal.Measure PFE = ExposurePreDeal.Measure.of("PFE 95 peak", 0.95);

    /** A what-if answer: the peak PFE before and after, and a profile of three dates (0.25, 0.5 and 2 years). */
    private static Map<String, Object> whatIf(double before, double after, double[] pfeBase, double[] pfe) {
        double[] t = {0.25, 0.5, 2.0};
        List<Map<String, Object>> prof = new java.util.ArrayList<>();
        for (int i = 0; i < t.length; i++) {
            prof.add(Map.of("t", t[i], "pfeBase", pfeBase[i], "pfe", pfe[i], "eeBase", 0.0, "ee", 0.0));
        }
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("base", Map.of("peakPfe", before));
        w.put("with", Map.of("peakPfe", after));
        w.put("incremental", Map.of("peakPfe", after - before, "profile", prof));
        return w;
    }

    @Test
    void withinNearAndBreachOnTheLimit() {
        ExposureDate.Limit lim = new ExposureDate.Limit("LIM-1", 100.0, "PFE 95 peak");
        double[] z = {0, 0, 0};
        Map<String, Object> within = ExposurePreDeal.limitUse(lim, PFE, whatIf(50, 70, z, z), 0.8);
        assertThat(within.get("status")).isEqualTo("within");
        assertThat(within.get("headroomBefore")).isEqualTo(50L);
        assertThat(within.get("headroomAfter")).isEqualTo(30L);
        assertThat(within.get("increment")).isEqualTo(20L);
        assertThat(within.get("near")).isEqualTo(0.8);
        Map<String, Object> near = ExposurePreDeal.limitUse(lim, PFE, whatIf(50, 80, z, z), 0.8);
        assertThat(near.get("status")).isEqualTo("near");                       // at the near share exactly: near
        assertThat(near.get("breachText")).isNull();
        Map<String, Object> breach = ExposurePreDeal.limitUse(lim, PFE, whatIf(50, 101, z, z), 0.8);
        assertThat(breach.get("status")).isEqualTo("breach");
        assertThat(breach.get("headroomAfter")).isEqualTo(-1L);
        assertThat((String) breach.get("breachText")).contains("over the limit 100");
        assertThat(ExposurePreDeal.limitUse(lim, PFE, whatIf(50, 80, z, z), 0.9).get("status")).isEqualTo("within");   // the setting moves it
    }

    @Test
    void aBucketNearItsLimitMakesTheCheckNear() {
        ExposureDate.Limit lim = new ExposureDate.Limit("LIM-1", 1000.0, "PFE 95 peak",
                List.of(ExposureDate.Bucket.of("0-1Y", 100), ExposureDate.Bucket.of("1Y+", 1000)));
        Map<String, Object> l = ExposurePreDeal.limitUse(lim, PFE, whatIf(60, 90, new double[] {40, 60, 30}, new double[] {70, 90, 35}), 0.8);
        assertThat(l.get("status")).isEqualTo("near");                           // 9% of the limit, but the 0-1Y bucket at 90%
        assertThat((String) l.get("nearText")).contains("0-1Y").contains("90.0%");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> b = (List<Map<String, Object>>) l.get("buckets");
        assertThat(b.get(0)).containsEntry("status", "near").containsEntry("before", 60L).containsEntry("after", 90L).containsEntry("increment", 30L)
                .containsEntry("headroomAfter", 10L);
        assertThat(b.get(1)).containsEntry("status", "within").containsEntry("after", 35L);
        Map<String, Object> over = ExposurePreDeal.limitUse(lim, PFE, whatIf(60, 110, new double[] {40, 60, 30}, new double[] {70, 110, 35}), 0.8);
        assertThat(over.get("status")).isEqualTo("breach");
        assertThat((String) over.get("breachText")).contains("0-1Y");
    }

    @Test
    void eadIsAlphaTimesEepeWhereTheAnswerHasEepe() {
        Map<String, Object> exp = new LinkedHashMap<>();
        exp.put("base", Map.of("eepe", 100L, "peakPfe", 300L));
        exp.put("with", Map.of("eepe", 150L, "peakPfe", 330L));
        exp.put("incremental", Map.of("eepe", 50L));
        ExposurePreDeal.ead(exp, 1.4);
        assertThat((Map<?, ?>) exp.get("base")).containsEntry("ead", 140L);
        assertThat((Map<?, ?>) exp.get("with")).containsEntry("ead", 210L);
        assertThat((Map<?, ?>) exp.get("incremental")).containsEntry("ead", 70L);
        assertThat(exp.get("alpha")).isEqualTo(1.4);
        Map<String, Object> proxy = new LinkedHashMap<>();                       // the proxy has no EEPE: no EAD, left out
        proxy.put("base", Map.of("peakPfe", 300L));
        proxy.put("with", Map.of("peakPfe", 330L));
        ExposurePreDeal.ead(proxy, 1.4);
        assertThat((Map<?, ?>) proxy.get("with")).doesNotContainKey("ead");
        assertThat(proxy).doesNotContainKey("alpha");
    }
}
