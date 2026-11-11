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
package com.ash.drishti.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The provider layer with no network: config files, registry and reload, the mock, a stub OpenAI-compatible server, policy. */
class LlmLayerTest {

    @TempDir Path dir;

    private LlmRegistry registry(Map<String, String> env) {
        LlmRegistry r = new LlmRegistry(ProviderFactories.discover(List.of()), () -> ProviderConfigLoader.load(dir, env::get), "");
        r.reload();
        return r;
    }

    private void write(String name, String text) throws Exception {
        Files.writeString(dir.resolve(name), text);
    }

    private static final String MOCK = """
            type: mock
            default-model: m
            models:
              - name: m
                cost-per-1k-input: 1.0
                cost-per-1k-output: 1.0
              - name: vec
                kind: embedding
            mock:
              rules:
                - match: "(?i)hello"
                  reply: "Hi there"
                - match: "weather"
                  tool: get_weather
                  tool-args: '{"city":"Oslo"}'
                - match: "boom"
                  error: "scripted"
            """;

    @Test
    void mockProviderAnswersByRuleEchoToolsErrorsAndEmbeds() throws Exception {
        write("mock.yaml", MOCK);
        LlmRegistry reg = registry(Map.of());
        LlmGateway gw = new LlmGateway(reg, LlmPolicy.none());
        CallContext ctx = new CallContext("ann", Set.of(), "test");
        assertThat(gw.chat(ctx, ChatRequest.of(null, "sys", "Hello you", 50, null)).text()).isEqualTo("Hi there");
        assertThat(gw.chat(ctx, ChatRequest.of("mock/m", "", "plain", 50, null)).text()).isEqualTo("echo: plain");
        ChatResponse tool = gw.chat(ctx, ChatRequest.of("m", "", "the weather", 50, null));
        assertThat(tool.toolCalls()).singleElement().satisfies(c -> assertThat(c.name()).isEqualTo("get_weather"));
        assertThat(tool.usage().cost()).isGreaterThan(0);
        assertThatThrownBy(() -> gw.chat(ctx, ChatRequest.of(null, "", "boom", 5, null))).isInstanceOf(LlmException.class).hasMessage("scripted");
        assertThat(reg.embedding("mock/vec").orElseThrow().embed(List.of("abc", "abc")).get(0)).isEqualTo(reg.embedding("vec").orElseThrow().embed(List.of("abc")).get(0));
        assertThat(reg.health(reg.provider("mock").orElseThrow()).status()).isEqualTo(LlmRegistry.Status.DOWN);   // the last call failed
    }

