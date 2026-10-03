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
package com.ash.drishti.engine.shape;

import static com.ash.drishti.engine.shape.ShapeTestSupport.at;
import static com.ash.drishti.engine.shape.ShapeTestSupport.infer;
import static com.ash.drishti.engine.shape.ShapeTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One test per merge rule of the design's table. */
class ShapeMergeRulesTest {

    @Test
    void schemaIsDraft2020AndCountsSamples() {
        Shape s = infer("{\"a\":1}");
        assertThat(s.schema().get("$schema").asText()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(at(s, "/x-drishti/samples").asInt()).isEqualTo(1);
    }

    @Test
    void presentInEveryDocumentIsRequiredAndInSomeIsOptionalWithPresence() {
        Shape s = infer("{\"a\":1,\"b\":2}", "{\"a\":2}", "{\"a\":3}", "{\"a\":4}");
        assertThat(texts(at(s, "/required"))).containsExactly("a");
        assertThat(at(s, "/properties/b/x-drishti/presence").asDouble()).isEqualTo(0.25);
        assertThat(at(s, "/properties/a/x-drishti/presence").isMissingNode()).isTrue();
        assertThat(s.report().rare()).extracting(PathRow::path).containsExactly("$.b");
        assertThat(s.report().rare().get(0).files()).containsExactly("doc1.json");
    }

    @Test
    void nullInSomeMakesTheTypeNullable() {
        Shape s = infer("{\"a\":1}", "{\"a\":null}");
        assertThat(texts(at(s, "/properties/a/type"))).containsExactly("integer", "null");
        assertThat(texts(at(s, "/required"))).containsExactly("a");
    }

    @Test
    void differentTypesBecomeOneOfAndAreFlaggedAsAConflictFirstInTheReport() {
        Shape s = infer("{\"z\":1,\"a\":1}", "{\"z\":2,\"a\":\"x\"}");
        JsonNode a = at(s, "/properties/a");
        assertThat(a.get("oneOf")).hasSize(2);
        assertThat(texts(a.at("/x-drishti/conflict"))).containsExactly("string", "integer");
        assertThat(s.report().conflicts()).extracting(Conflict::path).containsExactly("$.a");
        assertThat(s.report().paths().get(0).path()).isEqualTo("$.a");
        assertThat(s.report().paths().get(0).conflict()).isTrue();
    }

    @Test
    void integerAndFractionMergeToNumberWithoutConflict() {
        Shape s = infer("{\"a\":1}", "{\"a\":2.5}");
        assertThat(at(s, "/properties/a/type").asText()).isEqualTo("number");
        assertThat(s.report().conflicts()).isEmpty();
    }

    @Test
    void objectsWhoseKeysVaryBetweenDocumentsAreAMap() {
        Shape s = infer("{\"px\":{\"AAA\":1.5,\"BBB\":2.5,\"CCC\":3.5,\"DDD\":4.5}}", "{\"px\":{\"EEE\":1.5,\"FFF\":2.5,\"GGG\":3.5,\"HHH\":4.5}}");
        JsonNode px = at(s, "/properties/px");
        assertThat(px.get("type").asText()).isEqualTo("object");
        assertThat(px.at("/additionalProperties/type").asText()).isEqualTo("number");
        assertThat(px.has("properties")).isFalse();
        assertThat(px.at("/x-drishti/map").asBoolean()).isTrue();
    }

    @Test
    void idLikeKeysInOneDocumentAreAMapButFixedFieldNamesAreARecord() {
        Shape map = infer("{\"m\":{\"TRD-1\":{\"q\":1},\"TRD-2\":{\"q\":2},\"TRD-3\":{\"q\":3},\"TRD-4\":{\"q\":4},\"TRD-5\":{\"q\":5}}}");
        assertThat(at(map, "/properties/m/additionalProperties/properties/q/type").asText()).isEqualTo("integer");
        Shape rec = infer("{\"m\":{\"price\":1,\"qty\":2,\"side\":3,\"venue\":4,\"book\":5}}");
        assertThat(at(rec, "/properties/m/properties/price/type").asText()).isEqualTo("integer");
        assertThat(at(rec, "/properties/m/additionalProperties").isMissingNode()).isTrue();
    }

    @Test
    void arraysMergeEveryElementOfEveryDocumentWithLengthBounds() {
        Shape s = infer("{\"xs\":[{\"a\":1},{\"a\":2,\"b\":\"x\"}]}", "{\"xs\":[]}", "{\"xs\":[{\"a\":3}]}");
        JsonNode xs = at(s, "/properties/xs");
        assertThat(xs.get("minItems").asInt()).isZero();
        assertThat(xs.get("maxItems").asInt()).isEqualTo(2);
        assertThat(texts(xs.at("/items/required"))).containsExactly("a");
        assertThat(xs.at("/items/properties/b/x-drishti/presence").asDouble()).isEqualTo(0.333);
    }

    @Test
    void aListOfRecordsHoldingAListOfTheSameRecordsIsARecursiveRef() {
        Shape s = infer("{\"units\":[{\"name\":\"G\",\"x\":1,\"children\":[{\"name\":\"R\",\"x\":2,\"children\":[{\"name\":\"R1\",\"x\":3}]},"
                + "{\"name\":\"C\",\"x\":4}]}]}");
        JsonNode items = at(s, "/properties/units/items");
        assertThat(items.get("$ref").asText()).startsWith("#/$defs/");
        JsonNode def = s.schema().at(items.get("$ref").asText().substring(1));
        assertThat(def.at("/properties/children/items/$ref").asText()).isEqualTo(items.get("$ref").asText());
        assertThat(texts(def.get("required"))).containsExactly("name", "x");
        assertThat(s.roles().get("$.units").role()).isEqualTo("tree");
        assertThat(new MiniSchemaValidator(s.schema()).validate(json("{\"units\":[{\"name\":\"a\",\"x\":1,\"children\":[{\"name\":\"b\",\"x\":2,"
                + "\"children\":[{\"name\":\"c\",\"x\":3,\"children\":[]}]}]}]}"))).isEmpty();
    }

    @Test
    void aRootThatIsItselfATreeRefersBackToTheRoot() {
        Shape s = infer("{\"name\":\"a\",\"size\":1,\"children\":[{\"name\":\"b\",\"size\":2,\"children\":[{\"name\":\"c\",\"size\":3}]}]}");
        assertThat(at(s, "/properties/children/items/$ref").asText()).isEqualTo("#");
    }

    @Test
    void lowCardinalityTextIsAnEnumOnlyWhenValuesRepeat() {
        Shape s = infer("{\"k\":\"A\",\"u\":\"p\"}", "{\"k\":\"B\",\"u\":\"q\"}", "{\"k\":\"A\",\"u\":\"r\"}", "{\"k\":\"B\",\"u\":\"s\"}");
        assertThat(texts(at(s, "/properties/k/enum"))).containsExactly("A", "B");
        assertThat(at(s, "/properties/u/enum").isMissingNode()).isTrue();
    }

    @Test
    void anEnumKeepsNullWhenNullWasSeen() {
        Shape s = infer("{\"k\":\"A\"}", "{\"k\":\"B\"}", "{\"k\":\"A\"}", "{\"k\":null}");
        assertThat(at(s, "/properties/k/enum").toString()).contains("null");
        assertThat(new MiniSchemaValidator(s.schema()).validate(json("{\"k\":null}"))).isEmpty();
    }

    @Test
    void tooManyDistinctValuesIsNoEnum() {
        List<String> docs = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            docs.add("{\"k\":\"v" + (i % 20) + "\"}");
        }
        assertThat(at(infer(docs.toArray(String[]::new)), "/properties/k/enum").isMissingNode()).isTrue();
    }

