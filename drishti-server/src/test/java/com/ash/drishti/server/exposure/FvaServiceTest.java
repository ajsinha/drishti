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
import static org.assertj.core.api.Assertions.within;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.ccr.FvaMath;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * FVA's rules that need no exposure date (docs/guides/FVA_GUIDE.md section 10): a run that is not risk-neutral refused with DRS-5447 (409)
 * when {@code require-risk-neutral} is on (decision 10), priced and flagged {@code REAL_WORLD_RUN} when off, a risk-neutral run never
 * flagged; a curve's points read from its document (a credit curve's SURVIVAL, log-linear like a discount factor); the settings' defaults.
 */
class FvaServiceTest {

    static final LocalDate DAY = LocalDate.of(2026, 9, 30);

    @Test
    void aRunThatIsNotRiskNeutralIsRefusedWhenTheSettingIsOn() {
        List<String> flags = new ArrayList<>();
        assertThatThrownBy(() -> FvaService.checkRun("REAL_WORLD", true, "XR-1", DAY, flags))
                .isInstanceOfSatisfying(DrishtiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.FVA_REFUSED);
                    assertThat(e.errorCode().httpStatus()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("DRS-5447").contains("XR-1").contains("REAL_WORLD").contains("require-risk-neutral");
                });
        assertThatThrownBy(() -> FvaService.checkRun("", true, "XR-1", DAY, flags)).hasMessageContaining("no stated calibration");
        assertThat(flags).isEmpty();
    }

    @Test
    void offItIsPricedAndFlaggedAndARiskNeutralRunIsNeverFlagged() {
        List<String> flags = new ArrayList<>();
        FvaService.checkRun("REAL_WORLD", false, "XR-1", DAY, flags);
        assertThat(flags).containsExactly("REAL_WORLD_RUN");
        List<String> none = new ArrayList<>();
        FvaService.checkRun("risk_neutral", true, "XR-1", DAY, none);
        assertThat(none).isEmpty();
    }

    @Test
    void aCreditCurvesSurvivalPointsReadLogLinear() {
        DataNode doc = DataNode.of(Map.of("CURVE_ID", "CDS-X", "POINTS", List.of(Map.of("TENOR", "1Y", "SPREAD", 50.0, "SURVIVAL", Math.exp(-0.02)),
                Map.of("TENOR", "6M", "SURVIVAL", Math.exp(-0.009)), Map.of("TENOR", "5Y", "HAZARD", 0.03))));
        FvaMath.Curve c = FvaService.points("CDS-X", doc, "SURVIVAL", "test");
        assertThat(c.tenors()).containsExactly("6M", "1Y");                         // sorted by tenor; the point without SURVIVAL left out
        assertThat(c.logLinear(0.25)).isCloseTo(Math.exp(-0.0045), within(1e-15));
        assertThat(c.logLinear(0.75)).isCloseTo(Math.exp(-0.0145), within(1e-15));
        assertThat(FvaService.points("CDS-X", doc, "DF", "test")).isNull();
    }

    @Test
    void theKindsNamesAndThePivotCellsScopes() {
        assertThat(FvaResultEngine.name("eeStar")).isEqualTo("EE_STAR");
        assertThat(FvaResultEngine.name("fca")).isEqualTo("FCA");
        assertThat(FvaResultEngine.name("nettingSet")).isEqualTo("NETTING_SET");
        assertThat(FvaPivotProvider.scope(Map.of())).isEqualTo("BANK");
        assertThat(FvaPivotProvider.scope(Map.of("COUNTERPARTY", "CP-1"))).isEqualTo("CPTY:CP-1");
        assertThat(FvaPivotProvider.scope(Map.of("NETTING_SET", "NS-X"))).isEqualTo("NSET:NS-X");
        assertThat(FvaPivotProvider.scope(Map.of("GROUP", "GRP-1"))).isEqualTo("GROUP:GRP-1");
        assertThat(FvaPivotProvider.scope(Map.of("COUNTERPARTY", "CP-1", "LEGAL_ENTITY", "LE-A"))).isNull();   // two words: no one scope
        assertThat(FvaPivotProvider.NATIVES).extracting(n -> n.name()).containsExactly("fva", "fca", "fba", "nettingSets");
    }

    @Test
    void theDefaults() {
        FvaProperties p = FvaProperties.defaults();
        assertThat(p.requireRiskNeutral()).isTrue();                               // decision 10: production refuses
        assertThat(p.fundingSpread()).isEqualTo("CRV-USD-FUNDING");
        assertThat(p.creditCurveKind()).isEqualTo("credit-curve");
        assertThat(p.ownCurve()).isEmpty();
        assertThat(p.fundingSurvival()).isFalse();
        assertThat(p.fba()).isTrue();
        assertThat(p.playgroundMaxPaths()).isEqualTo(5000);
    }
}
