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
package com.ash.drishti.plugin.redis;

import com.ash.drishti.api.ColumnSet;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.lettuce.core.Range;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.SetArgs;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads JSON lines into Redis in the layout {@link RedisSourcePlugin} reads ({@link RedisLayout}). Each line is one
 * entity on one business date: {@code {"domain","kind","id","date","doc","columns":{path:value}}} (written by
 * {@code make_data.py --jsonl} and {@code bulk_trades.py --jsonl -}).
 *
 * <pre>
 *   RedisLoader &lt;file | -&gt; [uri] [--cluster] [--ttl-days N] [--codec zstd-dict|zstd|deflate|none] [--level N]
 *               [--chunk-rows N] [--in-flight N] [--threads N] [--publish] [--retrain] [--dict-samples N] [--dict-kb N]
 * </pre>
 *
 * <p>Documents stream: worker threads parse and compress each line and send its commands without waiting (the
 * document's {@code SET}, the entity's days {@code ZADD}, their expiry), at most {@code --in-flight} lines (2,000)
 * unanswered at once. A kind's first {@code --dict-samples} documents (2,000) train its zstd dictionary unless Redis
 * already has one for the kind (then later loads reuse it; {@code --retrain} trains anew). Promoted values are gathered
 * per kind and day ({@link DayColumns}, about 230 MB per million trades) and written column-wise when the input ends,
 * merged with what Redis already holds for that day (so a day can be loaded in parts, and loading the same rows twice
 * changes nothing): into a staging hash renamed over the day's in one step, after which the day joins the kind's days.
 * A reader therefore switches to a new day only once its every document and column is in. {@code --ttl-days N} expires
 * every key N days after it is written. {@code --publish} announces each written entity on {@code <domain>:changes}, for
 * open views to refresh (meant for intraday updates, not bulk loads).
 */
public final class RedisLoader {

    private static final JsonFactory JSON = new JsonFactory();

    /** The loader's settings, from the command line. */
    record Options(String uri, boolean cluster, int ttlDays, DocCodec.Kind codec, int level, int chunkRows, int inFlight, int threads, boolean publish,
            boolean retrain, int dictSamples, int dictKb, String user, String password) {

        static Options parse(String[] args) {
            String uri = args.length > 1 && !args[1].startsWith("--") ? args[1] : "redis://localhost:6379";
            Map<String, String> flags = new LinkedHashMap<>();
            for (int i = 1; i < args.length; i++) {
                if (args[i].startsWith("--")) {
                    boolean valued = i + 1 < args.length && !args[i + 1].startsWith("--");
                    flags.put(args[i].substring(2), valued ? args[++i] : "true");
                }
            }
            return new Options(uri, flags.containsKey("cluster"), Integer.parseInt(flags.getOrDefault("ttl-days", "0")),
                    DocCodec.Kind.of(flags.getOrDefault("codec", "zstd-dict")), Integer.parseInt(flags.getOrDefault("level", "3")),
                    Integer.parseInt(flags.getOrDefault("chunk-rows", "10000")), Integer.parseInt(flags.getOrDefault("in-flight", "2000")),
                    Integer.parseInt(flags.getOrDefault("threads", String.valueOf(Math.max(2, Runtime.getRuntime().availableProcessors() - 2)))),
                    flags.containsKey("publish"), flags.containsKey("retrain"), Integer.parseInt(flags.getOrDefault("dict-samples", "2000")),
                    Integer.parseInt(flags.getOrDefault("dict-kb", "112")), System.getenv("DRISHTI_REDIS_USER"), System.getenv("DRISHTI_REDIS_PASSWORD"));
        }

        long ttlSeconds() {
            return ttlDays * 86_400L;
        }
    }

    /** One line. */
    record Row(String domain, String kind, String id, LocalDate date, String doc, Map<String, Object> columns) {}

    private record DayKey(String domain, String kind, LocalDate date) {}

    /** A kind's codec, and the documents held back while its dictionary is not yet trained. */
    private static final class KindState {
        volatile DocCodec codec;
        List<Row> held = new ArrayList<>();
    }

    private final Options options;
    private final RedisConnection redis;
    private final RedisClusterAsyncCommands<byte[], byte[]> async;
    private final Semaphore inFlight;
    private final Map<String, KindState> kinds = new ConcurrentHashMap<>();
    private final Map<DayKey, DayColumns> days = new ConcurrentHashMap<>();
    private final AtomicReference<Throwable> failed = new AtomicReference<>();
    private final AtomicLong rows = new AtomicLong();
    private final AtomicLong rawBytes = new AtomicLong();
    private final AtomicLong storedBytes = new AtomicLong();
    private final long t0 = System.nanoTime();