    @Test
    void textThatIsAlwaysADateDateTimeUuidEmailOrCurrencyHasAFormat() {
        Shape s = infer("{\"d\":\"2026-09-01\",\"t\":\"2026-09-01T10:00:00Z\",\"u\":\"123e4567-e89b-12d3-a456-426614174000\","
                + "\"e\":\"a@b.com\",\"c\":\"USD\",\"x\":\"2026-09-01\"}", "{\"d\":\"2026-10-01\",\"t\":\"2026-09-02T10:00:00+01:00\","
                + "\"u\":\"123e4567-e89b-12d3-a456-426614174001\",\"e\":\"c@d.org\",\"c\":\"EUR\",\"x\":\"hello\"}");
        assertThat(at(s, "/properties/d/format").asText()).isEqualTo("date");
        assertThat(at(s, "/properties/t/format").asText()).isEqualTo("date-time");
        assertThat(at(s, "/properties/u/format").asText()).isEqualTo("uuid");
        assertThat(at(s, "/properties/e/format").asText()).isEqualTo("email");
        assertThat(at(s, "/properties/c/format").asText()).isEqualTo("currency");
        assertThat(at(s, "/properties/x/format").isMissingNode()).isTrue();
    }

    @Test
    void threeUppercaseLettersThatAreNotACurrencyGetNoFormat() {
        Shape s = infer("{\"c\":\"ZZZ\"}", "{\"c\":\"QQQ\"}");
        assertThat(at(s, "/properties/c/format").isMissingNode()).isTrue();
    }

