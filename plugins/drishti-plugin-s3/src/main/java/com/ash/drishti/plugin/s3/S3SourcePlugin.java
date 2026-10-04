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
package com.ash.drishti.plugin.s3;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.HitIndex;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Entity documents in Amazon S3 or any S3-compatible store (MinIO, Ceph, on-prem appliances), laid out as the file
 * connector's folders: {@code <prefix><kind>/<id>.json}, and by business date {@code <prefix><yyyy-MM-dd>/<kind>/<id>.json}.
 * A picked date reads the newest dated folder on or before it (within {@code lookback-days}), then the undated one.
 *
 * <p>Settings: {@code bucket}, {@code prefix} (optional, e.g. {@code risk/}), {@code region} ({@code us-east-1}),
 * {@code endpoint} (for S3-compatible stores; path-style addressing then), {@code access-key} / {@code secret-key}
 * (otherwise the standard AWS credential chain: environment, profile, instance role), {@code rescan-seconds} (60: how
 * often identifiers and dates are listed for search), {@code cache-seconds} (30), {@code lookback-days} (10). Every call
 * is a plain HTTPS request, so there is no connection to lose; failures show in health and the next call retries.
 */
public final class S3SourcePlugin implements SourcePlugin {

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private S3Client s3;
    private String bucket;
    private String prefix;
    private String sourceName;
    private int lookbackDays;
    private SourceContext context;
    private final HitIndex index = new HitIndex();
    private volatile List<LocalDate> dates = List.of();            // dated folders, newest first
    private volatile Set<String> kinds = Set.of();
    private volatile String lastError;
    /** Why the last listing failed (a successful read does not clear it: only the next good listing does). */
    private volatile String listingError;
    private Cache<String, Optional<EntityDocument>> cache;
    private final com.ash.drishti.api.SingleFlight<String> reads = new com.ash.drishti.api.SingleFlight<>();

    @Override
    public PluginManifest manifest() {
        return new PluginManifest(sourceName == null ? "s3" : sourceName, "1.0", kinds, new SourceCapabilities(false, false, true, true));
    }

