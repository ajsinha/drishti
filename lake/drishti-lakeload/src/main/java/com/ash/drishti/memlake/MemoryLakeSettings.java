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
package com.ash.drishti.memlake;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The in-memory lake, {@code drishti.lake.memory.*} (docs/architecture/DATA_ARCHITECTURE.md section 11; docs/guides/DATA_LAKE_GUIDE.md):
 * the memory stores (each a hard cap of off-heap memory), the tables their replenish jobs fill, and the automated compaction. Plain values:
 * the server binds {@code drishti.lake.memory.*} onto this record ({@code MemoryLakeConfiguration}); this module reads no configuration.
 *
 * @param stores the memory stores by name ({@code mem://<name>/…})
 * @param tables the tables and their replenish jobs
 * @param compaction the automated compaction (on by default)
 * @param historyKeep the runs kept per table for Admin, Lakes and {@code drishti.py lake memory jobs} (50)
 * @param jobThreads the replenish jobs that may run at once (2; one per table at most)
 */
public record MemoryLakeSettings(Map<String, Store> stores, List<TableSpec> tables, Compaction compaction, Integer historyKeep, Integer jobThreads) {

    public MemoryLakeSettings {
        stores = stores == null ? Map.of() : Map.copyOf(stores);
        tables = tables == null ? List.of() : List.copyOf(tables);
        compaction = compaction == null ? Compaction.defaults() : compaction;
        historyKeep = historyKeep == null || historyKeep < 1 ? 50 : historyKeep;
        jobThreads = jobThreads == null || jobThreads < 1 ? 2 : jobThreads;
    }

    /** No stores, no tables: the in-memory lake is unused. */
    public static MemoryLakeSettings none() {
        return new MemoryLakeSettings(null, null, null, null, null);
    }

    /**
     * These settings with the memory stores the lake files describe (decision L3, docs/guides/DATA_LAKE_GUIDE.md): every lake whose
     * root is {@code mem://<store>} is a store, its {@code memory:} block giving the cap, chunk size, contract, owner and idle trim, its
     * {@code format} the store's. A store still under {@code drishti.lake.memory.stores} (the engine file, the old place) is read with one
     * warning naming {@code drishti.py lake migrate-config}; a store named in both places is refused, naming both.
     *
     * @param lakes the lakes of this server
     * @param warn  where the one warning goes
     */
    public MemoryLakeSettings withLakeStores(com.ash.drishti.lakeconfig.Lakes lakes, java.util.function.Consumer<String> warn) {
        Map<String, Store> merged = new java.util.LinkedHashMap<>(stores);
        if (!stores.isEmpty()) {
            warn.accept("the in-memory lake's stores " + stores.keySet() + " are in drishti.lake.memory.stores (config/engines/memory-lake.yaml): "
                    + "describe each as a lake (config/lakes/<store>.yaml, root: mem://<store>, memory: {max-mb: ...}); read there for one more release");
        }
        for (com.ash.drishti.lakeconfig.Lake l : lakes.all().values()) {
            if (!l.inMemory()) {
                continue;
            }
            String name = l.store();
            if (merged.containsKey(name) && stores.containsKey(name)) {
                throw new IllegalArgumentException("memory store " + name + " is described twice: in " + l.file() + " and in drishti.lake.memory.stores "
                        + "(config/engines/memory-lake.yaml); keep the lake file and remove it from the engine file");
            }
            Map<String, String> m = l.memory();
            merged.put(name, new Store(integer(m.get("max-mb")), integer(m.get("chunk-kb")), m.get("contract"),
                    m.get("owner") == null ? null : Boolean.parseBoolean(m.get("owner").trim()), l.format(), integer(m.get("idle-trim-minutes"))));
        }
        return new MemoryLakeSettings(merged, tables, compaction, historyKeep, jobThreads);
    }

    private static Integer integer(String v) {
        return v == null || v.isBlank() ? null : (int) Double.parseDouble(v.trim());
    }

    /**
     * One memory store.
     *
     * @param maxMb the hard cap in MB (the stores' caps add to the JVM's direct-memory limit: the launchers add them)
     * @param chunkKb the chunk size (1024)
     * @param contract the contract the data is held under, shown on every refusal and on Admin, Lakes
     * @param owner whether this node owns the store and binds its pack (true; false: the store is not loaded here and the pack's
     *     requests go to its owner over Drishti*net)
     * @param format {@code delta} or {@code iceberg} (a table may say otherwise)
     * @param idleTrimMinutes free chunks are given back to the operating system after the pool has been idle this long (10)
     */
    public record Store(Integer maxMb, Integer chunkKb, String contract, Boolean owner, String format, Integer idleTrimMinutes) {

        public Store {
            maxMb = maxMb == null || maxMb < 0 ? 0 : maxMb;
            chunkKb = chunkKb == null || chunkKb < 4 ? 1024 : chunkKb;
            contract = contract == null ? "" : contract.trim();
            owner = owner == null || owner;
            format = format == null || format.isBlank() ? "delta" : format.trim().toLowerCase(Locale.ROOT);
            idleTrimMinutes = idleTrimMinutes == null || idleTrimMinutes < 1 ? 10 : idleTrimMinutes;
        }
    }

    /**
     * Where a replenish job reads: a connector's documents of one kind, a JSON Lines file (plain or {@code .gz}), or a URL answering JSON
     * Lines or a JSON array (a vendor's REST feed).
     *
     * @param connector a connector's name (it must be able to list its documents of {@code kind})
     * @param kind the connector's kind
     * @param file a JSON Lines file, {@code .jsonl} or {@code .jsonl.gz}
     * @param url an {@code http(s)} address answering JSON Lines or a JSON array
     * @param headers request headers for {@code url} (an API key from the environment: {@code ${VENDOR_KEY}})
     * @param ids with {@code connector}: the entity ids to fetch one by one, for a connector that computes documents when asked (the news
     *     connector) rather than listing them
     * @param idsFrom with {@code connector}: the ids read from another memory table's field, {@code <table>.<FIELD>}
     *     ({@code listed-company.NEWS_ID}: the news of every listed company)
     */
    public record Source(String connector, String kind, String file, String url, Map<String, String> headers, List<String> ids, String idsFrom) {

        public Source {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            ids = ids == null ? List.of() : List.copyOf(ids);
        }

        /** In words, for Admin and the history (no secrets). */
        public String describe() {
            if (connector != null && !connector.isBlank()) {
                return "connector " + connector + (kind == null || kind.isBlank() ? "" : " (" + kind + ")");
            }
            if (file != null && !file.isBlank()) {
                return "file " + file;
            }
            return url == null || url.isBlank() ? "no source" : "url " + url.replaceAll("\\?.*$", "?…");
        }
    }

    /**
     * One table held in memory and its replenish job.
     *
     * @param name the table's name (unique)
     * @param store the memory store it lives in
     * @param folder its folder in the store (the name when blank): the Delta connector with {@code root: mem://<store>} reads kind
     *     {@code <folder>}
     * @param source where its job reads
     * @param mode {@code replace} (each run is the whole table), {@code append} (rows added), {@code merge} (rows replaced by key)
     * @param key the document fields that make a row's id (joined with '|'); the first field alone when one
     * @param sortBy the fields rows are sorted by before writing (runs of equal values compress best); the key when empty
     * @param businessDate {@code today} (the default), a date ({@code 2026-11-02}) or {@code field:<NAME>} (the first ten characters of a
     *     document field)
     * @param columns document fields promoted beside the document as string columns (searches and aggregates read them)
     * @param every how often the job runs ({@code 15m}, {@code 30s}, {@code 1h}); blank: only at start, on a signal and on demand
     * @param cron a cron line (six fields, seconds first) instead of {@code every} ({@code 0 0/15 * * * *})
     * @param signal run when the source changes (a file's time, a connector's last update), checked every 10 s (true)
     * @param warmUp the start-up order (1 first); blank: after the numbered ones
     * @param pin never evicted (a pinned table that is empty turns Health red)
     * @param keepVersions versions kept readable (2: the current and the one a reader may still be on)
     * @param codec {@code zstd} (default), {@code lz4}, {@code snappy}
     * @param format {@code delta} (the store's format when blank); {@code iceberg} is written by the Iceberg engine's own jobs
     */
    public record TableSpec(String name, String store, String folder, Source source, String mode, List<String> key, List<String> sortBy,
            String businessDate, List<String> columns, String every, String cron, Boolean signal, Integer warmUp, Boolean pin,
            Integer keepVersions, String codec, String format) {

        public TableSpec {
            mode = mode == null || mode.isBlank() ? "replace" : mode.trim().toLowerCase(Locale.ROOT);
            key = key == null ? List.of() : List.copyOf(key);
            sortBy = sortBy == null || sortBy.isEmpty() ? key : List.copyOf(sortBy);
            businessDate = businessDate == null || businessDate.isBlank() ? "today" : businessDate.trim();
            columns = columns == null ? List.of() : List.copyOf(columns);
            signal = signal == null || signal;
            pin = pin != null && pin;
            keepVersions = keepVersions == null || keepVersions < 1 ? 2 : keepVersions;
            codec = codec == null || codec.isBlank() ? "zstd" : codec.trim().toLowerCase(Locale.ROOT);
            folder = folder == null || folder.isBlank() ? name : folder.trim().replaceAll("^/+|/+$", "");
        }

        /** The table's path: {@code mem://<store>/<folder>}. */
        public String path() {
            return "mem://" + store + "/" + folder;
        }
    }

    /**
     * The automated compaction (section 13a).
     *
     * @param enabled on by default
     * @param idleOnly true: only when no request has arrived for {@code idle-seconds}
     * @param idleSeconds the quiet that counts as idle (30)
     * @param headroomMb memory kept free for a compaction's new files (512; never more than a quarter of the store)
     * @param smallFileMb files below this are merged (8)
     * @param deadRows a merge-mode table is rewritten when this share of its rows was replaced since its last rewrite (0.2)
     * @param resortAfter appends after which the table is rewritten sorted by {@code sort-by} (20)
     * @param everySeconds how often the compactor looks, besides after every commit (60)
     */
    public record Compaction(Boolean enabled, Boolean idleOnly, Integer idleSeconds, Integer headroomMb, Integer smallFileMb, Double deadRows,
            Integer resortAfter, Integer everySeconds) {

        public Compaction {
            enabled = enabled == null || enabled;
            idleOnly = idleOnly != null && idleOnly;
            idleSeconds = idleSeconds == null || idleSeconds < 1 ? 30 : idleSeconds;
            headroomMb = headroomMb == null || headroomMb < 0 ? 512 : headroomMb;
            smallFileMb = smallFileMb == null || smallFileMb < 1 ? 8 : smallFileMb;
            deadRows = deadRows == null || deadRows <= 0 || deadRows > 1 ? 0.2 : deadRows;
            resortAfter = resortAfter == null || resortAfter < 1 ? 20 : resortAfter;
            everySeconds = everySeconds == null || everySeconds < 5 ? 60 : everySeconds;
        }

        static Compaction defaults() {
            return new Compaction(null, null, null, null, null, null, null, null);
        }
    }
}
