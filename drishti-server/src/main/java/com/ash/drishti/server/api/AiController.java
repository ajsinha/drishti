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
package com.ash.drishti.server.api;

import com.ash.drishti.ai.CallContext;
import com.ash.drishti.ai.ChatMessage;
import com.ash.drishti.ai.ChatRequest;
import com.ash.drishti.ai.ChatResponse;
import com.ash.drishti.ai.LlmException;
import com.ash.drishti.ai.LlmGateway;
import com.ash.drishti.ai.LlmRegistry;
import com.ash.drishti.ai.McpGateway;
import com.ash.drishti.ai.McpRegistry;
import com.ash.drishti.ai.McpTypes;
import com.ash.drishti.ai.ModelInfo;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.ai.AiProperties;
import com.ash.drishti.server.ai.McpProperties;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The AI layer's REST face (docs/architecture/AI_PROVIDERS.md). Admin: {@code GET /api/v1/admin/ai} (providers, models, health, MCP
 * servers), {@code GET /api/v1/admin/mcp/{server}/tools}, {@code POST /api/v1/admin/ai/test} (a real call, audited). Features and the
 * console: {@code GET /api/v1/ai/models}, {@code POST /api/v1/ai/chat}, {@code POST /api/v1/ai/mcp/{server}/tools/{tool}}, all through
 * the policy. Errors: DRS-4015 off, DRS-4016 failed/slow, DRS-4017 rate/cost, DRS-4018 policy, DRS-4019 unknown.
 */
@RestController
@RequestMapping("/api/v1")
public class AiController {

    /** A chat request. {@code feature} names the caller's feature for policy; it is prefixed {@code api:} so it cannot pose as a built-in one. */
    public record ChatBody(String model, String system, List<Msg> messages, String prompt, Double temperature, Integer maxTokens, Boolean jsonMode,
            Integer timeoutSeconds, String feature) {}

    public record Msg(String role, String content) {}

    public record TestBody(String model, String prompt) {}

    public record ToolBody(Map<String, Object> arguments, String feature) {}

    private final AiProperties ai;
    private final McpProperties mcp;
    private final LlmGateway gateway;
    private final McpGateway mcpGateway;
    private final Entitlements entitlements;
    private final AuditLog audit;

    private final com.ash.drishti.server.RetryService retry;

    public AiController(AiProperties ai, McpProperties mcp, LlmGateway gateway, McpGateway mcpGateway, Entitlements entitlements, AuditLog audit,
            com.ash.drishti.server.RetryService retry) {
        this.retry = retry;
        this.ai = ai;
        this.mcp = mcp;
        this.gateway = gateway;
        this.mcpGateway = mcpGateway;
        this.entitlements = entitlements;
        this.audit = audit;
    }

