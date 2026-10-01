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

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.rachana.RachanaProperties;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.model.Sutra;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Real feeds are imperfect. Every Sutra of every pack is applied to documents that lack what it asks for, have
 * the wrong types, or have their shapes flipped. The view must still build, serialise to valid JSON, and flag
 * the panels it could not fill as empty ("No data available") rather than fail.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=finance"})
class ImperfectDataTest {

    @Autowired ViewPipeline pipeline;
    @Autowired ObjectMapper mapper;
    @Autowired JsonCodec codec;

    static List<Sutra> allSutras() throws Exception {
        List<Sutra> out = new ArrayList<>();
        try (Stream<Path> packs = Files.list(Path.of("..", "packs"))) {
            for (Path pack : packs.filter(p -> Files.isDirectory(p.resolve("sutras"))).sorted().toList()) {
                try (SutraRegistry r = new SutraRegistry(new RachanaProperties(List.of(pack.resolve("sutras").toString()), false,
                        null, null, null, null, null, null), new ElCompiler())) {
                    out.addAll(r.all());
                }
            }
        }
        return out;
    }

    /** Scalars become the string "x"; arrays become objects and objects arrays, one level down. */
    static Object mangle(Object v, int mode, Random rnd) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> {
                if (mode == 3 && rnd.nextBoolean()) {
                    return;                                             // drop half the fields
                }
                out.put(String.valueOf(k), mode == 2 && x instanceof List ? Map.of("unexpected", true)
                        : mode == 2 && x instanceof Map ? List.of("unexpected") : mangle(x, mode, rnd));
            });
            return out;
        }
        if (v instanceof List<?> l) {
            return l.stream().map(x -> mangle(x, mode, rnd)).toList();
        }
        return mode == 1 && v != null ? "x" : v;
    }

    @Test
    void everySutraSurvivesImperfectDocuments() throws Exception {
        List<Sutra> sutras = allSutras();
        assertThat(sutras).hasSizeGreaterThan(150);
        Object sample = mapper.readValue(Files.readString(Path.of("../packs/finance/samples/trade/IRS-48213.json")), Object.class);
        int empties = 0;
        for (Sutra s : sutras) {
            List<Object> docs = List.of(Map.of("id", "X-1"), mangle(sample, 1, new Random(1)), mangle(sample, 2, new Random(2)),
                    mangle(sample, 3, new Random(3)));
            for (Object d : docs) {
                DataNode node = codec.read(mapper.writeValueAsString(d));
                EntityDocument doc = new EntityDocument(EntityRef.of(s.match().kind(), "X-1"), node, new Provenance("test", 1, Instant.now(), false));
                ViewModel vm = pipeline.preview(Optional.of(s), doc);
                String json = mapper.writeValueAsString(vm);
                JsonNode back = mapper.readTree(json);                  // valid JSON: no NaN or Infinity tokens
                assertThat(back.path("panels").size()).as(s.id()).isEqualTo(s.panels().size());
                assertThat(vm.title().id()).as(s.id()).isNotBlank();
                for (ViewModel.PanelView p : vm.panels()) {
                    if (p.data() == null) {
                        assertThat(p.empty()).as(s.id() + "/" + p.id()).isTrue();
                    }
                    empties += p.empty() ? 1 : 0;
                }
            }
        }
        assertThat(empties).isPositive();
    }

    /** Tutorial 4's Sutra, over a real, deeply nested trade from the trading pack: every panel has data. */
    @Test
    void theNestedDocumentsTutorialWorksOnARealTrade() throws Exception {
        String guide = Files.readString(Path.of("../console/web/guides/nested-data.md"));
        var m = java.util.regex.Pattern.compile("(?ms)^```yaml\\s*$\\n((?:#[^\\n]*\\n|\\s*\\n)*rachana:.*?)^```\\s*$").matcher(guide);
        assertThat(m.find()).isTrue();
        Sutra s = new com.ash.drishti.rachana.parse.SutraParser().parse(m.group(1), "nested-data.md", "docs");
        DataNode trade = codec.read(Files.readString(Path.of("../packs/trading/samples/trade/MX-20000001.json")));
        ViewModel vm = pipeline.preview(Optional.of(s), new EntityDocument(EntityRef.of("trade", "MX-20000001"), trade,
                new Provenance("test", 1, Instant.now(), false)));
        assertThat(vm.panels()).allSatisfy(p -> assertThat(p.empty()).as(p.id()).isFalse());
        assertThat(vm.strip()).extracting(ViewModel.Cell::label).contains("UTI", "Venue");
        assertThat(vm.strip()).filteredOn(c -> c.label().equals("UTI")).allSatisfy(c -> assertThat(c.text()).startsWith("5493"));
    }
}
