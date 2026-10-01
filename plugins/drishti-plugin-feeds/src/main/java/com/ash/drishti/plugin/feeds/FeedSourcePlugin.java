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

import com.ash.drishti.api.AsOf;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * A public data feed as a source: one connector per feed, each switched on or off in configuration and off by
 * default. The feed is fetched at start and every {@code refresh-minutes}; its dated observations are kept, so a
 * picked business date shows that day's fixing, rates or curve. Entities carry the feed in their identifier
 * ({@code FIX-SOFR-NYFED}, {@code FX-EURUSD-ECB}, {@code CRV-USD-UST}, {@code FIX-ESTR-ECB}, {@code FIX-FRED-DGS10}),
 * so real data is never confused with the samples.
 *
 * <p>Settings: {@code feed} ({@code nyfed-sofr}, {@code ecb-estr}, {@code ecb-fx}, {@code us-treasury},
 * {@code fred}), {@code refresh-minutes} (60), {@code timeout-seconds} (20), {@code url} (override; {@code file:}
 * URLs are read directly), {@code api-key} and {@code series} (FRED), {@code source-name}.
 */
public final class FeedSourcePlugin implements SourcePlugin {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<EntityRef, Feed.Series> series = new ConcurrentHashMap<>();
    private final HitIndex index = new HitIndex();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private Feed feed;
    private SourceContext context;
    private String userAgent = "public-data-feed-connector";
    private String sourceName = "feed";
    private volatile String health = "DOWN: not fetched yet";
    /** The last refresh that brought data. */
    private volatile java.time.Instant lastUpdate;

    @Override
    public java.time.Instant lastUpdate() {
        return lastUpdate;
    }
    private volatile Instant fetchedAt = Instant.EPOCH;
    private int timeoutSeconds;

    @Override
    public PluginManifest manifest() {
        return new PluginManifest(sourceName, "1.0", feed == null ? java.util.Set.of() : new HashSet<>(feed.kinds()),
                new SourceCapabilities(false, false, true, true));
    }

    @Override
    public void start(SourceContext ctx) {
        this.context = ctx;
        if (ctx.setting("feed", "").isBlank()) {
            throw new com.ash.drishti.api.PluginNotConfigured("feed needs settings.feed (nyfed-sofr, ecb-estr, ecb-fx, us-treasury, fred)");
        }
        this.feed = Feeds.named(ctx.setting("feed", ""));
        this.sourceName = ctx.setting("source-name", ctx.setting("feed", "feed"));
        this.timeoutSeconds = Integer.parseInt(ctx.setting("timeout-seconds", "20"));
        this.userAgent = ctx.setting("user-agent", userAgent);       // the pack sets it (product name and version)
        refresh();
        long every = Long.parseLong(ctx.setting("refresh-minutes", "60"));
        ctx.scheduler().scheduleWithFixedDelay(this::refresh, every, every, TimeUnit.MINUTES);
    }

    /** Fetches the feed and replaces what is served; on failure keeps the last good data and reports it in health. */
    void refresh() {
        try {
            List<String> bodies = new ArrayList<>();
            for (String url : feed.urls(context.settings(), LocalDate.now(ZoneId.of("America/New_York")))) {
                bodies.add(get(url));
            }
            List<Feed.Series> parsed = feed.parse(bodies, context.settings());
            if (parsed.isEmpty()) {                            // an answer with no rows: keep serving the last good data
                health = series.isEmpty() ? "DOWN: the feed returned no data" : "DOWN: the feed returned no data (serving the last data)";
                return;
            }
            List<EntityHit> hits = new ArrayList<>();
            for (Feed.Series s : parsed) {
                EntityRef ref = EntityRef.of(s.kind(), s.id());
                series.put(ref, s);
                hits.add(new EntityHit(ref, s.id(), s.kind() + " · " + sourceName + " (public feed)"));
            }
            index.replaceAll(hits);
            fetchedAt = Instant.now();
            health = "UP";
            lastUpdate = java.time.Instant.now();
        } catch (Exception e) {
            health = "DOWN: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private String get(String url) throws Exception {
        if (url.startsWith("file:")) {
            return Files.readString(Path.of(URI.create(url)));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", userAgent).GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + r.statusCode() + " from " + URI.create(url).getHost());
        }
        return r.body();
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws Exception {
        return fetch(ref, AsOf.LATEST);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) throws Exception {
        Feed.Series s = series.get(ref);
        if (s == null || s.observations().isEmpty()) {
            return Optional.empty();
        }
        LocalDate on = asOf.businessDate() == null ? s.observations().lastKey() : asOf.businessDate();
        LocalDate dataDate = s.observations().floorKey(on);
        if (dataDate == null) {
            return Optional.empty();
        }
        Map<String, Object> doc = feed.document(s, on);
        if (doc == null) {
            return Optional.empty();
        }
        DataNode data = context.parseJson(new ByteArrayInputStream(JSON.writeValueAsBytes(doc)));
        return Optional.of(new EntityDocument(ref, data, new Provenance(sourceName, fetchedAt.toEpochMilli(), fetchedAt, false, dataDate)));
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return index.search(kind, text, limit);
    }

    @Override
    public Map<String, Object> cacheStats() {
        return Map.of("series", series.size(), "observations", series.values().stream().mapToInt(s -> s.observations().size()).sum(),
                "fetchedAt", fetchedAt.toString());
    }

    /** Drops the feed's data and fetches it again now. */
    @Override
    public void purgeCaches() {
        series.clear();
        refresh();
    }

    @Override
    public String health() {
        return health;
    }
}
