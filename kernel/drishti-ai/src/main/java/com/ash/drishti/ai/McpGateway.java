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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The one door to MCP servers: the policy (which roles and features may use which server and tool; arguments and results
 * masked with the field masks), the audit record, and health bookkeeping around every call. Timeouts are the server file's.
 *
 * @param serverRoles roles allowed per server (absent = everyone signed in)
 * @param toolRoles roles allowed per {@code server/tool}
 * @param serverFeatures features allowed per server (absent = all)
 * @param redactedFields argument names whose values never leave (drishti.security.redact)
 */
public final class McpGateway {

    public record Settings(Map<String, Set<String>> serverRoles, Map<String, Set<String>> toolRoles, Map<String, Set<String>> serverFeatures,
            Set<String> redactedFields, boolean audit) {
        public Settings {
            serverRoles = serverRoles == null ? Map.of() : Map.copyOf(serverRoles);
            toolRoles = toolRoles == null ? Map.of() : Map.copyOf(toolRoles);
            serverFeatures = serverFeatures == null ? Map.of() : Map.copyOf(serverFeatures);
            redactedFields = redactedFields == null ? Set.of() : Set.copyOf(redactedFields);
        }

        public static Settings defaults() {
            return new Settings(Map.of(), Map.of(), Map.of(), Set.of(), true);
        }
    }

    /** Who called which tool on which server, how big, how it ended. Never the arguments or the result. */
    public record AuditRecord(String user, String feature, String server, String tool, int argChars, int resultChars, String outcome, long millis) {}

    private final McpRegistry registry;
    private final Settings settings;
    private final PromptMasker masker;
    private final Consumer<AuditRecord> audit;

    public McpGateway(McpRegistry registry, Settings settings, PromptMasker masker, Consumer<AuditRecord> audit) {
        this.registry = registry;
        this.settings = settings;
        this.masker = masker;
        this.audit = audit;
    }

    public McpRegistry registry() {
        return registry;
    }

    /** The tools of a server the caller may use. */
    public List<McpTypes.Tool> tools(CallContext ctx, String server) {
        McpRegistry.Entry e = entry(ctx, server);
        return e.client().tools().stream().filter(t -> allowedTool(ctx, server, t.name())).toList();
    }

    public McpTypes.Result callTool(CallContext ctx, String server, String tool, Map<String, Object> args) {
        McpRegistry.Entry e = entry(ctx, server);
        if (!allowedTool(ctx, server, tool)) {
            throw new LlmException(LlmException.Kind.POLICY, "tool '" + server + "/" + tool + "' is not open to your roles");
        }
        Map<String, Object> clean = new HashMap<>();
        int argChars = 0;
        for (Map.Entry<String, Object> a : (args == null ? Map.<String, Object>of() : args).entrySet()) {
            Object v = settings.redactedFields().stream().anyMatch(f -> PromptMasker.sameField(f, a.getKey())) ? PromptMasker.MASK
                    : a.getValue() instanceof String s ? masker.mask(s) : a.getValue();
            clean.put(a.getKey(), v);
            argChars += String.valueOf(v).length();
        }
        long t0 = System.nanoTime();
        try {
            McpTypes.Result r = e.client().callTool(tool, clean);
            McpTypes.Result masked = new McpTypes.Result(masker.mask(r.text()), r.error());
            registry.record(e, true, null);
            audit(ctx, server, tool, argChars, masked.text().length(), r.error() ? "tool-error" : "ok", t0);
            return masked;
        } catch (LlmException ex) {
            registry.record(e, false, ex.getMessage());
            audit(ctx, server, tool, argChars, 0, ex.kind().name().toLowerCase(java.util.Locale.ROOT), t0);
            throw ex;
        }
    }

    public String readResource(CallContext ctx, String server, String uri) {
        McpRegistry.Entry e = entry(ctx, server);
        long t0 = System.nanoTime();
        try {
            String text = masker.mask(e.client().readResource(uri));
            registry.record(e, true, null);
            audit(ctx, server, "resource:" + uri, 0, text.length(), "ok", t0);
            return text;
        } catch (LlmException ex) {
            registry.record(e, false, ex.getMessage());
            audit(ctx, server, "resource:" + uri, 0, 0, ex.kind().name().toLowerCase(java.util.Locale.ROOT), t0);
            throw ex;
        }
    }

    private McpRegistry.Entry entry(CallContext ctx, String server) {
        McpRegistry.Entry e = registry.server(server).orElseThrow(() -> new LlmException(LlmException.Kind.NOT_FOUND, "no MCP server '" + server + "'"));
        if (!e.usable()) {
            throw new LlmException(LlmException.Kind.NOT_FOUND, "MCP server '" + server + "' is not available");
        }
        Set<String> roles = settings.serverRoles().get(server);
        if (roles != null && !roles.isEmpty() && roles.stream().noneMatch(ctx.roles()::contains)) {
            throw new LlmException(LlmException.Kind.POLICY, "MCP server '" + server + "' is not open to your roles");
        }
        Set<String> features = settings.serverFeatures().get(server);
        if (features != null && !features.isEmpty() && !features.contains(ctx.feature())) {
            throw new LlmException(LlmException.Kind.POLICY, "MCP server '" + server + "' is not open to feature '" + ctx.feature() + "'");
        }
        return e;
    }

    private boolean allowedTool(CallContext ctx, String server, String tool) {
        Set<String> roles = settings.toolRoles().get(server + "/" + tool);
        return roles == null || roles.isEmpty() || roles.stream().anyMatch(ctx.roles()::contains);
    }

    private void audit(CallContext ctx, String server, String tool, int a, int r, String outcome, long t0) {
        if (settings.audit() && audit != null) {
            try {
                audit.accept(new AuditRecord(ctx.user(), ctx.feature(), server, tool, a, r, outcome, (System.nanoTime() - t0) / 1_000_000L));
            } catch (RuntimeException ignored) {
                // an audit sink must never fail a call
            }
        }
    }
}
