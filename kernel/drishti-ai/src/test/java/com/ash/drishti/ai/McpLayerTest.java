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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The MCP client with no network: the mock, the policy, and the Streamable HTTP client against a local stub server. */
class McpLayerTest {

    @TempDir Path dir;

    private McpRegistry registry(Map<String, String> env) {
        McpRegistry r = new McpRegistry(McpRegistry.discover(List.of()), () -> McpServerConfigLoader.load(dir, env::get));
        r.reload();
        return r;
    }

    @Test
    void mockServerListsCallsAndReadsUnderPolicy() throws Exception {
        Files.writeString(dir.resolve("demo.yaml"), """
                transport: mock
                mock:
                  tools:
                    - name: add
                      description: adds
                      reply: "sum ${a}+${b}"
                    - name: secret
                      reply: "key is hunter2"
                  resources:
                    - uri: mock://readme
                      text: "salary: 5"
                """);
        List<McpGateway.AuditRecord> audit = new ArrayList<>();
        McpGateway.Settings st = new McpGateway.Settings(Map.of("demo", Set.of("user")), Map.of("demo/secret", Set.of("admin")), Map.of(), Set.of("password"), true);
        McpGateway gw = new McpGateway(registry(Map.of()), st, new PromptMasker(List.of("salary"), List.of("hunter2")), audit::add);
        CallContext ann = new CallContext("ann", Set.of("user"), "chat");
        assertThat(gw.tools(ann, "demo")).extracting(McpTypes.Tool::name).containsExactly("add");          // secret is for admins only
        assertThat(gw.callTool(ann, "demo", "add", Map.of("a", 1, "b", 2, "password", "p")).text()).isEqualTo("sum 1+2");
        assertThat(gw.readResource(ann, "demo", "mock://readme")).isEqualTo("salary: •••");
        assertThatThrownBy(() -> gw.callTool(ann, "demo", "secret", Map.of())).isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.POLICY));
        assertThatThrownBy(() -> gw.callTool(new CallContext("x", Set.of("guest"), "chat"), "demo", "add", Map.of())).hasMessageContaining("not open to your roles");
        assertThat(audit).extracting(McpGateway.AuditRecord::tool).containsExactly("add", "resource:mock://readme");
        assertThat(gw.registry().health(gw.registry().server("demo").orElseThrow()).status()).isEqualTo(LlmRegistry.Status.UP);
    }

    @Test
    void allowedToolsFilterAndCredentialHeadersMustBePlaceholders() throws Exception {
        Files.writeString(dir.resolve("a.yaml"), "transport: mock\nallowed-tools: [one]\nmock:\n  tools:\n    - name: one\n    - name: two\n");
        Files.writeString(dir.resolve("b.yaml"), "transport: mock\nheaders:\n  Authorization: Bearer abc\n");
        McpRegistry reg = registry(Map.of());
        assertThat(reg.server("a").orElseThrow().client().tools()).extracting(McpTypes.Tool::name).containsExactly("one");
        assertThat(reg.fileErrors().get("b.yaml")).contains("placeholder").doesNotContain("abc");
    }

    @Test
    void streamableHttpClientHandshakesListsAndCallsOverJsonAndEventStreams() throws Exception {
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
            if (x.body().contains("tools/list")) {
                return new String[] {"200", "text/event-stream", "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"echo\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}},{\"name\":\"hidden\"}]}}\n\n"};
            }
            return new String[] {"200", "application/json", "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"pong\"}],\"isError\":false}}"};
        })) {
            Files.writeString(dir.resolve("remote.yaml"), "transport: streamable-http\nurl: " + s.url() + "/mcp\nallowed-tools: [echo]\nheaders:\n  Authorization: Bearer ${TOK}\n");
            McpRegistry reg = registry(Map.of("TOK", "t0k"));
            McpClient c = reg.server("remote").orElseThrow().client();
            assertThat(c.tools()).extracting(McpTypes.Tool::name).containsExactly("echo");                 // allowed-tools hides the rest
            assertThat(c.callTool("echo", Map.of("m", "ping")).text()).isEqualTo("pong");
            assertThatThrownBy(() -> c.callTool("hidden", Map.of())).hasMessageContaining("allowed-tools");
            assertThat(s.seen.get(0).headers().getFirst("Authorization")).isEqualTo("Bearer t0k");
            assertThat(s.seen.get(2).headers().getFirst("Mcp-Session-Id")).isEqualTo("sess-1");           // the session id is sent back
            assertThat(s.seen.get(2).headers().getFirst("MCP-Protocol-Version")).isEqualTo("2025-11-25");
            c.close();
            assertThat(s.seen.get(s.seen.size() - 1).method()).isEqualTo("DELETE");
        }
    }
}
