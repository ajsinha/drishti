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

import com.ash.drishti.graph.GraphProperties;
import com.ash.drishti.graph.ReferenceCatalog;
import com.ash.drishti.inference.Semantics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds a {@link ShapeService} without Spring and parses sample text. */
final class ShapeTestSupport {

    static final ObjectMapper JSON = new ObjectMapper();

    private ShapeTestSupport() {}

    static ShapeService service() {
        return service(BuilderProperties.defaults());
    }

    static ShapeService service(BuilderProperties props) {
        GraphProperties graph = new GraphProperties(
                List.of(new GraphProperties.IdPattern("^CP-", "counterparty"), new GraphProperties.IdPattern("^NS-", "netting-set")),
                Map.of("nettingSet", new GraphProperties.FieldRef("netting-set", "Netting set")), null, null, null);
        return new ShapeService(props, Semantics.defaults(), new ReferenceCatalog(graph));
    }

    static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    static Shape infer(String... documents) {
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < documents.length; i++) {
            samples.add(new Sample("doc" + (i + 1) + ".json", json(documents[i])));
        }
        return service().infer(samples);
    }

    static JsonNode at(Shape shape, String pointer) {
        return shape.schema().at(pointer);
    }

    static String role(Shape shape, String path) {
        var r = shape.roles().get(path);
        return r == null ? null : r.role();
    }
}
