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
package com.ash.drishti.bicompute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.kernel.api.pivot.Viewer;
import com.ash.drishti.common.DrishtiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Currencies and units (HOW_IT_FITS.md sections A26.3 and 26.13, CurrencyAndUnitsTest): a money total over several currencies is refused in
 * words (never added raw) while a cell in one currency shows its figure and its currency; with an explicit conversion every money figure
 * equals the converted rows' own figure and the header carries the report currency; a sum of a percentage is refused; counts stay counts.
 */
class CurrencyAndUnitsTest {

    final PivotFixture fx = new PivotFixture(2_000, StateCache.lru(16L << 20), PivotCore.Settings.defaults(), true);
    final Viewer rita = Viewer.of("rita");

    @AfterEach
    void close() {
        fx.close();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> m(Object o) {
        return (Map<String, Object>) o;
    }

    @Test
    @SuppressWarnings("unchecked")
    void mixedCurrenciesAreRefusedUnlessConverted() {
        Map<String, Object> a = fx.core.query(rita, PivotView.parse(Map.of("source", "trades", "rows", List.of("CCY"),
                "values", List.of(Map.of("measure", "sum(NOTIONAL)", "as", "N"), Map.of("measure", "count()", "as", "C")))));
        Map<String, Object> cells = m(a.get("cells"));
        Map<String, Object> usd = m(cells.get("CCY=USD|*"));
        assertThat(((Number) m(usd.get("values")).get("N")).doubleValue())
                .isCloseTo(fx.scalar("SELECT sum(NOTIONAL) FROM trades WHERE CCY = 'USD'"), offset(1e-6));
        assertThat(usd.get("currency")).isEqualTo("USD");
        Map<String, Object> grand = m(cells.get("*|*"));
        assertThat(m(grand.get("values")).get("N")).isNull();
        assertThat(String.valueOf(m(grand.get("notes")).get("N"))).startsWith("mixed currencies (EUR to USD)").contains("never added raw");
        assertThat(((Number) m(grand.get("values")).get("C")).doubleValue()).isEqualTo(2_000.0);
        List<Map<String, Object>> measures = (List<Map<String, Object>>) a.get("measures");
        assertThat(measures.get(0).get("unit")).isEqualTo("money");
        assertThat(measures.get(1).get("unit")).isEqualTo("count");

        Map<String, Object> rates = Map.of("EUR", 1.08, "GBP", 1.27, "JPY", 0.0066);
        Map<String, Object> b = fx.core.query(rita, PivotView.parse(Map.of("source", "trades", "rows", List.of("REGION"),
                "values", List.of(Map.of("measure", "sum(NOTIONAL)", "as", "N"), Map.of("measure", "avg(PNL)", "as", "A")),
                "currency", Map.of("report", "USD", "rates", rates, "source", "test rates"))));
        String conv = "CASE CCY WHEN 'USD' THEN 1.0 WHEN 'EUR' THEN 1.08 WHEN 'GBP' THEN 1.27 WHEN 'JPY' THEN 0.0066 END";
        for (String region : PivotFixture.REGIONS) {
            Map<String, Object> v = m(m(m(b.get("cells")).get("REGION=" + region + "|*")).get("values"));
            assertThat(((Number) v.get("N")).doubleValue()).as(region)
                    .isCloseTo(fx.scalar("SELECT sum(NOTIONAL * " + conv + ") FROM trades WHERE REGION = ?", region), offset(1e-4));
            assertThat(((Number) v.get("A")).doubleValue()).as(region)
                    .isCloseTo(fx.scalar("SELECT avg(PNL * " + conv + ") FROM trades WHERE REGION = ?", region), offset(1e-6));
        }
        assertThat(((List<Map<String, Object>>) b.get("measures")).get(0).get("unit")).isEqualTo("USD");
        assertThat(String.valueOf(m(b.get("provenanceShared")).get("currency"))).contains("converted into USD").contains("test rates");
    }

    @Test
    void aSumOfAPercentageIsRefusedAndItsAverageIsOffered() {
        assertThatThrownBy(() -> fx.core.query(rita, PivotView.parse(Map.of("source", "trades", "rows", List.of("DESK"),
                "values", List.of("sum(RATE_PCT)"))))).isInstanceOf(DrishtiException.class).hasMessageContaining("avg(RATE_PCT)");
        Map<String, Object> a = fx.core.query(rita, PivotView.parse(Map.of("source", "trades", "rows", List.of("DESK"),
                "values", List.of("avg(RATE_PCT)"))));
        assertThat(((Number) m(m(m(a.get("cells")).get("*|*")).get("values")).get("avg(RATE_PCT)")).doubleValue())
                .isCloseTo(fx.scalar("SELECT avg(RATE_PCT) FROM trades"), offset(1e-9));
        assertThatThrownBy(() -> PivotView.parse(Map.of("source", "trades", "values", List.of("count()"), "currency",
                Map.of("report", "usd dollars")))).isInstanceOf(DrishtiException.class);
    }
}
