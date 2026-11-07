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
package com.ash.drishti.server.pivot;

import com.ash.drishti.server.bi.DatasetService;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.engine.Layers;
import com.ash.drishti.server.bi.engine.QueryService;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The dataset route of a document list's fields (ARCHITECTURE.md section 23.6.7): a published BI dataset table over the kind's list
 * ({@code source: {kind: trade, each: LEGS}}) whose columns name the list's fields ({@code NOTIONAL}, or {@code INDEX_NAME} with
 * {@code path: INDEX.NAME}), its date built into the BI {@code cached} layer (Parquet, one row per element with the entity's id and the
 * element's index). The pivot reads those files instead of every document when the view's list fields are all there. BI off, no such
 * table, or a layer that cannot be built: no route (the documents are read).
 */
final class DatasetLayers {

    private final ObjectProvider<DatasetService> datasets;
    private final ObjectProvider<QueryService> queries;

    DatasetLayers(ObjectProvider<DatasetService> datasets, ObjectProvider<QueryService> queries) {
        this.datasets = datasets;
        this.queries = queries;
    }

    /** {@code LEGS[].CASH_FLOWS}, {@code $.LEGS[*]} or {@code LEGS} as the grain's dotted name. */
    static String grainOf(String each) {
        return each == null ? "" : each.replace("$.", "").replace("[*]", "").replace("[]", "").strip();
    }

    /** The cached layer of a list of {@code kind} on {@code day} (null: the latest), or null when no dataset holds it. */
    DocView.Layer layer(String kind, String grain, LocalDate day) {
        DatasetService ds = datasets.getIfAvailable();
        QueryService qs = queries.getIfAvailable();
        if (ds == null || qs == null || grain.contains(".")) {
            return null;                                      // one list deep: a nested list is read from the documents
        }
        try {
            for (QueryService.Loaded l : ds.published()) {
                for (Dataset.Table t : l.dataset().tables().values()) {
                    if (!kind.equals(t.kind()) || !grain.equals(grainOf(t.each()))) {
                        continue;
                    }
                    Map<String, String> cols = new LinkedHashMap<>();
                    for (Dataset.Column c : t.physical()) {
                        String path = c.path() == null || c.path().isBlank() ? c.name() : grainOf(c.path());
                        cols.put(grain + "." + path, c.name());
                    }
                    Layers.Layer layer = qs.layers().columns(l.key(), t, day, day);
                    List<Path> files = layer.files();
                    if (files.isEmpty()) {
                        return null;
                    }
                    String from = "read_parquet([" + String.join(", ", files.stream().map(p -> "'" + p.toString().replace("'", "''") + "'").toList())
                            + "], union_by_name = true)";
                    return new DocView.Layer(from, "id", "index", cols, l.id() + "/" + t.name());
                }
            }
        } catch (RuntimeException e) {
            return null;                                      // the layer could not be built now: the documents are read
        }
        return null;
    }

    /** The BI dataset tables over a kind's lists, by grain (for the field list's route notes). */
    Optional<String> tableFor(String kind, String grain) {
        DatasetService ds = datasets.getIfAvailable();
        if (ds == null) {
            return Optional.empty();
        }
        for (QueryService.Loaded l : ds.published()) {
            for (Dataset.Table t : l.dataset().tables().values()) {
                if (kind.equals(t.kind()) && grain.equals(grainOf(t.each()))) {
                    return Optional.of(l.id() + "/" + t.name());
                }
            }
        }
        return Optional.empty();
    }
}
