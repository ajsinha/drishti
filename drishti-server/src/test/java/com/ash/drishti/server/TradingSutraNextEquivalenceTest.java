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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The trading pack's 130 deal Sutras, built from the pack's own fragments ({@code trading/deal-ticket@1}, legs, flow-schedule,
 * events, payments) and the kit's data-provenance and linked-entities, draw what they drew from the six blocks of before (kept as
 * {@code old-<block>} beside the old layouts), each on the first committed sample trade of its source system and product type; and
 * the four derived trade pages, built from {@code trading/payments-metrics@1}, {@code trading/trade-table@1} (its columns a
 * parameter) and the kit's data-provenance, draw what their inline layouts drew on a synthetic page each.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.packs.enabled=trading", "drishti.sources.connectors.trade-pages.enabled=false", "drishti.security.redact=NOTIONAL,COUNTERPARTY,TRADE_DATE",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true"})
@AutoConfigureMockMvc
class TradingSutraNextEquivalenceTest extends PackSutraNextEquivalence {

    private static final Pattern MATCH = Pattern.compile("\\$\\.SOURCE_SYSTEM == '([A-Z]+)' && \\$\\.PRODUCT_TYPE == '([^']+)'");

    @DynamicPropertySource
    static void site(DynamicPropertyRegistry r) throws Exception {
        site(r, "trading");
        lake(r, "trading", PAGES);
    }

    /** The derived trade pages: drawn on a synthetic page each (sutra-next-data/trading), the derived connector off. */
    static final String[] PAGES = {"counterparty-trades", "portfolio-trades", "legal-entity-trades", "index-trades"};

    @Override
    String pack() {
        return "trading";
    }

    @Override
    Map<String, String> screens() throws Exception {
        ObjectMapper json = new ObjectMapper();
        Map<String, String> first = new HashMap<>();
        for (String line : Files.readAllLines(Path.of("..", "config", "packs", "trading", "samples", "trade.jsonl"), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                JsonNode t = json.readTree(line);
                first.putIfAbsent(t.path("SOURCE_SYSTEM").asText() + "|" + t.path("PRODUCT_TYPE").asText(), t.path("TRADE_ID").asText());
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, String> pages = lakeRecords("trading", PAGES);
        for (String name : names()) {
            if (pages.containsKey(name)) {
                out.put(name, pages.get(name));
                continue;
            }
            Matcher m = MATCH.matcher(Files.readString(resources("trading").resolve(name + ".v2.sutra.yaml")));
            assertThat(m.find()).as(name).isTrue();
            String id = first.get(m.group(1) + "|" + m.group(2));
            assertThat(id).as("a committed sample trade for " + name).isNotNull();
            out.put(name, "trade/" + id);
        }
        assertThat(out).hasSize(134);
        return out;
    }
}
