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
package com.ash.drishti.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.engine.source.RowPredicate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Row rules on the canonical trade kind by role and person attributes (ACCESS_MODEL.md section 10): a rates trader sees rates trades of their
 * desks, Endur support sees Endur's trades, a person with both sees either, a role without a rule sees every record it may open; edit rights
 * per {@code SOURCE_SYSTEM}.
 */
class RowRulesTest {

    private static SecurityProperties.Role role(Map<String, String> rows, List<String> editSystems) {
        return new SecurityProperties.Role(List.of("trade", "position"), false, false, false, false, false, true, true, false, List.of(), true,
                List.of(), rows, editSystems);
    }

    private static final Map<String, SecurityProperties.Role> ROLES = Map.of(
            "rates-trader", role(Map.of("trade", "ASSET_CLASS = 'RATES' and DESK in $user.desks"), List.of()),
            "endur-support", role(Map.of("trade", "SOURCE_SYSTEM = 'ENDUR'"), List.of("ENDUR")),
            "viewer", role(Map.of(), List.of()));

    private static DataNode trade(String system, String assetClass, String desk) {
        return DataNode.of(Map.of("SOURCE_SYSTEM", system, "ASSET_CLASS", assetClass, "DESK", desk));
    }

    private static final DataNode RATES_NY_ENDUR = trade("ENDUR", "RATES", "RATES-NY");
    private static final DataNode RATES_LDN_CALYPSO = trade("CALYPSO", "RATES", "RATES-LDN");
    private static final DataNode FX_ENDUR = trade("ENDUR", "FX", "FX-LDN");

    @Test
    void twoPeopleSeeTheirOwnRowsOfOneCanonicalKind() {
        RowPredicate ann = Entitlements.rowRule(ROLES, List.of("rates-trader"), "trade", Map.of("desks", List.of("RATES-NY"))).orElseThrow();
        assertThat(ann.test(RATES_NY_ENDUR)).isTrue();
        assertThat(ann.test(RATES_LDN_CALYPSO)).as("another desk").isFalse();
        assertThat(ann.test(FX_ENDUR)).as("another asset class").isFalse();

        RowPredicate bob = Entitlements.rowRule(ROLES, List.of("endur-support"), "trade", Map.of()).orElseThrow();
        assertThat(bob.test(RATES_NY_ENDUR)).isTrue();
        assertThat(bob.test(FX_ENDUR)).isTrue();
        assertThat(bob.test(RATES_LDN_CALYPSO)).isFalse();
        assertThat(bob.el()).as("a search's condition").isEqualTo("( SOURCE_SYSTEM == 'ENDUR' )");

        RowPredicate both = Entitlements.rowRule(ROLES, List.of("rates-trader", "endur-support"), "trade", Map.of("desks", List.of("RATES-LDN")))
                .orElseThrow();
        assertThat(both.test(RATES_LDN_CALYPSO)).isTrue();
        assertThat(both.test(FX_ENDUR)).isTrue();
        assertThat(both.test(trade("CALYPSO", "FX", "FX-LDN"))).isFalse();
    }

    @Test
    void aRoleWithoutARuleOnTheKindSeesEveryRecordAndAnotherKindIsUnrestricted() {
        assertThat(Entitlements.rowRule(ROLES, List.of("rates-trader", "viewer"), "trade", Map.of())).isEmpty();
        assertThat(Entitlements.rowRule(ROLES, List.of("rates-trader"), "position", Map.of())).isEmpty();
        assertThat(Entitlements.rowRule(ROLES, List.of("unknown"), "trade", Map.of())).isEmpty();
        assertThat(Entitlements.rowRule(ROLES, List.of("rates-trader"), "trade", Map.of()).orElseThrow().test(RATES_NY_ENDUR))
                .as("a person without desks sees none of the desk-scoped rows").isFalse();
    }

    @Test
    void editRightsPerSourceSystem() {
        assertThat(Entitlements.mayEditSystem(ROLES, List.of("endur-support"), "ENDUR")).isTrue();
        assertThat(Entitlements.mayEditSystem(ROLES, List.of("endur-support"), "CALYPSO")).isFalse();
        assertThat(Entitlements.mayEditSystem(ROLES, List.of("rates-trader"), "CALYPSO")).as("no edit-systems listed: any system").isTrue();
        assertThat(Entitlements.mayEditSystem(ROLES, List.of("rates-trader", "endur-support"), "CALYPSO")).isFalse();
    }

    @Test
    void aRuleThatIsNotAPredicateSaysSo() {
        Map<String, SecurityProperties.Role> bad = Map.of("x", role(Map.of("trade", "DESK = = 'A'"), List.of()));
        assertThatThrownBy(() -> Entitlements.rowRule(bad, List.of("x"), "trade", Map.of())).hasMessageContaining("is not a row predicate");
    }
}
