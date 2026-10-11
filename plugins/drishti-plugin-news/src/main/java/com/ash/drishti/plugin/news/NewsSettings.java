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

import com.ash.drishti.common.RetryPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Everything the connector reads from its settings, parsed once at start. Nothing here is a constant of the code: a
 * site changes the edition, the limits, the filters and the retry behaviour in its connector file.
 *
 * @param baseUrl the RSS search address (Google News by default)
 * @param language default edition language ({@code en})
 * @param country default edition country ({@code US})
 * @param maxItems headlines returned per entity
 * @param timeout one HTTP attempt, connect and read
 * @param cacheTtl how long an entity's headlines are reused
 * @param cacheMax how many entities are cached (least recently used leave first)
 * @param maxBytes the largest response accepted
 * @param userAgent the User-Agent header
 * @param queryTemplate the search text, {@code {name}} replaced by the entity name
 * @param relevanceInTitle keep only headlines whose title names the entity or one of its aliases
 * @param allowSources when not empty, only these publishers (lower case)
 * @param denySources publishers never shown (lower case)
 * @param aliases alias lists by entity (lower case, underscores for spaces)
 * @param retries how many times a failed request is repeated
 * @param retryPolicy the backoff between attempts (the shared {@code drishti.retry.*} shape)
 * @param idPrefix the prefix of entity ids
 * @param kind the kind served
 * @param sourceName the name shown as the document's source
 */
public record NewsSettings(String baseUrl, String language, String country, int maxItems, Duration timeout, Duration cacheTtl, int cacheMax,
        int maxBytes, String userAgent, String queryTemplate, boolean relevanceInTitle, Set<String> allowSources, Set<String> denySources,
        Map<String, List<String>> aliases, int retries, RetryPolicy retryPolicy, String idPrefix, String kind, String sourceName) {

    static NewsSettings of(Map<String, String> s) {
        Map<String, String> retry = new LinkedHashMap<>();
        retry.put("drishti.retry.initial", s.getOrDefault("retry-initial", "300ms"));
        retry.put("drishti.retry.max", s.getOrDefault("retry-max", "3s"));
        retry.put("drishti.retry.factor", s.getOrDefault("retry-factor", "2"));
        retry.put("drishti.retry.jitter", s.getOrDefault("retry-jitter", "0.2"));
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        s.forEach((k, v) -> {
            if (k.startsWith("aliases.") && v != null && !v.isBlank()) {
                aliases.put(key(k.substring("aliases.".length())), list(v));
            }
        });
        String base = pick(s, "base-url", "https://news.google.com/rss/search");
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw new IllegalArgumentException("news: base-url must be an http or https address, not " + base);
        }
        String relevance = pick(s, "relevance", "title").toLowerCase(Locale.ROOT);
        if (!relevance.equals("title") && !relevance.equals("off")) {
            throw new IllegalArgumentException("news: relevance must be title or off, not " + relevance);
        }
        return new NewsSettings(base, pick(s, "language", "en"), pick(s, "country", "US"), integer(s, "max-items", 10, 1, 100),
                Duration.ofSeconds(integer(s, "timeout-seconds", 10, 1, 120)), Duration.ofSeconds(integer(s, "cache-ttl-seconds", 600, 0, 86400)),
                integer(s, "cache-max-entities", 200, 1, 100000), integer(s, "max-bytes", 2_000_000, 1000, 50_000_000),
                pick(s, "user-agent", "Mozilla/5.0 (compatible; drishti-news-connector)"), pick(s, "query-template", "{name}"), relevance.equals("title"),
                lowerSet(s.get("allow-sources")), lowerSet(s.get("deny-sources")), Map.copyOf(aliases), integer(s, "retries", 2, 0, 10),
                RetryPolicy.of(retry::get, null), pick(s, "id-prefix", "NEWS-"), pick(s, "kind", "news"), pick(s, "source-name", "news"));
    }

    /** The key aliases are stored under: lower case, spaces as underscores. */
    static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static String pick(Map<String, String> s, String k, String d) {
        String v = s.get(k);
        return v == null || v.isBlank() ? d : v.strip();
    }

    private static int integer(Map<String, String> s, String k, int d, int lo, int hi) {
        String v = s.get(k);
        if (v == null || v.isBlank()) {
            return d;
        }
        try {
            int n = Integer.parseInt(v.strip());
            if (n < lo || n > hi) {
                throw new IllegalArgumentException("news: " + k + " must be between " + lo + " and " + hi + ", not " + n);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("news: " + k + " is not a whole number: " + v);
        }
    }

    private static List<String> list(String v) {
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) {
            if (!p.isBlank()) {
                out.add(p.strip());
            }
        }
        return List.copyOf(out);
    }

    private static Set<String> lowerSet(String v) {
        return v == null ? Set.of() : list(v).stream().map(x -> x.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }
}
