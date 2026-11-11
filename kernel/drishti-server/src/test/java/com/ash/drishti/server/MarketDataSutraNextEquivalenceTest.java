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

/** The market-data pack's generated fragment Sutras (ir-curve aside: {@link SutraNextEquivalenceTest}) draw what the inline ones drew. */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.packs.enabled=market-data",
        "drishti.security.redact=AMOUNT,ATM,BREAKEVEN,CLOSE,COMMODITY,CURRENCY,INDEX,ISSUER_NAME,MID,M_100,NAME,PAIR,PIPS,PRICE,RATE,RATE_PCT,SPREAD,T_10Y,UNDERLIER,ZERO_RATE",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true"})
@AutoConfigureMockMvc
class MarketDataSutraNextEquivalenceTest extends PackSutraNextEquivalence {

    @DynamicPropertySource
    static void site(DynamicPropertyRegistry r) throws Exception {
        site(r, "market-data");
    }

    @Override
    String pack() {
        return "market-data";
    }
}
