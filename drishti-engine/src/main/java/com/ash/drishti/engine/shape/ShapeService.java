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
package com.ash.drishti.engine.shape;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.graph.ReferenceCatalog;
import com.ash.drishti.inference.Semantics;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Screen Builder step 1: merges sample documents into one JSON Schema with Drishti roles and a report. Reads
 * nothing and writes nothing outside its arguments; stateless, so any number of requests may run at once.
 */
public final class ShapeService {

    private final BuilderProperties props;
    private final RoleDetector roles;

    public ShapeService(BuilderProperties props, Semantics semantics, ReferenceCatalog references) {
        this.props = props;
        this.roles = new RoleDetector(props, semantics, references);
    }

    public BuilderProperties limits() {
        return props;
    }

    /** The shape of {@code samples}; refuses more than {@code max-samples} or documents nested deeper than {@code max-depth}. */
    public Shape infer(List<Sample> samples) {
        if (samples == null || samples.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "no samples: send at least one document");
        }
        if (samples.size() > props.maxSamples()) {
            throw new ShapeException(samples.size() + " samples is over the limit of " + props.maxSamples() + " (drishti.builder.max-samples)");
        }
        List<String> names = new ArrayList<>();
        List<JsonNode> docs = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            Sample s = samples.get(i);
            names.add(s.name() == null || s.name().isBlank() ? "sample-" + (i + 1) : s.name());
            docs.add(s.document());
        }
        Facts root = new ShapeMerger(props).merge(names, docs);
        SchemaWriter writer = new SchemaWriter(props, roles, docs);
        var schema = writer.write(root);
        return new Shape(schema, writer.roleByPath, report(names, writer));
    }

    private ShapeReport report(List<String> names, SchemaWriter writer) {
        List<PathRow> conflicts = writer.rows.stream().filter(PathRow::conflict).toList();
        List<PathRow> rare = writer.rows.stream().filter(r -> !r.conflict() && r.presence() < props.rareBelow())
                .sorted(Comparator.comparingDouble(PathRow::presence)).toList();
        List<PathRow> ordered = new ArrayList<>(conflicts);
        ordered.addAll(rare);
        writer.rows.stream().filter(r -> !conflicts.contains(r) && !rare.contains(r)).forEach(ordered::add);
        return new ShapeReport(names.size(), List.copyOf(names), List.copyOf(writer.conflicts), rare, ordered);
    }
}
