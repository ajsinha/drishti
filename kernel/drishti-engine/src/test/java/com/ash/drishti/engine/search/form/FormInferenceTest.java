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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The form model (declared fields, problems) and the form inferred when a pack declares none. */
class FormInferenceTest {

    @Test
    void declaredFieldsKeepTheirSettingsAndOrder() {
        List<String> problems = new ArrayList<>();
        FormSpec s = FormSpec.parse("trade", Map.of("calendar", "USNY", "fields", List.of(
                Map.of("path", "$.book", "type", "enum", "values", List.of("A", "B"), "order", 2, "group", "Where", "help", "The book"),
                Map.of("path", "mtm", "type", "amount", "operators", List.of(">", "between"), "default", "1m", "order", 1, "required", true),
                Map.of("path", "name", "hidden", true))), problems);
        assertThat(problems).isEmpty();
        assertThat(s.calendar()).isEqualTo("USNY");
        assertThat(s.fields()).extracting(FormField::path).containsExactly("mtm", "book", "name");        // ordered ones first
        assertThat(s.field("mtm")).get().satisfies(f -> {
            assertThat(f.operators()).containsExactly(">", "between");
            assertThat(f.required()).isTrue();
            assertThat(f.defaultValue()).isEqualTo("1m");
            assertThat(f.label()).isEqualTo("Mtm");
        });
        assertThat(s.field("name").orElseThrow().hidden()).isTrue();
        assertThat(s.field("book").orElseThrow().values()).containsExactly("A", "B");
    }

    @Test
    void problemsAreNamedClearly() {
        List<String> problems = new ArrayList<>();
        FormSpec.parse("trade", List.of(Map.of("path", "a b"), Map.of("path", "x", "type", "money"), Map.of("path", "y", "type", "text", "operators", List.of("between")),
                Map.of("path", "z", "values", List.of("q")), Map.of("path", "x"), Map.of("path", "x"), "oops"), problems);
        assertThat(problems).anyMatch(p -> p.contains("form.trade.fields[0]") && p.contains("not a field path"));
        assertThat(problems).anyMatch(p -> p.contains("type 'money' is not one of"));
        assertThat(problems).anyMatch(p -> p.contains("operator 'between' does not suit type text"));
        assertThat(problems).anyMatch(p -> p.contains("values belongs to type enum"));
        assertThat(problems).anyMatch(p -> p.contains("x appears twice"));
        assertThat(problems).anyMatch(p -> p.contains("[6] must be a mapping"));
        List<String> more = new ArrayList<>();
        FormSpec.parse("trade", "nonsense", more);
        assertThat(more).anyMatch(p -> p.contains("needs a list of fields"));
        FormSpec.parse("trade", Map.of("fields", List.of(), "colour", "red"), more);
        assertThat(more).anyMatch(p -> p.contains("unknown key 'colour'"));
    }

    @Test
    void inferredFromColumnsMatchAndStrip() {
        FormSpec s = new FormInference(10, 6).infer("trade", List.of("$.tradeId", "$.currency", "$.notional", "$.maturityDate", "$.direction", "$.counterparty.name"),
                List.of("$.assetClass == 'Rates' && $.isActive"), List.of("$.mtm", "fmt($.pnl, 'x')"), p -> p.equals("notional") ? "Notional amount" : null,
                Map.of("currency", List.<Object>of("USD", "EUR", "USD", "EUR", "USD", "EUR"), "notional", List.<Object>of(1.0, 2.0),
                        "tradeId", List.<Object>of("MX-1", "MX-2"), "maturityDate", List.<Object>of("2027-01-01", "2028-01-01"),
                        "isActive", List.<Object>of(true, false)));
        assertThat(s.inferred()).isTrue();
        assertThat(s.fields()).extracting(FormField::path).containsExactly("tradeId", "currency", "notional", "maturityDate", "direction", "counterparty.name",
                "assetClass", "isActive", "mtm");
        assertThat(types(s)).containsEntry("tradeId", FieldType.ID).containsEntry("currency", FieldType.ENUM).containsEntry("notional", FieldType.AMOUNT)
                .containsEntry("maturityDate", FieldType.DATE).containsEntry("direction", FieldType.TEXT).containsEntry("isActive", FieldType.BOOL)
                .containsEntry("mtm", FieldType.AMOUNT);
        assertThat(s.field("notional").orElseThrow().label()).isEqualTo("Notional amount");
        assertThat(s.field("counterparty.name").orElseThrow().label()).isEqualTo("Counterparty name");
        assertThat(s.field("currency").orElseThrow().values()).containsExactly("EUR", "USD");
        assertThat(s.fields()).allMatch(FormField::inferred);
    }

    @Test
    void inferenceHonoursTheFieldCap() {
        FormSpec s = new FormInference(2, 6).infer("k", List.of("a", "b", "c"), List.of(), List.of(), null, Map.of());
        assertThat(s.fields()).hasSize(2);
    }

    private static Map<String, FieldType> types(FormSpec s) {
        Map<String, FieldType> out = new java.util.LinkedHashMap<>();
        s.fields().forEach(f -> out.put(f.path(), f.type()));
        return out;
    }
}
