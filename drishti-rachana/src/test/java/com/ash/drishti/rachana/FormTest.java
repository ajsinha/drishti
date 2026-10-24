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
package com.ash.drishti.rachana;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.rachana.model.Form;
import com.ash.drishti.rachana.model.FormExpr;
import com.ash.drishti.rachana.parse.SutraParser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A panel's {@code form:} (SUTRA_NEXT.md 10): the grammar, its lint (DRS-2058), the expressions and the checks the server runs. */
class FormTest {

    private final SutraParser parser = new SutraParser();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);

    static final String SUTRA = """
            rachana: 1
            sutra: desk
            version: 1
            match: { kind: book }
            title: { id: $.id }
            panels:
              - id: trades
                kind: table
                rows: $.TRADES
                columns:
                  - { label: Trade, bind: "@.TRADE_ID" }
                  - { label: Notional, bind: "@.NOTIONAL" }
                form:
                  key: trade_id
                  edit: beside
                  new: drawer
                  steps: [ { id: who, label: Who }, { id: what, label: What } ]
                  fields:
                    - { id: trade_id, bind: TRADE_ID, readonly: true, new: false }
                    - { id: counterparty, bind: COUNTERPARTY, type: lookup, required: true, step: who,
                        options: { source: { kind: credit-limit, where: "nonblank(@.COUNTERPARTY)" }, value: COUNTERPARTY, carry: [LIMIT, USED] } }
                    - { id: notional, label: Notional, bind: NOTIONAL, type: number, min: 0, max: 1e9, required: true, step: what }
                    - { id: status, label: Status, type: enum, options: [PENDING, VALIDATED], editable-by: [middle-office] }
                    - { id: maturity, label: Maturity, type: date, min: today }
                    - { id: code, label: Code, pattern: "[A-Z]{3}" }
                    - { id: comment, label: Comment, type: textarea, max-length: 10 }
                    - { id: ccy, label: Currency, type: enum, options: [USD, EUR] }
                    - { id: fee, label: Fee, type: money, currency: ccy, min: 0, default: "=notional * 0.01" }
                    - { id: premium, label: Premium, type: money, currency: USD, max: 5e6 }
                    - { id: executed, label: Executed, type: datetime, tz: Europe/London, min: "2026-01-01T09:00" }
                    - { id: guarantor, label: Guarantor, type: entity, default: CP-NONE,
                        options: { source: { pack: core, kind: counterparty }, label: NAME, carry: [RATING] } }
                  rules:
                    - { rule: "notional <= 30000000", severity: warn, fields: [notional], message: "Above the desk limit: a second approver is needed" }
                    - { rule: "not (notional > 30000000 and empty(comment))", fields: [comment], message: "Say why" }
                  hints:
                    - { label: Headroom, value: "counterparty.USED + notional", of: "counterparty.LIMIT", step: what }
            """;

    private Form form() {
        return parser.parse(SUTRA, "desk.yaml", "x").panels().get(0).form().orElseThrow();
    }

    @Test
    void aFormReads() {
        Form f = form();
        assertThat(f.edit()).isEqualTo("beside");
        assertThat(f.create()).isEqualTo("drawer");
        assertThat(f.fields()).extracting(Form.Field::id).containsExactly("trade_id", "counterparty", "notional", "status", "maturity", "code", "comment", "ccy", "fee",
                "premium", "executed", "guarantor");
        assertThat(f.field("counterparty").orElseThrow().lookup().source().where()).isEqualTo("nonblank(@.COUNTERPARTY)");
        assertThat(f.field("trade_id").orElseThrow().onNew()).isFalse();
        assertThat(f.rules()).extracting(Form.Rule::error).containsExactly(false, true);
        assertThat(f.hints().get(0).of().text()).isEqualTo("counterparty.LIMIT");
    }

    @Test
    void lintSaysWhatIsWrongInWords() {
        Map<String, String> bad = Map.of(
                "edit: beside", "edit: inline",
                "type: number, min: 0", "type: currency, min: 0",
                "\"notional <= 30000000\"", "\"notionl <= 30000000\"",
                "severity: warn", "severity: maybe",
                "step: what }", "step: later }",
                "max-length: 10", "max-length: zero",
                "key: trade_id", "key: nothing",
                "pattern: \"[A-Z]{3}\"", "pattern: \"[A-Z\"");
        bad.forEach((from, to) -> assertThatThrownBy(() -> parser.parse(SUTRA.replace(from, to), "bad.yaml", "x")).as(to)
                .isInstanceOfSatisfying(SutraException.class, e -> assertThat(e.problems()).extracting(SutraProblem::code).contains("DRS-2058")));
        assertThatThrownBy(() -> parser.parse(SUTRA.replace("\"notional <= 30000000\"", "\"notionl <= 30000000\""), "bad.yaml", "x"))
                .isInstanceOfSatisfying(SutraException.class, e -> assertThat(e.problems()).extracting(SutraProblem::message)
                        .anySatisfy(m -> assertThat(m).contains("reads 'notionl', which is not a field of the form")));
        assertThat(Form.problems(Map.of("fields", List.of(Map.of("id", "a"))))).anySatisfy(m -> assertThat(m).contains("says how it opens"));
        assertThat(Form.problems(Map.of("edit", "cells", "fields", List.of(Map.of("id", "a"))))).anySatisfy(m -> assertThat(m).contains("key:"));
    }

    @Test
    void expressions() {
        Map<String, Object> v = new HashMap<>();
        v.put("notional", new BigDecimal("40000000"));
        v.put("counterparty", Map.of("LIMIT", 120_000_000, "USED", 82_400_000));
        v.put("maturity", "2027-01-15");
        assertThat(FormExpr.parse("counterparty.USED + notional").eval(v, TODAY)).isEqualTo(new BigDecimal("122400000"));
        assertThat(FormExpr.parse("notional <= 30_000_000").holds(v, TODAY)).isFalse();
        assertThat(FormExpr.parse("maturity > today and len('abc') == 3").holds(v, TODAY)).isTrue();
        assertThat(FormExpr.parse("missing > 3").holds(v, TODAY)).as("empty: it cannot be told, so it holds").isTrue();
        assertThat(FormExpr.parse("empty(missing) or missing > 3").holds(v, TODAY)).isTrue();
        assertThat(FormExpr.parse("min(notional, 5) == 5 and max(-1, abs(-2)) == 2").holds(v, TODAY)).isTrue();
        assertThat(FormExpr.parse("not (notional > 1 and empty(comment))").names()).containsExactly("notional", "comment");
        for (String bad : List.of("a +", "(a", "foo(a)", "len(a, b)", "a ; b", "'open", "a..b")) {
            assertThatThrownBy(() -> FormExpr.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theServerChecksValuesAndRules() {
        Form f = form();
        Set<String> all = Set.of("notional", "status", "maturity", "code", "comment", "counterparty");
        Map<String, Object> v = new HashMap<>(Map.of("notional", "40,000,000", "counterparty", "CP-A", "maturity", "2027-01-15", "code", "ABC"));
        List<Form.Issue> issues = f.check(v, all, TODAY);
        assertThat(issues).extracting(Form.Issue::message).containsExactly("Above the desk limit: a second approver is needed", "Say why");
        assertThat(issues).extracting(Form.Issue::severity).containsExactly("warn", "error");
        v.put("comment", "too long for ten");
        v.put("maturity", "2026-01-01");
        v.put("code", "abc");
        v.put("status", "LOST");
        v.remove("counterparty");
        assertThat(f.check(v, all, TODAY)).extracting(Form.Issue::message).contains("Comment is at most 10 characters",
                "Maturity is on or after 2026-10-24", "Code is not in the expected form", "Status is one of PENDING, VALIDATED", "counterparty is required");
        assertThat(f.check(Map.of("notional", "lots"), Set.of("notional"), TODAY)).extracting(Form.Issue::message).containsExactly("Notional is a number");
        assertThat(f.check(Map.of("notional", "-5"), Set.of("notional"), TODAY)).extracting(Form.Issue::message).containsExactly("Notional is at least 0");
    }

    @Test
    void rolesAndReadOnly() {
        Form f = form();
        assertThat(f.editableBy(f.field("status").orElseThrow(), List.of("trader"))).isFalse();
        assertThat(f.editableBy(f.field("status").orElseThrow(), List.of("middle-office"))).isTrue();
        assertThat(f.editableBy(f.field("trade_id").orElseThrow(), List.of("middle-office"))).isFalse();
        assertThat(f.enterableBy(f.field("trade_id").orElseThrow(), List.of("trader"))).as("not asked on new").isFalse();
        assertThat(f.enterableBy(f.field("notional").orElseThrow(), List.of("trader"))).isTrue();
    }

    @Test
    void moneyDatetimeEntityAndDefaults() {
        Form f = form();
        assertThat(f.field("fee").orElseThrow().currency()).isEqualTo("ccy");
        assertThat(f.field("executed").orElseThrow().zone()).isEqualTo("Europe/London");
        assertThat(f.field("guarantor").orElseThrow().lookup().value()).as("an entity keeps its own id").isNull();
        assertThat(f.field("guarantor").orElseThrow().lookup().source().where()).isNull();
        assertThat(Form.amount("1.5m")).isEqualByComparingTo("1500000");
        assertThat(Form.amount("250k")).isEqualByComparingTo("250000");
        assertThat(Form.amount("1,000,000")).isEqualByComparingTo("1000000");
        assertThat(Form.amount("2bn")).isEqualByComparingTo("2000000000");
        assertThat(Form.amount("lots")).isNull();
        Set<String> c = Set.of("fee", "premium", "executed", "guarantor");
        assertThat(f.check(Map.of("fee", "1.5m", "premium", "250k", "executed", "2026-03-02T10:30", "guarantor", "CP-X"), c, TODAY)).isEmpty();
        assertThat(f.check(Map.of("fee", "-1", "premium", "6m", "executed", "2025-12-31T23:00"), c, TODAY)).extracting(Form.Issue::message)
                .containsExactly("Fee is at least 0", "Premium is at most 5,000,000", "Executed is on or after 2026-01-01 09:00 Europe/London");
        assertThat(f.check(Map.of("fee", "lots", "executed", "2026-03-02"), c, TODAY)).extracting(Form.Issue::message)
                .containsExactly("Fee is an amount (1,000,000, 1.5m or 250k)", "Executed is a date and time (2026-12-31T17:00)");
        Map<String, Object> filled = f.withDefaults(Map.of("notional", "40m"), Set.of("fee", "guarantor"), TODAY);
        assertThat(filled.get("fee")).isEqualTo(new BigDecimal("4E+5"));
        assertThat(filled.get("guarantor")).isEqualTo("CP-NONE");
        assertThat(f.withDefaults(Map.of("notional", "40m", "fee", "7"), Set.of("fee"), TODAY).get("fee")).as("a value given stays").isEqualTo("7");
        assertThat(f.withDefaults(Map.of("notional", "40m"), Set.of(), TODAY)).doesNotContainKey("fee");
        Map<String, String> bad = Map.of(
                "currency: ccy,", "currency: cash,",
                "type: money, currency: USD,", "type: money,",
                "tz: Europe/London", "tz: Mars/Olympus",
                "min: \"2026-01-01T09:00\"", "min: \"soon\"",
                "\"=notional * 0.01\"", "\"=notionl * 0.01\"",
                "kind: counterparty }", "kind: counterparty, where: x, search: y }");
        bad.forEach((from, to) -> assertThatThrownBy(() -> parser.parse(SUTRA.replace(from, to), "bad.yaml", "x")).as(to)
                .isInstanceOfSatisfying(SutraException.class, e -> assertThat(e.problems()).extracting(SutraProblem::code).contains("DRS-2058")));
        assertThatThrownBy(() -> parser.parse(SUTRA.replace("currency: ccy,", "currency: cash,"), "bad.yaml", "x"))
                .isInstanceOfSatisfying(SutraException.class, e -> assertThat(e.problems()).extracting(SutraProblem::message)
                        .anySatisfy(m -> assertThat(m).contains("currency is a code (USD) or the id of the field holding it")));
    }

    @Test
    void theJsonSchemaDescribesForms() {
        String schema = RachanaSchema.build(List.of("amount0"), List.of("trade")).toString();
        assertThat(schema).contains("form=").contains("editable-by").contains("drawer").contains("money").contains("datetime").contains("entity")
                .contains("tz=");
    }
}
