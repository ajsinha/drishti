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

import com.ash.drishti.api.DataNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The exposure engine's inputs re-pointed at their fundamental sources (2.9.6, E0 of docs/architecture/COMPUTED_ATTRIBUTES.md): a netting
 * set's members and current MtM from the trade valuations of a live status (never the set's stored {@code TRADES[]}), the stored copies
 * only when a date has no valuations; the collateral held today after haircut from the balance's holdings.
 */
class ExposureInputsTest {

    private static DataNode tval(String id, String set, String status, double mtm, String maturity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("TRADE_ID", id);
        m.put("NETTING_SET", set);
        m.put("TRADE_STATUS", status);
        m.put("MTM", mtm);
        m.put("MATURITY_DATE", maturity);
        return DataNode.of(m);
    }

    @Test
    void membersAndMtmComeFromTheLiveTradeValuationsNotTheSetsStoredCopies() {
        Map<String, DataNode> valuations = new LinkedHashMap<>();
        valuations.put("TVAL-A", tval("A", "NS-X", "VALIDATED", 3_000_000, "2028-12-31"));
        valuations.put("TVAL-B", tval("B", "NS-X", "AMENDED", -1_000_000, "2026-09-01"));     // live status, past maturity: a member, flagged
        valuations.put("TVAL-C", tval("C", "NS-X", "CANCELLED", 9_000_000, "2028-12-31"));    // not a live status: not a member
        valuations.put("TVAL-D", tval("D", "", "NEW", 1, "2028-12-31"));                      // no netting set
        Map<String, DataNode> sets = Map.of("NS-X", DataNode.of(Map.of("TRADES", List.of(Map.of("TRADE_ID", "A", "MTM", 42.0)))));
        Map<String, List<ExposureLoader.Member>> m = ExposureLoader.members(valuations, sets);
        assertThat(m).containsOnlyKeys("NS-X");
        assertThat(m.get("NS-X")).extracting(ExposureLoader.Member::id).containsExactly("A", "B");
        assertThat(m.get("NS-X").get(0).mtm()).isEqualTo(3_000_000);                         // the valuation's MtM, not the stored 42
        assertThat(m.get("NS-X").get(1).maturity()).isEqualTo("2026-09-01");
    }

    @Test
    void withoutValuationsTheStoredMembersAreRead() {
        Map<String, DataNode> sets = Map.of("NS-X", DataNode.of(Map.of("TRADES", List.of(Map.of("TRADE_ID", "A", "MTM", 42.0, "MATURITY", "2028-12-31")))));
        Map<String, List<ExposureLoader.Member>> m = ExposureLoader.members(Map.of(), sets);
        assertThat(m.get("NS-X")).containsExactly(new ExposureLoader.Member("A", 42.0, "2028-12-31"));
    }

    @Test
    void theCollateralHeldTodayIsAfterHaircut() {
        DataNode bal = DataNode.of(Map.of("NET", 300.0, "POSITIONS", List.of(
                Map.of("DIRECTION", "Held", "MARKET_VALUE", 100.0, "HAIRCUT", 0.02, "VALUE", 98.0),
                Map.of("DIRECTION", "Held", "MARKET_VALUE", 200.0, "HAIRCUT", 0.01),                 // no VALUE: worked out from the haircut
                Map.of("DIRECTION", "Posted", "MARKET_VALUE", 50.0, "HAIRCUT", 0.0, "VALUE", 50.0))));
        assertThat(ExposureLoader.heldAfterHaircut(bal)).isEqualTo(98.0 + 198.0 - 50.0);
        assertThat(ExposureLoader.heldAfterHaircut(DataNode.of(Map.of("NET", -7.0)))).isEqualTo(-7.0);   // no holdings: the stored net
        assertThat(ExposureLoader.heldAfterHaircut(null)).isZero();
    }
}
