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
package com.ash.drishti.server.exposure;

import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Exposure Playground's settings (docs/architecture/COUNTERPARTY_ENGINES.md section 11.2, decision 6; docs/guides/EXPOSURE_GUIDE.md
 * section 29), under {@code drishti.ccr.exposure.playground} in config/engines/exposure.yaml.
 *
 * @param maxPaths the most paths one playground (screen or workbook) takes from the run; more asked is refused with the reason
 * @param browserPaths the paths the screen lists (the figures are always of every path taken)
 * @param browserTrades the trade contributions the screen lists
 * @param exportChoices the trade columns the export offers ({@code All}: every trade within the column limit)
 * @param exportDefault the export's trade columns when none is asked
 * @param exportMaxCells the most cells one workbook may hold on this server
 * @param contributionDate the primary date the contributions split EE at: {@code peak} (the peak EE date) or a tenor ({@code 1Y})
 * @param template a bank's workbook template folder; blank: the built-in playground/exposure/template/
 */
@ConfigurationProperties("drishti.ccr.exposure.playground")
public record ExposurePlaygroundProperties(Integer maxPaths, Integer browserPaths, Integer browserTrades, List<String> exportChoices, String exportDefault,
        Long exportMaxCells, String contributionDate, String template) {

    public ExposurePlaygroundProperties {
        maxPaths = maxPaths == null || maxPaths < 1 ? 5000 : maxPaths;
        browserPaths = browserPaths == null || browserPaths < 1 ? 200 : browserPaths;
        browserTrades = browserTrades == null || browserTrades < 1 ? 50 : browserTrades;
        exportChoices = exportChoices == null || exportChoices.isEmpty() ? List.of("50", "500", "2000", "All") : List.copyOf(exportChoices);
        exportDefault = exportDefault == null || exportDefault.isBlank() ? "500" : exportDefault.trim();
        exportMaxCells = exportMaxCells == null || exportMaxCells < 0 ? 20_000_000L : exportMaxCells;
        contributionDate = contributionDate == null || contributionDate.isBlank() ? "peak" : contributionDate.trim();
        template = template == null ? "" : template.trim();
    }

    public static ExposurePlaygroundProperties defaults() {
        return new ExposurePlaygroundProperties(null, null, null, null, null, null, null, null);
    }

    /**
     * The trade columns of a choice: a count, or 0 for {@code All}.
     *
     * @throws IllegalArgumentException when it is neither
     */
    static int trades(String choice) {
        String c = choice == null ? "" : choice.trim().toLowerCase(Locale.ROOT);
        if (c.equals("all")) {
            return 0;
        }
        try {
            int v = Integer.parseInt(c);
            if (v >= 1) {
                return v;
            }
        } catch (NumberFormatException e) {
            // said below
        }
        throw new IllegalArgumentException("trades is a count of trade columns (50, 500, 2000) or All, not " + choice);
    }
}
