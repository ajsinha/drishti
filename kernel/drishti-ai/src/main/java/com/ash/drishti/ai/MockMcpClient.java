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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code transport: mock}: an in-process MCP server with scripted tools, resources and prompts; no network. Configured under
 * {@code mock.*}:
 * <pre>
 * transport: mock
 * mock:
 *   tools:
 *     - name: add
 *       description: "adds"
 *       reply: "sum of ${a} and ${b}"     # ${arg} is replaced by the call's argument; omit to echo the arguments
 *       error: false                       # true: the result is flagged as an error
 *   resources:
 *     - uri: mock://readme
 *       text: "hello"
 *   prompts:
 *     - name: greet
 * </pre>
 */
public final class MockMcpClient implements McpClient {

    private final McpServerConfig cfg;
    private final List<McpTypes.Tool> tools = new ArrayList<>();
    private final List<McpTypes.Resource> resources = new ArrayList<>();
    private final List<McpTypes.Prompt> prompts = new ArrayList<>();

    MockMcpClient(McpServerConfig c) {
        this.cfg = c;
        Map<String, String> s = c.settings();
        for (int i = 0; s.containsKey("mock.tools." + i + ".name"); i++) {
            String p = "mock.tools." + i + ".";
            tools.add(new McpTypes.Tool(s.get(p + "name"), s.getOrDefault(p + "description", ""), "{\"type\":\"object\"}"));
        }
        for (int i = 0; s.containsKey("mock.resources." + i + ".uri"); i++) {
            String p = "mock.resources." + i + ".";
            resources.add(new McpTypes.Resource(s.get(p + "uri"), s.getOrDefault(p + "name", s.get(p + "uri")), "", "text/plain"));
        }
        for (int i = 0; s.containsKey("mock.prompts." + i + ".name"); i++) {
            prompts.add(new McpTypes.Prompt(s.get("mock.prompts." + i + ".name"), s.getOrDefault("mock.prompts." + i + ".description", "")));
        }
    }

    @Override
    public String server() {
        return cfg.name();
    }

    @Override
    public void connect() {}

    @Override
    public List<McpTypes.Tool> tools() {
        return tools.stream().filter(t -> cfg.toolAllowed(t.name())).toList();
    }

    @Override
    public List<McpTypes.Resource> resources() {
        return List.copyOf(resources);
    }

    @Override
    public List<McpTypes.Prompt> prompts() {
        return List.copyOf(prompts);
    }

    @Override
    public McpTypes.Result callTool(String tool, Map<String, Object> arguments) {
        if (!cfg.toolAllowed(tool)) {
            throw new LlmException(LlmException.Kind.POLICY, "tool '" + tool + "' is not in allowed-tools of " + cfg.name());
        }
        for (int i = 0; i < tools.size(); i++) {
            if (tools.get(i).name().equals(tool)) {
                String p = "mock.tools." + i + ".";
                String reply = cfg.settings().get(p + "reply");
                Map<String, Object> args = arguments == null ? Map.of() : arguments;
                String text = reply == null ? "echo " + tool + " " + args : reply;
                for (Map.Entry<String, Object> e : args.entrySet()) {
                    text = text.replace("${" + e.getKey() + "}", String.valueOf(e.getValue()));
                }
                return new McpTypes.Result(text, Boolean.parseBoolean(cfg.settings().getOrDefault(p + "error", "false")));
            }
        }
        throw new LlmException(LlmException.Kind.NOT_FOUND, "no tool '" + tool + "' on " + cfg.name());
    }

    @Override
    public String readResource(String uri) {
        for (int i = 0; i < resources.size(); i++) {
            if (resources.get(i).uri().equals(uri)) {
                return cfg.settings().getOrDefault("mock.resources." + i + ".text", "");
            }
        }
        throw new LlmException(LlmException.Kind.NOT_FOUND, "no resource '" + uri + "' on " + cfg.name());
    }

    @Override
    public void close() {}

    /** {@code transport: mock}. */
    public static final class Factory implements McpClientFactory {
        @Override
        public String transport() {
            return "mock";
        }

        @Override
        public McpClient create(McpServerConfig config) {
            return new MockMcpClient(config);
        }
    }
}
