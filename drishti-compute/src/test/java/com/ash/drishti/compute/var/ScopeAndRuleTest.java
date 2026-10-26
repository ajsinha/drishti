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
package com.ash.drishti.compute.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The scope grammar (precedence, ids with hyphens, canonical text) and the rule language over a position's columns. */
class ScopeAndRuleTest {

    @Test
    void operatorsBindAsInArithmeticAndHyphensStayInIds() {
        ScopeExpr e = ScopeExpr.parse("DESK:RATES - POS:MX-1,CLY-3 + BASKET:asha/usd-hedges & DESK:FX");
        // (DESK:RATES - POS:...) + (BASKET & DESK:FX)
        assertThat(e).isInstanceOf(ScopeExpr.Union.class);
        ScopeExpr.Union u = (ScopeExpr.Union) e;
        assertThat(u.terms().get(0)).isInstanceOf(ScopeExpr.Minus.class);
        assertThat(u.terms().get(1)).isInstanceOf(ScopeExpr.And.class);
        assertThat(e.refs()).extracting(ScopeExpr.Ref::word).containsExactly("DESK", "POS", "BASKET", "DESK");
        assertThat(((ScopeExpr.Ref) e.refs().get(1)).ids()).containsExactly("CLY-3", "MX-1");
        assertThat(e.refs().get(2).arg()).isEqualTo("asha/usd-hedges");          // a basket keeps its case
    }

    @Test
    void equalScopesShareOneCanonicalText() {
        assertThat(ScopeExpr.parse("TRD:MX-2, MX-1 + desk:fx").toString()).isEqualTo(ScopeExpr.parse("DESK:FX + TRD:MX-1,MX-2").toString());
        assertThat(ScopeExpr.parse("trd:mx-2").toString()).isEqualTo("TRD:mx-2");          // ids are kept as written, matched ignoring case
        assertThat(ScopeExpr.parse("cpty:CP-B, CP-A").toString()).isEqualTo("CPTY:CP-A,CP-B");
        assertThat(ScopeExpr.parse("le:ny").toString()).isEqualTo("LE:NY");
        assertThat(ScopeExpr.parse("bank").toString()).isEqualTo("BANK");
        assertThat(ScopeExpr.parse("(DESK:A + DESK:B) - TRD:X").toString()).isEqualTo("DESK:A + DESK:B - TRD:X");
        assertThat(ScopeExpr.parse("DESK:A - (DESK:B + DESK:C)").toString()).isEqualTo("DESK:A - (DESK:B + DESK:C)");
    }

    @Test
    void aWhereRuleRunsToTheNextOperator() {
        ScopeExpr e = ScopeExpr.parse("WHERE:PRODUCT_GROUP == 'Rates' and CURRENCY in ('USD', 'CAD') - TRD:MX-9");
        assertThat(e).isInstanceOf(ScopeExpr.Minus.class);
        assertThat(e.refs().get(0).arg()).isEqualTo("PRODUCT_GROUP == 'Rates' and CURRENCY in ('USD', 'CAD')");
    }

    @Test
    void badScopesSayWhy() {
        assertThatThrownBy(() -> ScopeExpr.parse("TEAM:X")).hasMessageContaining("not a scope word");
        assertThatThrownBy(() -> ScopeExpr.parse("DESK")).hasMessageContaining("needs ':'");
        assertThatThrownBy(() -> ScopeExpr.parse("(DESK:A")).hasMessageContaining("without its ')'");
        assertThatThrownBy(() -> ScopeExpr.parse("WHERE:CURRENCY ==")).hasMessageContaining("expected a value");
        assertThatThrownBy(() -> ScopeExpr.parse("")).hasMessageContaining("empty scope");
    }

