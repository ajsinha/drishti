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

import com.ash.drishti.common.RetryPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** AI providers and MCP servers that could not be set up, or lost their connection, recover on the retry schedule. */
class RetryRecoveryTest {

    @TempDir Path dir;

    private static final RetryPolicy FAST = new RetryPolicy(true, Duration.ofMillis(60), 2.0, Duration.ofMillis(240), 0, 3);

    /** A provider type that cannot be built while the stub is "down". */
    static final class Flaky implements ProviderFactory {
        final AtomicBoolean up = new AtomicBoolean();
        int tries;

        public String type() {
            return "flaky";
        }

        public LlmProvider create(ProviderConfig config) {
            tries++;
            if (!up.get()) {
                throw new LlmException(LlmException.Kind.CONFIG, "connection refused by the stub");
            }
            return new LlmProvider() {
                public String id() {
                    return config.name();
                }

                public String type() {
                    return "flaky";
                }

                public Set<Capability> capabilities() {
                    return Set.of();
                }

                public List<ModelInfo> models() {
                    return List.of();
                }

                public Optional<LlmModel> chatModel(String n) {
                    return Optional.empty();
                }
            };
        }
    }

    @Test
    void aProviderThatCouldNotBeBuiltIsTriedOnTheScheduleAndLoadsWhenTheStubIsUp() throws Exception {
        Files.writeString(dir.resolve("p.yaml"), "type: flaky\n");
        Flaky f = new Flaky();
        LlmRegistry r = new LlmRegistry(List.of(f), () -> ProviderConfigLoader.load(dir, k -> null), "", FAST);
        r.reload();
        assertThat(r.provider("p").orElseThrow().error()).contains("connection refused");
        assertThat(r.retry().status("p").orElseThrow().attempt()).isEqualTo(1);
        int tries = f.tries;
        r.reload();                                                             // too early: not tried again
        assertThat(f.tries).isEqualTo(tries);
        Thread.sleep(150);
        r.reload();
        assertThat(f.tries).isEqualTo(tries + 1);
        assertThat(r.retry().status("p").orElseThrow().attempt()).isEqualTo(2);
        f.up.set(true);
        Thread.sleep(300);
        r.reload();
        assertThat(r.provider("p").orElseThrow().error()).isNull();
        assertThat(r.provider("p").orElseThrow().usable()).isTrue();
        assertThat(r.retry().statuses()).isEmpty();
    }

    @Test
    void retryNowBuildsAtOnceAndAnEditedFileStartsAgain() throws Exception {
        Files.writeString(dir.resolve("p.yaml"), "type: flaky\n");
        Flaky f = new Flaky();
        LlmRegistry r = new LlmRegistry(List.of(f), () -> ProviderConfigLoader.load(dir, k -> null), "", new RetryPolicy(true, Duration.ofMinutes(5), 2, Duration.ofMinutes(5), 0, 3));
        r.reload();
        f.up.set(true);
        r.reload();
        assertThat(r.provider("p").orElseThrow().error()).isNotNull();          // five minutes away
        assertThat(r.retryNow()).isEqualTo(1);
        assertThat(r.provider("p").orElseThrow().error()).isNull();
    }

    /** An MCP transport whose connection can be taken away. */
    static final class StubMcp implements McpClientFactory {
        final AtomicBoolean up = new AtomicBoolean(true);
        int connects;

        public String transport() {
            return "stub";
        }

        public McpClient create(McpServerConfig c) {
            return new MockMcpClient.Factory().create(c) instanceof McpClient m ? new McpClient() {
                public String server() {
                    return c.name();
                }

                public void connect() {
                    connects++;
                    if (!up.get()) {
                        throw new LlmException(LlmException.Kind.FAILED, "stub server is down");
                    }
                }

                public List<McpTypes.Tool> tools() {
                    return m.tools();
                }

                public List<McpTypes.Resource> resources() {
                    return List.of();
                }

                public List<McpTypes.Prompt> prompts() {
                    return List.of();
                }

                public McpTypes.Result callTool(String tool, Map<String, Object> arguments) {
                    return m.callTool(tool, arguments);
                }

                public String readResource(String uri) {
                    return "";
                }

                public void close() {}
            } : null;
        }
    }

    @Test
    void anMcpServerWhoseCallFailedReconnectsOnTheScheduleAndIsUpAgain() throws Exception {
        Files.writeString(dir.resolve("s.yaml"), "transport: stub\nmock:\n  tools:\n    - name: ping\n      reply: pong\n");
        StubMcp stub = new StubMcp();
        McpRegistry r = new McpRegistry(List.of(stub, new MockMcpClient.Factory()), () -> McpServerConfigLoader.load(dir, k -> null), FAST);
        r.reload();
        McpRegistry.Entry e = r.server("s").orElseThrow();
        r.record(e, false, "stub server is down");                                // the gateway saw a failed call
        assertThat(r.health(r.server("s").orElseThrow()).status()).isEqualTo(LlmRegistry.Status.DOWN);
        r.reload();                                                               // lists it as failing
        assertThat(r.retry().status("s").orElseThrow().lastError()).contains("connection lost");
        Thread.sleep(100);
        r.reload();
        Thread.sleep(300);
        r.reload();
        assertThat(r.health(r.server("s").orElseThrow()).status()).isEqualTo(LlmRegistry.Status.UP);
        assertThat(r.retry().statuses()).isEmpty();
    }

    @Test
    void anMcpServerWithABadTransportIsRefusedAndLoadsWhenTheTransportExists() throws Exception {
        Files.writeString(dir.resolve("s.yaml"), "transport: late\n");
        java.util.List<McpClientFactory> factories = new java.util.ArrayList<>(List.of(new MockMcpClient.Factory()));
        McpRegistry r = new McpRegistry(factories, () -> McpServerConfigLoader.load(dir, k -> null), FAST);
        r.reload();
        assertThat(r.server("s").orElseThrow().error()).contains("no MCP transport");
        assertThat(r.retry().status("s").orElseThrow().permanent()).isFalse();   // one look is not yet "permanent"
        assertThat(r.entries()).hasSize(1);
    }
}
