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
package com.ash.drishti.plugin.yfinance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** The connector against a local fake of Yahoo's chart endpoint: parsing, safety limits, retries, the cache and the contract. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YFinanceSourcePluginTest {

    private FakeYahooServer fake;

    @BeforeAll
    void up() throws Exception {
        fake = new FakeYahooServer();
    }

    @AfterAll
    void down() {
        fake.close();
    }

    private YFinanceSourcePlugin plugin(Map<String, String> extra) {
        Map<String, String> s = new HashMap<>(Map.of("base-url", fake.baseUrl(), "timeout-seconds", "1", "retry-initial", "10ms", "retry-max", "50ms"));
        s.putAll(extra);
        YFinanceSourcePlugin p = new YFinanceSourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    private static DataNode doc(YFinanceSourcePlugin p, String id) throws Exception {
        return p.fetch(EntityRef.of("quote", id)).orElseThrow().data();
    }

    @Test
    void aQuoteAndItsHistoryAreParsed() throws Exception {
        YFinanceSourcePlugin p = plugin(Map.of("range", "1mo"));
        DataNode d = doc(p, "QUOTE-acme");
        assertThat(d.get("quoteId").asText()).isEqualTo("QUOTE-ACME");
        assertThat(d.get("name").asText()).isEqualTo("ACME Corporation");
        assertThat(d.get("exchange").asText()).isEqualTo("NasdaqGS");
        assertThat(d.get("price").asDouble()).isEqualTo(191.5);
        assertThat(d.get("previousClose").asDouble()).isEqualTo(189.0);              // the bar before, not the chart's range start
        assertThat(d.get("change").asDouble()).isEqualTo(2.5);
        assertThat(d.get("changePct").asDouble()).isBetween(1.32, 1.33);
        assertThat(d.get("dayRange").asText()).isEqualTo("188.90 – 192.30");
        assertThat(d.get("fiftyTwoWeekRange").asText()).isEqualTo("140.10 – 199.60");
        assertThat(d.get("volume").asDouble()).isEqualTo(51234567);
        assertThat(d.get("marketCap").isNull()).isTrue();
        assertThat(d.get("marketState").asText()).isEqualTo("CLOSED");
        assertThat(d.get("history").size()).isEqualTo(4);                           // the bar without a close is skipped
        assertThat(d.get("history").get(3).get("close").asDouble()).isEqualTo(191.5);
        assertThat(d.get("history").get(0).get("date").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(d.get("historyRange").asText()).isEqualTo("1mo");
        assertThat(fake.lastQuery()).isEqualTo("range=1mo&interval=1d");
    }

    @Test
    void anUnknownSymbolIsNotHeldAndHealthStaysUp() throws Exception {
        YFinanceSourcePlugin p = plugin(Map.of());
        assertThat(p.fetch(EntityRef.of("quote", "QUOTE-NOPE"))).isEmpty();
        assertThat(p.fetch(EntityRef.of("quote", "QUOTE-NOPE"))).isEmpty();
        assertThat(fake.calls("NOPE")).isEqualTo(1);                                // the "no" is cached too
        assertThat(p.health()).isEqualTo("UP");
        assertThat(p.fetch(EntityRef.of("quote", "QUOTE-bad symbol!"))).isEmpty();   // never sent to Yahoo
        assertThat(p.fetch(EntityRef.of("quote", "OTHER-1"))).isEmpty();
    }

    @Test
    void badJsonAnOddShapeAndAnOversizedBodyAreErrors() {
        YFinanceSourcePlugin p = plugin(Map.of("max-bytes", "100000", "retries", "0"));
        assertThatThrownBy(() -> doc(p, "QUOTE-BAD")).hasMessageContaining("not readable JSON");
        assertThat(p.health()).startsWith("DOWN");
        assertThatThrownBy(() -> doc(p, "QUOTE-ODD")).hasMessageContaining("not a Yahoo chart answer");
        assertThatThrownBy(() -> doc(p, "QUOTE-BIG")).hasMessageContaining("larger than max-bytes");
        assertThat(fake.calls("BIG")).isEqualTo(1);
    }

    @Test
    void aSlowResponseTimesOutThenRetries() {
        YFinanceSourcePlugin p = plugin(Map.of("retries", "1"));
        assertThatThrownBy(() -> doc(p, "QUOTE-SLOW")).hasMessageContaining("timed out");
        assertThat(fake.calls("SLOW")).isEqualTo(2);
    }

    @Test
    void serverErrorsAndRateLimitsAreRetriedAClientErrorIsNot() throws Exception {
        YFinanceSourcePlugin p = plugin(Map.of("retries", "2"));
        assertThat(doc(p, "QUOTE-FLAKY").get("price").asDouble()).isEqualTo(191.5);
        assertThat(fake.calls("FLAKY")).isEqualTo(3);
        assertThat(doc(plugin(Map.of("retries", "1")), "QUOTE-LIMITED").get("price").asDouble()).isEqualTo(191.5);
        assertThatThrownBy(() -> doc(p, "QUOTE-DENIED")).hasMessageContaining("HTTP 403");
        assertThat(fake.calls("DENIED")).isEqualTo(1);
    }

    @Test
    void theCacheIsPerSymbolTimedAndBounded() throws Exception {
        YFinanceSourcePlugin p = plugin(Map.of("cache-ttl-seconds", "60", "cache-max-symbols", "2"));
        doc(p, "QUOTE-C1");
        doc(p, "QUOTE-C1");
        assertThat(fake.calls("C1")).isEqualTo(1);
        doc(p, "QUOTE-C2");
        doc(p, "QUOTE-C3");
        assertThat(p.cacheStats().get("symbols")).isEqualTo(2);
        YFinanceSourcePlugin none = plugin(Map.of("cache-ttl-seconds", "0"));
        doc(none, "QUOTE-C4");
        doc(none, "QUOTE-C4");
        assertThat(fake.calls("C4")).isEqualTo(2);
        assertThat(p.search("quote", "c1", 5)).extracting(h -> h.ref().id()).contains("QUOTE-C1");
    }

    @Test
    void badSettingsFailAtStartWithTheirName() {
        assertThatThrownBy(() -> plugin(Map.of("range", "forever"))).hasMessageContaining("range");
        assertThatThrownBy(() -> plugin(Map.of("interval", "3s"))).hasMessageContaining("interval");
        assertThatThrownBy(() -> plugin(Map.of("base-url", "file:///etc"))).hasMessageContaining("base-url");
    }

    @Test
    void theContractOfAnOnDemandSource() throws Exception {
        YFinanceSourcePlugin p = plugin(Map.of());
        assertThat(p.manifest().kinds()).containsExactly("quote");
        assertThat(p.manifest().capabilities().dated()).isFalse();
        assertThat(p.health()).startsWith("UP");
        EntityDocument d = p.fetch(EntityRef.of("quote", "QUOTE-ACME")).orElseThrow();
        assertThat(d.ref()).isEqualTo(EntityRef.of("quote", "QUOTE-ACME"));
        assertThat(d.provenance().source()).isEqualTo("yfinance");
        assertThat(d.deleted()).isFalse();
        assertThat(p.lastUpdate()).isNotNull();
    }
}
