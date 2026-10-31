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
package com.ash.drishti.server.bi.runs;

import com.ash.drishti.server.bi.plan.SecuritySignature;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The key of a stored run result (docs/architecture/BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), section 8.2):
 *
 * <pre>
 * resultKey = sha256( planHash | resolvedTime | dataVersionVector | securitySignature | engineSemantics )
 * </pre>
 *
 * Two submits with the same key may share one result: the same question (normalised, {@link QuestionNormaliser}), the same dates actually read,
 * the same data, the same rights and the same engine semantics. A key over a vector that is not {@linkplain DataVersionVector#complete() complete}
 * is not {@linkplain #reusable() reusable}: it is stored for its runner but never served for another submit.
 *
 * @param value       the 64 hex digits of the hash (the result's folder is {@code results/<first two>/<value>})
 * @param dataVersion the vector's hash, for the run row's {@code data_version}
 * @param reusable    false when a source gave no data version
 */
public record ResultKey(String value, String dataVersion, boolean reusable) {

    /** The engine semantics in force: bumped when an engine change alters answers (approximate sketches, rounding). */
    public static final String ENGINE_SEMANTICS = "v1";

    public static ResultKey of(String planHash, Collection<LocalDate> resolvedDates, DataVersionVector data, String securitySignature) {
        return of(planHash, resolvedDates, data, securitySignature, ENGINE_SEMANTICS);
    }

    public static ResultKey of(String planHash, Collection<LocalDate> resolvedDates, DataVersionVector data, String securitySignature,
                               String engineSemantics) {
        Objects.requireNonNull(planHash, "planHash");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(securitySignature, "securitySignature");
        Objects.requireNonNull(engineSemantics, "engineSemantics");
        String time = new TreeSet<>(resolvedDates == null ? java.util.List.<LocalDate>of() : resolvedDates).stream().map(LocalDate::toString)
                .collect(Collectors.joining(","));
        String text = String.join("|", planHash, time, data.text(), securitySignature, engineSemantics);
        return new ResultKey(SecuritySignature.sha(text), data.hash(), data.complete());
    }

    /** The result's folder under {@code results/}: sharded by the key's first two hex digits. */
    public String folder() {
        return value.substring(0, 2) + "/" + value;
    }
}
