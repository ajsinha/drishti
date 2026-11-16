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
package com.ash.drishti.engine.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The name the documents of a connector carry as their provenance defaults to the connector's name: also when the setting is there but blank (a form sends it so). */
class SourceNameDefaultTest {

    private static SourcesProperties.ConnectorSettings with(Map<String, String> settings) {
        return new SourcesProperties.ConnectorSettings("file", true, List.of("quote"), settings);
    }

    @Test
    void aMissingSettingIsTheConnectorsName() {
        assertThat(SourceRegistry.ownSettings("desk-quotes", with(Map.of("root", "x"))).get("source-name")).isEqualTo("desk-quotes");
    }

    @Test
    void aBlankSettingIsTheConnectorsNameToo() {
        assertThat(SourceRegistry.ownSettings("desk-quotes", with(Map.of("root", "x", "source-name", ""))).get("source-name")).isEqualTo("desk-quotes");
        assertThat(SourceRegistry.ownSettings("desk-quotes", with(Map.of("source-name", "   "))).get("source-name")).isEqualTo("desk-quotes");
    }

    @Test
    void aDocumentWithNoSourceNameCarriesTheConnectorsName() {
        var blank = new com.ash.drishti.api.EntityDocument(com.ash.drishti.api.EntityRef.of("quote", "Q-1"), new com.ash.drishti.api.DataNode.Obj(Map.of()),
                new com.ash.drishti.api.Provenance("", 0, java.time.Instant.now(), false), false);
        assertThat(SourceRouter.named(blank, "desk-quotes").provenance().source()).isEqualTo("desk-quotes");
        var named = new com.ash.drishti.api.EntityDocument(blank.ref(), blank.data(), new com.ash.drishti.api.Provenance("feed-1", 7, java.time.Instant.now(), false), false);
        assertThat(SourceRouter.named(named, "desk-quotes").provenance().source()).isEqualTo("feed-1");
    }

    @Test
    void aNamedSettingIsKept() {
        assertThat(SourceRegistry.ownSettings("desk-quotes", with(Map.of("source-name", "desk-days"))).get("source-name")).isEqualTo("desk-days");
    }
}
