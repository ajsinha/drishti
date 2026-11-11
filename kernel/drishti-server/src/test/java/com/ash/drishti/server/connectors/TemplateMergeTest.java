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
package com.ash.drishti.server.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The deep additive merge of a reactive connector's computation: every key the template has added at any depth, the file's values never
 * overwritten (a different one is a conflict), lists appended with the file's order kept, a mapping item naming the same thing merged
 * into it; and a path set to the template's value.
 */
class TemplateMergeTest {

    private static final Map<String, Object> FILE = Map.of(
            "kinds", Map.of("var-whatif", Map.of("compute", "var-whatif", "params", Map.of("sandbox", Map.of("type", "sandbox-ref")))),
            "reads", List.of(Map.of("kind", "var-result", "as", "base", "id", "{id}")),
            "triggers", List.of(Map.of("on", "data-version", "kinds", List.of("pnl-vector", "my-kind"))),
            "limits", Map.of("timeout", "60s", "wait", "45s"));

    private static final Map<String, Object> TEMPLATE = Map.of(
            "kinds", Map.of("var-whatif", Map.of("compute", "var-whatif", "params", Map.of("sandbox", Map.of("type", "sandbox-ref"),
                    "ccy", Map.of("type", "text", "default", "USD")), "result", Map.of("required", List.of("BEFORE_VAR")))),
            "reads", List.of(Map.of("kind", "var-result", "as", "base", "id", "{id}~ccy={params.ccy}")),
            "triggers", List.of(Map.of("on", "data-version", "kinds", List.of("pnl-vector", "scenario-pv"))),
            "limits", Map.of("timeout", 60 + "s", "wait", "5s", "max-inflight-per-user", 2),
            "cache", Map.of("results", 500));

    @Test
    void everyMissingKeyIsAddedAtAnyDepthAndNoValueOverwritten() {
        TemplateMerge.Result r = TemplateMerge.merge(FILE, TEMPLATE);
        Map<String, Object> m = r.merged();
        assertThat(m.toString()).contains("ccy").contains("BEFORE_VAR").contains("max-inflight-per-user").contains("results=500");
        assertThat(r.added()).contains("kinds.var-whatif.params.ccy", "kinds.var-whatif.result", "limits.max-inflight-per-user", "cache");
        assertThat(((Map<?, ?>) m.get("limits")).get("wait")).isEqualTo("45s");                        // the file's edit
        assertThat((List<?>) m.get("triggers")).as("the same trigger (on: data-version) gains the template's kinds").hasSize(1);
        assertThat(m.get("triggers").toString()).contains("[pnl-vector, my-kind, scenario-pv]");
        assertThat(r.conflicts()).extracting(TemplateMerge.Conflict::key).contains("limits.wait", "reads[0].id");
        @SuppressWarnings("unchecked")
        List<Object> reads = (List<Object>) m.get("reads");
        assertThat(reads).hasSize(1);                                                                    // the same read (kind var-result), not twice
        assertThat(TemplateMerge.merge(m, TEMPLATE).added()).as("merging again adds nothing").isEmpty();
    }

    @Test
    void listsKeepTheFilesOrderAndGainTheTemplatesItems() {
        TemplateMerge.Result r = TemplateMerge.merge(Map.of("kinds", List.of("b", "a")), Map.of("kinds", List.of("a", "c")));
        assertThat(r.merged().get("kinds")).isEqualTo(List.of("b", "a", "c"));
        assertThat(r.conflicts()).isEmpty();
        assertThat(TemplateMerge.same(4, "4")).isTrue();
    }

    @Test
    void aPathTakesTheTemplatesValue() {
        TemplateMerge.Result r = TemplateMerge.merge(FILE, TEMPLATE);
        TemplateMerge.Conflict c = r.conflicts().stream().filter(x -> x.key().equals("reads[0].id")).findFirst().orElseThrow();
        Map<String, Object> fixed = TemplateMerge.set(r.merged(), c.path(), c.template());
        assertThat(fixed.toString()).contains("{id}~ccy={params.ccy}");
        assertThat(TemplateMerge.merge(fixed, TEMPLATE).conflicts()).extracting(TemplateMerge.Conflict::key).containsExactly("limits.wait");
    }
}
