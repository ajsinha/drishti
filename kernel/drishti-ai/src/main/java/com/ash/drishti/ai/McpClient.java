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

import java.util.List;
import java.util.Map;

/**
 * Drishti's own view of an MCP (Model Context Protocol) server connection. Implementations speak one transport; features use
 * {@link McpGateway}, which adds policy. Implementations are thread-safe.
 */
public interface McpClient extends AutoCloseable {

    /** The server's logical name (its file name). */
    String server();

    /** Opens the session (initialize handshake). Idempotent. */
    void connect() throws LlmException;

    List<McpTypes.Tool> tools() throws LlmException;

    List<McpTypes.Resource> resources() throws LlmException;

    List<McpTypes.Prompt> prompts() throws LlmException;

    McpTypes.Result callTool(String tool, Map<String, Object> arguments) throws LlmException;

    String readResource(String uri) throws LlmException;

    @Override
    void close();
}