    RedisLoader(Options options, RedisConnection redis) {
        this.options = options;
        this.redis = redis;
        this.async = redis.async();
        this.inFlight = new Semaphore(options.inFlight());
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: RedisLoader <file|-> [uri] [--cluster] [--ttl-days N] [--codec zstd-dict|zstd|deflate|none] [--publish] …");
            System.exit(2);
        }
        Options o = Options.parse(args);
        try (RedisConnection c = RedisConnection.open(o.uri(), o.cluster(), o.user(), o.password(), Duration.ofSeconds(60));
             BufferedReader in = args[0].equals("-") ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8), 1 << 20)
                     : Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            RedisLoader loader = new RedisLoader(o, c);
            loader.load(in);
            loader.report(System.out);
        }
    }

    /** Streams every line in, then writes each day's columns. */
    void load(BufferedReader in) throws Exception {
        ThreadPoolExecutor workers = new ThreadPoolExecutor(options.threads(), options.threads(), 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(options.threads() * 64), new ThreadPoolExecutor.CallerRunsPolicy());
        try {
            String line;
            while ((line = in.readLine()) != null && failed.get() == null) {
                if (!line.isBlank()) {
                    String l = line;
                    workers.execute(() -> {
                        try {
                            accept(parse(l));
                        } catch (Throwable e) {
                            failed.compareAndSet(null, e);
                        }
                    });
                }
            }
        } finally {
            workers.shutdown();
            workers.awaitTermination(1, TimeUnit.DAYS);
        }
        rethrow();
        for (Map.Entry<String, KindState> e : kinds.entrySet()) {   // kinds with fewer documents than the samples
            List<Row> held = release(e.getKey(), e.getValue(), true);
            for (Row r : held) {
                write(r, e.getValue().codec);
            }
        }
        inFlight.acquire(options.inFlight());                          // every document answered
        inFlight.release(options.inFlight());
        rethrow();
        for (Map.Entry<DayKey, DayColumns> e : days.entrySet()) {
            flush(e.getKey(), e.getValue());
        }
        Map<String, Boolean> domains = new LinkedHashMap<>();
        days.keySet().forEach(k -> domains.put(k.domain(), true));
        for (String domain : domains.keySet()) {
            ColumnReader.await(async.set(RedisLayout.bytes(RedisLayout.updated(domain)), RedisLayout.bytes(String.valueOf(System.currentTimeMillis()))),
                    Duration.ofSeconds(60));
        }
    }

    private void rethrow() throws Exception {
        Throwable t = failed.get();
        if (t instanceof Exception e) {
            throw e;
        }
        if (t != null) {
            throw new IllegalStateException(t);
        }
    }

    /** A parsed line: compressed and sent, or held while its kind's dictionary is trained. */
    private void accept(Row row) throws Exception {
        days.computeIfAbsent(new DayKey(row.domain(), row.kind(), row.date()), k -> new DayColumns()).put(row.id(), row.columns());
        String key = row.domain() + ":" + row.kind();
        KindState state = kinds.computeIfAbsent(key, k -> initial(row.domain(), row.kind()));
        DocCodec codec = state.codec;
        if (codec == null) {
            List<Row> ready;
            synchronized (state) {
                if (state.codec == null) {
                    state.held.add(row);
                    if (state.held.size() < options.dictSamples()) {
                        return;
                    }
                }
                ready = release(key, state, false);
                codec = state.codec;
            }
            for (Row r : ready) {
                write(r, codec);
            }
            if (!ready.contains(row)) {
                write(row, codec);
            }
            return;
        }
        write(row, codec);
    }

    /** A kind's codec when no training is needed: the codec asked for, or the kind's dictionary Redis already holds. */
    private KindState initial(String domain, String kind) {
        KindState s = new KindState();
        if (options.codec() != DocCodec.Kind.ZSTD_DICT) {
            s.codec = new DocCodec(options.codec(), options.level(), null);
        } else if (!options.retrain()) {
            try {
                byte[] id = ColumnReader.await(async.get(RedisLayout.bytes(RedisLayout.dictionaryOf(domain, kind))), Duration.ofSeconds(60));
                byte[] dict = id == null ? null
                        : ColumnReader.await(async.get(RedisLayout.bytes(RedisLayout.dictionary(domain, Integer.parseUnsignedInt(new String(id, StandardCharsets.UTF_8), 16)))),
                                Duration.ofSeconds(60));
                if (dict != null) {
                    s.codec = new DocCodec(DocCodec.Kind.ZSTD_DICT, options.level(), DocCodec.Dictionary.of(dict, options.level()));
                }
            } catch (Exception e) {
                throw new IllegalStateException("cannot read the dictionary of " + domain + ":" + kind, e);
            }
        }
        return s;
    }

    /** Trains the kind's dictionary on its held documents (plain zstd when they are too few), stores it, and hands them back. */
    private List<Row> release(String key, KindState state, boolean end) throws Exception {
        synchronized (state) {
            if (state.codec != null) {
                List<Row> held = state.held;
                state.held = new ArrayList<>();
                return held;
            }
            List<byte[]> samples = state.held.stream().map(r -> r.doc().getBytes(StandardCharsets.UTF_8)).toList();
            byte[] dict = samples.size() >= Math.min(64, options.dictSamples()) ? DocCodec.train(samples, options.dictKb() * 1024) : null;
            if (dict == null) {
                state.codec = new DocCodec(DocCodec.Kind.ZSTD, options.level(), null);
            } else {
                DocCodec.Dictionary d = DocCodec.Dictionary.of(dict, options.level());
                String[] dk = key.split(":", 2);
                ColumnReader.await(async.set(RedisLayout.bytes(RedisLayout.dictionary(dk[0], d.id())), dict), Duration.ofSeconds(60));
                ColumnReader.await(async.set(RedisLayout.bytes(RedisLayout.dictionaryOf(dk[0], dk[1])), RedisLayout.bytes(Integer.toUnsignedString(d.id(), 16))),
                        Duration.ofSeconds(60));
                state.codec = new DocCodec(DocCodec.Kind.ZSTD_DICT, options.level(), d);
                System.err.printf("redis: %s: trained a %,d-byte zstd dictionary on %,d documents%s%n", key, dict.length, samples.size(), end ? " (all it has)" : "");
            }
            List<Row> held = state.held;
            state.held = new ArrayList<>();
            return held;
        }
    }

    /** The document and the entity's days, sent without waiting; at most {@code --in-flight} lines unanswered. */
    private void write(Row row, DocCodec codec) throws InterruptedException {
        byte[] json = row.doc().getBytes(StandardCharsets.UTF_8);
        byte[] value = codec.encode(json);
        rawBytes.addAndGet(json.length);
        storedBytes.addAndGet(value.length);
        byte[] entity = RedisLayout.bytes(RedisLayout.entity(row.domain(), row.kind(), row.id()));
        long day = RedisLayout.day(row.date());
        byte[] dayBytes = RedisLayout.bytes(String.valueOf(day));
        inFlight.acquire();
        List<RedisFuture<?>> sent = new ArrayList<>(5);
        long ttl = options.ttlSeconds();
        sent.add(ttl > 0 ? async.set(RedisLayout.bytes(RedisLayout.doc(row.domain(), row.kind(), row.id(), row.date())), value, SetArgs.Builder.ex(ttl))
                : async.set(RedisLayout.bytes(RedisLayout.doc(row.domain(), row.kind(), row.id(), row.date())), value));
        sent.add(async.zadd(entity, day, dayBytes));
        if (ttl > 0) {
            // the entity forgets days older than the retention, and goes itself when nothing is written for that long
            sent.add(async.zremrangebyscore(entity, before(row.date().minusDays(options.ttlDays()))));
            sent.add(async.expire(entity, ttl));
        }
        if (options.publish()) {
            sent.add(async.publish(RedisLayout.bytes(RedisLayout.changes(row.domain())), RedisLayout.bytes(RedisLayout.change(row.kind(), row.id(), row.date()))));
        }
        RedisFuture<?> last = sent.get(sent.size() - 1);
        for (RedisFuture<?> f : sent) {
            f.whenComplete((v, e) -> {
                if (e != null) {
                    failed.compareAndSet(null, e);
                }
                if (f == last) {
                    inFlight.release();
                    long n = rows.incrementAndGet();
                    if (n % 100_000 == 0) {
                        System.err.printf("redis: %,d rows (%,.0f s)%n", n, (System.nanoTime() - t0) / 1e9);
                    }
                }
            });
        }
    }

    /**
     * A day's columns: merged with what Redis holds for the day (rows this load did not write are kept), written to a
     * staging hash and renamed over the day's; then the day joins the kind's days and is announced.
     */
    private void flush(DayKey k, DayColumns cols) throws Exception {
        Duration timeout = Duration.ofMinutes(5);
        Optional<ColumnCodec.Meta> old = ColumnReader.meta(async, k.domain(), k.kind(), k.date(), timeout);
        if (old.isPresent()) {
            Optional<ColumnSet> was = ColumnReader.read(async, k.domain(), k.kind(), k.date(), old.get(), null, timeout);
            if (was.isPresent()) {
                ColumnSet c = was.get();
                for (int i = 0; i < c.size(); i++) {
                    if (!cols.has(c.ids()[i])) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        for (String p : old.get().columns().keySet()) {
                            values.put(p, c.value(p, i));
                        }
                        cols.put(c.ids()[i], values);
                    }
                }
            }
        }
        DayColumns.Chunks chunks = cols.chunks(options.chunkRows(), System.currentTimeMillis());
        byte[] staging = RedisLayout.bytes(RedisLayout.columnsStaging(k.domain(), k.kind(), k.date()));
        byte[] target = RedisLayout.bytes(RedisLayout.columns(k.domain(), k.kind(), k.date()));
        ColumnReader.await(async.del(staging), timeout);
        List<RedisFuture<?>> sent = new ArrayList<>();
        for (Map.Entry<String, byte[]> f : chunks.fields().entrySet()) {
            sent.add(async.hset(staging, RedisLayout.bytes(f.getKey()), f.getValue()));
        }
        sent.add(async.hset(staging, RedisLayout.bytes(RedisLayout.META), chunks.meta().encode()));
        for (RedisFuture<?> f : sent) {
            ColumnReader.await(f, timeout);
        }
        if (options.ttlSeconds() > 0) {
            ColumnReader.await(async.expire(staging, options.ttlSeconds()), timeout);
        }
        ColumnReader.await(async.rename(staging, target), timeout);
        long day = RedisLayout.day(k.date());
        ColumnReader.await(async.zadd(RedisLayout.bytes(RedisLayout.days(k.domain(), k.kind())), day, RedisLayout.bytes(String.valueOf(day))), timeout);
        if (options.ttlDays() > 0) {
            ColumnReader.await(async.zremrangebyscore(RedisLayout.bytes(RedisLayout.days(k.domain(), k.kind())),
                    before(k.date().minusDays(options.ttlDays()))), timeout);
        }
        ColumnReader.await(async.sadd(RedisLayout.bytes(RedisLayout.kinds(k.domain())), RedisLayout.bytes(k.kind())), timeout);
        ColumnReader.await(async.publish(RedisLayout.bytes(RedisLayout.changes(k.domain())), RedisLayout.bytes(RedisLayout.change(k.kind(), "*", k.date()))), timeout);
        if (chunks.meta().rows() >= 100_000) {
            System.err.printf("redis: %s:%s %s: %,d rows, %d columns in %,d chunks of %,d bytes%n", k.domain(), k.kind(), k.date(), chunks.meta().rows(),
                    chunks.meta().columns().size(), chunks.fields().size(), chunks.bytes());
        }
    }

    /** Days before {@code date} (exclusive), as a score range. */
    private static Range<Long> before(LocalDate date) {
        return Range.from(Range.Boundary.unbounded(), Range.Boundary.excluding(RedisLayout.day(date)));
    }

    void report(java.io.PrintStream out) {
        long raw = rawBytes.get();
        long stored = Math.max(1, storedBytes.get());
        out.printf("redis: loaded %,d rows into %s in %,.0f s; documents %,d MB as JSON, %,d MB stored (%.1f times smaller)%n", rows.get(), redis.describe(),
                (System.nanoTime() - t0) / 1e9, raw >> 20, stored >> 20, (double) raw / stored);
    }

    static Row parse(String line) throws java.io.IOException {
        String domain = null;
        String kind = null;
        String id = null;
        String date = null;
        String doc = null;
        Map<String, Object> columns = new LinkedHashMap<>();
        try (JsonParser p = JSON.createParser(line)) {
            p.nextToken();
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken t = p.nextToken();
                switch (name) {
                    case "domain" -> domain = p.getText();
                    case "kind" -> kind = p.getText();
                    case "id" -> id = p.getText();
                    case "date" -> date = p.getText();
                    case "doc" -> doc = p.getText();
                    case "columns" -> {
                        if (t == JsonToken.START_OBJECT) {
                            while (p.nextToken() == JsonToken.FIELD_NAME) {
                                String path = p.currentName();
                                JsonToken v = p.nextToken();
                                columns.put(path, v == JsonToken.VALUE_NUMBER_INT || v == JsonToken.VALUE_NUMBER_FLOAT ? (Object) p.getDoubleValue()
                                        : v == JsonToken.VALUE_NULL ? null : p.getText());
                            }
                        }
                    }
                    default -> p.skipChildren();
                }
            }
        }
        if (domain == null || kind == null || id == null || date == null || doc == null) {
            throw new IllegalArgumentException("a line without domain, kind, id, date or doc: " + line.substring(0, Math.min(200, line.length())));
        }
        return new Row(domain, kind, id, LocalDate.parse(date), doc, columns);
    }
}
