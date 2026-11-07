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
package com.ash.drishti.server.explain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.rachana.UnderConstruction;
import com.ash.drishti.server.engines.EngineRegistry;
import com.ash.drishti.server.security.Principal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The one Explain's service without a server: explainers found by kind, the measure checked, the parts asked and nothing more, a part
 * not built carrying its under-construction entry, two explainers of one kind refused, a stored figure saying so.
 */
class NumberExplainServiceTest {

    /** A core kind of number: a stored figure with a formula, an input, a part not built. */
    static final class Stored implements Explainer {
        @Override
        public String kind() {
            return "demo";
        }

        @Override
        public String title() {
            return "a demo figure";
        }

        @Override
        public List<String> measures() {
            return List.of("total", "count");
        }

        @Override
        public Explanation explain(Principal p, ExplainRequest r) {
            Explanation x = new Explanation("demo", r.measureOr("total"), r.required("scope", "scope=A"), "Total")
                    .value(1234567.0, "USD", "2026-11-06").stored("the demo kind's TOTAL column").preview("as stored", "1 row");
            x.formula("Total = Σ rows").input("Rows", 1).lineage("demo-table", "business date 2026-11-06", "v7");
            x.whyUnavailable("one date only").unbuilt("workbook", "explain-demo-workbook").unbuilt("official-check", "explain-official-check");
            return x;
        }
    }

    @SuppressWarnings("unchecked")
    static <T> ObjectProvider<T> emptyProvider() {
        return (ObjectProvider<T>) new StaticListableBeanFactory().getBeanProvider(EngineRegistry.class);
    }

    static ObjectProvider<UnderConstruction> registry() {
        StaticListableBeanFactory f = new StaticListableBeanFactory();
        f.addBean("uc", UnderConstruction.parse("""
                items:
                  - id: explain-demo-workbook
                    what: panel
                    about: "the demo kind's workbook"
                    release: "a later release"
                    treatment: under-construction
                  - id: something-else
                    what: field
                    about: "not Explain's"
                    release: "x"
                    treatment: under-construction
                """));
        return f.getBeanProvider(UnderConstruction.class);
    }

    final NumberExplainService svc = new NumberExplainService(List.of(new Stored()), emptyProvider(), registry());

    @Test
    void theKindsAndTheWorkNotBuilt() {
        Map<String, Object> k = svc.kinds();
        assertThat(k.get("kinds").toString()).contains("kind=demo").contains("measures=[total, count]");
        assertThat(k.get("underConstruction").toString()).contains("explain-demo-workbook").doesNotContain("something-else");
    }

    @Test
    void theWholeAnswerAndAPartNotBuilt() {
        Map<String, Object> a = svc.explain(null, new ExplainRequest("demo", null, null, Map.of("scope", "A")));
        assertThat(a.get("text")).isEqualTo("1,234,567");
        assertThat(a.get("stored")).isEqualTo(true);
        assertThat(String.valueOf(a.get("origin"))).startsWith("stored: the demo kind's TOTAL column");
        assertThat(a.get("formula").toString()).contains("Total = Σ rows");
        assertThat(a.get("inputs").toString()).contains("label=Rows, value=1").contains("source=demo-table");
        assertThat(a.get("why").toString()).contains("available=false").contains("one date only");
        assertThat(a.get("workbook").toString()).contains("available=false").contains("underConstruction={id=explain-demo-workbook");
        assertThat(a.get("underConstruction").toString()).contains("explain-official-check").contains("part=official-check");
    }

    @Test
    void thePreviewIsTheHeadOnly() {
        Map<String, Object> a = svc.explain(null, new ExplainRequest("demo", "count", "preview", Map.of("scope", "A")));
        assertThat(a).containsKeys("title", "text", "preview", "facts").doesNotContainKeys("formula", "inputs", "why", "workbook", "checks",
                "underConstruction");
        Map<String, Object> f = svc.explain(null, new ExplainRequest("demo", null, "formula", Map.of("scope", "A")));
        assertThat(f).containsKeys("formula", "checks").doesNotContainKeys("inputs", "why", "workbook");
    }

    @Test
    void refusalsInWords() {
        assertThatThrownBy(() -> svc.explain(null, new ExplainRequest("", null, null, Map.of()))).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("kind=demo");
        assertThatThrownBy(() -> svc.explain(null, new ExplainRequest("nope", null, null, Map.of()))).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("no explainer of the kind nope");
        assertThatThrownBy(() -> svc.explain(null, new ExplainRequest("demo", "max", null, Map.of("scope", "A")))).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("explains total, count");
        assertThatThrownBy(() -> svc.explain(null, new ExplainRequest("demo", null, null, Map.of()))).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("give scope");
        assertThatThrownBy(() -> new ExplainRequest("demo", null, "everything", Map.of())).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("preview");
        assertThatThrownBy(() -> new NumberExplainService(List.of(new Stored(), new Stored()), emptyProvider(), registry()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("two explainers");
    }

    @Test
    void numbersAsTheScreensShowThem() {
        assertThat(Explanation.number(7463368.4)).isEqualTo("7,463,368");
        assertThat(Explanation.number(0.0123)).isEqualTo("0.0123");
        assertThat(Explanation.signed(-212000)).isEqualTo("−212,000");
        assertThat(Explanation.signed(40)).isEqualTo("+40");
        assertThat(Explanation.number(Double.NaN)).isEqualTo("—");
    }
}
