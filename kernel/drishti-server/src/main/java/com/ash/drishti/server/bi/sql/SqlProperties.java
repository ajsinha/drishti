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
package com.ash.drishti.server.bi.sql;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Drishti SQL, {@code drishti.bi.sql.*} (docs/admin/CONFIGURATION.md).
 *
 * @param enabled false: {@code /bi/sql} answers {@code 403 DRS-9006}
 * @param maxLeaves the most model queries one composite statement may ask (its {@code SELECT}s over the model's tables)
 * @param compositeMemory the memory of the in-memory engine a composite statement's outer query runs in
 * @param compositeTimeout the longest the outer query of a composite statement may run
 * @param maxRows the most rows a composite statement answers
 */
@ConfigurationProperties("drishti.bi.sql")
public record SqlProperties(Boolean enabled, Integer maxLeaves, String compositeMemory, Duration compositeTimeout, Integer maxRows) {

    public SqlProperties {
        enabled = enabled == null || enabled;
        maxLeaves = maxLeaves == null || maxLeaves < 1 ? 8 : maxLeaves;
        compositeMemory = compositeMemory == null || compositeMemory.isBlank() ? "256MB" : compositeMemory.strip();
        compositeTimeout = compositeTimeout == null || compositeTimeout.isNegative() || compositeTimeout.isZero() ? Duration.ofSeconds(30) : compositeTimeout;
        maxRows = maxRows == null || maxRows < 1 ? 100_000 : maxRows;
    }

    public static SqlProperties defaults() {
        return new SqlProperties(null, null, null, null, null);
    }
}