    @Override
    public void start(SourceContext ctx) {
        this.context = ctx;
        this.sourceName = ctx.setting("source-name", "s3");
        this.bucket = ctx.setting("bucket", "");
        if (bucket.isBlank()) {
            throw new IllegalStateException("s3 plugin needs settings.bucket");
        }
        String p = ctx.setting("prefix", "");
        this.prefix = p.isEmpty() || p.endsWith("/") ? p : p + "/";
        this.lookbackDays = Integer.parseInt(ctx.setting("lookback-days", "10"));
        S3ClientBuilder b = S3Client.builder().region(Region.of(ctx.setting("region", "us-east-1")))
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(20)));
        String endpoint = ctx.setting("endpoint", "");
        if (!endpoint.isBlank()) {
            b.endpointOverride(URI.create(endpoint)).forcePathStyle(Boolean.parseBoolean(ctx.setting("path-style", "true")));
        }
        String key = ctx.setting("access-key", "");
        b.credentialsProvider(key.isBlank() ? DefaultCredentialsProvider.builder().build()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(key, ctx.setting("secret-key", ""))));
        this.s3 = b.build();
        this.cache = Caffeine.newBuilder().maximumSize(Long.parseLong(ctx.setting("cache-entries", "10000")))
                .expireAfterWrite(Duration.ofSeconds(Long.parseLong(ctx.setting("cache-seconds", "30")))).build();
        rescan();
        long every = Long.parseLong(ctx.setting("rescan-seconds", "60"));
        ctx.scheduler().scheduleWithFixedDelay(this::rescan, every, every, TimeUnit.SECONDS);
    }

    /** Lists the dated folders and every object, for dated reads and search; keeps the last good listing on failure. */
    void rescan() {
        try {
            List<LocalDate> found = new ArrayList<>();
            ListObjectsV2Request top = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).delimiter("/").build();
            for (var page : s3.listObjectsV2Paginator(top)) {
                for (CommonPrefix cp : page.commonPrefixes()) {
                    String name = cp.prefix().substring(prefix.length()).replace("/", "");
                    if (DATE.matcher(name).matches()) {
                        try {
                            found.add(LocalDate.parse(name));
                        } catch (java.time.DateTimeException e) {
                            // a folder named like a date that is not one (2026-13-01): ignored, not fatal to the listing
                        }
                    }
                }
            }
            found.sort(Comparator.reverseOrder());
            Map<EntityRef, EntityHit> hits = new LinkedHashMap<>();
            Set<String> ks = new HashSet<>();
            for (var page : s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())) {
                for (S3Object o : page.contents()) {
                    String[] parts = o.key().substring(prefix.length()).split("/");
                    String[] kindAndFile = parts.length == 3 && DATE.matcher(parts[0]).matches() ? new String[] {parts[1], parts[2]}
                            : parts.length == 2 ? parts : null;
                    if (kindAndFile == null || !kindAndFile[1].endsWith(".json")) {
                        continue;
                    }
                    EntityRef ref = EntityRef.of(kindAndFile[0], kindAndFile[1].substring(0, kindAndFile[1].length() - 5));
                    ks.add(ref.kind());
                    hits.putIfAbsent(ref, new EntityHit(ref, ref.id(), ref.kind() + " · " + sourceName));
                }
            }
            dates = List.copyOf(found);
            kinds = Set.copyOf(ks);
            index.replaceAll(new ArrayList<>(hits.values()));
            listingError = null;
        } catch (RuntimeException e) {
            listingError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws Exception {
        return fetch(ref, AsOf.LATEST);
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) throws Exception {
        if (!safe(ref.kind()) || !safe(ref.id())) {
            return Optional.empty();                                   // an id cannot reach outside its folder
        }
        LocalDate want = asOf.businessDate();
        for (LocalDate d : dates) {
            if (want != null && (d.isAfter(want) || d.isBefore(want.minusDays(lookbackDays)))) {
                continue;
            }
            Optional<EntityDocument> hit = read(ref, prefix + d + "/", d);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return read(ref, prefix, null);
    }

    private Optional<EntityDocument> read(EntityRef ref, String base, LocalDate date) {
        String key = base + ref.kind() + "/" + ref.id() + ".json";
        return reads.get(key, cache::getIfPresent, k -> {          // not Cache.get(key, loader): that blocks inside synchronized (pins a virtual thread on Java 21)
            try (ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(k).build())) {
                Instant modified = in.response().lastModified();
                EntityDocument d = new EntityDocument(ref, context.parseJson((InputStream) in),
                        new Provenance(sourceName, modified == null ? 0 : modified.toEpochMilli(), Instant.now(), false, date));
                lastError = null;
                return Optional.of(d);
            } catch (NoSuchKeyException e) {
                return Optional.empty();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            } catch (RuntimeException e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                throw e;
            }
        }, cache::put);
    }

    private static boolean safe(String part) {
        return !part.isEmpty() && !part.contains("/") && !part.contains("\\") && !part.equals(".") && !part.equals("..");
    }

    @Override
    public List<EntityHit> search(String kind, String text, int limit) {
        return index.search(kind, text, limit);
    }

    @Override
    public Map<String, Object> cacheStats() {
        return Map.of("cachedObjects", cache.estimatedSize(), "indexed", index.size(), "datedFolders", dates.size());
    }

    @Override
    public void purgeCaches() {
        cache.invalidateAll();
    }

    @Override
    public String health() {
        String e = listingError != null ? listingError : lastError;
        return e == null ? "UP" : "DOWN: " + e + " (retrying)";
    }

    @Override
    public void close() {
        if (s3 != null) {
            s3.close();
        }
    }
}