    @Test
    void numbersGetMinimumMaximumAndIntegerWhenNeverFractional() {
        Shape s = infer("{\"n\":5,\"f\":0.5}", "{\"n\":-2,\"f\":9.25}");
        assertThat(at(s, "/properties/n/type").asText()).isEqualTo("integer");
        assertThat(at(s, "/properties/n/minimum").asLong()).isEqualTo(-2);
        assertThat(at(s, "/properties/n/maximum").asLong()).isEqualTo(5);
        assertThat(at(s, "/properties/f/type").asText()).isEqualTo("number");
        assertThat(at(s, "/properties/f/maximum").asDouble()).isEqualTo(9.25);
    }

    @Test
    void maskedValuesStayMaskedInExamplesAndAreNeverEnumsOrFormats() {
        Shape s = infer("{\"k\":\"•••\"}", "{\"k\":\"•••\"}", "{\"k\":\"•••\"}");
        assertThat(at(s, "/properties/k/enum").isMissingNode()).isTrue();
        PathRow k = s.report().paths().stream().filter(r -> r.path().equals("$.k")).findFirst().orElseThrow();
        assertThat(k.examples()).containsExactly("•••");
        assertThat(k.masked()).isTrue();
        assertThat(k.role()).isEqualTo("plain");
    }

    @Test
    void reportHasTypeRolePresenceAndAtMostThreeExamplesPerPath() {
        Shape s = infer("{\"a\":1}", "{\"a\":2}", "{\"a\":3}", "{\"a\":4}", "{\"a\":5}");
        PathRow a = s.report().paths().stream().filter(r -> r.path().equals("$.a")).findFirst().orElseThrow();
        assertThat(a.examples()).hasSize(3);
        assertThat(a.type()).isEqualTo("integer");
        assertThat(a.presence()).isEqualTo(1.0);
        assertThat(s.report().files()).hasSize(5);
    }

    @Test
    void conflictsThenRareFieldsComeFirst() {
        Shape s = infer("{\"plain\":1,\"rare\":1,\"bad\":1}", "{\"plain\":2,\"bad\":\"x\"}", "{\"plain\":3,\"bad\":2}", "{\"plain\":4,\"bad\":3}");
        List<String> order = s.report().paths().stream().map(PathRow::path).toList();
        assertThat(order.subList(0, 2)).containsExactly("$.bad", "$.rare");
    }

    @Test
    void refusesMoreThanMaxSamplesAndTooDeepDocuments() {
        BuilderProperties p = new BuilderProperties(2, null, null, 3, null, null, null, null, null, null, null, null, null, null, null, null);
        var svc = ShapeTestSupport.service(p);
        assertThatThrownBy(() -> svc.infer(List.of(new Sample("a", json("{}")), new Sample("b", json("{}")), new Sample("c", json("{}")))))
                .isInstanceOf(ShapeException.class).hasMessageContaining("max-samples");
        assertThatThrownBy(() -> svc.infer(List.of(new Sample("deep", json("{\"a\":{\"b\":{\"c\":{\"d\":1}}}}")))))
                .isInstanceOf(ShapeException.class).hasMessageContaining("max-depth").hasMessageContaining("deep");
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.isNull() ? "null" : n.asText()));
        return out;
    }
}
