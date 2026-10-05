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
package com.ash.drishti.server.explain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Ask about this page end to end against an in-process fake model endpoint (never a real model): the request the endpoint
 * receives, the per-pack switch, the masks, the limits and the failure modes. {@code values: shown} is set here so the masking
 * proof has something to protect.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=market-risk,genomics,trading",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact=var99,significance",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true",
        "drishti.explain.ask.enabled=true", "drishti.explain.ask.packs[0]=market-risk", "drishti.explain.ask.values=shown",
        "drishti.explain.ask.model=fake-model", "drishti.explain.ask.api-key=test-key-123", "drishti.explain.ask.timeout=1s",
        "drishti.explain.ask.per-user-per-minute=3", "drishti.explain.ask.max-answer-chars=60",
        "drishti.identity.database-url=jdbc:sqlite:target/ask-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class AskEndToEndTest {

    static final FakeModel MODEL = new FakeModel();

    @DynamicPropertySource
    static void endpoint(DynamicPropertyRegistry r) {
        r.add("drishti.explain.ask.endpoint", () -> MODEL.url("/v1/chat/completions"));
    }

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void reset() {
        MODEL.mode = FakeModel.Mode.OK;
        MODEL.reply = "It is a value-at-risk result.";
        MODEL.requests.clear();
        MODEL.calls.set(0);
    }

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private ResultActions ask(String user, String role, String path, String question) throws Exception {
        return mvc.perform(post("/api/v1/views/" + path + "/ask").header("Authorization", as(user, role)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("question", question))));
    }

    private JsonNode explain(String user, String role, String path) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/views/" + path + "/explain").header("Authorization", as(user, role))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void aQuestionGoesThroughTheFakeEndpointAndTheAnswerComesBack() throws Exception {
        ask("ann", "full", "var/VAR-COMM", "What is this page?").andExpect(status().isOk()).andExpect(jsonPath("$.answer").value("It is a value-at-risk result."))
                .andExpect(jsonPath("$.sources[0]").value("this page's context"));
        assertThat(MODEL.calls.get()).isEqualTo(1);
        FakeModel.Seen s = MODEL.last();
        assertThat(s.authorization()).isEqualTo("Bearer test-key-123");
        JsonNode body = json.readTree(s.body());
        assertThat(body.path("model").asText()).isEqualTo("fake-model");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(400);
        assertThat(body.has("tools")).isFalse();                                  // the model has no tools
        assertThat(body.path("messages").path(0).path("role").asText()).isEqualTo("system");
        String user = body.path("messages").path(1).path("content").asText();
        assertThat(user).contains("What is this page?").contains("<<<BEGIN UNTRUSTED page-context").contains("<<<BEGIN UNTRUSTED glossary").contains("Value at risk");
        assertThat(user).doesNotContain("test-key-123");                           // the key is a header, never in the text
    }

    @Test
    void theAnswerIsCutToTheConfiguredLength() throws Exception {
        MODEL.reply = "word ".repeat(100);
        String a = json.readTree(ask("bob", "full", "var/VAR-COMM", "q").andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("answer").asText();
        assertThat(a.length()).isLessThanOrEqualTo(60);
    }

    @Test
    void aMaskedFieldsValueNeverReachesTheEndpointForACallerWhoCannotSeeIt() throws Exception {
        String fullText = explain("fran", "full", "var/VAR-COMM").path("about").path("text").asText();
        String maskedText = explain("mia", "masked", "var/VAR-COMM").path("about").path("text").asText();
        assertThat(fullText).isNotBlank().isNotEqualTo(maskedText);
        assertThat(maskedText).contains("•••");
        ask("mia", "masked", "var/VAR-COMM", "How big is VaR?").andExpect(status().isOk());
        String user = json.readTree(MODEL.last().body()).path("messages").path(1).path("content").asText();
        assertThat(user).contains("•••");                          // the mask is what the model saw
        assertThat(fullText).contains("11.0m");                                   // the figure an unmasked caller sees
        assertThat(user).doesNotContain("11.0m").doesNotContain("58%");           // ... and the masked caller's prompt has neither it nor what derives from it
    }

    @Test
    void aPackThatHasNotOptedInGetsDrs4007AndNoCall() throws Exception {
        ask("gil", "full", "variant/VRNT-APOE-E4", "What is this?").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-4007"));
        assertThat(MODEL.calls.get()).isZero();
    }

    @Test
    void theExplainAnswerSaysWhetherTheBoxMayBeDrawn() throws Exception {
        assertThat(explain("hal", "full", "var/VAR-COMM").path("ask").path("enabled").asBoolean()).isTrue();
        assertThat(explain("hal", "full", "variant/VRNT-APOE-E4").path("ask").path("enabled").asBoolean()).isFalse();
    }

    @Test
    void anEmptyOrOverlongQuestionIsRefusedBeforeAnyCall() throws Exception {
        ask("ivy", "full", "var/VAR-COMM", "   ").andExpect(status().isBadRequest());
        ask("ivy", "full", "var/VAR-COMM", "x".repeat(501)).andExpect(status().isBadRequest());
        assertThat(MODEL.calls.get()).isZero();
    }

    @Test
    void overTheRatePerUserIsDrs4009ButAnotherUserIsUnaffected() throws Exception {
        for (int i = 0; i < 3; i++) {
            ask("rate-user", "full", "var/VAR-COMM", "q" + i).andExpect(status().isOk());
        }
        ask("rate-user", "full", "var/VAR-COMM", "one too many").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("DRS-4009"));
        assertThat(MODEL.calls.get()).isEqualTo(3);
        ask("other-user", "full", "var/VAR-COMM", "q").andExpect(status().isOk());
    }

    @Test
    void aFailingEndpointIsDrs4008AndTheExplainPageStillWorks() throws Exception {
        MODEL.mode = FakeModel.Mode.ERROR;
        ask("jo", "full", "var/VAR-COMM", "q").andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("DRS-4008"));
        MODEL.mode = FakeModel.Mode.GARBAGE;
        ask("jo", "full", "var/VAR-COMM", "q").andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("DRS-4008"));
        assertThat(explain("jo", "full", "var/VAR-COMM").path("about").path("text").asText()).isNotBlank();   // the offline layers are unaffected
    }

    @Test
    void aSlowEndpointIsDrs4008With504() throws Exception {
        MODEL.mode = FakeModel.Mode.SLOW;
        ask("kay", "full", "var/VAR-COMM", "q").andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("DRS-4008"));
    }

    @Test
    void theAnthropicShapeIsSpokenToo() throws Exception {
        AskProperties c = new AskProperties(true, MODEL.url("/v1/messages"), "anthropic", "fake-claude", "k-9", List.of("p"), null, null, null, null, null, null,
                Duration.ofSeconds(5), null, null, null, null, null);
        String text = AskProviders.create(c).complete("SYS", "USER", 50, Duration.ofSeconds(5));
        assertThat(text).isEqualTo("It is a value-at-risk result.");
        FakeModel.Seen s = MODEL.last();
        assertThat(s.apiKey()).isEqualTo("k-9");
        assertThat(s.version()).isEqualTo("2023-06-01");
        JsonNode body = json.readTree(s.body());
        assertThat(body.path("system").asText()).isEqualTo("SYS");
        assertThat(body.path("messages").path(0).path("content").asText()).isEqualTo("USER");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(50);
        assertThat(body.has("tools")).isFalse();
    }
}
