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
package com.ash.drishti.engine.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.engine.search.PhraseParser.Field;
import com.ash.drishti.engine.search.PhraseParser.KindWord;
import com.ash.drishti.engine.search.PhraseParser.Type;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PhraseParserTest {

    static final List<Field> TRADE = List.of(
            new Field("mtm", "mtm", Type.NUMBER, Map.of()),
            new Field("notional", "notional", Type.NUMBER, Map.of()),
            new Field("status", "status", Type.TEXT, Map.of("live", "Live", "matured", "Matured")),
            new Field("currency", "currency", Type.TEXT, Map.of("usd", "USD", "eur", "EUR")),
            new Field("book", "book", Type.TEXT, Map.of("book-rates-3", "BOOK-RATES-3")),
            new Field("maturityDate", "maturity date", Type.DATE, Map.of()),
            new Field("tradeDate", "trade date", Type.DATE, Map.of()),
            new Field("counterparty.name", "counterparty name", Type.TEXT, Map.of("meridian reinsurance ltd", "Meridian Reinsurance Ltd")));
    static final PhraseParser P = new PhraseParser(new PhraseParser.Vocabulary() {
        @Override
        public Map<String, KindWord> kinds() {
            return PhraseParser.kindWords(List.of(new KindWord("trade", "TRD", "Trade"), new KindWord("counterparty", "CPTY", "Counterparty"),
                    new KindWord("netting-set", "NSET", "Netting set")));
        }

        @Override
        public List<Field> fields(String kind) {
            return kind.equals("trade") ? TRADE : List.of(new Field("exposure", "exposure", Type.NUMBER, Map.of()));
        }
    });

    private static String q(String phrase) {
        return P.parse(phrase).query();
    }

    @Test
    void readsComparisonsValuesAndOrder() {
        assertThat(q("live trades over 5m in BOOK-RATES-3, biggest first"))
                .isEqualTo("TRD where status = 'Live' and mtm > 5000000 and book = 'BOOK-RATES-3' order by mtm desc");
        assertThat(q("usd trades with notional between 10m and 50m"))
                .isEqualTo("TRD where currency = 'USD' and notional >= 10000000 and notional <= 50000000");
        assertThat(q("trades maturing before 2027 with negative mtm")).isEqualTo("TRD where maturityDate < '2027-01-01' and mtm < 0");
        assertThat(q("trades maturing in 2028")).isEqualTo("TRD where maturityDate >= '2028-01-01' and maturityDate < '2029-01-01'");
        assertThat(q("top 10 counterparties by exposure")).isEqualTo("CPTY order by exposure desc limit 10");
        assertThat(q("show me trades with notional at least 2.5 bn")).isEqualTo("TRD where notional >= 2500000000");
        assertThat(q("smallest 5 trades by notional")).isEqualTo("TRD order by notional asc limit 5");
        assertThat(q("netting sets")).isEqualTo("NSET");
        assertThat(q("trades traded after 2025")).isEqualTo("TRD where tradeDate >= '2026-01-01'");
        assertThat(q("trades with mtm less than -1,000,000")).isEqualTo("TRD where mtm < -1000000");
    }

    @Test
    void explainsWhatItReadAndSaysWhatItDidNot() {
        PhraseParser.Parsed p = P.parse("live trades over 5m for the snack desk");
        assertThat(p.query()).isEqualTo("TRD where status = 'Live' and mtm > 5000000");
        assertThat(p.steps()).extracting(PhraseParser.Step::words).containsExactly("trades", "live", "over 5m");
        assertThat(p.steps()).extracting(PhraseParser.Step::meaning).contains("Trade (TRD)", "status is Live", "mtm > 5000000");
        assertThat(p.ignored()).containsExactly("snack", "desk");
        PhraseParser.Parsed none = P.parse("what is the weather");
        assertThat(none.query()).isNull();
        assertThat(none.problem()).contains("say what to look for");
        // QA 2026-10-01 GRAM-07: the kinds as words with their mnemonics, not a list printed with brackets and guessed plurals
        assertThat(none.problem()).contains("trade (TRD)").doesNotContain("[", "]", "trades");
    }

    @Test
    void everyQueryItWritesParses() {
        for (String phrase : List.of("live trades over 5m in BOOK-RATES-3, biggest first", "trades maturing before 2027 with negative mtm",
                "top 10 counterparties by exposure", "usd trades with notional between 10m and 50m")) {
            SearchQuery parsed = SearchQuery.pick(q(phrase));
            assertThat(parsed.head()).isIn("TRD", "CPTY");
        }
    }

    /** HISTORY_QUERIES.md section 15.5: kind word, id, time phrase is the entity over time, read with a fixed today. */
    @Test
    void aKindAnIdAndATimePhraseAreAHistoryCommand() {
        PhraseParser on = new PhraseParser(new PhraseParser.Vocabulary() {
            @Override
            public Map<String, KindWord> kinds() {
                return PhraseParser.kindWords(List.of(new KindWord("trade", "TRD", "Trade"), new KindWord("equity", "EQ", "Equity")));
            }

            @Override
            public List<Field> fields(String kind) {
                return kind.equals("trade") ? TRADE : List.of();
            }
        }, () -> java.time.LocalDate.of(2026, 10, 20));
        assertThat(on.parse("trade 1000015 last month").command()).isEqualTo("TRD 1000015 FROM 2026-09-01 TO 2026-09-30");
        assertThat(on.parse("equity EQ-CSCA last 3 months").command()).isEqualTo("EQ EQ-CSCA 3M");
        assertThat(on.parse("trade 1000015 last 30 days").command()).isEqualTo("TRD 1000015 30");
        assertThat(on.parse("show me trade 1000015 over the past 2 weeks").command()).isEqualTo("TRD 1000015 2W");
        assertThat(on.parse("equity eq-csca this year").command()).isEqualTo("EQ EQ-CSCA YTD");
        assertThat(on.parse("equity EQ-CSCA ytd").command()).isEqualTo("EQ EQ-CSCA YTD");
        assertThat(on.parse("trade 1000015 since 2026-10-01").command()).isEqualTo("TRD 1000015 FROM 2026-10-01");
        assertThat(on.parse("trade 1000015 between 2026-09-30 and 2026-09-01").command()).isEqualTo("TRD 1000015 FROM 2026-09-01 TO 2026-09-30");
        assertThat(on.parse("trade 1000015 from 2026-09-01 to 2026-09-30").command()).isEqualTo("TRD 1000015 FROM 2026-09-01 TO 2026-09-30");
        PhraseParser.Parsed changed = on.parse("what changed on trade 1000015 this week");
        assertThat(changed.command()).isEqualTo("TRD 1000015 FROM 2026-10-19 DIFF");
        assertThat(changed.query()).isEqualTo(changed.command());
        assertThat(changed.ignored()).isEmpty();
        assertThat(changed.steps()).extracting(PhraseParser.Step::words).containsExactly("trade", "1000015", "this week");
        // still searches: no id, or a field's value with a date
        assertThat(on.parse("trades maturing before 2027 with negative mtm").command()).isNull();
        assertThat(on.parse("trades in BOOK-RATES-3 since 2026-01-01").command()).isNull();
        assertThat(on.parse("live trades last 30 days").command()).isNull();
    }
}
