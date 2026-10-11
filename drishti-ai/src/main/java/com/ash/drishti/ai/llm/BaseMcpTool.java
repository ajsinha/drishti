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
package com.ash.drishti.ai.llm;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The base class of a tool: Java twin of {@code BaseMCPTool} in the console's {@code core/ai/mcp_tool.py}, which mirrors sajhamcpserver's
 * {@code sajha/tools/base_mcp_tool.py}. A tool is configured by a map ({@code name}, {@code description}, {@code version}, {@code enabled}, ...) and implements three
 * abstract methods: {@link #execute}, {@link #getInputSchema} and {@link #getOutputSchema}. {@link #executeWithTracking} validates the arguments, runs the tool and
 * keeps the metrics. The method names are Python's in camelCase ({@code to_mcp_format} is {@link #toMcpFormat}).
 */
public abstract class BaseMcpTool {

    /** The arguments do not satisfy the tool's input schema ({@code ToolArgumentError} in Python). */
    public static final class ToolArgumentException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public ToolArgumentException(String tool, String detail) {
            super("tool '" + tool + "': " + detail);
        }
    }

    protected final Map<String, Object> config;
    private boolean enabled;
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private volatile Instant lastCalled;
    private volatile String lastError = "";

    protected BaseMcpTool(Map<String, Object> config) {
        this.config = config == null ? Map.of() : Map.copyOf(config);
        this.enabled = !Boolean.FALSE.equals(this.config.get("enabled"));
    }

    public String name() {
        Object n = config.get("name");
        return n == null || String.valueOf(n).isBlank() ? getClass().getSimpleName() : String.valueOf(n);
    }

    public String description() {
        return String.valueOf(config.getOrDefault("description", ""));
    }

    /** The description a client is served (a subclass may add usage notes). */
    public String servedDescription() {
        return description();
    }

    public String version() {
        return String.valueOf(config.getOrDefault("version", "1.0.0"));
    }

    public boolean enabled() {
        return enabled;
    }

    public void enable() {
        enabled = true;
    }

    public void disable() {
        enabled = false;
    }

    /** Runs the tool. */
    public abstract Object execute(Map<String, Object> arguments);

    /** The JSON Schema of the arguments. */
    public abstract Map<String, Object> getInputSchema();

    /** The JSON Schema of the result. */
    public abstract Map<String, Object> getOutputSchema();

    public Map<String, Object> inputSchema() {
        return getInputSchema();
    }

    public Map<String, Object> outputSchema() {
        return getOutputSchema();
    }

    /** Checks the arguments against {@link #getInputSchema}: required names present, and known names only when the schema forbids more. */
    @SuppressWarnings("unchecked")
    public boolean validateArguments(Map<String, Object> arguments) {
        Map<String, Object> schema = getInputSchema();
        Object req = schema.get("required");
        if (req instanceof List<?> l) {
            for (Object r : l) {
                if (arguments == null || !arguments.containsKey(String.valueOf(r))) {
                    throw new ToolArgumentException(name(), "missing required argument '" + r + "'");
                }
            }
        }
        if (Boolean.FALSE.equals(schema.get("additionalProperties")) && schema.get("properties") instanceof Map<?, ?> props && arguments != null) {
            for (String k : arguments.keySet()) {
                if (!props.containsKey(k)) {
                    throw new ToolArgumentException(name(), "unknown argument '" + k + "'");
                }
            }
        }
        return true;
    }

    public Object executeWithTracking(Map<String, Object> arguments) {
        calls.incrementAndGet();
        lastCalled = Instant.now();
        try {
            validateArguments(arguments);
            return execute(arguments);
        } catch (RuntimeException e) {
            errors.incrementAndGet();
            lastError = e.getClass().getSimpleName();
            throw e;
        }
    }

    public Map<String, Object> getMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name());
        m.put("calls", calls.get());
        m.put("errors", errors.get());
        m.put("lastCalled", lastCalled == null ? null : lastCalled.toString());
        m.put("lastError", lastError);
        return m;
    }

    /** The tool as an MCP {@code tools/list} entry. */
    public Map<String, Object> toMcpFormat() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name());
        m.put("description", servedDescription());
        m.put("inputSchema", getInputSchema());
        m.put("outputSchema", getOutputSchema());
        return m;
    }
}
