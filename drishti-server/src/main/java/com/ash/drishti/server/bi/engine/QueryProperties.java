package com.ash.drishti.server.bi.engine;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The analytic engine's settings, {@code drishti.bi.query.*} (docs/admin/CONFIGURATION.md): limits per query and per server, the result cache,
 * the layers and the embedded DuckDB. Every bound has a default here and can be changed without a build.
 *
 * @param storeDir where the columns and rollup layers are written (the BI store): default {@code <data dir>/bi-store}
 * @param timeout longest a query may run; past it the query is cancelled (DRS-9024)
 * @param maxRows most rows a query may return (DRS-9022)
 * @param maxBytes most bytes of a result, as Arrow (DRS-9023)
 * @param maxConcurrent queries running at once on the server (DRS-9025)
 * @param maxConcurrentPerUser queries running at once for one user (DRS-9025)
 * @param jsonMaxRows most rows an answer in JSON may hold; larger results are asked for as Arrow
 * @param cacheEntries results kept in the cache (0 switches the cache off)
 * @param cacheMaxBytes the cache's budget in bytes of results
 * @param cacheTtl how long a cached result may be served
 * @param memoryLimit DuckDB's memory limit
 * @param threads DuckDB's worker threads
 * @param scanBatchRows rows in one batch when a layer is built from a connector
 * @param recordReadMax most entities read as documents when a connector cannot scan columns (the slow path; past it the build stops and warns)
 * @param recordReadBudget how long one document read may take
 * @param latestTtl how long "the latest business date" of a table is trusted before the source is asked again
 * @param eagerRebuild rebuild a layer's date right away when a data-load signal lands (else on the next query)
 * @param liveTtl how long a live table's snapshot is served before the current state is read again
 * @param admission how long a query waits for a slot before it is refused as busy
 * @param inferSample most values of a column the relationship finder looks at
 * @param inferMinMatch the share of a column's values that must be found in the other table's keys for a relationship to be proposed
 */
@ConfigurationProperties("drishti.bi.query")
public record QueryProperties(String storeDir, Duration timeout, Integer maxRows, Long maxBytes, Integer maxConcurrent, Integer maxConcurrentPerUser,
        Integer jsonMaxRows, Integer cacheEntries, Long cacheMaxBytes, Duration cacheTtl, String memoryLimit, Integer threads, Integer scanBatchRows,
        Integer recordReadMax, Duration recordReadBudget, Duration latestTtl, Boolean eagerRebuild, Duration liveTtl, Duration admission, Integer inferSample, Double inferMinMatch) {

    public QueryProperties {
        storeDir = storeDir == null || storeDir.isBlank() ? "./data/bi-store" : storeDir;
        timeout = positive(timeout, Duration.ofSeconds(30));
        maxRows = maxRows == null || maxRows <= 0 ? 100_000 : maxRows;
        maxBytes = maxBytes == null || maxBytes <= 0 ? 64L * 1024 * 1024 : maxBytes;
        maxConcurrent = maxConcurrent == null || maxConcurrent <= 0 ? 8 : maxConcurrent;
        maxConcurrentPerUser = maxConcurrentPerUser == null || maxConcurrentPerUser <= 0 ? 3 : maxConcurrentPerUser;
        jsonMaxRows = jsonMaxRows == null || jsonMaxRows <= 0 ? 5_000 : jsonMaxRows;
        cacheEntries = cacheEntries == null || cacheEntries < 0 ? 200 : cacheEntries;
        cacheMaxBytes = cacheMaxBytes == null || cacheMaxBytes < 0 ? 64L * 1024 * 1024 : cacheMaxBytes;
        cacheTtl = positive(cacheTtl, Duration.ofMinutes(15));
        memoryLimit = memoryLimit == null || memoryLimit.isBlank() ? "768MB" : memoryLimit;
        threads = threads == null || threads <= 0 ? 2 : threads;
        scanBatchRows = scanBatchRows == null || scanBatchRows <= 0 ? 65_536 : scanBatchRows;
        recordReadMax = recordReadMax == null || recordReadMax <= 0 ? 50_000 : recordReadMax;
        recordReadBudget = positive(recordReadBudget, Duration.ofSeconds(60));
        latestTtl = positive(latestTtl, Duration.ofSeconds(30));
        eagerRebuild = eagerRebuild == null || eagerRebuild;
        liveTtl = positive(liveTtl, Duration.ofSeconds(5));
        admission = positive(admission, Duration.ofSeconds(2));
        inferSample = inferSample == null || inferSample <= 0 ? 5_000 : inferSample;
        inferMinMatch = inferMinMatch == null || inferMinMatch <= 0 || inferMinMatch > 1 ? 0.8 : inferMinMatch;
    }

    private static Duration positive(Duration d, Duration dflt) {
        return d == null || d.isNegative() || d.isZero() ? dflt : d;
    }

    /** Defaults for tests and embedded use. */
    public static QueryProperties defaults(String storeDir) {
        return new QueryProperties(storeDir, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
