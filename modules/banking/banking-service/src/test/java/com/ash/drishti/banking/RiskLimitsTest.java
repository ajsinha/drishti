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
package com.ash.drishti.banking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The banking module's one limit rule (drishti.risk.limit-near): within, near from the share, breach over 100%. */
class RiskLimitsTest {

    @Test
    void withinNearAndBreach() {
        RiskLimits r = RiskLimits.defaults();
        assertThat(r.limitNear()).isEqualTo(0.8);
        assertThat(r.status(0.5)).isEqualTo("within");
        assertThat(r.status(0.8)).isEqualTo("near");                     // at the share: near
        assertThat(r.status(1.0)).isEqualTo("near");                     // at the limit: still within it, near
        assertThat(r.status(1.0001)).isEqualTo("breach");
        assertThat(r.status(null)).isNull();
        assertThat(r.status(Double.NaN)).isNull();
        assertThat(new RiskLimits(0.9).status(0.85)).isEqualTo("within");   // the setting moves it
    }

    @Test
    void aBadSettingFallsBackToTheDefault() {
        assertThat(new RiskLimits(0.0).limitNear()).isEqualTo(0.8);
        assertThat(new RiskLimits(1.5).limitNear()).isEqualTo(0.8);
        assertThat(new RiskLimits(1.0).limitNear()).isEqualTo(1.0);
    }
}
