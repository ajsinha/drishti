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
package com.ash.drishti.server.edit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.rachana.model.Form;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /api/v1/forms/check} (RACHANA_REFERENCE.md section 10): the cases of the form expressions through the endpoint's own answer
 * (issues, computed defaults, hints, typed values), the book desk's real form through HTTP, a person who may not open the form's kind
 * refused, and the time budget of ten fields.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=trading,counterparty-risk",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/formcheck-${random.uuid}.db",
        "drishti.security.roles.trader.kinds[0]=*", "drishti.security.roles.trader.raw=true", "drishti.security.roles.trader.edit=true",
        "drishti.security.roles.outsider.kinds[0]=risk-metric"})
@AutoConfigureMockMvc
class FormCheckControllerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);
    private final ObjectMapper json = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    /** A small form in the shape a Sutra writes it: a required limit-bound amount, a default worked out, a lookup, a rule and a hint. */
    private static Form form() {
        return Form.fromOption(Map.of(
                "title", "Test", "key", "id",
                "steps", List.of(Map.of("id", "a", "label", "A"), Map.of("id", "b", "label", "B")),
                "fields", List.of(
                        Map.of("id", "id", "label", "Id", "readonly", true),
                        Map.of("id", "party", "label", "Party", "type", "text", "step", "a"),   // its carried record is sent beside it
                        Map.of("id", "notional", "label", "Notional", "type", "money", "min", 0, "required", true, "step", "a"),
                        Map.of("id", "fee", "label", "Fee", "type", "money", "default", "=notional * 0.001", "step", "a"),
                        Map.of("id", "maturity", "label", "Maturity", "type", "date", "min", "today", "step", "a"),
                        Map.of("id", "comment", "label", "Comment", "type", "textarea", "step", "b")),
                "rules", List.of(
                        Map.of("rule", "notional <= 30000000", "severity", "warn", "fields", List.of("notional"), "message", "Above the desk limit."),
                        Map.of("rule", "not (notional > 30000000 and empty(comment))", "fields", List.of("comment"), "message", "Say why.")),
                "hints", List.of(Map.of("label", "Limit after this trade", "value", "party.USED + notional", "of", "party.LIMIT", "step", "b"))))
                .orElseThrow();
    }

    private JsonNode answer(String body) throws Exception {
        return json.valueToTree(FormCheckController.answer(form(), List.of("*"), json.readTree(body), TODAY));
    }

    @Test
    void anAmountTypedAsPeopleTypeItIsReadAsTheServerReadsIt() throws Exception {
        JsonNode a = answer("{\"values\":{\"notional\":\"1.5m\"}}");
        assertThat(a.path("typed").path("notional").decimalValue()).isEqualByComparingTo("1500000");
        assertThat(a.path("typed").path("comment").isNull()).isTrue();
        assertThat(a.path("issues")).isEmpty();
    }

    @Test
    void aMissingRequiredValueAndABrokenRuleAreIssuesInPlainWords() throws Exception {
        JsonNode blank = answer("{\"values\":{}}");
        assertThat(blank.path("issues").get(0).path("field").asText()).isEqualTo("notional");
        assertThat(blank.path("issues").get(0).path("message").asText()).isEqualTo("Notional is required");
        JsonNode big = answer("{\"values\":{\"notional\":\"40m\"}}");
        assertThat(big.path("issues").findValuesAsText("message")).contains("Above the desk limit.", "Say why.");
        assertThat(big.path("issues").findValuesAsText("severity")).contains("warn", "error");
        assertThat(answer("{\"values\":{\"notional\":\"abc\"}}").path("issues").get(0).path("message").asText()).contains("is an amount");
    }

    @Test
    void aDefaultWorkedOutIsComputedAndAnAutoFieldIsWorkedOutAgain() throws Exception {
        JsonNode a = answer("{\"values\":{\"notional\":\"2,000,000\"}}");
        assertThat(a.path("computed").path("fee").decimalValue()).isEqualByComparingTo("2000");
        JsonNode again = answer("{\"values\":{\"notional\":\"3000000\",\"fee\":2000},\"auto\":[\"fee\"]}");
        assertThat(again.path("computed").path("fee").decimalValue()).isEqualByComparingTo("3000");
        JsonNode typedOver = answer("{\"values\":{\"notional\":\"3000000\",\"fee\":5}}");
        assertThat(typedOver.path("computed").has("fee")).isFalse();
        assertThat(typedOver.path("typed").path("fee").decimalValue()).isEqualByComparingTo("5");
    }

    @Test
    void aHintIsEvaluatedAndWordedOnTheServer() throws Exception {
        String sent = "{\"values\":{\"notional\":\"1000\"},\"step\":\"b\",\"carried\":{\"party\":{\"USED\":4000,\"LIMIT\":10000}}}";
        JsonNode h = answer(sent).path("hints").get(0);
        assertThat(h.path("value").decimalValue()).isEqualByComparingTo("5000");
        assertThat(h.path("of").decimalValue()).isEqualByComparingTo("10000");
        assertThat(h.path("percent").asInt()).isEqualTo(50);
        assertThat(h.path("over").asBoolean()).isFalse();
        assertThat(h.path("text").asText()).isEqualTo("Limit after this trade: 5,000 of 10,000 (50%)");
        assertThat(answer(sent.replace("\"USED\":4000", "\"USED\":14000")).path("hints").get(0).path("over").asBoolean()).isTrue();
        assertThat(answer("{\"values\":{},\"step\":\"b\"}").path("hints").get(0).path("text").asText()).endsWith("fill in the fields above to see it");
        assertThat(answer("{\"values\":{},\"step\":\"a\"}").path("hints")).isEmpty();                 // the hint belongs to step b
        assertThat(answer("{\"values\":{\"notional\":1},\"step\":\"b\",\"carried\":{\"party\":{\"USED\":\"•••\",\"LIMIT\":5}}}")
                .path("hints").get(0).path("text").asText()).endsWith("Hidden for you");
    }

    @Test
    void tenFieldsAreAnsweredUnder20Milliseconds() throws Exception {
        JsonNode body = json.readTree("{\"values\":{\"notional\":\"1m\",\"maturity\":\"2027-01-01\"},\"step\":\"b\",\"carried\":{\"party\":{\"USED\":1,\"LIMIT\":2}}}");
        Form f = form();
        for (int i = 0; i < 200; i++) {
            FormCheckController.answer(f, List.of("*"), body, TODAY);          // warm up
        }
        long t0 = System.nanoTime();
        int n = 500;
        for (int i = 0; i < n; i++) {
            FormCheckController.answer(f, List.of("*"), body, TODAY);
        }
        double ms = (System.nanoTime() - t0) / 1e6 / n;
        assertThat(ms).as("ms per check").isLessThan(20.0);
    }

    @Test
    void theBookDesksFormIsCheckedThroughHttpAsTheCaller() throws Exception {
        String tia = "Bearer " + tokens.mint("tia", List.of("trader"), 300);
        String body = "{\"sutra\":\"book-desk\",\"panel\":\"trades\",\"values\":{\"notional\":\"-5\"},\"step\":\"what\"}";
        String out = mvc.perform(post("/api/v1/forms/check").header("Authorization", tia).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode a = json.readTree(out);
        assertThat(a.path("issues").findValuesAsText("message")).anyMatch(m -> m.startsWith("Notional is at least"));
        assertThat(a.path("typed").path("notional").decimalValue()).isEqualByComparingTo("-5");
        mvc.perform(post("/api/v1/forms/check").header("Authorization", tia).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sutra\":\"book-desk\",\"panel\":\"nope\",\"values\":{}}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/forms/check").header("Authorization", tia).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sutra\":\"no-such-sutra\",\"panel\":\"trades\",\"values\":{}}")).andExpect(status().isNotFound());
    }

    @Test
    void aPersonWhoMayNotOpenTheFormsKindIsRefusedAndNoTokenIsRefused() throws Exception {
        String body = "{\"sutra\":\"book-desk\",\"panel\":\"trades\",\"values\":{}}";
        String out = "Bearer " + tokens.mint("olga", List.of("outsider"), 300);
        mvc.perform(post("/api/v1/forms/check").header("Authorization", out).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is4xxClientError());
        mvc.perform(post("/api/v1/forms/check").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
    }
}
