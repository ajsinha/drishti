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
package com.ash.drishti.engine.search.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.search.SearchQuery;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every field type and operator compiles to a command the existing search grammar reads; refusals are clear. */
class FormCompilerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);          // a Friday
    private static final BusinessCalendar CAL = BusinessCalendar.of("USNY");
    private final FormCompiler compiler = new FormCompiler(5);

    private static FormSpec spec() {
        List<String> problems = new ArrayList<>();
        FormSpec s = FormSpec.parse("trade", List.of(
                Map.of("path", "counterparty.name", "type", "text"), Map.of("path", "mtm", "type", "amount"),
                Map.of("path", "quantity", "type", "number"), Map.of("path", "maturityDate", "type", "date"),
                Map.of("path", "book", "type", "enum"), Map.of("path", "tradeId", "type", "id"), Map.of("path", "isActive", "type", "bool")), problems);
        assertThat(problems).isEmpty();
        return s;
    }

    private String run(String path, String op, boolean cs, String... values) {
        String command = compiler.compile("TRD", spec(), List.of(new Criterion(path, op, List.of(values), cs)), null, false, 0, TODAY, CAL);
        SearchQuery.pick(command);                                  // the existing grammar reads it
        return command;
    }

    @Test
    void textOperators() {
        assertThat(run("counterparty.name", "equals", false, "Meridian")).isEqualTo("TRD where counterparty.name = 'Meridian'");
        assertThat(run("counterparty.name", "contains", false, "merid")).isEqualTo("TRD where counterparty.name contains 'merid'");
        assertThat(run("counterparty.name", "starts", false, "Mer")).isEqualTo("TRD where counterparty.name startswith 'Mer'");
        assertThat(run("counterparty.name", "regex", false, "^Mer.*d$")).isEqualTo("TRD where matches(counterparty.name, '^Mer.*d$')");
        assertThat(run("counterparty.name", "equals", true, "A.B")).isEqualTo("TRD where matchesCase(counterparty.name, '^A\\.B$')");
        assertThat(run("counterparty.name", "contains", true, "x")).isEqualTo("TRD where matchesCase(counterparty.name, 'x')");
        assertThat(run("counterparty.name", "starts", true, "x")).isEqualTo("TRD where matchesCase(counterparty.name, '^x')");
        assertThat(run("counterparty.name", "regex", true, "[A-Z]+")).isEqualTo("TRD where matchesCase(counterparty.name, '[A-Z]+')");
        assertThat(run("counterparty.name", "equals", false, "O'Brien")).isEqualTo("TRD where counterparty.name = \"O'Brien\"");
    }

    @Test
    void numberAndAmountOperators() {
        assertThat(run("mtm", "=", false, "10m")).isEqualTo("TRD where mtm = 10m");
        assertThat(run("mtm", ">", false, "1bn")).isEqualTo("TRD where mtm > 1bn");
        assertThat(run("mtm", "<", false, "-5k")).isEqualTo("TRD where mtm < -5k");
        assertThat(run("mtm", ">=", false, "1,500")).isEqualTo("TRD where mtm >= 1500");
        assertThat(run("mtm", "<=", false, "2.5m")).isEqualTo("TRD where mtm <= 2.5m");
        assertThat(run("mtm", "between", false, "1m", "5m")).isEqualTo("TRD where (mtm >= 1m and mtm <= 5m)");
        assertThat(run("quantity", "=", false, "12")).isEqualTo("TRD where quantity = 12");
        assertThatThrownBy(() -> run("mtm", ">", false, "lots")).isInstanceOf(DrishtiException.class).hasMessageContaining("not a number");
        assertThatThrownBy(() -> run("mtm", "between", false, "1m")).hasMessageContaining("two values");
    }

    @Test
    void dateOperators() {
        assertThat(run("maturityDate", "on", false, "2026-12-31")).isEqualTo("TRD where (maturityDate >= '2026-12-31' and maturityDate < '2027-01-01')");
        assertThat(run("maturityDate", "between", false, "2026-01-01", "2026-03-31"))
                .isEqualTo("TRD where (maturityDate >= '2026-01-01' and maturityDate < '2026-04-01')");
        assertThat(run("maturityDate", "between", false, "2026-03-31", "2026-01-01")).contains(">= '2026-01-01'");     // swapped
        assertThat(run("maturityDate", "before", false, "2027-01-01")).isEqualTo("TRD where maturityDate < '2027-01-01'");
        assertThat(run("maturityDate", "after", false, "2026-12-31")).isEqualTo("TRD where maturityDate >= '2027-01-01'");
        assertThat(run("maturityDate", "relative", false, "last 5 business days"))
                .isEqualTo("TRD where (maturityDate >= '2026-10-05' and maturityDate < '2026-10-10')");
        assertThatThrownBy(() -> run("maturityDate", "on", false, "yesterday-ish")).hasMessageContaining("not a date");
        assertThatThrownBy(() -> run("maturityDate", "relative", false, "whenever")).hasMessageContaining("not a range");
    }

    @Test
    void relativeRangesOnTheCalendar() {
        assertThat(RelativeDates.resolve("next 3 business days", TODAY, CAL)).get().extracting(RelativeDates.Range::from, RelativeDates.Range::to)
                .containsExactly(TODAY, LocalDate.of(2026, 10, 14));                 // Fri, then Tue (Mon 12 Oct is a US holiday)
        assertThat(RelativeDates.resolve("month to date", TODAY, CAL)).get().extracting(RelativeDates.Range::from).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(RelativeDates.resolve("last 3 days", TODAY, CAL)).get().extracting(RelativeDates.Range::from).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(RelativeDates.resolve("last 1 business day", LocalDate.of(2026, 10, 10), CAL)).get()
                .extracting(RelativeDates.Range::to).isEqualTo(TODAY);               // a Saturday: the Friday
        assertThat(RelativeDates.resolve("last month", TODAY, CAL)).get().extracting(RelativeDates.Range::to).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void enumIdAndBool() {
        assertThat(run("book", "in", false, "RATES-1")).isEqualTo("TRD where book = 'RATES-1'");
        assertThat(run("book", "in", false, "A", "B")).isEqualTo("TRD where (book = 'A' or book = 'B')");
        assertThat(run("tradeId", "in", false, "MX-1 MX-2,MX-3\nMX-1")).isEqualTo("TRD where (tradeId = 'MX-1' or tradeId = 'MX-2' or tradeId = 'MX-3')");
        assertThat(run("tradeId", "in", false, "MX-2*")).isEqualTo("TRD where matches(tradeId, '^MX-2.*$')");
        assertThat(run("isActive", "is", false, "true")).isEqualTo("TRD where isActive = true");
        assertThat(run("isActive", "is", false, "false")).isEqualTo("TRD where isActive = false");
        assertThat(compiler.compile("TRD", spec(), List.of(new Criterion("isActive", "is", List.of("any"), false)), null, false, 0, TODAY, CAL)).isEqualTo("TRD");
        assertThatThrownBy(() -> run("tradeId", "in", false, "a b c d e f")).hasMessageContaining("at most 5");
    }

    @Test
    void combinedWithOrderAndLimit() {
        String c = compiler.compile("TRD", spec(), List.of(new Criterion("mtm", ">", List.of("1m"), false),
                new Criterion("book", "in", List.of("X"), false), new Criterion("counterparty.name", "contains", List.of(""), false)),
                "mtm", true, 50, TODAY, CAL);
        assertThat(c).isEqualTo("TRD where mtm > 1m and book = 'X' order by mtm desc limit 50");
        assertThat(SearchQuery.pick(c).limit()).isEqualTo(50);
    }

    @Test
    void refusalsAreClear() {
        assertThatThrownBy(() -> run("nope", "equals", false, "x")).hasMessageContaining("not a field of this form");
        assertThatThrownBy(() -> run("mtm", "contains", false, "x")).hasMessageContaining("not an operator of a amount field");
        assertThatThrownBy(() -> run("counterparty.name", "equals", false, "a'b\"c")).hasMessageContaining("both kinds of quote");
        FormSpec required = FormSpec.parse("trade", List.of(Map.of("path", "book", "type", "enum", "required", true)), new ArrayList<>());
        assertThatThrownBy(() -> compiler.compile("TRD", required, List.of(), null, false, 0, TODAY, CAL)).hasMessageContaining("Book is required");
    }

    @Test
    void regexRefusalsCarryTheirCode() {
        for (String bad : new String[] {"(a)\\1", "(?=x)y", "a++", "\\bword", "(", "[a-", "x{1000}"}) {
            assertThatThrownBy(() -> run("counterparty.name", "regex", false, bad)).as(bad).isInstanceOfSatisfying(DrishtiException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.REGEX_REFUSED));
        }
        assertThatThrownBy(() -> run("counterparty.name", "regex", false, "a".repeat(201))).hasMessageContaining("limit is 200");
    }
}
