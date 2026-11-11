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
package com.ash.drishti.plugin.news;

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

/** The connector against a local fake of Google News: the logic, the safety limits and the retry behaviour. Nothing leaves the machine. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NewsSourcePluginTest {

    private FakeNewsServer fake;

    @BeforeAll
    void up() throws Exception {
        fake = new FakeNewsServer();
    }

    @AfterAll
    void down() {
        fake.close();
    }

    private NewsSourcePlugin plugin(Map<String, String> extra) {
        Map<String, String> s = new HashMap<>(Map.of("base-url", fake.baseUrl(), "timeout-seconds", "1", "retry-initial", "10ms", "retry-max", "50ms",
                "deny-sources", "Blog Spam", "aliases.Acme", "Acme Corp"));
        s.putAll(extra);
        NewsSourcePlugin p = new NewsSourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    private static DataNode doc(NewsSourcePlugin p, String id) throws Exception {
        return p.fetch(EntityRef.of("news", id)).orElseThrow().data();
    }

    @Test
    void filtersDeduplicatesSortsAndNormalises() throws Exception {
        NewsSourcePlugin p = plugin(Map.of());
        DataNode d = doc(p, "NEWS-Acme");
        // 7 usable items: 3 "opens new plant" (one exact duplicate, one same headline from the same publisher), 1 unrelated, 1 denied, 1 FT, 1 Guardian
        assertThat(d.get("FETCHED").asDouble()).isEqualTo(7.0);
        assertThat(titles(d)).containsExactly("The wider Acme story: profits", "ACME names a new chief executive", "Acme opens new plant!");
        assertThat(d.get("ITEMS").get(0).get("SOURCE").asText()).isEqualTo("The Guardian");
        assertThat(d.get("ITEMS").get(0).get("LINK").asText()).isEqualTo("https://n.example/e1");
        assertThat(d.get("ITEMS").get(0).get("PUBLISHED").asText()).isEqualTo("2026-10-09 18:45 UTC");
        assertThat(d.get("ITEMS").get(1).get("SNIPPET").asText()).doesNotContain("<").doesNotContain("&nbsp;");
        assertThat(d.get("RELEVANCE_FILTER").asText()).isEqualTo("on");
        assertThat(d.get("PIPELINE").size()).isGreaterThanOrEqualTo(4);
        assertThat(d.get("BY_SOURCE").size()).isEqualTo(3);
    }

    @Test
    void relevanceCanBeOffPerReadAndAliasesCount() throws Exception {
        NewsSourcePlugin p = plugin(Map.of());
        assertThat(titles(doc(p, "NEWS-Acme~all"))).contains("Markets rally on Friday");
        assertThat(titles(doc(p, "NEWS-Acme~all"))).doesNotContain("Why Acme shares fell");          // the deny list still applies
        NewsSourcePlugin noAlias = plugin(Map.of("query-template", "Acme"));
        assertThat(titles(doc(noAlias, "NEWS-Zed"))).isEmpty();                                         // the feed is about Acme, the entity is Zed
        NewsSourcePlugin alias = plugin(Map.of("query-template", "Acme", "aliases.Zed", "Acme"));
        assertThat(titles(doc(alias, "NEWS-Zed"))).isNotEmpty();                                        // an alias counts as naming the entity
        NewsSourcePlugin off = plugin(Map.of("relevance", "off"));
        assertThat(titles(doc(off, "NEWS-Acme"))).contains("Markets rally on Friday");
    }

    @Test
    void allowListAndMaxItemsApply() throws Exception {
        NewsSourcePlugin p = plugin(Map.of("allow-sources", "financial times,the guardian", "max-items", "1"));
        assertThat(titles(doc(p, "NEWS-Acme"))).containsExactly("The wider Acme story: profits");
    }

    @Test
    void theRequestIsGoogleShapedAndEncoded() throws Exception {
        NewsSourcePlugin p = plugin(Map.of("language", "de", "country", "DE", "max-items", "5"));
        doc(p, "NEWS-Banco_Santander@es-ES");
        assertThat(fake.lastQuery()).isEqualTo("q=Banco+Santander&hl=es&gl=ES&ceid=ES%3Aes");
    }

    @Test
    void theResultIsCachedPerEntityForTheTtl() throws Exception {
        NewsSourcePlugin p = plugin(Map.of("cache-ttl-seconds", "60"));
        doc(p, "NEWS-Cached");
        doc(p, "NEWS-Cached");
        doc(p, "NEWS-Cached~all");                                      // a different filter, the same web call
        assertThat(fake.calls("Cached")).isEqualTo(1);
        NewsSourcePlugin none = plugin(Map.of("cache-ttl-seconds", "0"));
        doc(none, "NEWS-Uncached");
        doc(none, "NEWS-Uncached");
        assertThat(fake.calls("Uncached")).isEqualTo(2);
        assertThat(p.search("news", "cach", 5)).extracting(h -> h.ref().id()).contains("NEWS-Cached");
    }

    @Test
    void theCacheIsBounded() throws Exception {
        NewsSourcePlugin p = plugin(Map.of("cache-max-entities", "2"));
        doc(p, "NEWS-B1");
        doc(p, "NEWS-B2");
        doc(p, "NEWS-B3");
        assertThat(p.cacheStats().get("entities")).isEqualTo(2);
    }

    @Test
    void anXxeAttemptIsRefusedAndReadsNothing() {
        NewsSourcePlugin p = plugin(Map.of());
        assertThatThrownBy(() -> doc(p, "NEWS-Evil")).hasMessageContaining("not a readable RSS");
        assertThat(p.health()).startsWith("DOWN");
    }

    @Test
    void anOversizedBodyIsRefusedWithoutRetry() {
        NewsSourcePlugin p = plugin(Map.of("max-bytes", "100000"));
        assertThatThrownBy(() -> doc(p, "NEWS-Huge")).hasMessageContaining("larger than max-bytes");
        assertThat(fake.calls("Huge")).isEqualTo(1);
    }

    @Test
    void aSlowResponseTimesOutThenRetries() {
        NewsSourcePlugin p = plugin(Map.of("retries", "1"));
        assertThatThrownBy(() -> doc(p, "NEWS-Slow")).hasMessageContaining("timed out");
        assertThat(fake.calls("Slow")).isEqualTo(2);
        assertThat(p.health()).startsWith("DOWN");
    }

    @Test
    void aServerErrorIsRetriedAndThenSucceeds() throws Exception {
        NewsSourcePlugin p = plugin(Map.of("retries", "2"));
        assertThat(titles(doc(p, "NEWS-Flaky"))).isNotEmpty();
        assertThat(fake.calls("Flaky")).isEqualTo(3);
        assertThat(p.health()).isEqualTo("UP");
        NewsSourcePlugin q = plugin(Map.of("retries", "1"));
        assertThat(titles(doc(q, "NEWS-Limited"))).isNotEmpty();         // one 429 then ok
    }

    @Test
    void aServerThatKeepsFailingGivesUpAndReportsDownUntilItRecovers() throws Exception {
        NewsSourcePlugin p = plugin(Map.of("retries", "0", "cache-ttl-seconds", "60"));
        assertThatThrownBy(() -> doc(p, "NEWS-Forbidden")).hasMessageContaining("HTTP 403");
        assertThat(p.health()).startsWith("DOWN: HTTP 403");
        assertThat(fake.calls("Forbidden")).isEqualTo(1);               // a 403 is not repeated
        doc(p, "NEWS-Acme");
        assertThat(p.health()).isEqualTo("UP");
    }

    @Test
    void garbageIsAnErrorNotAnEmptyAnswer() {
        NewsSourcePlugin p = plugin(Map.of("retries", "0"));
        assertThatThrownBy(() -> doc(p, "NEWS-Broken")).hasMessageContaining("not a readable RSS");
    }

    @Test
    void anIdOfAnotherKindOrNoNameIsNotHeld() throws Exception {
        NewsSourcePlugin p = plugin(Map.of());
        assertThat(p.fetch(EntityRef.of("news", "OTHER-1"))).isEmpty();
        assertThat(p.fetch(EntityRef.of("news", "NEWS-"))).isEmpty();
        assertThat(p.fetch(EntityRef.of("news", "NEWS-___"))).isEmpty();
    }

    @Test
    void badSettingsFailAtStartWithTheirName() {
        assertThatThrownBy(() -> plugin(Map.of("base-url", "ftp://x"))).hasMessageContaining("base-url");
        assertThatThrownBy(() -> plugin(Map.of("max-items", "0"))).hasMessageContaining("max-items");
        assertThatThrownBy(() -> plugin(Map.of("relevance", "maybe"))).hasMessageContaining("relevance");
    }

    @Test
    void theContractOfAnOnDemandSource() throws Exception {
        NewsSourcePlugin p = plugin(Map.of());
        assertThat(p.manifest().kinds()).containsExactly("news");
        assertThat(p.manifest().capabilities().dated()).isFalse();
        assertThat(p.health()).startsWith("UP");
        EntityDocument d = p.fetch(EntityRef.of("news", "NEWS-Acme")).orElseThrow();
        assertThat(d.ref()).isEqualTo(EntityRef.of("news", "NEWS-Acme"));
        assertThat(d.provenance().source()).isEqualTo("news");
        assertThat(d.deleted()).isFalse();
        assertThat(p.lastUpdate()).isNotNull();
    }

    private static java.util.List<String> titles(DataNode d) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < d.get("ITEMS").size(); i++) {
            out.add(d.get("ITEMS").get(i).get("TITLE").asText());
        }
        return out;
    }
}
