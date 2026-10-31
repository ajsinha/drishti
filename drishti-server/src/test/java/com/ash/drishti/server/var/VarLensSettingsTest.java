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
package com.ash.drishti.server.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.compute.var.VarLens;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The settings of the horizons and confidences (2.9.7, VAR_GUIDE.md section 18.6): {@code ten-day-method} (sqrt10 by default),
 * {@code confidences} and {@code es-confidences} (the default always offered, sorted, checked), a lens asked for in words or numbers,
 * the parameters of {@code var-result}, and a var-limit row's horizon and confidence.
 */
class VarLensSettingsTest {

    private static VarProperties props(String method, List<Double> confs, List<Double> esConfs) {
        return new VarProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, method, confs, esConfs);
    }

    @Test
    void theDefaults() {
        VarProperties p = props(null, null, null);
        assertThat(p.tenDayMethod()).isEqualTo("sqrt10");
        assertThat(p.confidences()).containsExactly(0.95, 0.975, 0.99, 0.995);
        assertThat(p.esConfidences()).containsExactly(0.95, 0.975, 0.99);
        assertThat(p.defaultLens()).isEqualTo(VarLens.oneDay(0.99, 0.975));
        assertThat(p.lens(null, null, null, null)).isEqualTo(p.defaultLens());
        assertThat(props("sum", null, null).lens("10d", null, null, null).method()).isEqualTo(VarLens.Method.SUM);
        assertThat(props("SQRT", List.of(0.95), null).confidences()).containsExactly(0.95, 0.99);   // the default always in
        assertThatThrownBy(() -> props("full", null, null)).hasMessageContaining("sqrt10");
        assertThatThrownBy(() -> props(null, List.of(1.5), null)).hasMessageContaining("not in (0.5, 1)");
    }

    @Test
    void aLensAskedFor() {
        VarProperties p = props(null, null, null);
        assertThat(p.lens("10D", "SUM", 99.0, 97.5)).isEqualTo(new VarLens(10, VarLens.Method.SUM, 0.99, 0.975));
        assertThat(p.lens("10d", null, 0.95, null)).isEqualTo(new VarLens(10, VarLens.Method.SQRT, 0.95, 0.975));
        assertThat(p.lens(null, "sum", null, null).days()).isEqualTo(10);                    // a method alone is 10 days
        assertThat(p.lens("1d", null, 0.995, null).confidence()).isEqualTo(0.995);
        assertThatThrownBy(() -> p.lens("1d", "sum", null, null)).hasMessageContaining("10-day");
        assertThatThrownBy(() -> p.lens(null, null, 0.9, null)).hasMessageContaining("not offered").hasMessageContaining("99.5%");
        assertThatThrownBy(() -> p.lens(null, null, null, 0.995)).hasMessageContaining("esConfidence");
    }

    @Test
    void theParametersOfVarResult() {
        VarProperties p = props(null, null, null);
        assertThat(VarResultEngine.lens(p, Map.of("horizon", "1d", "method", "default"))).isNull();
        assertThat(VarResultEngine.lens(p, Map.of("horizon", "1d", "method", "sum"))).isEqualTo(new VarLens(10, VarLens.Method.SUM, 0.99, 0.975));
        assertThat(VarResultEngine.lens(p, Map.of("horizon", "10d", "method", "default", "confidence", 0.95)))
                .isEqualTo(new VarLens(10, VarLens.Method.SQRT, 0.95, 0.975));
    }

    @Test
    void aLimitSaysItsHorizonAndConfidence() {
        assertThat(VarLoader.LimitBasis.of(null, null)).isEqualTo(VarLoader.LimitBasis.DEFAULT);
        assertThat(VarLoader.LimitBasis.of("10D", "99")).isEqualTo(new VarLoader.LimitBasis(10, 0.99));
        assertThat(VarLoader.LimitBasis.of("1D", "0.975").label()).isEqualTo("1-day 97.5%");
        assertThat(VarLoader.LimitBasis.DEFAULT.matches(VarLens.oneDay(0.99, 0.95))).isTrue();
        assertThat(VarLoader.LimitBasis.DEFAULT.matches(VarLens.oneDay(0.95, 0.975))).isFalse();
        assertThat(VarLoader.LimitBasis.DEFAULT.matches(new VarLens(10, null, 0.99, 0.975))).isFalse();
        assertThat(new VarLoader.LimitBasis(10, 0.99).matches(new VarLens(10, VarLens.Method.SUM, 0.99, 0.975))).isTrue();
    }
}