    @Test
    void rulesMatchAPositionsColumns() {
        Map<String, Object> pos = Map.of("PRODUCT_GROUP", "Rates", "CURRENCY", "USD", "DIRECTION", "SHORT", "MARKET_VALUE", 2_500_000.0,
                "TAGS", List.of("hedge", "option"));
        assertThat(new PositionRule("PRODUCT_GROUP == 'Rates' and CURRENCY in ('USD', 'CAD')").matches(pos::get)).isTrue();
        assertThat(new PositionRule("'hedge' in TAGS and not DIRECTION == 'LONG'").matches(pos::get)).isTrue();
        assertThat(new PositionRule("MARKET_VALUE > 1000000 and MARKET_VALUE <= 2500000").matches(pos::get)).isTrue();
        assertThat(new PositionRule("COUNTERPARTY == 'CP-X'").matches(pos::get)).isFalse();         // missing: unequal
        assertThat(new PositionRule("COUNTERPARTY != 'CP-X'").matches(pos::get)).isTrue();
        assertThat(new PositionRule("(CURRENCY == 'EUR' or CURRENCY == 'USD') and PRODUCT_GROUP != 'FX'").matches(pos::get)).isTrue();
        assertThat(new PositionRule("CURRENCY == 'EUR' or CURRENCY == 'USD'").fields()).containsExactly("CURRENCY", "CURRENCY");
    }

    @Test
    void positionRulesKeySecuritiesByBookAndSecurityAndOtcByTrade() {
        PositionRules rules = PositionRules.of(Map.of("live-statuses", List.of("NEW", "AMENDED"), "rules", List.of(
                Map.of("name", "securities", "groups", List.of("Fixed income"), "types", List.of("EQ_CASH"), "key", List.of("SECURITY_ID", "ISIN")),
                Map.of("name", "listed", "types", List.of("BOND_FUTURE"), "key", List.of("CONTRACT")),
                Map.of("name", "otc"))));
        Map<String, String> bond = Map.of("ISIN", "US912828XX", "SECURITY_ID", " ");
        assertThat(rules.derive("T1", "BOOK-A", "fixed income", "GOVT_BOND", bond::get))
                .isEqualTo(new PositionRules.Derived("POS-BOOK-A~US912828XX", "US912828XX", "US912828XX", "securities"));
        assertThat(rules.derive("T2", "BOOK-A", "Equity", "EQ_CASH", Map.of("SECURITY_ID", "SEC-1")::get).positionId()).isEqualTo("POS-BOOK-A~SEC-1");
        assertThat(rules.derive("T3", "BOOK-B", "Rates", "BOND_FUTURE", Map.of("CONTRACT", "FUT-TY")::get).rule()).isEqualTo("listed");
        // a security without a key field, and a swap: a position of one trade each
        assertThat(rules.derive("T4", "BOOK-B", "Fixed income", "CORP_BOND", Map.<String, String>of()::get))
                .isEqualTo(new PositionRules.Derived("POS-BOOK-B~T4", "T4", null, "otc"));
        assertThat(rules.derive("T5", "BOOK-B", "Rates", "IRS", Map.<String, String>of()::get).rule()).isEqualTo("otc");
        assertThat(rules.live("amended")).isTrue();
        assertThat(rules.live("VALIDATED")).isFalse();
        assertThat(rules.live(null)).isTrue();                                                       // the batch valued it: it counts
        assertThat(rules.keyFields()).containsExactly("SECURITY_ID", "ISIN", "CONTRACT");
        assertThat(rules.digest()).isEqualTo(PositionRules.of(Map.of("live-statuses", List.of("AMENDED", "NEW"), "rules", List.of(
                Map.of("name", "securities", "groups", List.of("Fixed income"), "types", List.of("EQ_CASH"), "key", List.of("SECURITY_ID", "ISIN")),
                Map.of("name", "listed", "types", List.of("BOND_FUTURE"), "key", List.of("CONTRACT")),
                Map.of("name", "otc")))).digest()).hasSize(16);
        assertThat(PositionRules.defaults().derive("T6", "B", "Fixed income", "GOVT_BOND", Map.of("SECURITY_ID", "S")::get).positionId())
                .isEqualTo("POS-B~T6");
    }
}