    @Test
    void timeoutIsHonouredAndReportedAsTimeout() throws Exception {
        write("slow.yaml", "type: mock\nmodels:\n  - name: m\nmock:\n  latency-ms: 2000\n");
        LlmGateway gw = new LlmGateway(registry(Map.of()), LlmPolicy.none());
        assertThatThrownBy(() -> gw.chat(new CallContext("a", Set.of(), "t"), ChatRequest.of(null, "", "x", 5, Duration.ofMillis(100))))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.timeout()).isTrue());
    }

    @Test
    void configProblemsAreReportedPerFileAndOthersStillLoad() throws Exception {
        write("good.yaml", "type: mock\nmodels:\n  - name: m\n");
        write("literal.yaml", "type: openai\napi-key: sk-123\n");
        write("notype.yaml", "models: []\n");
        write("unknown.yaml", "type: nonesuch\nmodels:\n  - name: x\n");
        write("off.yaml", "type: mock\nenabled: false\nmodels:\n  - name: y\n");
        LlmRegistry reg = registry(Map.of());
        assertThat(reg.fileErrors()).containsKeys("literal.yaml", "notype.yaml");
        assertThat(reg.fileErrors().get("literal.yaml")).contains("placeholder").doesNotContain("sk-123");
        assertThat(reg.health(reg.provider("unknown").orElseThrow()).status()).isEqualTo(LlmRegistry.Status.ERROR);
        assertThat(reg.health(reg.provider("off").orElseThrow()).status()).isEqualTo(LlmRegistry.Status.OFF);
        assertThat(reg.models()).extracting(ModelInfo::ref).containsExactly("good/m");
    }

    @Test
    void reloadPicksUpAddedChangedAndRemovedFiles() throws Exception {
        write("a.yaml", "type: mock\nmodels:\n  - name: one\n");
        LlmRegistry reg = registry(Map.of());
        assertThat(reg.models()).extracting(ModelInfo::ref).containsExactly("a/one");
        write("b.yaml", "type: mock\nmodels:\n  - name: two\n");
        Files.delete(dir.resolve("a.yaml"));
        reg.reload();
        assertThat(reg.models()).extracting(ModelInfo::ref).containsExactly("b/two");
    }

    @Test
    void ambiguousAndMissingModelNames() throws Exception {
        write("a.yaml", "type: mock\nmodels:\n  - name: same\n");
        write("b.yaml", "type: mock\nmodels:\n  - name: same\n");
        LlmRegistry reg = registry(Map.of());
        assertThatThrownBy(() -> reg.chat("same")).hasMessageContaining("several providers");
        assertThatThrownBy(() -> reg.chat("nope")).hasMessageContaining("no chat model");
        assertThat(reg.chat("a/same").info().ref()).isEqualTo("a/same");
    }

    @Test
    void openAiCompatibleProviderSpeaksToAStubServerAndKeepsTheKeyOut() throws Exception {
        String ok = "{\"id\":\"1\",\"object\":\"chat.completion\",\"model\":\"gpt-x\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"4\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12}}";
        try (StubServer s = new StubServer(x -> x.path().endsWith("/v1/chat/completions") ? new String[] {"200", "application/json", ok}
                : new String[] {"404", "application/json", "{}"})) {
            write("oai.yaml", "type: openai\nbase-url: " + s.url() + "\napi-key: ${OAI_KEY}\nheader:\n  X-Team: red\nmodels:\n  - name: fast\n    id: gpt-x\n    max-tokens: 77\n"
                    + "    cost-per-1k-input: 2.0\n    cost-per-1k-output: 4.0\n");
            LlmGateway gw = new LlmGateway(registry(Map.of("OAI_KEY", "sekret")), LlmPolicy.none());
            ChatResponse r = gw.chat(new CallContext("ann", Set.of(), "t"), ChatRequest.of("fast", "be brief", "2+2?", null, Duration.ofSeconds(10)));
            assertThat(r.text()).isEqualTo("4");
            assertThat(r.usage().promptTokens()).isEqualTo(10);
            assertThat(r.usage().cost()).isEqualTo(0.028, org.assertj.core.data.Offset.offset(1e-9));
            StubServer.Seen seen = s.seen.get(0);
            assertThat(seen.headers().getFirst("Authorization")).isEqualTo("Bearer sekret");
            assertThat(seen.headers().getFirst("X-Team")).isEqualTo("red");
            assertThat(seen.body()).contains("\"model\":\"gpt-x\"").contains("\"max_tokens\":77").contains("be brief").contains("2+2?");
        }
    }

    @Test
    void openAiCompatibleServerErrorBecomesAFailureWithoutTheBody() throws Exception {
        try (StubServer s = new StubServer(x -> new String[] {"500", "application/json", "{\"error\":\"secret prompt echo\"}"})) {
            write("oai.yaml", "type: openai\nbase-url: " + s.url() + "\nmodels:\n  - name: m\n");
            LlmGateway gw = new LlmGateway(registry(Map.of()), LlmPolicy.none());
            assertThatThrownBy(() -> gw.chat(new CallContext("a", Set.of(), "t"), ChatRequest.of(null, "", "q", 5, null)))
                    .isInstanceOf(LlmException.class).hasMessageContaining("HTTP 500").message().doesNotContain("secret prompt");
            assertThat(s.seen).hasSize(1);   // no retry unless the file asks for one
        }
    }

    @Test
    void anthropicProviderSpeaksTheMessagesApi() throws Exception {
        String ok = "{\"model\":\"claude-x\",\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}";
        try (StubServer s = new StubServer(x -> new String[] {"200", "application/json", ok})) {
            write("claude.yaml", "type: anthropic\nbase-url: " + s.url() + "\napi-key: ${A_KEY}\nmodels:\n  - name: c\n    id: claude-x\n");
            LlmGateway gw = new LlmGateway(registry(Map.of("A_KEY", "k1")), LlmPolicy.none());
            assertThat(gw.chat(new CallContext("a", Set.of(), "t"), ChatRequest.of(null, "sys", "hi", 9, null)).text()).isEqualTo("hello");
            StubServer.Seen seen = s.seen.get(0);
            assertThat(seen.path()).isEqualTo("/v1/messages");
            assertThat(seen.headers().getFirst("x-api-key")).isEqualTo("k1");
            assertThat(seen.headers().getFirst("anthropic-version")).isEqualTo("2023-06-01");
            assertThat(seen.body()).contains("\"system\":\"sys\"").contains("\"max_tokens\":9");
        }
    }

    @Test
    void policyMasksLimitsAuditsAndGatesFeaturesByRole() throws Exception {
        write("mock.yaml", "type: mock\nmodels:\n  - name: m\n    cost-per-1k-input: 1000\n");
        List<DefaultLlmPolicy.AuditRecord> audit = new ArrayList<>();
        PolicySettings ps = new PolicySettings(true, List.of("\\b\\d{16}\\b"), true, 2, 0, 0.5, Map.of("admin-only", Set.of("admin")), Map.of());
        LlmGateway gw = new LlmGateway(registry(Map.of()), new DefaultLlmPolicy(ps, new PromptMasker(List.of("salary"), ps.maskPatterns()), audit::add, Clock.systemUTC()));
        CallContext ann = new CallContext("ann", Set.of("user"), "help");
        ChatResponse r = gw.chat(ann, ChatRequest.of(null, "", "salary: 90000 card 1234567812345678 ok", 5, null));
        assertThat(r.text()).isEqualTo("echo: salary: ••• card ••• ok");                      // the model never saw the values
        assertThat(audit).singleElement().satisfies(a -> {
            assertThat(a.user()).isEqualTo("ann");
            assertThat(a.provider()).isEqualTo("mock");
            assertThat(a.outcome()).isEqualTo("ok");
        });
        assertThatThrownBy(() -> gw.chat(new CallContext("ann", Set.of("user"), "admin-only"), ChatRequest.of(null, "", "x", 5, null)))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.POLICY));
        assertThatThrownBy(() -> {
            gw.chat(ann, ChatRequest.of(null, "", "again", 5, null));
            gw.chat(ann, ChatRequest.of(null, "", "third", 5, null));
        }).isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isIn(LlmException.Kind.RATE_LIMITED));
        assertThat(audit.stream().map(DefaultLlmPolicy.AuditRecord::outcome)).doesNotContain("rate_limited").contains("ok");
    }

    @Test
    void dailyCostLimitStopsFurtherCalls() throws Exception {
        write("mock.yaml", "type: mock\nmodels:\n  - name: m\n    cost-per-1k-input: 1000\n");
        PolicySettings ps = new PolicySettings(true, List.of(), false, 0, 0, 0.5, Map.of(), Map.of());
        LlmGateway gw = new LlmGateway(registry(Map.of()), new DefaultLlmPolicy(ps, PromptMasker.none(), null, Clock.systemUTC()));
        CallContext c = new CallContext("bo", Set.of(), "f");
        gw.chat(c, ChatRequest.of(null, "", "a long enough prompt to cost something", 5, null));
        assertThatThrownBy(() -> gw.chat(c, ChatRequest.of(null, "", "x", 5, null))).hasMessageContaining("cost limit");
    }
}
