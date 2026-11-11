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

import com.ash.drishti.api.proxy.SiteProxy;
import com.ash.drishti.testkit.FakeProxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The AI providers (OpenAI-compatible and Anthropic) and the Streamable HTTP MCP client through an outbound proxy: the same
 * {@code proxy:} block as a connector, the site default, {@code proxy: none}, the no-proxy list, and a literal password refused.
 * A fake proxy and stub servers on local ports; no network.
 */
class AiProxyTest {

    private static final String OPENAI = "{\"id\":\"1\",\"object\":\"chat.completion\",\"model\":\"gpt-x\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
            + "\"message\":{\"role\":\"assistant\",\"content\":\"4\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12}}";
    private static final String ANTHROPIC = "{\"model\":\"claude-x\",\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],"
            + "\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}";

    @TempDir Path dir;

    @AfterEach
    void reset() {
        SiteProxy.reset();
    }

    private LlmGateway gateway(Map<String, String> env) {
        LlmRegistry r = new LlmRegistry(ProviderFactories.discover(List.of()), () -> ProviderConfigLoader.load(dir, env::get), "");
        r.reload();
        return new LlmGateway(r, LlmPolicy.none());
    }

    private McpRegistry mcp(Map<String, String> env) {
        McpRegistry r = new McpRegistry(McpRegistry.discover(List.of()), () -> McpServerConfigLoader.load(dir, env::get));
        r.reload();
        return r;
    }

    private static String ask(LlmGateway gw) {
        return gw.chat(new CallContext("a", Set.of(), "t"), ChatRequest.of(null, "", "2+2?", 5, Duration.ofSeconds(10))).text();
    }

    @Test
    void anOpenAiCompatibleProviderGoesThroughTheProxyWithCredentialsFromTheEnvironment() throws Exception {
        try (StubServer s = new StubServer(x -> new String[] {"200", "application/json", OPENAI}); FakeProxy proxy = FakeProxy.start().requireAuth("svc", "s3cr3t")) {
            Files.writeString(dir.resolve("oai.yaml"), "type: openai\nbase-url: " + s.url() + "\nproxy:\n  url: " + proxy.url()
                    + "\n  username: svc\n  password: ${PX_PW}\nmodels:\n  - name: m\n    id: gpt-x\n");
            assertThat(ask(gateway(Map.of("PX_PW", "s3cr3t")))).isEqualTo("4");
            assertThat(proxy.forwarded()).isGreaterThanOrEqualTo(1);
            assertThat(proxy.authorizations()).last().isEqualTo("Basic c3ZjOnMzY3IzdA==");
        }
    }

    @Test
    void anAnthropicProviderGoesThroughTheProxyAndAProxyNoneProviderDoesNot() throws Exception {
        try (StubServer s = new StubServer(x -> new String[] {"200", "application/json", ANTHROPIC}); FakeProxy proxy = FakeProxy.start()) {
            SiteProxy.configure(Map.of("proxy.url", proxy.url()), false);
            Files.writeString(dir.resolve("claude.yaml"), "type: anthropic\nbase-url: " + s.url() + "\nmodels:\n  - name: c\n    id: claude-x\n");
            assertThat(ask(gateway(Map.of()))).isEqualTo("hello");
            assertThat(proxy.forwarded()).as("the site default applies").isEqualTo(1);
            Files.writeString(dir.resolve("claude.yaml"), "type: anthropic\nbase-url: " + s.url() + "\nproxy: none\nmodels:\n  - name: c\n    id: claude-x\n");
            assertThat(ask(gateway(Map.of()))).isEqualTo("hello");
            assertThat(proxy.forwarded()).as("proxy: none went direct").isEqualTo(1);
        }
    }

    @Test
    void aNoProxyListInTheFileIsRead() throws Exception {
        try (StubServer s = new StubServer(x -> new String[] {"200", "application/json", ANTHROPIC}); FakeProxy proxy = FakeProxy.start()) {
            Files.writeString(dir.resolve("claude.yaml"), "type: anthropic\nbase-url: " + s.url() + "\nproxy:\n  url: " + proxy.url()
                    + "\n  no-proxy: [localhost, 127.0.0.0/8]\nmodels:\n  - name: c\n    id: claude-x\n");
            assertThat(ask(gateway(Map.of()))).isEqualTo("hello");
            assertThat(proxy.served()).isZero();
        }
    }

    @Test
    void aLiteralProxyPasswordRefusesTheFileWithoutEchoingIt() throws Exception {
        Files.writeString(dir.resolve("oai.yaml"), "type: openai\nproxy:\n  url: http://p:3128\n  username: svc\n  password: hunter2\nmodels:\n  - name: m\n");
        Files.writeString(dir.resolve("m.yaml"), "transport: mock\nproxy:\n  password: hunter2\n");
        var loaded = ProviderConfigLoader.load(dir, k -> null);
        assertThat(loaded.errors().get("oai.yaml")).contains("proxy.password must be an ${ENV_NAME}").doesNotContain("hunter2");
        assertThat(McpServerConfigLoader.load(dir, k -> null).errors().get("m.yaml")).contains("proxy.password").doesNotContain("hunter2");
    }

    @Test
    void theMcpClientGoesThroughTheProxy() throws Exception {
        try (StubServer s = new StubServer(x -> {
            if (x.method().equals("DELETE")) {
                return new String[] {"200", "text/plain", ""};
            }
            if (x.body().contains("\"initialize\"")) {
                return new String[] {"200", "application/json", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"serverInfo\":{\"name\":\"s\"}}}", "sess-1"};
            }
            if (x.body().contains("notifications/initialized")) {
                return new String[] {"202", "text/plain", ""};
            }
            return new String[] {"200", "application/json", "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"echo\"}]}}"};
        }); FakeProxy proxy = FakeProxy.start().requireAuth("svc", "pw")) {
            Files.writeString(dir.resolve("remote.yaml"), "transport: streamable-http\nurl: " + s.url() + "/mcp\nproxy:\n  url: " + proxy.url()
                    + "\n  username: svc\n  password: ${PX_PW}\n");
            McpClient c = mcp(Map.of("PX_PW", "pw")).server("remote").orElseThrow().client();
            assertThat(c.tools()).extracting(McpTypes.Tool::name).containsExactly("echo");
            assertThat(proxy.forwarded()).isGreaterThanOrEqualTo(3);                 // initialize, initialized, tools/list
            assertThat(s.seen).isNotEmpty();
            c.close();
        }
    }

    @Test
    void aWrongProxyLoginIsReportedAsTheProxysRefusal() throws Exception {
        try (StubServer s = new StubServer(x -> new String[] {"200", "application/json", OPENAI}); FakeProxy proxy = FakeProxy.start().requireAuth("svc", "right")) {
            Files.writeString(dir.resolve("oai.yaml"), "type: openai\nbase-url: " + s.url() + "\nproxy:\n  url: " + proxy.url()
                    + "\n  username: svc\n  password: ${PX_PW}\nmodels:\n  - name: m\n    id: gpt-x\n");
            assertThatThrownBy(() -> ask(gateway(Map.of("PX_PW", "wrong")))).isInstanceOf(LlmException.class).message().doesNotContain("wrong");
            assertThat(proxy.served()).isZero();
        }
    }
}
