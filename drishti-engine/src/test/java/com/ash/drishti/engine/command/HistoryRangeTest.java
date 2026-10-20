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
package com.ash.drishti.engine.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The range after an id (HISTORY_QUERIES.md section 4): every form, case, DIFF, and what is never a range. */
class HistoryRangeTest {

    private static HistoryRange.Split split(String text) {
        return HistoryRange.trailing(text).orElseThrow();
    }

    @Test
    void aBareNumberIsDataDays() {
        HistoryRange.Split s = split("TRD 1000015 30");
        assertThat(s.command()).isEqualTo("TRD 1000015");
        assertThat(s.range().mode()).isEqualTo(HistoryRange.Mode.DATA_DAYS);
        assertThat(s.range().days()).isEqualTo(30);
        assertThat(s.diff()).isFalse();
        assertThat(s.range().query()).isEqualTo("days=30");
        assertThat(s.range().render()).isEqualTo("30");
        assertThat(s.range().describe()).isEqualTo("the last 30 days with data");
    }

    @Test
    void calendarSpansInAnyCaseWithOrWithoutASpace() {
        assertThat(split("EQ EQ-CSCA 3M").range().span()).isEqualTo("3M");
        assertThat(split("eq eq-csca 3m").range().span()).isEqualTo("3M");
        assertThat(split("TRD 1000015 30d").range().span()).isEqualTo("30D");
        assertThat(split("TRD 1000015 30 D").range().span()).isEqualTo("30D");
        assertThat(split("TRD 1000015 30 D").command()).isEqualTo("TRD 1000015");
        assertThat(split("TRD 1000015 2W").range().describe()).isEqualTo("the last 2 weeks");
        assertThat(split("TRD 1000015 1Y").range().describe()).isEqualTo("the last 1 year");
        assertThat(split("FX FX-AUDUSD YTD").range().span()).isEqualTo("YTD");
        assertThat(split("FX FX-AUDUSD ytd").range().describe()).isEqualTo("since 1 January");
        assertThat(split("FX FX-AUDUSD MTD").range().mode()).isEqualTo(HistoryRange.Mode.CALENDAR);
        assertThat(split("FX FX-AUDUSD qtd").range().query()).isEqualTo("range=QTD");
    }

