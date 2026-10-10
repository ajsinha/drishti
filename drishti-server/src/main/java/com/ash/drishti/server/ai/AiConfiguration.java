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
package com.ash.drishti.server.ai;

import com.ash.drishti.ai.CallContext;
import com.ash.drishti.ai.DefaultLlmPolicy;
import com.ash.drishti.ai.LlmGateway;
import com.ash.drishti.ai.LlmPolicy;
import com.ash.drishti.ai.LlmRegistry;
import com.ash.drishti.ai.McpClientFactory;
import com.ash.drishti.ai.McpGateway;
import com.ash.drishti.ai.McpRegistry;
import com.ash.drishti.ai.McpServerConfigLoader;
import com.ash.drishti.ai.PolicySettings;
import com.ash.drishti.ai.PromptMasker;
import com.ash.drishti.ai.ProviderConfig;
import com.ash.drishti.ai.ProviderConfigLoader;
import com.ash.drishti.ai.ProviderFactories;
import com.ash.drishti.ai.ProviderFactory;
import com.ash.drishti.identity.AccessLog;
import com.ash.drishti.server.connectors.DirectoryWatcher;
import com.ash.drishti.server.explain.AskProperties;
import com.ash.drishti.server.security.SecurityProperties;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires the AI layer: the registry (provider files when {@code drishti.ai.enabled}, plus Ask's deprecated own endpoint), the
 * policy (masks from {@code drishti.security.redact}, audit into the access log), the gateway features call, the MCP registry
 * and gateway, and the folder watchers that reload them. Everything is off by default.
 */
@Configuration
@EnableConfigurationProperties({AiProperties.class, McpProperties.class})
public class AiConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(AiConfiguration.class);

    /** Provider types supplied as Spring beans join those listed in META-INF/services. */
    @Bean(destroyMethod = "close")
    LlmRegistry llmRegistry(AiProperties ai, AskProperties ask, Environment env, ObjectProvider<ProviderFactory> beans) {
        boolean legacy = LegacyAsk.configured(ask);
        if (legacy) {
            LOG.warn("drishti.explain.ask.endpoint/api/model/api-key are deprecated: write a provider file in {} and set drishti.explain.ask.model "
                    + "to its logical model (docs/architecture/AI_PROVIDERS.md). The old settings still work as provider 'ask'.", ai.dir());
        }
        LlmRegistry registry = new LlmRegistry(ProviderFactories.discover(beans.orderedStream().toList()), () -> {
            List<ProviderConfig> all = new ArrayList<>();
            Map<String, String> errors = new HashMap<>();
            if (ai.enabled()) {
                ProviderConfigLoader.Loaded l = ProviderConfigLoader.load(Path.of(ai.dir()), env::getProperty);
                errors.putAll(l.errors());
                for (ProviderConfig c : l.providers()) {
                    if (legacy && LegacyAsk.PROVIDER.equals(c.name())) {
                        errors.put(c.name() + ".yaml", "name 'ask' is taken by Ask's deprecated endpoint settings");
                    } else {
                        all.add(c);
                    }
                }
            }
            if (legacy) {
                all.add(LegacyAsk.provider(ask));
            }
            return new ProviderConfigLoader.Loaded(all, errors);
        }, ai.defaultModel());
        registry.reload();
        return registry;
    }

    @Bean
    LlmPolicy llmPolicy(AiProperties ai, SecurityProperties security, ObjectProvider<AccessLog> accessLog) {
        AiProperties.Policy p = ai.policy();
        Map<String, Set<String>> roles = new HashMap<>();
        Map<String, Integer> perMin = new HashMap<>();
        p.features().forEach((f, v) -> {
            roles.put(f, new HashSet<>(v.roles()));
            if (v.perUserPerMinute() != null) {
                perMin.put(f, v.perUserPerMinute());
            }
        });
        PolicySettings ps = new PolicySettings(p.maskRedacted(), p.maskPatterns(), p.audit(), p.perUserPerMinute(), p.perUserPerDay(),
                p.costPerUserPerDay(), roles, perMin);
        return new DefaultLlmPolicy(ps, new PromptMasker(security.redact(), p.maskPatterns()), a -> {
            AccessLog sink = accessLog.getIfAvailable();
            if (sink != null) {
                sink.record(new AccessLog.Event(Instant.now(), a.user(), "ai", a.feature(), a.provider() + "/" + a.model(),
                        a.outcome() + " chars=" + a.promptChars() + "/" + a.answerChars() + " tokens=" + a.promptTokens() + "/" + a.completionTokens()
                                + " ms=" + a.millis(), null));
            }
        }, Clock.systemUTC());
    }

    @Bean
    LlmGateway llmGateway(LlmRegistry registry, LlmPolicy policy) {
        return new LlmGateway(registry, policy);
    }

    @Bean(destroyMethod = "close")
    McpRegistry mcpRegistry(McpProperties mcp, Environment env, ObjectProvider<McpClientFactory> beans) {
        McpRegistry r = new McpRegistry(McpRegistry.discover(beans.orderedStream().toList()), () -> mcp.enabled()
                ? McpServerConfigLoader.load(Path.of(mcp.dir()), env::getProperty) : new McpServerConfigLoader.Loaded(List.of(), Map.of()));
        r.reload();
        return r;
    }

    @Bean
    McpGateway mcpGateway(McpRegistry registry, McpProperties mcp, SecurityProperties security, AiProperties ai, ObjectProvider<AccessLog> accessLog) {
        Map<String, Set<String>> sr = new HashMap<>();
        Map<String, Set<String>> tr = new HashMap<>();
        Map<String, Set<String>> sf = new HashMap<>();
        mcp.serverRoles().forEach((k, v) -> sr.put(k, Set.copyOf(v)));
        mcp.toolRoles().forEach((k, v) -> tr.put(k, Set.copyOf(v)));
        mcp.serverFeatures().forEach((k, v) -> sf.put(k, Set.copyOf(v)));
        McpGateway.Settings st = new McpGateway.Settings(sr, tr, sf, Set.copyOf(security.redact()), mcp.audit());
        return new McpGateway(registry, st, new PromptMasker(security.redact(), ai.policy().maskPatterns()), a -> {
            AccessLog sink = accessLog.getIfAvailable();
            if (sink != null) {
                sink.record(new AccessLog.Event(Instant.now(), a.user(), "mcp", a.feature(), a.server() + "/" + a.tool(),
                        a.outcome() + " chars=" + a.argChars() + "/" + a.resultChars() + " ms=" + a.millis(), null));
            }
        });
    }

    /** Starts the folder watchers (live reload, like connectors) and stops them with the application. */
    @Bean(destroyMethod = "close")
    AiWatchers aiWatchers(AiProperties ai, McpProperties mcp, LlmRegistry llm, McpRegistry mcpRegistry) {
        AiWatchers w = new AiWatchers();
        if (ai.enabled()) {
            w.add(new DirectoryWatcher(Path.of(ai.dir()), ai.watch(), ai.pollMs(), llm::reload, "drishti-ai-watch"));
        }
        if (mcp.enabled()) {
            w.add(new DirectoryWatcher(Path.of(mcp.dir()), mcp.watch(), mcp.pollMs(), mcpRegistry::reload, "drishti-mcp-watch"));
        }
        return w;
    }

    /** The watchers, started on creation. */
    public static final class AiWatchers implements AutoCloseable {
        private final List<DirectoryWatcher> list = new ArrayList<>();

        void add(DirectoryWatcher w) {
            w.start();
            list.add(w);
        }

        @Override
        public void close() {
            list.forEach(DirectoryWatcher::close);
        }
    }

    /** The call context for a signed-in user. */
    public static CallContext context(String user, List<String> roles, String feature) {
        return new CallContext(user, Set.copyOf(roles), feature);
    }
}
