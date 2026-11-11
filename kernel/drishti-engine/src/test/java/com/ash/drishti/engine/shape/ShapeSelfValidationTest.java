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
/*
 * Project Drishti · Any data. Any domain. One grammar.
 */
package com.ash.drishti.engine.shape;

import static com.ash.drishti.engine.shape.ShapeTestSupport.infer;
import static com.ash.drishti.engine.shape.ShapeTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Deterministic regressions found by {@link ShapePropertyTest}: a sample must validate against its own inferred schema. */
class ShapeSelfValidationTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"c\":{\"name\":[null,{\"x y\":false}],\"x y\":204}}",
        "{\"name\":[null,{\"a\":false}]}",
        "{\"c\":{\"name\":[null,{\"a\":false}]}}",
        "{\"c\":{\"x y\":204}}",
        "{\"c\":{\"a\":[{\"b\":1}],\"d\":2}}",
        "{\"c\":{\"a\":[null,{\"b\":1}],\"d\":2}}"
    })
    void aSampleValidatesAgainstItsOwnSchema(String doc) {
        assertThat(new MiniSchemaValidator(infer(doc).schema()).validate(json(doc))).isEmpty();
    }
}
