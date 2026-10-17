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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.rachana.SutraException;
import com.ash.drishti.rachana.SutraExpressions;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.SutraParser;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The {@code map} panel: the grammar (link and shade are mappings, the expressions are compiled when the Sutra loads), the
 * binder (which country is highlighted, which countries have a page, the figures that shade the map) and what the caller's
 * masks and rights do to them. Read through the real pipeline over the world-economy pack's real documents.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=world-economy"})
class MapPanelTest {

    @Autowired ViewPipeline pipeline;
    @Autowired JsonCodec codec;

    private static Sutra sutra(String panel) {
        return new SutraParser().parse("""
                rachana: 1
                sutra: map-demo
                version: 1
                match: { kind: jurisdiction }
                title: { pill: Country, id: $.jurisdictionId }
                panels:
                  - %s
                """.formatted(panel), "map-demo.sutra.yaml", "test");
    }

    private static final String WORLD = "{ id: m, kind: map, title: Where, highlight: $.iso3, link: { kind: jurisdiction, id: 'JUR-{iso3}' } }";
    private static final String SHADED = "{ id: m, kind: map, title: Where, highlight: $.iso3, link: { kind: economy, id: 'ECON-{iso3}' },"
            + " shade: { kind: economy, field: $.GDP_PER_CAPITA, by: $.ISO3, fmt: amount0, label: GDP per capita } }";

    private ViewModel.PanelView panel(String panel, UnaryOperator<DataNode> redact, java.util.function.Predicate<String> may) {
        EntityDocument doc = new EntityDocument(EntityRef.of("jurisdiction", "JUR-AUS"), codec.read("{\"jurisdictionId\":\"JUR-AUS\",\"iso3\":\"AUS\",\"name\":\"Australia\"}"),
                new Provenance("test", 1, Instant.now(), false));
        return pipeline.preview(Optional.of(sutra(panel)), doc, redact, may).panels().get(0);
    }

    private static PanelData.GeoMap map(ViewModel.PanelView p) {
        return (PanelData.GeoMap) p.data();
    }

    @Test
    void theGrammarReadsTheOptionsAndCompilesTheExpressions() {
        assertThat(sutra(WORLD).panels().get(0).kind().id()).isEqualTo("map");
        var el = new SutraExpressions(new ElCompiler());
        assertThat(el.check(sutra(WORLD))).isEmpty();
        assertThat(el.check(sutra(SHADED))).isEmpty();
        assertThat(el.check(sutra("{ id: m, kind: map, highlight: '$.a +* 2' }"))).extracting("code").contains("DRS-2101");
        assertThat(el.check(sutra("{ id: m, kind: map, region: $.iso3, shade: { kind: economy, field: '$.a +* 2' } }"))).extracting("code").contains("DRS-2101");
        // a map needs nothing; the options it takes are fixed
        assertThat(sutra("{ id: m, kind: map, title: World }").panels().get(0).options()).isEmpty();
        assertThatThrownBy(() -> sutra("{ id: m, kind: map, rows: $.b }")).isInstanceOf(SutraException.class).hasMessageContaining("DRS-2023");
    }

    @Test
    void aLinkNeedsAKindAndATemplateWithACodeAndAShadeNeedsAKindAndAField() {
        for (String bad : new String[] {"{ id: m, kind: map, link: JUR-AUS }", "{ id: m, kind: map, link: { kind: jurisdiction } }",
                "{ id: m, kind: map, link: { kind: jurisdiction, id: JUR-AUS } }", "{ id: m, kind: map, link: { kind: jurisdiction, id: 'JUR-{iso3}', x: 1 } }",
                "{ id: m, kind: map, shade: economy }", "{ id: m, kind: map, shade: { kind: economy } }", "{ id: m, kind: map, shade: { field: $.a } }",
                "{ id: m, kind: map, highlight: [a, b] }", "{ id: m, kind: map, region: 7 }"}) {
            assertThatThrownBy(() -> sutra(bad)).as(bad).isInstanceOf(SutraException.class).hasMessageContaining("DRS-2029");
        }
    }

    @Test
    void theWorldHighlightsTheCountryTheDocumentNames() {
        var p = panel("{ id: m, kind: map, title: Where, highlight: $.iso3 }", UnaryOperator.identity(), k -> true);
        assertThat(p.kind()).isEqualTo("map");
        assertThat(map(p).mode()).isEqualTo("world");
        assertThat(map(p).highlight()).isEqualTo("AUS");
        assertThat(map(p).link()).isNull();
        assertThat(map(p).shade()).isNull();
        assertThat(p.empty()).isFalse();
        assertThat(p.error()).isNull();
        var named = panel("{ id: m, kind: map, highlight: $.name }", UnaryOperator.identity(), k -> true);
        assertThat(map(named).highlight()).isEqualTo("Australia");              // a name is passed as it is; the console matches it to a shape
    }

