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

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The news-and-markets pack's four hand-migrated Sutras draw what the inline ones drew: news-topic and listed-company on the pack's
 * committed samples, quote and news (which the live connectors answer, with no committed sample) on a synthetic quote and headline
 * set kept with the test resources ({@code sutra-next-data/news-and-markets}) and served by a file connector of the test's own.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.packs.enabled=news-and-markets",
        "drishti.security.redact=NAME,TICKER,ENTITY",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true"})
@AutoConfigureMockMvc
class NewsAndMarketsSutraNextEquivalenceTest extends PackSutraNextEquivalence {

    @DynamicPropertySource
    static void site(DynamicPropertyRegistry r) throws Exception {
        site(r, "news-and-markets");
        lake(r, "news-and-markets", "quote", "news");
    }

    @Override
    java.util.Map<String, String> synthetic() throws Exception {
        return lakeRecords("news-and-markets", "quote", "news");
    }

    @Override
    String pack() {
        return "news-and-markets";
    }
}
