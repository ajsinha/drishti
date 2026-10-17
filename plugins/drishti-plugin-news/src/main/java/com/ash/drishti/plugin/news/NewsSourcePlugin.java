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

import com.ash.drishti.api.DataNode;
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
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A connector that is code, not a store: nothing is held, every document is <em>computed when it is asked for</em>. The
 * entity id names what to look for, the connector searches Google News (RSS, no key), reads the answer safely, filters,
 * de-duplicates, sorts and normalises it, and keeps the result for a few minutes so a busy page does not hit the web.
 *
 * <p>Ids: {@code NEWS-Apple_Inc} (underscores stand for spaces). {@code @de-DE} after the name picks another edition,
 * {@code ~all} switches the relevance filter off for that read: {@code NEWS-Apple_Inc@de-DE~all}. Aliases of an entity
 * ({@code aliases.Apple_Inc: Apple, AAPL}) also count as naming it. Every setting is in {@link NewsSettings}.
 *
 * <p>Entity names leave the network to the configured address. The connector is therefore off until a site enables it.
 */
public final class NewsSourcePlugin implements SourcePlugin {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern LOCALE = Pattern.compile("^(.*?)@([a-zA-Z]{2,3})-([a-zA-Z]{2})$");
    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    /** What one web call brought: the parsed items before any filter, and when. */
    private record Fetched(List<NewsItem> items, Instant at) {}

    /** The parts of an id. */
    private record Query(String name, String language, String country, boolean relevance) {}

