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
package com.ash.drishti.registry;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A function (ACCESS_MODEL.md section 3): a command word ({@code VAR}, {@code TRD}, {@code EQV}, {@code EXP}), the finest unit of access,
 * as a Bloomberg function is. Each belongs to exactly one module (a word no module declares is the core's); lenses bundle functions;
 * roles will grant or deny single functions (2.9.11.i). Off (its module off, or switched off by an administrator), it is gone everywhere:
 * on the backend its APIs answer {@code 404 DRS-1026}.
 *
 * @param word the command word, upper case
 * @param api  the server API prefixes behind it (none: a pack's kind command, answered through the kind's own APIs)
 * @param help its Help pages (slugs)
 */
public record FunctionSpec(String word, List<String> api, List<String> help) {

    public FunctionSpec {
        word = word == null ? "" : word.strip().toUpperCase(Locale.ROOT);
        api = api == null ? List.of() : List.copyOf(api);
        help = help == null ? List.of() : List.copyOf(help);
    }

    /** A function from a manifest's entry: a word, or {@code {word, api, help}}. */
    static FunctionSpec of(Object o) {
        if (o instanceof Map<?, ?> m) {
            return new FunctionSpec(ModuleManifest.str(m.get("word")), ModuleManifest.list(m.get("api")), ModuleManifest.list(m.get("help")));
        }
        return new FunctionSpec(String.valueOf(o), List.of(), List.of());
    }

    /** True when the API path is behind this function (a prefix, at a segment boundary). */
    public boolean ownsApi(String path) {
        return ModuleManifest.under(path, api);
    }
}
