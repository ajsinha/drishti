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
package com.ash.drishti.packs;

import java.nio.file.Path;
import java.util.Map;

/**
 * A loaded domain pack.
 *
 * @param name pack name ({@code finance})
 * @param version pack version
 * @param title display title
 * @param description one paragraph
 * @param dir the pack's directory
 * @param manifest the parsed {@code pack.yaml}
 */
public record Pack(String name, String version, String title, String description, Path dir, Map<String, Object> manifest) {

    /** The entity kinds this pack owns. */
    @SuppressWarnings("unchecked")
    public java.util.List<String> kinds() {
        Object k = manifest.get("kinds");
        return k instanceof java.util.List<?> l ? l.stream().map(String::valueOf).toList() : java.util.List.of();
    }

    public Path resolve(String relative) {
        return dir.resolve(relative).normalize();
    }
}
