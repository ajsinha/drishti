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

import com.ash.drishti.common.RetryPolicy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Everything the connector reads from its settings, parsed once at start.
 *
 * @param baseUrl the chart endpoint, without the symbol (Yahoo's unofficial public one by default)
 * @param range how much history ({@code 1mo}, {@code 6mo}, {@code 1y} ...)
 * @param interval the bar size ({@code 1d}, {@code 1wk} ...)
 * @param timeout one HTTP attempt
 * @param cacheTtl how long a symbol's answer is reused
 * @param cacheMax how many symbols are cached (least recently used leave first)
 * @param maxBytes the largest response accepted
 * @param userAgent the User-Agent header (Yahoo refuses an empty one)
 * @param retries how many times a failed request is repeated
 * @param retryPolicy the backoff between attempts
 * @param idPrefix the prefix of entity ids
 * @param kind the kind served
 * @param sourceName the name shown as the document's source
 */
public record YFinanceSettings(String baseUrl, String range, String interval, Duration timeout, Duration cacheTtl, int cacheMax, int maxBytes,
        String userAgent, int retries, RetryPolicy retryPolicy, String idPrefix, String kind, String sourceName) {

    private static final Set<String> RANGES = Set.of("1d", "5d", "1mo", "3mo", "6mo", "1y", "2y", "5y", "10y", "ytd", "max");
    private static final Set<String> INTERVALS = Set.of("1m", "2m", "5m", "15m", "30m", "60m", "90m", "1h", "1d", "5d", "1wk", "1mo", "3mo");

    static YFinanceSettings of(Map<String, String> s) {
        Map<String, String> retry = new LinkedHashMap<>();
        retry.put("drishti.retry.initial", s.getOrDefault("retry-initial", "400ms"));
        retry.put("drishti.retry.max", s.getOrDefault("retry-max", "4s"));
        retry.put("drishti.retry.factor", s.getOrDefault("retry-factor", "2"));
        retry.put("drishti.retry.jitter", s.getOrDefault("retry-jitter", "0.2"));
        String base = pick(s, "base-url", "https://query1.finance.yahoo.com/v8/finance/chart");
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw new IllegalArgumentException("yfinance: base-url must be an http or https address, not " + base);
        }
        String range = pick(s, "range", "6mo");
        String interval = pick(s, "interval", "1d");
        if (!RANGES.contains(range)) {
            throw new IllegalArgumentException("yfinance: range must be one of " + new java.util.TreeSet<>(RANGES) + ", not " + range);
        }
        if (!INTERVALS.contains(interval)) {
            throw new IllegalArgumentException("yfinance: interval must be one of " + new java.util.TreeSet<>(INTERVALS) + ", not " + interval);
        }
        return new YFinanceSettings(base.endsWith("/") ? base.substring(0, base.length() - 1) : base, range, interval,
                Duration.ofSeconds(integer(s, "timeout-seconds", 10, 1, 120)), Duration.ofSeconds(integer(s, "cache-ttl-seconds", 60, 0, 86400)),
                integer(s, "cache-max-symbols", 100, 1, 100000), integer(s, "max-bytes", 2_000_000, 1000, 50_000_000),
                pick(s, "user-agent", "Mozilla/5.0 (compatible; drishti-yfinance-connector)"), integer(s, "retries", 2, 0, 10),
                RetryPolicy.of(retry::get, null), pick(s, "id-prefix", "QUOTE-"), pick(s, "kind", "quote"), pick(s, "source-name", "yfinance"));
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
                throw new IllegalArgumentException("yfinance: " + k + " must be between " + lo + " and " + hi + ", not " + n);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("yfinance: " + k + " is not a whole number: " + v);
        }
    }
}
