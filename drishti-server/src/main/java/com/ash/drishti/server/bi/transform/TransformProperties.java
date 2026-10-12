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
package com.ash.drishti.server.bi.transform;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of Python Transforms and of composed datasets, {@code drishti.bi.transform.*} (docs/admin/CONFIGURATION.md): the runner that executes a Transform in
 * the console's sandbox, how long its result is kept for a reader, and the size limits on what goes in and comes out.
 *
 * @param enabled false: a derived dataset answers {@code DRS-9074}
 * @param python the Python that has pandas and pyarrow (the console's environment); blank: the console's {@code .venv} when it exists, else {@code python3}
 * @param runner the console's {@code core/transform_cli.py}; blank: looked for in {@code drishti-console/core} from the working directory and its parents
 * @param workDir scratch folders of runs; blank: {@code <bi store>/transform-tmp}
 * @param cacheTtl how long a Transform's result is served to the reader it was made for
 * @param cacheEntries results kept per derived dataset (the oldest go first)
 * @param maxInputRows most rows of one input frame handed to a Transform
 * @param maxRows most rows a Transform may return
 * @param maxBytes largest result file, in bytes
 * @param timeout longest a run may take (the item's own {@code timeout} may be shorter)
 * @param maxConcurrent Transforms running at once
 * @param queueWait how long a run waits for a place before {@code DRS-9074}
 * @param composeSourceMaxRows most rows one source of a composed dataset may answer (past it the query is refused with {@code DRS-9022})
 */
@ConfigurationProperties("drishti.bi.transform")
public record TransformProperties(Boolean enabled, String python, String runner, String workDir, Duration cacheTtl, Integer cacheEntries, Integer maxInputRows, Integer maxRows,
        Long maxBytes, Duration timeout, Integer maxConcurrent, Duration queueWait, Integer composeSourceMaxRows) {

    public TransformProperties {
        enabled = enabled == null || enabled;
        python = python == null ? "" : python.trim();
        runner = runner == null ? "" : runner.trim();
        workDir = workDir == null ? "" : workDir.trim();
        cacheTtl = positive(cacheTtl, Duration.ofMinutes(30));
        cacheEntries = cacheEntries == null || cacheEntries <= 0 ? 20 : cacheEntries;
        maxInputRows = maxInputRows == null || maxInputRows <= 0 ? 50_000 : maxInputRows;
        maxRows = maxRows == null || maxRows <= 0 ? 100_000 : maxRows;
        maxBytes = maxBytes == null || maxBytes <= 0 ? 32L * 1024 * 1024 : maxBytes;
        timeout = positive(timeout, Duration.ofSeconds(60));
        maxConcurrent = maxConcurrent == null || maxConcurrent <= 0 ? 2 : maxConcurrent;
        queueWait = positive(queueWait, Duration.ofSeconds(5));
        composeSourceMaxRows = composeSourceMaxRows == null || composeSourceMaxRows <= 0 ? 50_000 : composeSourceMaxRows;
    }

    private static Duration positive(Duration d, Duration dflt) {
        return d == null || d.isNegative() || d.isZero() ? dflt : d;
    }

    /** Defaults for tests and embedded use. */
    public static TransformProperties defaults() {
        return new TransformProperties(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
