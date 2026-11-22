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
package com.ash.drishti.server.enginehost;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.VectorSink;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.enginehost.KindData;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The engines' read API over the server's router (EN-40): a kind or lake table read the normal way (its connectors, the read order, the
 * lake connectors over Drishti Lake's {@code TableSnapshot.columns} and {@code vectors}), so an engine never sees a connector, a credential
 * or a path. Row rules and masks are the host's ({@code LakeInputs}), applied to what this returns.
 */
final class RouterKindData implements KindData {

    private final SourceRouter router;
    private final Duration budget;

    RouterKindData(SourceRouter router, Duration budget) {
        this.router = router;
        this.budget = budget;
    }

    private static AsOf asOf(LocalDate date) {
        return date == null ? AsOf.LATEST : AsOf.of(date);
    }

    @Override
    public boolean serves(String kind) {
        return !router.readers(kind).isEmpty();
    }

    @Override
    public Optional<LocalDate> latest(String kind) {
        return router.latestDate(kind);
    }

    @Override
    public Optional<String> stamp(String kind, LocalDate date) {
        return router.dataVersion(kind, asOf(date));
    }

    @Override
    public Optional<ColumnSet> columns(String kind, Collection<String> paths, LocalDate date) {
        return router.columns(kind, paths, asOf(date), budget);
    }

    @Override
    public boolean vectors(String kind, String column, List<String> texts, LocalDate date, VectorSink sink) throws Exception {
        return router.vectors(kind, column, texts, asOf(date), sink);
    }
}