    @Test
    void fromAndToAreKeywordsOnlyBeforeADate() {
        HistoryRange.Split s = split("CRV CRV-USD-SOFR FROM 2026-09-01 TO 2026-09-30");
        assertThat(s.command()).isEqualTo("CRV CRV-USD-SOFR");
        assertThat(s.range().mode()).isEqualTo(HistoryRange.Mode.BETWEEN);
        assertThat(s.range().from()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(s.range().to()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(s.range().query()).isEqualTo("from=2026-09-01&to=2026-09-30");
        HistoryRange.Split open = split("trd 1000015 from 2026-09-01");
        assertThat(open.range().to()).isNull();
        assertThat(open.range().render()).isEqualTo("FROM 2026-09-01");
        assertThat(HistoryRange.trailing("TRD FROM ABC")).isEmpty();
        assertThat(HistoryRange.trailing("TRD 1000015 TO 2026-09-30")).isEmpty();
    }

    @Test
    void diffEndsAnyRange() {
        assertThat(split("TRD 1000015 30 DIFF").diff()).isTrue();
        assertThat(split("TRD 1000015 30 diff").range().days()).isEqualTo(30);
        HistoryRange.Split s = split("TRD 1000015 FROM 2026-09-01 TO 2026-09-30 DIFF");
        assertThat(s.diff()).isTrue();
        assertThat(s.command()).isEqualTo("TRD 1000015");
        assertThat(HistoryRange.trailing("TRD 1000015 DIFF")).isEmpty();
    }

    @Test
    void theRangeIsNeverTheWholeText() {
        assertThat(HistoryRange.trailing("30")).isEmpty();
        assertThat(HistoryRange.trailing("YTD")).isEmpty();
        assertThat(HistoryRange.trailing("FROM 2026-09-01")).isEmpty();
        assertThat(HistoryRange.trailing("")).isEmpty();
        assertThat(HistoryRange.trailing(null)).isEmpty();
        // TRD 30 is split here (the command is then "TRD"): the controller keeps a lone id after a mnemonic as the id
        assertThat(split("TRD 30").command()).isEqualTo("TRD");
    }

    @Test
    void wordsThatAreNotRangesAreLeftAlone() {
        assertThat(HistoryRange.trailing("TRD 1000015")).isEmpty();
        assertThat(HistoryRange.trailing("TRD MX-2000")).isEmpty();
        assertThat(HistoryRange.trailing("TRD 1000015 30X")).isEmpty();
        assertThat(HistoryRange.trailing("TRD 1000015 0")).isEmpty();
        assertThat(HistoryRange.trailing("TRD 1000015 12345")).isEmpty();
        assertThat(HistoryRange.trailing("CPTY northbridge")).isEmpty();
    }

    @Test
    void idsWithSpacesKeepTheirWords() {
        HistoryRange.Split s = split("BOND UST 2030 30");
        assertThat(s.command()).isEqualTo("BOND UST 2030");
        assertThat(s.range().days()).isEqualTo(30);
    }

    @Test
    void aRangeThatCannotHoldSaysWhy() {
        assertThatThrownBy(() -> HistoryRange.trailing("TRD 1000015 FROM 2026-09-30 TO 2026-09-01"))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("is after TO");
        assertThatThrownBy(() -> HistoryRange.trailing("TRD 1000015 FROM 2026-13-01"))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("yyyy-mm-dd").hasMessageStartingWith("DRS-4022");
    }

    private static HistoryRange.Bad bad(String text) {
        return HistoryRange.malformed(text, 366).get(0);
    }

    @Test
    void whatReadsAsARangeButIsNoneIsNamed() {
        // QA1-03: each bad range, the command before it, its code and what to type
        assertThat(bad("TRD 1000001 0")).satisfies(b -> {
            assertThat(b.command()).isEqualTo("TRD 1000001");
            assertThat(b.code()).isEqualTo(com.ash.drishti.common.ErrorCode.HISTORY_BAD_RANGE);
            assertThat(b.message()).contains("0 days is empty").contains("TRD 1000001 30");
        });
        assertThat(bad("TRD 1000001 -5").message()).contains("'-5' is not a range").contains("3M");
        assertThat(bad("TRD 1000001 99999").code()).isEqualTo(com.ash.drishti.common.ErrorCode.HISTORY_RANGE_TOO_LARGE);
        assertThat(bad("TRD 1000001 99999").message()).startsWith("366 business days at most");
        assertThat(bad("TRD 1000001 1.5M").message()).contains("'1.5M' is not a range");
        assertThat(bad("TRD 1000001 3X").command()).isEqualTo("TRD 1000001");
        assertThat(bad("TRD 1000001 FROM yesterday").message()).contains("'yesterday' is not a date");
        assertThat(bad("TRD 1000001 FROM 2026-02-30").message()).contains("'2026-02-30' is not a date");
        assertThat(bad("TRD 1000001 FROM 2026-10-15 TO 2026-10-01").message()).contains("is after TO");
        assertThat(bad("TRD 1000001 FROM 2026-10-15 TO 2026-10-01").command()).isEqualTo("TRD 1000001");
        assertThat(bad("TRD 1000001 DIFF DIFF").message()).contains("DIFF is said once");
        assertThat(bad("TRD 1000001 30 DIFF DIFF").message()).contains("TRD 1000001 30 DIFF");
        assertThat(bad("TRD 1000001 30 30").message()).contains("two ranges");
        // a bare DIFF: the range candidate (1000001 after TRD) comes first, the caller takes the one whose command is an entity
        assertThat(HistoryRange.malformed("TRD 1000001 DIFF", 366)).extracting(HistoryRange.Bad::command).containsExactly("TRD", "TRD 1000001");
        assertThat(HistoryRange.malformed("TRD 1000001 DIFF", 366).get(1).message()).contains("DIFF follows a range");
        // a range read whole, and text that does not end like a range, are not bad
        assertThat(HistoryRange.malformed("TRD 1000001 30", 366)).isEmpty();
        assertThat(HistoryRange.malformed("TRD 1000001 3M DIFF", 366)).isEmpty();
        assertThat(HistoryRange.malformed("CPTY northbridge", 366)).isEmpty();
        assertThat(HistoryRange.malformed("TRD", 366)).isEmpty();
    }

    /** The first bad reading whose command is the entity (as CommandController takes it). */
    private static HistoryRange.Bad badAfter(String entity, String text) {
        return HistoryRange.malformed(text, 366).stream().filter(b -> b.command().equals(entity)).findFirst().orElseThrow(() ->
                new AssertionError(text + ": " + HistoryRange.malformed(text, 366)));
    }

    @Test
    void theFormsThatStillEscapedAreNamed() {
        // QA1-203: a trailing point, an exponent, TO without a date or without FROM, DIFF before the range, a dangling FROM
        assertThat(badAfter("TRD 1000001", "TRD 1000001 30.").message()).contains("'30.' is not a range");
        assertThat(badAfter("TRD 1000001", "TRD 1000001 1e3").message()).contains("'1e3' is not a range");
        assertThat(badAfter("TRD 1000001", "TRD 1000001 FROM 2026-10-01 TO").message())
                .isEqualTo("TO needs a date: TRD 1000001 FROM 2026-10-01 TO yyyy-mm-dd");
        assertThat(badAfter("TRD 1000001", "TRD 1000001 TO 2026-10-01").message())
                .isEqualTo("TO follows FROM: TRD 1000001 FROM yyyy-mm-dd TO 2026-10-01");
        assertThat(badAfter("TRD 1000001", "TRD 1000001 DIFF 30").message()).isEqualTo("DIFF follows a range: TRD 1000001 30 DIFF");
        assertThat(badAfter("TRD 1000001", "TRD 1000001 FROM").message()).contains("FROM needs a date");
        assertThat(badAfter("TRD 1000001", "TRD 1000001 FROM 2026-10-01 TO yesterday").message()).contains("'yesterday' is not a date");
        HistoryRange.malformed("TRD 1000001 FROM 2026-10-01 TO yesterday", 366)
                .forEach(b -> assertThat(b.message()).doesNotContain("TO follows FROM"));
        assertThat(HistoryRange.malformed("TRD 1000001 FROM 2026-10-01 TO 2026-10-10", 366)).isEmpty();
        // digits of another script are read as digits: TRD 1000001 ３0 is TRD 1000001 30
        assertThat(HistoryRange.asciiDigits("TRD 1000001 \uFF130")).isEqualTo("TRD 1000001 30");
        assertThat(badAfter("TRD 1000001", HistoryRange.asciiDigits("TRD 1000001 \uFF10")).message()).contains("0 days is empty");
        assertThat(HistoryRange.asciiDigits("CPTY Zürich 30")).isEqualTo("CPTY Zürich 30");
    }
}