    @Test
    void aRegionMapNamesTheCountryAndIsEmptyWhenTheDocumentDoesNotNameOne() {
        var p = panel("{ id: m, kind: map, region: $.iso3 }", UnaryOperator.identity(), k -> true);
        assertThat(map(p).mode()).isEqualTo("region");
        assertThat(map(p).region()).isEqualTo("AUS");
        var none = panel("{ id: m, kind: map, region: $.nothing }", UnaryOperator.identity(), k -> true);
        assertThat(none.empty()).isTrue();
        assertThat(none.error()).isNull();
    }

    @Test
    void aLinkListsTheCountriesThatHaveAnEntityAndNothingElse() {
        var l = map(panel(WORLD, UnaryOperator.identity(), k -> true)).link();
        assertThat(l.kind()).isEqualTo("jurisdiction");
        assertThat(l.template()).isEqualTo("JUR-{iso3}");
        assertThat(l.note()).isNull();
        assertThat(l.codes()).contains("AUS", "NZL", "USA", "IND").hasSizeGreaterThan(150).allMatch(c -> c.matches("[A-Z]{3}"));
        // a template no entity matches lists no country, and says why
        var none = map(panel("{ id: m, kind: map, link: { kind: jurisdiction, id: 'XYZ-{iso3}' } }", UnaryOperator.identity(), k -> true)).link();
        assertThat(none.codes()).isEmpty();
        assertThat(none.note()).contains("no jurisdiction entity matches");
    }

    @Test
    void aShadeCarriesOneFigurePerCountryWithTheEntitysFormat() {
        var m = map(panel(SHADED, UnaryOperator.identity(), k -> true));
        assertThat(m.shade().note()).isNull();
        assertThat(m.shade().label()).isEqualTo("GDP per capita");
        assertThat(m.shade().items()).hasSizeGreaterThan(150);
        var aus = m.shade().items().stream().filter(i -> i.code().equals("AUS")).findFirst().orElseThrow();
        assertThat(aus.value()).isGreaterThan(10_000);
        assertThat(aus.text()).matches("[0-9,]+");                                // amount0
        assertThat(m.link().codes()).contains("AUS");
    }

    @Test
    void aMaskedFieldShadesNothingAndIsNeverRevealed() {
        UnaryOperator<DataNode> mask = n -> {
            if (n instanceof DataNode.Obj o && o.fields().containsKey("GDP_PER_CAPITA")) {
                Map<String, DataNode> f = new LinkedHashMap<>(o.fields());
                f.put("GDP_PER_CAPITA", DataNode.masked());
                return new DataNode.Obj(f);
            }
            return n;
        };
        var m = map(panel(SHADED, mask, k -> true));
        assertThat(m.shade().items()).isEmpty();
        assertThat(m.shade().note()).contains("masked");
        assertThat(m.link().codes()).contains("AUS");                              // which countries have a page is not secret
    }

    @Test
    void aMaskedHighlightNamesNoCountry() {
        UnaryOperator<DataNode> mask = n -> n instanceof DataNode.Obj o ? new DataNode.Obj(withMasked(o, "iso3")) : n;
        var p = panel("{ id: m, kind: map, highlight: $.iso3 }", mask, k -> true);
        assertThat(map(p).highlight()).isNull();
    }

    private static Map<String, DataNode> withMasked(DataNode.Obj o, String field) {
        Map<String, DataNode> f = new LinkedHashMap<>(o.fields());
        f.put(field, DataNode.masked());
        return f;
    }

    @Test
    void aKindTheCallerMayNotOpenIsNeverListedOrRead() {
        var m = map(panel(SHADED, UnaryOperator.identity(), k -> !"economy".equals(k)));
        assertThat(m.shade().items()).isEmpty();
        assertThat(m.shade().note()).isEqualTo("no access to economy");
        assertThat(m.link().codes()).isEmpty();
        assertThat(m.link().note()).isEqualTo("no access to economy");
    }

    @Test
    void theRealJurisdictionAndEconomyViewsCarryTheirMap() {
        ViewModel jur = pipeline.view(EntityRef.of("jurisdiction", "JUR-AUS"), AsOf.LATEST, UnaryOperator.identity());
        var geo = jur.panels().stream().filter(p -> p.id().equals("geography")).findFirst().orElseThrow();
        assertThat(map(geo).highlight()).isEqualTo("AUS");
        assertThat(map(geo).link().codes()).contains("NZL");
        ViewModel eco = pipeline.view(EntityRef.of("economy", "ECON-AUS"), AsOf.LATEST, UnaryOperator.identity());
        var shaded = eco.panels().stream().filter(p -> p.id().equals("geography")).findFirst().orElseThrow();
        assertThat(map(shaded).shade().items()).hasSizeGreaterThan(150);
    }
}
