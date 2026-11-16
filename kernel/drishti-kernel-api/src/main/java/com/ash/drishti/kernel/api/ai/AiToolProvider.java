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
package com.ash.drishti.kernel.api.ai;

import com.ash.drishti.kernel.api.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Tools a module gives the AI assistant (docs/architecture/KERNEL_AND_MODULES.md section 2.3), each tied to a function: a function that is
 * off (switched off, or its data need unbound) takes its tool with it.
 */
public interface AiToolProvider {

    /** The tools, with the function each belongs to. */
    List<Tool> tools();

    /** Runs a tool for {@code who} with the arguments its schema allows; the answer is what the assistant reads. */
    Map<String, Object> call(String tool, Map<String, Object> arguments, Principal who);

    /** One tool: its name, what it does, the function (mnemonic) it belongs to, and its arguments' JSON schema. */
    record Tool(String name, String description, String function, Map<String, Object> inputSchema) {}
}
