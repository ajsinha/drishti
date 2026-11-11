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
package com.ash.drishti.server.bi.layer;

import com.ash.drishti.server.bi.dataset.DatasetParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A {@code rachana: 2} dataset met where a version-1 dataset is read (the dataset list, a report's {@code dataset:}, {@code POST /datasets/{id}/query}): it is
 * the one-table dataset of its implicit model, without the pack dimensions (which need the server's packs; a model query on {@code dataset:<name>} brings them).
 * A dataset that only the model layer can resolve ({@code extends}, a connector query, a Python or a model source) says so.
 */
public final class Standalone {

    private Standalone() {}

    public static DatasetParser.Result asVersion1(String yaml) {
        DatasetDefParser.Result r = DatasetDefParser.parse(yaml);
        List<DatasetParser.Problem> problems = new ArrayList<>(r.problems());
        if (r.dataset() == null) {
            return new DatasetParser.Result(null, problems);
        }
        DatasetDef d = r.dataset();
        if (d.extendsRef() != null || d.source() == null || !DatasetDef.Source.PACK.equals(d.source().type())) {
            problems.add(new DatasetParser.Problem("error", "DRS-9102", d.extendsRef() != null ? "extends" : "source", "this dataset is read through the model layer: ask it with a model "
                    + "query (POST /api/v1/bi/mq with model: dataset:" + d.name() + ")"));
            return new DatasetParser.Result(null, problems);
        }
        ImplicitModels.Implicit im = ImplicitModels.of(ResolvedDataset.of(d), k -> Optional.empty());
        Flattener.Flat flat = Flattener.flatten(im.model(), im.datasets());
        problems.addAll(flat.problems());
        if (r.ok()) {
            problems.addAll(DatasetParser.check(flat.dataset()));
        }
        return new DatasetParser.Result(flat.dataset(), problems);
    }
}
