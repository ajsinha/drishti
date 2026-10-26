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
package com.ash.drishti.server.security;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code 403 DRS-1016}: an administrator turned the command off (Admin → Commands). The answer names the command and when it was turned
 * off, never anything of its data: {@code {"code": "DRS-1016", "detail": "FRTB is not active on this server", "command": "FRTB", "since": ...}}.
 */
public final class CommandOffException extends DrishtiException {

    private static final long serialVersionUID = 1L;
    private final String command;
    private final String kind;
    private final String since;

    public CommandOffException(String command, String kind, String since) {
        super(ErrorCode.COMMAND_OFF, command + " is not active on this server");
        this.command = command;
        this.kind = kind;
        this.since = since == null ? "" : since;
    }

    public String command() {
        return command;
    }

    public String kind() {
        return kind;
    }

    /** The extra properties of the problem answer. */
    public Map<String, Object> properties() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("command", command);
        out.put("kind", kind);
        out.put("since", since);
        return out;
    }
}
