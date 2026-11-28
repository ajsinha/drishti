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
package com.ash.drishti.packs.module;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The module ids the product retired (docs/plans/MODULE_RATIONALISATION.md): a site that still holds a switch for one stops the start,
 * with the new modules and packs to switch instead. No alias is read and no file is migrated: the server never rewrites a site's files.
 * A site names a module in {@code config/modules/<id>.yaml} or in the setting {@code drishti.modules.<id>.enabled}.
 */
public final class RetiredModules {

    private RetiredModules() {
    }

    /** Each retired module id and what replaces it, in words. */
    public static final Map<String, String> RETIRED = Map.of(
            "economy", "the modules external-data (packs world-economy, world-development, national-statistics, oecd, central-banks) and news-and-events "
                    + "(packs official-news, gdelt-events, news-and-markets); switch the one you meant",
            "capitalmarkets", "the module external-data (packs funds, us-equities)",
            "capital-markets", "the module external-data (packs funds, us-equities)");

    /**
     * The refusal lines for the retired module ids a site still names.
     *
     * @param modulesDir the site's module switch files ({@code config/modules}), or null
     * @param whereSet   where a setting key is set by a site (RetiredNames.Check#whereSet), null when it is not
     */
    public static List<String> lines(Path modulesDir, Function<String, String> whereSet) {
        List<String> out = new ArrayList<>();
        RETIRED.forEach((id, use) -> {
            if (modulesDir != null) {
                for (String ext : List.of("yaml", "yml")) {
                    Path f = modulesDir.resolve(id + "." + ext);
                    if (Files.isRegularFile(f)) {
                        out.add(RetiredNames.line("the module switch file " + f.toAbsolutePath().normalize(), null,
                                use + "; delete the file and switch the new module in config/modules/<id>.yaml"));
                    }
                }
            }
            String key = "drishti.modules." + id + ".enabled";
            String where = whereSet.apply(key);
            if (where != null) {
                out.add(RetiredNames.line(key, where, "drishti.modules.<id>.enabled of " + use));
            }
        });
        return List.copyOf(out);
    }
}
