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

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.HitIndex;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * A connector that is code, not a store: the quote and recent price history of a listed symbol, computed when asked from
 * Yahoo Finance's public chart endpoint (no key). The id names the symbol ({@code QUOTE-AAPL}); the answer is parsed
 * defensively, kept for a short time per symbol in a bounded cache, and a symbol Yahoo does not know is simply not held.
 *
 * <p><b>Unofficial and restricted.</b> The endpoint is not a supported API and Yahoo's terms limit its use: this connector is a
 * sample for internal demonstrations, off until a site enables it, and not for production or redistribution.
 */
public final class YFinanceSourcePlugin implements SourcePlugin {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SYMBOL = Pattern.compile("[A-Za-z0-9.^=\\-]{1,15}");

    /** One symbol's answer, or null document when Yahoo does not know it. */
    private record Fetched(Map<String, Object> quote, Instant at) {}

    private final ConcurrentHashMap<String, CompletableFuture<Fetched>> inflight = new ConcurrentHashMap<>();
    private final HitIndex index = new HitIndex();
    private final AtomicLong webCalls = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private volatile BoundedCache<Fetched> cache = new BoundedCache<>(1);
    private volatile YFinanceSettings settings;
    private volatile HttpFetcher fetcher;
    private volatile SourceContext context;
    private volatile String health = "UP";
    private com.ash.drishti.api.proxy.ProxySettings proxy = com.ash.drishti.api.proxy.ProxySettings.none();
    private volatile Instant lastUpdate;

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("yfinance", "1.0", Set.of(settings == null ? "quote" : settings.kind()), new SourceCapabilities(false, false, true, false));
    }

    @Override
    public void start(SourceContext ctx) {
        this.context = ctx;
        this.settings = YFinanceSettings.of(ctx.settings());
        this.cache = new BoundedCache<>(settings.cacheMax());
        this.proxy = com.ash.drishti.api.proxy.ProxySettings.resolve(ctx.settings(), settings.baseUrl());   // proxy.* or the site default
        HttpClient.Builder b = proxy.apply(HttpClient.newBuilder().connectTimeout(settings.timeout()).followRedirects(HttpClient.Redirect.NORMAL));
        if (com.ash.drishti.api.tls.TlsSettings.anyGiven(ctx.settings(), "tls.")) {          // a proxy or mirror on a private CA
            var tls = com.ash.drishti.api.tls.TlsHttp.material(ctx.settings(), settings.baseUrl(), "base-url");
            if (tls != null) {
                b = com.ash.drishti.api.tls.TlsHttp.apply(b, tls);
            }
        }
        this.fetcher = new HttpFetcher(b.build(), settings, proxy);
        // nothing is fetched at start: there is no symbol yet and the web may be unreachable
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws Exception {
        String prefix = settings.idPrefix();
        String id = ref.id();
        if (!id.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return Optional.empty();
        }
        String symbol = id.substring(prefix.length()).strip();
        if (!SYMBOL.matcher(symbol).matches()) {
            return Optional.empty();
        }
        symbol = symbol.toUpperCase(Locale.ROOT);
        Fetched f = results(symbol);
        if (f.quote() == null) {
            return Optional.empty();
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("quoteId", settings.idPrefix() + symbol);
        doc.putAll(f.quote());
        doc.put("dayRange", range(f.quote().get("dayLow"), f.quote().get("dayHigh")));
        doc.put("fiftyTwoWeekRange", range(f.quote().get("fiftyTwoWeekLow"), f.quote().get("fiftyTwoWeekHigh")));
        doc.put("historyRange", settings.range());
        doc.put("interval", settings.interval());
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("feed", "Yahoo Finance public chart endpoint (unofficial)");
        source.put("fetched", f.at().toString());
        source.put("cachedFor", settings.cacheTtl().toSeconds() + " s");
        source.put("terms", "Sample and internal demonstration use only; not for production or redistribution");
        doc.put("provenance", source);
        index.add(new EntityHit(EntityRef.of(ref.kind(), settings.idPrefix() + symbol), symbol, "quote · " + f.quote().get("name")));
        return Optional.of(new EntityDocument(EntityRef.of(ref.kind(), settings.idPrefix() + symbol),
                context.parseJson(new ByteArrayInputStream(JSON.writeValueAsBytes(com.ash.drishti.common.UpperSnake.keys(doc)))), new Provenance(settings.sourceName(), f.at().toEpochMilli(), f.at(), false)));
    }

    private static String range(Object low, Object high) {
        return low instanceof Number l && high instanceof Number h ? String.format(Locale.ROOT, "%.2f – %.2f", l.doubleValue(), h.doubleValue()) : "";
    }

    /** From the cache while fresh, else from Yahoo (one call at a time per symbol); an older answer is served when Yahoo fails. */
    private Fetched results(String symbol) throws Exception {
        Fetched cached = cache.get(symbol);
        if (cached != null && Duration.between(cached.at(), Instant.now()).compareTo(settings.cacheTtl()) < 0) {
            cacheHits.incrementAndGet();
            return cached;
        }
        CompletableFuture<Fetched> mine = new CompletableFuture<>();
        CompletableFuture<Fetched> running = inflight.putIfAbsent(symbol, mine);
        if (running != null) {
            try {
                return running.get();
            } catch (ExecutionException e) {
                throw e.getCause() instanceof Exception ex ? ex : e;
            }
        }
        try {
            Fetched f;
            try {
                f = new Fetched(load(symbol), Instant.now());
            } catch (ChartParser.UnknownSymbol u) {
                f = new Fetched(null, Instant.now());                  // remembered for the TTL: asking again would only repeat the answer
            } catch (HttpFetcher.FetchException e) {
                if (e.status() != 404) {
                    throw e;
                }
                f = new Fetched(null, Instant.now());
            }
            cache.put(symbol, f);
            lastUpdate = f.at();
            health = proxy.annotate("UP", settings.baseUrl());
            mine.complete(f);
            return f;
        } catch (Exception e) {
            health = proxy.annotate("DOWN: " + e.getMessage() + " (tried again on the next request)", settings.baseUrl());
            if (cached != null) {
                mine.complete(cached);
                return cached;
            }
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(symbol);
        }
    }

    private Map<String, Object> load(String symbol) throws Exception {
        URI uri = URI.create(settings.baseUrl() + "/" + URLEncoder.encode(symbol, StandardCharsets.UTF_8) + "?range=" + settings.range() + "&interval=" + settings.interval());
        webCalls.incrementAndGet();
        byte[] body = fetcher.get(uri);
        return ChartParser.parse(body, settings.interval(), Instant.now());
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return index.search(kind, text, limit);
    }

    @Override
    public Instant lastUpdate() {
        return lastUpdate;
    }

    @Override
    public Map<String, Object> cacheStats() {
        return Map.of("symbols", cache.size(), "webCalls", webCalls.get(), "cacheHits", cacheHits.get());
    }

    @Override
    public void purgeCaches() {
        cache.clear();
    }

    @Override
    public String health() {
        return health;
    }
}
