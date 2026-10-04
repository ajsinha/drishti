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
package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.SutraException;
import com.ash.drishti.rachana.parse.SutraParser;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The {@code metric} panel: one large formatted, toned figure with an optional change, unit and caption. Checked through the
 * real pipeline with a Sutra and a document the test supplies, and through the parser for what the grammar accepts.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=trading,counterparty-risk"})
class MetricPanelTest {

    @Autowired ViewPipeline pipeline;
    @Autowired JsonCodec codec;

    private static Sutra sutra(String panel) {
        return new SutraParser().parse("""
                rachana: 1
                sutra: metric-demo
                version: 1
                match: { kind: trade }
                title: { pill: Trade, id: $.tradeId }
                panels:
                  - %s
                """.formatted(panel), "metric-demo.sutra.yaml", "test");
    }

    private static final String FULL = "{ id: mtm, kind: metric, title: MTM, value: $.mtm, fmt: signed0, tone: sign, label: USD,"
            + " delta: $.chg, deltaFmt: pct2, unit: USD, caption: 'of ${$.tradeId}' }";

    private ViewModel.PanelView panel(String panel, String json, UnaryOperator<DataNode> redact, java.util.function.Predicate<String> may) {
        EntityDocument doc = new EntityDocument(EntityRef.of("trade", "MX-20000001"), codec.read(json),
                new Provenance("test", 1, Instant.now(), false));
        return pipeline.preview(Optional.of(sutra(panel)), doc, redact, may).panels().get(0);
    }

    private ViewModel.PanelView panel(String json) {
        return panel(FULL, json, UnaryOperator.identity(), k -> true);
    }

    private static PanelData.Metric metric(ViewModel.PanelView p) {
        return (PanelData.Metric) p.data();
    }

    @Test
    void showsOneFormattedTonedFigureWithItsChangeUnitAndCaption() {
        var p = panel("{\"tradeId\":\"MX-1\",\"mtm\":-1250,\"chg\":0.0123}");
        assertThat(p.kind()).isEqualTo("metric");
        var m = metric(p);
        assertThat(m.value().text()).isEqualTo("−1,250");
        assertThat(m.value().tone()).isEqualTo("neg");
        assertThat(m.value().label()).isEqualTo("USD");
        assertThat(m.delta().text()).isEqualTo("1.23%");
        assertThat(m.delta().tone()).isEqualTo("pos");                      // the change's own tone: sign by default
        assertThat(m.unit()).isEqualTo("USD");
        assertThat(m.caption()).isEqualTo("of MX-1");
        assertThat(p.empty()).isFalse();
        assertThat(p.error()).isNull();
    }

    @Test
    void theChangeTakesItsOwnToneAndIsOptional() {
        var own = panel("{ id: m, kind: metric, title: M, value: $.mtm, delta: $.chg, deltaFmt: pct2, deltaTone: bad }",
                "{\"mtm\":5,\"chg\":-0.5}", UnaryOperator.identity(), k -> true);
        assertThat(metric(own).delta().tone()).isEqualTo("bad");
        var none = panel("{ id: m, kind: metric, title: M, value: $.mtm }", "{\"mtm\":5}", UnaryOperator.identity(), k -> true);
        assertThat(metric(none).delta()).isNull();
        assertThat(metric(none).unit()).isNull();
        assertThat(metric(none).caption()).isNull();
        var absent = panel("{\"tradeId\":\"X\",\"mtm\":5}");
        assertThat(metric(absent).delta()).isNull();                         // a change the document lacks is not shown, not an error
        assertThat(absent.error()).isNull();
    }

    @Test
    void aMissingFigureIsAnEmptyPanelNotAnError() {
        var p = panel("{\"tradeId\":\"MX-1\"}");
        assertThat(p.empty()).isTrue();
        assertThat(p.error()).isNull();
    }

    @Test
    void aMaskedFigureShowsTheMaskAndNoTone() {
        UnaryOperator<DataNode> mask = n -> DataNode.of(Map.of("tradeId", "MX-1", "mtm", DataNode.masked(), "chg", DataNode.masked()));
        var p = panel(FULL, "{\"tradeId\":\"MX-1\",\"mtm\":-1250,\"chg\":0.5}", mask, k -> true);
        assertThat(metric(p).value().text()).isEqualTo(DataNode.MASK);
        assertThat(metric(p).value().tone()).isNull();
        assertThat(metric(p).delta().text()).isEqualTo(DataNode.MASK);
        assertThat(p.error()).isNull();
    }

    @Test
    void aSourceTheUserMayNotOpenShowsNoAccessAndNoValue() {
        var p = panel("{ id: m, kind: metric, title: M, source: \"link('USD-SOFR', 'curve')\", value: $.name }",
                "{\"tradeId\":\"MX-1\"}", UnaryOperator.identity(), k -> !"curve".equals(k));
        assertThat(p.denied()).isEqualTo("no access to curve");
        assertThat(p.data()).isNull();
        assertThat(p.empty()).isTrue();
    }

    @Test
    void theGrammarAcceptsTheOptionsAndRejectsTheRest() {
        assertThat(sutra(FULL).panels().get(0).kind().id()).isEqualTo("metric");
        assertThatThrownBy(() -> sutra("{ id: m, kind: metric, title: M }")).isInstanceOf(SutraException.class)
                .hasMessageContaining("DRS-2022");
        assertThatThrownBy(() -> sutra("{ id: m, kind: metric, value: $.a, rows: $.b }")).isInstanceOf(SutraException.class)
                .hasMessageContaining("DRS-2023");
        assertThatThrownBy(() -> sutra("{ id: m, kind: metric, value: $.a, caption: [x, y] }")).isInstanceOf(SutraException.class)
                .hasMessageContaining("DRS-2029");
        // value and delta are expressions, compiled and checked when the Sutra loads
        var el = new com.ash.drishti.rachana.SutraExpressions(new com.ash.drishti.rachana.el.ElCompiler());
        assertThat(el.check(sutra("{ id: m, kind: metric, value: '$.a +* 2' }"))).extracting("code").contains("DRS-2101");
        assertThat(el.check(sutra("{ id: m, kind: metric, value: $.a, delta: '$.b +* 2' }"))).extracting("code").contains("DRS-2101");
        assertThat(el.check(sutra(FULL))).isEmpty();
    }
}
