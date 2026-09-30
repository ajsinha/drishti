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
package com.ash.drishti.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ShapeFingerprinterTest {

    private final JsonCodec json = new JsonCodec();
    private final ShapeFingerprinter fp = new ShapeFingerprinter();

    private Fingerprint of(String s) {
        return fp.fingerprint(json.read(s));
    }

    @Test
    void valuesAndArrayLengthsDoNotChangeTheShape() {
        Fingerprint a = of("{\"id\":\"A\",\"n\":1,\"rows\":[{\"x\":1}]}");
        assertThat(of("{\"n\":99,\"id\":\"B\",\"rows\":[{\"x\":2},{\"x\":3},{\"x\":4}]}")).isEqualTo(a);
    }

    @Test
    void structureChangesTheShape() {
        Fingerprint a = of("{\"id\":\"A\",\"n\":1}");
        assertThat(of("{\"id\":\"A\",\"n\":\"1\"}")).isNotEqualTo(a);
        assertThat(of("{\"id\":\"A\",\"n\":1,\"m\":2}")).isNotEqualTo(a);
    }

    @Test
    void heterogeneousArrayElementsMergeAndNullsDefer() {
        assertThat(fp.signature(json.read("[{\"a\":null},{\"a\":1,\"b\":\"x\"}]"))).isEqualTo("a[o{a:n,b:s}]");
    }

    @Test
    void shortForm() {
        assertThat(new Fingerprint(0xa91c0000000071e4L).shortForm()).isEqualTo("a91c…71e4");
    }

    @Test
    void jsonRoundTrip() {
        String s = "{\"a\":[1,2.5,\"x\",true,null],\"b\":{\"c\":-3}}";
        assertThat(json.toJson(json.read(s))).isEqualTo(s);
    }
}
