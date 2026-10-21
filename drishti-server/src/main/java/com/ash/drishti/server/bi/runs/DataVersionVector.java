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
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The data version of every table and date a run reads (docs/architecture/BI_RUNS.md section 8.2): {@code (connector, kind, date) →
 * SourcePlugin.dataVersion(kind, date)}, sorted. It replaces the process-local generation for durable keys: a restated date has a new version,
 * so a new key. A source that cannot give a version ({@code dataVersion} empty) makes the whole vector {@linkplain #complete() incomplete}, and a
 * result over it is never reused for another submit.
 */
public final class DataVersionVector {

    /** One (connector, kind, date). */
    public record Part(String connector, String kind, LocalDate date) {
        static final Comparator<Part> ORDER = Comparator.comparing(Part::connector).thenComparing(Part::kind).thenComparing(Part::date);

        @Override
        public String toString() {
            return connector + "/" + kind + "/" + date;
        }
    }

    private final TreeMap<Part, Optional<String>> versions = new TreeMap<>(Part.ORDER);

    /** Records one part's version (empty: the source has none). */
    public DataVersionVector put(String connector, String kind, LocalDate date, Optional<String> version) {
        versions.put(new Part(connector, kind, date), version == null ? Optional.empty() : version.filter(v -> !v.isBlank()));
        return this;
    }

    public DataVersionVector put(String connector, String kind, LocalDate date, String version) {
        return put(connector, kind, date, Optional.ofNullable(version));
    }

    /** True when every part has a version (and there is at least one part). */
    public boolean complete() {
        return !versions.isEmpty() && versions.values().stream().allMatch(Optional::isPresent);
    }

    public Map<Part, Optional<String>> parts() {
        return Collections.unmodifiableMap(versions);
    }

    /** The canonical text: one {@code connector/kind/date=version} per part, sorted; a missing version reads {@code ?}. */
    public String text() {
        StringBuilder b = new StringBuilder();
        versions.forEach((p, v) -> b.append(p).append('=').append(v.orElse("?")).append(';'));
        return b.toString();
    }

    /** The {@code data_version} column: a short hash of the text. */
    public String hash() {
        return "dv_" + SecuritySignature.sha(text()).substring(0, 16);
    }
}
