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
package com.ash.drishti.api.write;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One command of a connector's {@code writes:} (WRITE_BACK.md › The design and its reasons, section 5.4), filled for one change and sent by the lane owner's outbox. The
 * template is the plugin's own block of the command ({@code jdbc: { sql, expect-rows, already-applied }}); the parameters are what Drishti
 * supplies ({@link #SUPPLIED}) and the ones the Sutra declared by name. A plugin binds them (prepared statements, encoded URLs and
 * JSON), never pastes them into text.
 *
 * @param name the command's name ({@code amend-notional})
 * @param template the plugin's block of the command
 * @param params the parameters, by name
 */
public record WriteCommand(String name, Map<String, Object> template, Map<String, Object> params) {

    /** What Drishti supplies for every command. */
    public static final Set<String> SUPPLIED = Set.of("entity", "kind", "businessDate", "value", "from", "basedOn", "changeId", "by", "reason", "at", "field");

    public WriteCommand {
        Objects.requireNonNull(name, "name");
        template = template == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(template));
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    public String changeId() {
        Object v = params.get("changeId");
        return v == null ? "" : v.toString();
    }
}