    private final ConcurrentHashMap<String, CompletableFuture<Fetched>> inflight = new ConcurrentHashMap<>();
    private final HitIndex index = new HitIndex();
    private volatile BoundedCache<Fetched> cache = new BoundedCache<>(1);
    private volatile NewsSettings settings;
    private volatile NewsPipeline pipeline;
    private volatile HttpFetcher fetcher;
    private volatile SourceContext context;
    private volatile String health = "UP";
    private com.ash.drishti.api.proxy.ProxySettings proxy = com.ash.drishti.api.proxy.ProxySettings.none();
    private volatile Instant lastUpdate;
    private final java.util.concurrent.atomic.AtomicLong webCalls = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong cacheHits = new java.util.concurrent.atomic.AtomicLong();

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("news", "1.0", Set.of(settings == null ? "news" : settings.kind()), new SourceCapabilities(false, false, true, false));
    }

    @Override
    public void start(SourceContext ctx) {
        this.context = ctx;
        this.settings = NewsSettings.of(ctx.settings());
        this.cache = new BoundedCache<>(settings.cacheMax());
        this.pipeline = new NewsPipeline(settings);
        this.proxy = com.ash.drishti.api.proxy.ProxySettings.resolve(ctx.settings(), settings.baseUrl());   // proxy.* or the site default
        HttpClient.Builder b = proxy.apply(HttpClient.newBuilder().connectTimeout(settings.timeout()).followRedirects(HttpClient.Redirect.NORMAL));
        if (com.ash.drishti.api.tls.TlsSettings.anyGiven(ctx.settings(), "tls.")) {          // a proxy or mirror on a private CA
            var tls = com.ash.drishti.api.tls.TlsHttp.material(ctx.settings(), settings.baseUrl(), "base-url");
            if (tls != null) {
                b = com.ash.drishti.api.tls.TlsHttp.apply(b, tls);
            }
        }
        this.fetcher = new HttpFetcher(b.build(), settings, proxy);
        // nothing is fetched at start: there is no entity yet, and the web may be unreachable. Health is UP until a call fails.
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws Exception {
        Optional<Query> parsed = parse(ref.id());
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Query q = parsed.get();
        Fetched f = results(q);
        List<String> aliases = settings.aliases().getOrDefault(NewsSettings.key(q.name()), List.of());
        NewsPipeline.Outcome out = pipeline.run(f.items(), q.name(), aliases, q.relevance());
        index.add(new EntityHit(ref, q.name(), "news · " + out.items().size() + " headlines"));
        DataNode data = context.parseJson(new ByteArrayInputStream(JSON.writeValueAsBytes(com.ash.drishti.common.UpperSnake.keys(document(ref, q, aliases, f, out)))));
        return Optional.of(new EntityDocument(ref, data, new Provenance(settings.sourceName(), f.at().toEpochMilli(), f.at(), false)));
    }

    private Map<String, Object> document(EntityRef ref, Query q, List<String> aliases, Fetched f, NewsPipeline.Outcome out) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("newsId", ref.id());
        doc.put("entity", q.name());
        doc.put("aliases", aliases);
        doc.put("edition", q.language() + "-" + q.country());
        doc.put("relevanceFilter", q.relevance() ? "on" : "off");
        doc.put("count", out.items().size());
        doc.put("fetchedAt", SHOWN.format(f.at()));
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> bySource = new LinkedHashMap<>();
        for (NewsItem i : out.items()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("published", i.published() == null ? "" : SHOWN.format(i.published()));
            r.put("source", i.source());
            r.put("title", i.title());
            r.put("link", i.link());
            r.put("snippet", i.snippet());
            rows.add(r);
            bySource.merge(i.source().isEmpty() ? "(unknown)" : i.source(), 1, Integer::sum);
        }
        doc.put("items", rows);
        doc.put("bySource", bySource.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> Map.<String, Object>of("source", e.getKey(), "count", e.getValue())).toList());
        doc.put("pipeline", out.stages().stream().map(s -> Map.<String, Object>of("step", s.stage(), "kept", s.kept(), "dropped", s.dropped())).toList());
        doc.put("fetched", out.fetched());
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("feed", "Google News RSS (" + URI.create(settings.baseUrl()).getHost() + ")");
        source.put("fetched", SHOWN.format(f.at()));
        source.put("cachedFor", settings.cacheTtl().toSeconds() + " s");
        source.put("terms", "Headlines and links belong to their publishers; shown for information");
        doc.put("provenance", source);
        return doc;
    }

    /** The unfiltered items for the query: from the cache while fresh, else from the web (one call at a time per query, others wait for it). */
    private Fetched results(Query q) throws Exception {
        String key = q.name().toLowerCase(Locale.ROOT) + "|" + q.language() + "|" + q.country();
        Fetched cached = cache.get(key);
        if (cached != null && Duration.between(cached.at(), Instant.now()).compareTo(settings.cacheTtl()) < 0) {
            cacheHits.incrementAndGet();
            return cached;
        }
        CompletableFuture<Fetched> mine = new CompletableFuture<>();
        CompletableFuture<Fetched> running = inflight.putIfAbsent(key, mine);
        if (running != null) {
            try {
                return running.get();
            } catch (ExecutionException e) {
                throw e.getCause() instanceof Exception ex ? ex : e;
            }
        }
        try {
            Fetched f = new Fetched(load(q), Instant.now());
            cache.put(key, f);
            lastUpdate = f.at();
            health = proxy.annotate("UP", settings.baseUrl());
            mine.complete(f);
            return f;
        } catch (Exception e) {
            health = proxy.annotate("DOWN: " + e.getMessage() + " (tried again on the next request)", settings.baseUrl());
            if (cached != null) {                                  // the web failed but an older answer is held: serve it rather than nothing
                mine.complete(cached);
                return cached;
            }
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(key);
        }
    }

    private List<NewsItem> load(Query q) throws IOException, InterruptedException {
        String text = settings.queryTemplate().replace("{name}", q.name());
        URI uri = URI.create(settings.baseUrl() + (settings.baseUrl().contains("?") ? "&" : "?") + "q=" + enc(text) + "&hl=" + enc(q.language())
                + "&gl=" + enc(q.country()) + "&ceid=" + enc(q.country() + ":" + q.language()));
        webCalls.incrementAndGet();
        return RssParser.parse(fetcher.get(uri));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** {@code NEWS-<name>[@lang-CC][~all]} to its parts; empty when the id is not one of ours or names nothing. */
    private Optional<Query> parse(String id) {
        String prefix = settings.idPrefix();
        if (!id.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return Optional.empty();
        }
        String rest = id.substring(prefix.length());
        boolean relevance = settings.relevanceInTitle();
        if (rest.endsWith("~all")) {
            rest = rest.substring(0, rest.length() - 4);
            relevance = false;
        }
        String lang = settings.language();
        String country = settings.country();
        Matcher m = LOCALE.matcher(rest);
        if (m.matches()) {
            rest = m.group(1);
            lang = m.group(2).toLowerCase(Locale.ROOT);
            country = m.group(3).toUpperCase(Locale.ROOT);
        }
        String name = rest.replace('_', ' ').strip();
        if (name.isEmpty() || name.length() > 120 || name.chars().anyMatch(Character::isISOControl)) {
            return Optional.empty();
        }
        return Optional.of(new Query(name, lang, country, relevance));
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
        return Map.of("entities", cache.size(), "webCalls", webCalls.get(), "cacheHits", cacheHits.get());
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
