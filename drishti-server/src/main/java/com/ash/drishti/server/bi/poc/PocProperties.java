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
package com.ash.drishti.server.bi.poc;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RUPAKA PHASE 0 PROOF OF CONCEPT (docs/architecture/RUPAKA_POC.md). Throw-away code that tests the risky parts of the BI
 * design; off unless {@code drishti.bi.poc.enabled}. Nothing outside the {@code bi.poc} package depends on it.
 *
 * @param enabled the POC endpoints exist at all (off: they answer 404, as if the code were not there)
 * @param rows trades per day in the generated sample lake (local-safe: at most 10,000)
 * @param days days in the sample lake
 * @param lakeDir where the sample lake is written (Parquet files); never under the repository's data folder
 * @param maxGroupBy most dimensions one query may group by
 * @param feedTrades the live demo trades the row feed follows (ids of kind trade)
 * @param feedBatch how often the feed writer sends the rows that changed (the latest value of each row wins)
 * @param benchRuns default runs of the layout comparison
 * @param biDir the folder Rupaka files live in ({@code drishti.bi.dir}, default {@code ./config/bi}: datasets/, reports/, python/); never inside a pack
 */
@ConfigurationProperties("drishti.bi.poc")
public record PocProperties(Boolean enabled, Integer rows, Integer days, String lakeDir, Integer maxGroupBy, List<String> feedTrades,
        Duration feedBatch, Integer benchRuns, String biDir) {

    public static final int MAX_ROWS = 10_000;

    public PocProperties {
        enabled = enabled != null && enabled;
        rows = rows == null || rows <= 0 ? MAX_ROWS : Math.min(rows, MAX_ROWS);
        days = days == null || days <= 0 ? 2 : Math.min(days, 5);
        lakeDir = lakeDir == null || lakeDir.isBlank() ? Path.of(System.getProperty("java.io.tmpdir"), "drishti-bi-poc").toString() : lakeDir;
        maxGroupBy = maxGroupBy == null || maxGroupBy <= 0 ? 4 : Math.min(maxGroupBy, 5);
        feedTrades = feedTrades == null || feedTrades.isEmpty() ? List.of("CLY-3000019", "CLY-3000040", "END-1000007", "END-1000022", "END-1000049", "END-1000064", "IMG-400007",
                "IMG-400022", "IMG-400037", "IMG-400094", "IMG-400109", "MX-20000004", "MX-20000019", "MX-20000034", "MX-20000049", "MX-20000064",
                "MX-20000079", "MX-20000094", "MX-20000109", "MX-20000124", "MX-20000163", "MX-20000184", "WSS-1500007", "WSS-1500022") : List.copyOf(feedTrades);
        feedBatch = feedBatch == null || feedBatch.isNegative() || feedBatch.isZero() ? Duration.ofMillis(100) : feedBatch;
        biDir = biDir == null || biDir.isBlank() ? "./config/bi" : biDir;
        benchRuns = benchRuns == null || benchRuns <= 0 ? 30 : Math.min(benchRuns, 500);
    }
}
