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

/** The small value types of the MCP client interfaces. */
public final class McpTypes {

    private McpTypes() {}

    /** A tool a server offers; {@code inputSchema} is its JSON Schema as text. */
    public record Tool(String name, String description, String inputSchema) {}

    public record Resource(String uri, String name, String description, String mimeType) {}

    public record Prompt(String name, String description) {}

    /** A tool call's outcome: the text content, and whether the server flagged it as an error. */
    public record Result(String text, boolean error) {}
}
