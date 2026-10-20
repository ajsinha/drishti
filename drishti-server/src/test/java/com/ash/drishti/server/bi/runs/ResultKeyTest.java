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
package com.ash.drishti.server.bi.runs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The result key (docs/architecture/BI_RUNS.md section 8.2, test 19.1 RunStoreTest's key cases): the same five parts give the same key in any order
 * of reading; each part changes it; a restated date makes a new key; a source without a data version is never reusable.
 */
class ResultKeyTest {

    static final LocalDate D1 = LocalDate.of(2026, 9, 29);
    static final LocalDate D2 = LocalDate.of(2026, 9, 30);

    static DataVersionVector vector(String v2) {
        return new DataVersionVector().put("trade-endur", "trade", D1, "v410").put("trade-endur", "trade", D2, v2).put("desks", "desk", D2, "l-07:31");
    }

    static ResultKey key(String plan, List<LocalDate> dates, DataVersionVector v, String sig, String engine) {
        return ResultKey.of(plan, dates, v, sig, engine);
    }

    @Test
    void theSamePartsGiveTheSameKeyWhateverTheOrderTheyWereRead() {
        DataVersionVector other = new DataVersionVector().put("desks", "desk", D2, "l-07:31").put("trade-endur", "trade", D2, "v412")
                .put("trade-endur", "trade", D1, "v410");
        ResultKey a = key("p_1", List.of(D1, D2), vector("v412"), "sig-a", "v1");
        ResultKey b = key("p_1", List.of(D2, D1, D2), other, "sig-a", "v1");
        assertThat(a).isEqualTo(b);
        assertThat(a.value()).hasSize(64);
        assertThat(a.folder()).isEqualTo(a.value().substring(0, 2) + "/" + a.value());
        assertThat(a.reusable()).isTrue();
        assertThat(a.dataVersion()).startsWith("dv_").isEqualTo(other.hash());
        assertThat(ResultKey.of("p_1", List.of(D1, D2), vector("v412"), "sig-a")).isEqualTo(a);
    }

    @Test
    void eachPartChangesTheKey() {
        ResultKey base = key("p_1", List.of(D1, D2), vector("v412"), "sig-a", "v1");
        assertThat(key("p_2", List.of(D1, D2), vector("v412"), "sig-a", "v1").value()).isNotEqualTo(base.value());
        assertThat(key("p_1", List.of(D2), vector("v412"), "sig-a", "v1").value()).isNotEqualTo(base.value());
        assertThat(key("p_1", List.of(D1, D2), vector("v413"), "sig-a", "v1").value()).isNotEqualTo(base.value());   // a restated date
        assertThat(key("p_1", List.of(D1, D2), vector("v412"), "sig-b", "v1").value()).isNotEqualTo(base.value());
        assertThat(key("p_1", List.of(D1, D2), vector("v412"), "sig-a", "v2").value()).isNotEqualTo(base.value());
    }

    @Test
    void aSourceWithoutADataVersionIsNeverReusable() {
        DataVersionVector v = vector("v412").put("feeds", "rate", D2, Optional.empty());
        ResultKey k = ResultKey.of("p_1", List.of(D2), v, "sig");
        assertThat(v.complete()).isFalse();
        assertThat(k.reusable()).isFalse();
        assertThat(v.text()).contains("feeds/rate/2026-09-30=?;");
        assertThat(new DataVersionVector().complete()).isFalse();
        assertThat(new DataVersionVector().put("c", "k", D1, " ").complete()).isFalse();
    }

    @Test
    void aRunKeyedWithoutAVersionStoresNoReusableKey() {
        Run r = RunStateTest.planned();
        Run keyed = r.keyed(ResultKey.of("p_1", List.of(D2), vector("v412"), "sig"));
        assertThat(keyed.resultKey()).hasSize(64);
        assertThat(keyed.dataVersion()).startsWith("dv_");
        Run unkeyed = r.keyed(ResultKey.of("p_1", List.of(D2), vector("v412").put("feeds", "rate", D2, (String) null), "sig"));
        assertThat(unkeyed.resultKey()).isNull();
        assertThat(unkeyed.dataVersion()).startsWith("dv_");
    }
}