    @GetMapping("/admin/ai")
    public Map<String, Object> overview(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        LlmRegistry reg = gateway.registry();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", ai.enabled());
        out.put("dir", ai.dir());
        out.put("types", reg.types());
        out.put("defaultModel", ai.defaultModel());
        List<Map<String, Object>> providers = new ArrayList<>();
        Map<String, Map<String, Object>> aiRetry = retry.ai();
        Map<String, Map<String, Object>> mcpRetry = retry.mcp();
        for (LlmRegistry.Entry e : reg.entries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.config().name());
            m.put("type", e.config().type());
            m.put("enabled", e.config().enabled());
            m.put("baseUrl", e.config().baseUrl());
            m.put("keySet", !e.config().apiKey().isEmpty());
            m.put("health", reg.health(e));
            m.put("retry", aiRetry.get(e.config().name()));
            m.put("models", e.provider() == null ? List.of() : e.provider().models());
            providers.add(m);
        }
        out.put("providers", providers);
        out.put("fileErrors", reg.fileErrors());
        out.put("mcpEnabled", mcp.enabled());
        out.put("mcpDir", mcp.dir());
        McpRegistry mr = mcpGateway.registry();
        List<Map<String, Object>> servers = new ArrayList<>();
        for (McpRegistry.Entry e : mr.entries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.config().name());
            m.put("transport", e.config().transport());
            m.put("enabled", e.config().enabled());
            m.put("url", e.config().url());
            m.put("allowedTools", e.config().allowedTools());
            m.put("health", mr.health(e));
            m.put("retry", mcpRetry.get(e.config().name()));
            servers.add(m);
        }
        out.put("mcpServers", servers);
        out.put("mcpFileErrors", mr.fileErrors());
        return out;
    }

    @GetMapping("/admin/mcp/{server}/tools")
    public List<McpTypes.Tool> tools(@PathVariable String server, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        mcpOn();
        return run(() -> mcpGateway.tools(ctx(p, "admin"), server));
    }

    @PostMapping("/admin/ai/test")
    public Map<String, Object> test(@RequestBody TestBody body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        aiOn();
        String model = body.model() == null ? "" : body.model().strip();
        String prompt = body.prompt() == null || body.prompt().isBlank() ? "Reply with the single word: ready" : body.prompt();
        long t0 = System.nanoTime();
        try {
            ChatResponse r = gateway.chat(ctx(p, "admin-test"), ChatRequest.of(model.isEmpty() ? null : model, "", prompt, 64, Duration.ofSeconds(30)));
            audit.record(p.user(), "ai-test", model.isEmpty() ? "(default)" : model, "ok");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("text", r.text());
            out.put("model", r.model());
            out.put("usage", r.usage());
            out.put("millis", (System.nanoTime() - t0) / 1_000_000L);
            return out;
        } catch (LlmException e) {
            audit.record(p.user(), "ai-test", model.isEmpty() ? "(default)" : model, e.kind().name().toLowerCase(java.util.Locale.ROOT));
            throw map(e);
        }
    }

    @GetMapping("/ai/models")
    public List<ModelInfo> models(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        aiOn();
        return gateway.registry().models();
    }

    @PostMapping("/ai/chat")
    public ChatResponse chat(@RequestBody ChatBody b, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        aiOn();
        List<ChatMessage> msgs = new ArrayList<>();
        if (b.messages() != null) {
            for (Msg m : b.messages()) {
                try {
                    msgs.add(new ChatMessage(ChatMessage.Role.valueOf(m.role().toUpperCase(java.util.Locale.ROOT)), m.content()));
                } catch (RuntimeException e) {
                    throw new DrishtiException(ErrorCode.BAD_REQUEST, "message role must be system, user, assistant or tool");
                }
            }
        }
        if (b.prompt() != null && !b.prompt().isBlank()) {
            msgs.add(ChatMessage.user(b.prompt()));
        }
        if (msgs.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "send messages or a prompt");
        }
        Duration t = Duration.ofSeconds(Math.min(120, Math.max(1, b.timeoutSeconds() == null ? 30 : b.timeoutSeconds())));
        ChatRequest req = new ChatRequest(b.model(), b.system(), msgs, List.of(), b.temperature(), b.maxTokens(), Boolean.TRUE.equals(b.jsonMode()), t);
        return run(() -> gateway.chat(ctx(p, "api:" + (b.feature() == null || b.feature().isBlank() ? "other" : b.feature())), req));
    }

    @PostMapping("/ai/mcp/{server}/tools/{tool}")
    public McpTypes.Result callTool(@PathVariable String server, @PathVariable String tool, @RequestBody(required = false) ToolBody b,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        mcpOn();
        String feature = "api:" + (b == null || b.feature() == null || b.feature().isBlank() ? "other" : b.feature());
        return run(() -> mcpGateway.callTool(ctx(p, feature), server, tool, b == null ? Map.of() : b.arguments()));
    }

    private void aiOn() {
        if (!ai.enabled()) {
            throw new DrishtiException(ErrorCode.AI_OFF, "the AI layer is switched off (drishti.ai.enabled)");
        }
    }

    private void mcpOn() {
        if (!mcp.enabled()) {
            throw new DrishtiException(ErrorCode.AI_OFF, "the MCP client is switched off (drishti.mcp.enabled)");
        }
    }

    private static CallContext ctx(Principal p, String feature) {
        return new CallContext(p.user(), java.util.Set.copyOf(p.roles()), feature);
    }

    private static <T> T run(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (LlmException e) {
            throw map(e);
        }
    }

    public static DrishtiException map(LlmException e) {
        ErrorCode code = switch (e.kind()) {
            case TIMEOUT -> ErrorCode.AI_TIMEOUT;
            case RATE_LIMITED -> ErrorCode.AI_RATE_LIMITED;
            case POLICY -> ErrorCode.AI_POLICY;
            case NOT_FOUND -> ErrorCode.AI_NOT_FOUND;
            case UNSUPPORTED, CONFIG -> ErrorCode.BAD_REQUEST;
            default -> ErrorCode.AI_FAILED;
        };
        return new DrishtiException(code, e.getMessage());
    }
}
