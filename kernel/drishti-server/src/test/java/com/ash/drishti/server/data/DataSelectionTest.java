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
package com.ash.drishti.server.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.ScanRequest;
import com.ash.drishti.common.DrishtiException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The Data view's simple selection: every operator read as typed, read back in words and as a SQL-like line, pushed down, checked per row. */
class DataSelectionTest {

    private static DataSelection sel(String... where) {
        return DataSelection.parse(List.of("TRADE_ID,DESK", "MTM"), List.of(where));
    }

    private static boolean row(DataSelection s, Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return s.matches(m::get);
    }

    @Test
    void everyOperatorIsReadAsTyped() {
        DataSelection s = sel("DESK = DESK-COMM", "PRODUCT_TYPE in ComSwap, ComFut", "MTM > 1,000,000", "PNL_1D < 0", "NAME contains bank",
                "BOOK starts with BK-", "DV01 between 1 and 5", "NETTING_SET is empty", "COUNTERPARTY is not empty", "STATUS != DEAD",
                "X one of a,b", "Y not equals z", "Z greater than 3", "W less than 4", "V starts W");
        assertThat(s.columns()).containsExactly("TRADE_ID", "DESK", "MTM");
        assertThat(s.lines()).extracting(DataSelection.Line::op).containsExactly(DataSelection.Op.EQ, DataSelection.Op.IN, DataSelection.Op.GT,
                DataSelection.Op.LT, DataSelection.Op.CONTAINS, DataSelection.Op.STARTS, DataSelection.Op.BETWEEN, DataSelection.Op.EMPTY,
                DataSelection.Op.NOT_EMPTY, DataSelection.Op.NE, DataSelection.Op.IN, DataSelection.Op.NE, DataSelection.Op.GT, DataSelection.Op.LT,
                DataSelection.Op.STARTS);
        assertThat(s.lines().get(1).values()).containsExactly("ComSwap", "ComFut");
        assertThat(s.lines().get(6).values()).containsExactly("1", "5");
        assertThat(s.lines().get(1).text()).isEqualTo("PRODUCT_TYPE in ComSwap,ComFut");
        assertThat(DataSelection.line("DESK = \"DESK COMM\"").values()).containsExactly("DESK COMM");
    }

    @Test
    void aLineThatCannotBeReadIsRefusedSayingHowToWriteIt() {
        assertThatThrownBy(() -> DataSelection.line("DESK ~ X")).isInstanceOf(DrishtiException.class).hasMessageContaining("attribute operator value");
        assertThatThrownBy(() -> DataSelection.line("MTM >")).isInstanceOf(DrishtiException.class).hasMessageContaining("no value");
        assertThatThrownBy(() -> DataSelection.line("MTM between 3")).isInstanceOf(DrishtiException.class).hasMessageContaining("two values");
        assertThatThrownBy(() -> DataSelection.line("X is empty now")).isInstanceOf(DrishtiException.class).hasMessageContaining("takes no value");
    }

    @Test
    void theSelectionReadsBackInWordsAndAsASqlLikeLine() {
        DataSelection s = sel("DESK = DESK-COMM", "PRODUCT_TYPE in ComSwap,ComFut", "MTM > 1000000");
        assertThat(s.words("trade-valuation", "2026-09-30", 3, false)).isEqualTo("From trade-valuation on 2026-09-30: 3 columns, rows where DESK is "
                + "DESK-COMM, PRODUCT_TYPE is one of ComSwap or ComFut, and MTM is greater than 1000000.");
        assertThat(s.sql("trade-valuation", "2026-09-30", List.of("TRADE_ID", "DESK", "MTM"))).isEqualTo("SELECT TRADE_ID, DESK, MTM\n"
                + "FROM trade-valuation  -- 2026-09-30\nWHERE DESK = 'DESK-COMM' AND PRODUCT_TYPE IN ('ComSwap', 'ComFut') AND MTM > 1000000");
        assertThat(DataSelection.parse(List.of(), List.of()).words("trade", null, 47, true)).isEqualTo("From trade: every column (47), every row.");
        assertThat(sel("NAME contains O'Brien").sql("t", null, List.of())).endsWith("WHERE NAME LIKE '%O''Brien%'");
    }

    @Test
    void comparisonsGoToTheConnectorAndTheRestIsCheckedOnEachRow() {
        DataSelection s = sel("DESK = DESK-COMM", "MTM between 1 and 5", "NAME contains x", "TRADE_ID in 1000001,1000002", "X is empty");
        List<ScanRequest.Filter> f = s.pushable(Set.of("MTM"));
        assertThat(f).extracting(ScanRequest.Filter::op).containsExactly(ScanRequest.Op.EQ, ScanRequest.Op.GE, ScanRequest.Op.LE, ScanRequest.Op.IN);
        assertThat(f.get(1).values()).containsExactly(1.0);                         // a number field: compared as a number
        assertThat(f.get(3).values()).containsExactly("1000001", "1000002");        // an id of digits: compared as its text
    }

    @Test
    void eachRowIsCheckedLineByLine() {
        DataSelection s = sel("DESK = DESK-COMM", "MTM > 1000000", "NAME contains BANK", "NETTING_SET is empty");
        assertThat(row(s, "DESK", "DESK-COMM", "MTM", 2_000_000.0, "NAME", "First bank", "NETTING_SET", "")).isTrue();
        assertThat(row(s, "DESK", "DESK-COMM", "MTM", 2_000_000.0, "NAME", "First bank")).isTrue();
        assertThat(row(s, "DESK", "DESK-RATES", "MTM", 2_000_000.0, "NAME", "bank")).isFalse();
        assertThat(row(s, "DESK", "DESK-COMM", "MTM", 10.0, "NAME", "bank")).isFalse();
        assertThat(row(s, "DESK", "DESK-COMM", "MTM", null, "NAME", "bank")).as("a missing value matches no comparison").isFalse();
        assertThat(row(s, "DESK", "DESK-COMM", "MTM", 2e6, "NAME", "bank", "NETTING_SET", "NS-1")).isFalse();
        DataSelection ids = sel("TRADE_ID in 1000001, 1000003", "D between 2026-09-01 and 2026-09-30", "B starts cp-");
        assertThat(row(ids, "TRADE_ID", "1000003", "D", "2026-09-30", "B", "CP-HARBOR")).isTrue();
        assertThat(row(ids, "TRADE_ID", 1000001.0, "D", "2026-09-01", "B", "CP-X")).as("numbers as numbers, dates as text").isTrue();
        assertThat(row(ids, "TRADE_ID", "1000002", "D", "2026-09-30", "B", "CP-X")).isFalse();
        assertThat(row(ids, "TRADE_ID", "1000001", "D", "2026-10-01", "B", "CP-X")).isFalse();
    }
}
