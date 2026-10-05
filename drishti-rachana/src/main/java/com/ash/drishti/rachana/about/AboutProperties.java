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
package com.ash.drishti.rachana.about;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.about.*}: the pack about files (docs/architecture/CONTEXT_HELP.md). The pack loader fills {@code packs}.
 *
 * @param packs every loaded pack, most specific first (a child before the packs it extends)
 * @param maxText the longest any one text of an entry may be, in characters (default 600; longer is {@code DRS-2044})
 * @param maxRendered the longest a rendered template may be, in characters (default 1000; longer is cut with an ellipsis)
 */
@ConfigurationProperties("drishti.about")
public record AboutProperties(List<PackSource> packs, Integer maxText, Integer maxRendered) {

    /**
     * One pack as the about catalogue sees it.
     *
     * @param name the pack's name
     * @param title the pack's display title
     * @param file the pack's about file, or null when it has none
     * @param sutraDir the pack's Sutra directory (tells which pack a Sutra belongs to), or null
     * @param kinds the kinds the pack itself owns
     * @param lineage the pack and everything it extends, most specific first
     */
    public record PackSource(String name, String title, String file, String sutraDir, List<String> kinds, List<String> lineage) {

        public PackSource {
            kinds = kinds == null ? List.of() : List.copyOf(kinds);
            lineage = lineage == null || lineage.isEmpty() ? List.of(name) : List.copyOf(lineage);
        }
    }

    public AboutProperties {
        packs = packs == null ? List.of() : List.copyOf(packs);
        maxText = maxText == null ? 600 : maxText;
        maxRendered = maxRendered == null ? 1000 : maxRendered;
    }
}
