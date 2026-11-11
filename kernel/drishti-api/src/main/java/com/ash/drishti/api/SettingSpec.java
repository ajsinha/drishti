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
package com.ash.drishti.api;

/**
 * One setting a plugin reads, as a connector form and the connector validator see it.
 *
 * @param name the key; a trailing {@code .*} stands for any key beneath it (for example {@code layout.*})
 * @param type {@code string}, {@code int}, {@code boolean}, {@code duration}, {@code path}, {@code list}, {@code enum:a|b|c}
 * @param required true when the plugin cannot start without it
 * @param defaultValue what the plugin uses when the setting is absent, or null
 * @param description one line for the form
 * @param secret true for a credential: it may only be an environment reference or a {@code file:} reference
 * @param group form section ({@code connection}, {@code security}, {@code tuning}, {@code tls}, …)
 */
public record SettingSpec(String name, String type, boolean required, String defaultValue, String description, boolean secret, String group) {

    public SettingSpec {
        type = type == null || type.isBlank() ? "string" : type;
        description = description == null ? "" : description;
        group = group == null || group.isBlank() ? "general" : group;
    }

    /** True when this spec covers {@code key} (exactly, or beneath a {@code .*} name). */
    public boolean covers(String key) {
        return name.endsWith(".*") ? key.startsWith(name.substring(0, name.length() - 1)) : name.equals(key);
    }
}
