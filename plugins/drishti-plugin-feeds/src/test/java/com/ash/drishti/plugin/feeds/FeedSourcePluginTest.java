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
package com.ash.drishti.plugin.feeds;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every feed against a recorded response (no network): documents in the shape the market-data Sutras read, by date. */
class FeedSourcePluginTest {

    static FeedSourcePlugin feed(String name, String fixture, String... extra) {
        Map<String, String> s = new HashMap<>(Map.of("feed", name, "url", Path.of("src/test/resources/recorded", fixture).toUri().toString()));
        for (int i = 0; i + 1 < extra.length; i += 2) {
            s.put(extra[i], extra[i + 1]);
        }
        FeedSourcePlugin p = new FeedSourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    static EntityDocument get(FeedSourcePlugin p, String kind, String id, LocalDate date) throws Exception {
        return p.fetch(EntityRef.of(kind, id), date == null ? AsOf.LATEST : AsOf.of(date)).orElseThrow();
    }

    @Test
    void nyFedSofrBecomesARateFixingWithHistory() throws Exception {
        FeedSourcePlugin p = feed("nyfed-sofr", "nyfed-sofr.json");
        assertThat(p.health()).isEqualTo("UP");
        assertThat(p.manifest().kinds()).containsExactly("rate-fixing");
        EntityDocument d = get(p, "rate-fixing", "FIX-SOFR-NYFED", null);
        assertThat(d.data().get("administrator").asText()).contains("New York");
        assertThat(d.data().get("fixings").size()).isEqualTo(20);
        assertThat(d.data().get("latest").asDouble()).isBetween(0.0, 0.1);
        EntityDocument earlier = get(p, "rate-fixing", "FIX-SOFR-NYFED", d.provenance().businessDate().minusDays(7));
        assertThat(earlier.provenance().businessDate()).isBefore(d.provenance().businessDate());
        assertThat(p.search("rate-fixing", "sofr", 5)).extracting(h -> h.ref().id()).containsExactly("FIX-SOFR-NYFED");
    }

    @Test
    void ecbEstrReadsTheCsv() throws Exception {
        EntityDocument d = get(feed("ecb-estr", "ecb-estr.csv"), "rate-fixing", "FIX-ESTR-ECB", null);
        assertThat(d.data().get("currency").asText()).isEqualTo("EUR");
        assertThat(d.data().get("fixings").get(0).get("ratePct").asDouble()).isBetween(0.0, 10.0);
    }

    @Test
    void ecbReferenceRatesGiveEurAndUsdPairs() throws Exception {
        FeedSourcePlugin p = feed("ecb-fx", "ecb-fx-90d.xml");
        EntityDocument eur = get(p, "fx-spot", "FX-EURUSD-ECB", null);
        EntityDocument jpy = get(p, "fx-spot", "FX-USDJPY-ECB", null);
        EntityDocument eurjpy = get(p, "fx-spot", "FX-EURJPY-ECB", null);
        assertThat(jpy.data().get("mid").asDouble()).isCloseTo(eurjpy.data().get("mid").asDouble() / eur.data().get("mid").asDouble(),
                org.assertj.core.data.Offset.offset(0.001));
        assertThat(eur.data().get("history").size()).isEqualTo(30);
        assertThat(eur.data().get("pairName").asText()).isEqualTo("EUR/USD");
    }

    @Test
    void usTreasuryParYieldsBecomeACurve() throws Exception {
        FeedSourcePlugin p = feed("us-treasury", "us-treasury-202609.xml");
        EntityDocument d = get(p, "ir-curve", "CRV-USD-UST", null);
        assertThat(d.data().get("points").size()).isGreaterThanOrEqualTo(10);
        assertThat(d.data().get("tenY").asDouble()).isBetween(0.0, 0.1);
        assertThat(d.data().get("asOf").asText()).isEqualTo("2026-09-30");
        assertThat(get(p, "ir-curve", "CRV-USD-UST", LocalDate.of(2026, 9, 27)).data().get("asOf").asText()).isEqualTo("2026-09-25");   // the 26th is a Saturday
    }

    @Test
    void fredSkipsMissingObservations() throws Exception {
        FeedSourcePlugin p = feed("fred", "fred-dgs10.json", "series", "DGS10");
        EntityDocument d = get(p, "rate-fixing", "FIX-FRED-DGS10", null);
        assertThat(d.data().get("fixings").size()).isEqualTo(5);                  // the "." observation is not a number
        assertThat(d.data().get("fixings").get(0).get("ratePct").asDouble()).isEqualTo(4.09);
    }

    @Test
    void anUnreachableFeedIsReportedNotFatal() throws Exception {
        FeedSourcePlugin p = feed("nyfed-sofr", "does-not-exist.json");
        assertThat(p.health()).startsWith("DOWN:");
        assertThat(p.fetch(EntityRef.of("rate-fixing", "FIX-SOFR-NYFED"), AsOf.LATEST)).isEmpty();
    }
}
