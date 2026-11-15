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

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.JsonCodec;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * The daily-loads rehearsal: on a date only WSS loaded, the trade kind read Endur, Calypso and Murex from 07-10 without a word. A date
 * with no data is never answered silently from an older one: {@link SourceRouter#carried} names each source (each trading system's
 * connector) that answers from an earlier date, with that date, and the pages and APIs say it.
 */
class CarriedForwardTest {

    /** A source system's table holding the dates of its {@code dates} setting. */
    public static final class Held implements SourcePlugin {
        private NavigableSet<LocalDate> dates = new TreeSet<>();

        @Override
        public PluginManifest manifest() {
            return new PluginManifest("held", "t", Set.of("trade"), new SourceCapabilities(false, false, true, true));
        }

        @Override
        public void start(SourceContext c) {
            Arrays.stream(c.setting("dates", "").split(",")).filter(s -> !s.isBlank()).map(LocalDate::parse).forEach(dates::add);
        }

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref) {
            return Optional.empty();
        }

        @Override
        public Optional<NavigableSet<LocalDate>> heldDates(String kind) {
            return Optional.of(dates);
        }
    }

    private static SourceRouter router(Map<String, String> systems) {
        Map<String, SourcesProperties.ConnectorSettings> connectors = new LinkedHashMap<>();
        systems.forEach((name, dates) -> connectors.put(name, new SourcesProperties.ConnectorSettings("held", true, List.of("trade"),
                Map.of("dates", dates, "union", "true"))));
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofSeconds(2), null, connectors);
        return new SourceRouter(new SourceRegistry(List.of(new Held()), props, new JsonCodec()), props, Executors.newVirtualThreadPerTaskExecutor());
    }

    @Test
    void eachSourceWithNoLoadForTheDateIsNamedWithTheDateThatAnswers() {
        Map<String, String> systems = new LinkedHashMap<>();
        systems.put("trade-endur", "2026-07-09,2026-07-10");
        systems.put("trade-calypso", "2026-07-10");
        systems.put("trade-wss", "2026-07-10,2026-07-13");
        systems.put("trade-murex", "2026-07-14");
        SourceRouter r = router(systems);
        LocalDate asked = LocalDate.of(2026, 7, 13);
        assertThat(r.carried("trade", asked)).extracting(SourceRouter.Carried::connector, SourceRouter.Carried::answered).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("trade-endur", LocalDate.of(2026, 7, 10)),
                org.assertj.core.groups.Tuple.tuple("trade-calypso", LocalDate.of(2026, 7, 10)),
                org.assertj.core.groups.Tuple.tuple("trade-murex", null));
        assertThat(r.carried("trade", asked).stream().map(SourceRouter.Carried::text))
                .contains("trade-endur: no load for 2026-07-13, showing 2026-07-10", "trade-murex: no load for 2026-07-13, no earlier date either");
        // the latest: each source against the newest any source holds (07-14, Murex)
        assertThat(r.carried("trade", null)).extracting(SourceRouter.Carried::connector).containsExactlyInAnyOrder("trade-endur", "trade-calypso", "trade-wss");
        assertThat(r.carried("trade", LocalDate.of(2026, 7, 10))).extracting(SourceRouter.Carried::connector).containsExactly("trade-murex");
    }

    @Test
    void everySourceHoldingTheDateSaysNothing() {
        SourceRouter r = router(Map.of("trade-endur", "2026-07-13", "trade-wss", "2026-07-13"));
        assertThat(r.carried("trade", LocalDate.of(2026, 7, 13))).isEmpty();
        assertThat(r.carried("trade", null)).isEmpty();
    }
}
