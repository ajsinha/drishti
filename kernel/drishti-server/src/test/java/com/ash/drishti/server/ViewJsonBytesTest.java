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

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.command.CommandParser;
import com.ash.drishti.testroots.TestRoots;
import com.ash.rachana.view.ViewJson;
import com.ash.rachana.view.ViewModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * SPLIT_PLAN 8.3g: the API answers a ViewModel with Rachana's writer ({@link ViewJson}) as a raw JSON body; before the switch, its bytes
 * must be the ones the server's Jackson wrote (with Rachana's {@code @Include} read as {@code @JsonInclude}): the reference entities, the
 * showcase of every panel kind, and a view with timings removed (they differ by run). Compared as text, byte for byte.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false"})
class ViewJsonBytesTest {

    private static final Path EXAMPLES = Path.of(TestRoots.repo(), "docs", "guides", "examples");

    @Autowired ViewPipeline pipeline;
    @Autowired CommandParser parser;
    @Autowired JsonCodec codec;
    @Autowired ObjectMapper mapper;

    @Test
    void rachanasWriterWritesTheBytesJacksonWrote() throws Exception {
        List<ViewModel> views = new ArrayList<>();
        for (String c : List.of("TRD IRS-48213 <GO>", "NSET NS-NORTH-01", "CRV USD-SOFR")) {
            views.add(pipeline.view(parser.require(c)));
        }
        var showcase = com.ash.drishti.rachana.DrishtiRachana.parser().parse(Files.readString(EXAMPLES.resolve("all-panels-showcase.sutra.yaml")),
                "all-panels-showcase.sutra.yaml", "test");
        views.add(pipeline.preview(Optional.of(showcase), new EntityDocument(EntityRef.of("trade", "DEMO-BOND-1"),
                codec.read(Files.readString(EXAMPLES.resolve("all-panels-showcase.json"))), new Provenance("test", 1, Instant.now(), true))));
        for (ViewModel v : views) {
            assertThat(ViewJson.write(v)).as(v.ref() == null ? "showcase" : v.ref().kind() + " " + v.ref().id()).isEqualTo(mapper.writeValueAsString(v));
        }
    }
}
