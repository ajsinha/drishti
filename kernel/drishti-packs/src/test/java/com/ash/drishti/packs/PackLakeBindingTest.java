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
package com.ash.drishti.packs;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.common.NamedResources;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code pack.<pack>.lake}: the templates of one pack that read the shared demo lake read the named lake; another pack's templates,
 * and a template that names another lake (the official results), never move.
 */
class PackLakeBindingTest {

    @BeforeEach
    void processView() {
        NamedResources.useView(null, null);
    }

    @AfterEach
    void clear() {
        System.clearProperty("pack.market-risk.lake");
    }

    private static Map<String, Object> template(String lake) {
        return Map.of("plugin", "delta", "kinds", java.util.List.of("pnl-vector"), "settings", Map.of("lake", lake, "engine", "native"));
    }

    @Test
    void onePacksDemoLakeTemplatesMoveAndNothingElse() {
        System.setProperty("pack.market-risk.lake", "risk");
        assertThat(settings(ConnectorLayers.packBound("market-risk", template("demo")))).containsEntry("lake", "risk").containsEntry("engine", "native");
        assertThat(settings(ConnectorLayers.packBound("counterparty-risk", template("demo")))).containsEntry("lake", "demo");
        assertThat(settings(ConnectorLayers.packBound("market-risk", template("var-official")))).containsEntry("lake", "var-official");
        Map<String, Object> noLake = Map.of("plugin", "kafka", "settings", Map.of("broker", "default"));
        assertThat(ConnectorLayers.packBound("market-risk", noLake)).isSameAs(noLake);
    }

    @Test
    void withoutABindingTheTemplateIsAsGiven() {
        Map<String, Object> t = template("demo");
        assertThat(ConnectorLayers.packBound("market-risk", t)).isSameAs(t);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> settings(Map<String, Object> t) {
        return (Map<String, Object>) t.get("settings");
    }
}
