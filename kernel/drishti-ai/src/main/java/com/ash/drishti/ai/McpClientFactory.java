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

/**
 * The extension point for a transport: one class per {@code transport:} value. Register as a Spring bean or in
 * {@code META-INF/services/com.ash.drishti.ai.McpClientFactory}.
 */
public interface McpClientFactory {

    /** The value of {@code transport:} in an MCP server file: {@code streamable-http}, {@code mock}, ... */
    String transport();

    McpClient create(McpServerConfig config) throws LlmException;
}
