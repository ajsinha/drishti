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
import com.ash.drishti.api.LoadGuard;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.lettuce.core.Range;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
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
 *   RedisLoader &lt;file | -&gt; [uri] [--cluster] [--merge | --replace] [--keep-replaced-seconds N] [--ttl-days N]
 *               [--codec zstd-dict|zstd|deflate|none] [--level N] [--chunk-rows N] [--in-flight N] [--threads N] [--publish]
 *               [--retrain] [--dict-samples N] [--dict-kb N] [--as-of yyyy-MM-dd] [--future-days N] [--zone Z]
 *               [--max-drop-share F] [--force-drop]
 * </pre>
 *
 * <p>Documents stream: worker threads parse and compress each line and send its commands without waiting (the
 * document's {@code SET}, the entity's days {@code ZADD}, their expiry), at most {@code --in-flight} lines (2,000)
 * unanswered at once. A kind's first {@code --dict-samples} documents (2,000) train its zstd dictionary unless Redis
 * already has one for the kind (then later loads reuse it; {@code --retrain} trains anew). Promoted values are gathered
 * per kind and day ({@link DayColumns}, about 230 MB per million trades) and written column-wise when the input ends.
 *
 * <p>A load <b>replaces</b> each day the stream reaches, by default, as the PostgreSQL, DuckDB and file loaders do:
 * the day then holds exactly the stream's entities, and readers see the old day or the new one, never a mix. The
 * documents and columns are written under a generation of this load that no reader looks at; once every day's columns
 * are in, each day is switched to it in one step (one script sets the day's generation and adds the day to the kind's
 * days), and the replaced generation's documents and columns expire {@code --keep-replaced-seconds} (120) later, so a
 * reader that has not yet seen the switch still reads a whole old day ({@link RedisLayout}). An entity the stream does
 * not carry leaves the day. Replacing a day with a stream that drops more than {@code --max-drop-share} (0.5) of its
 * entities is refused (nothing is switched) unless {@code --force-drop}: a partial stream meant to be merged is not
 * mistaken for the whole book.
 *
 * <p>{@code --merge} keeps the old behaviour: the load writes into the day's current generation in place, and the
 * day's columns keep the entities Redis already holds that the stream does not carry (a day loaded in parts, intraday
 * corrections with {@code --publish}); documents change one by one as they arrive, and a trade dropped from the book
 * stays until the day expires. Either way a load that dies half way leaves no document the day's columns do not list:
 * each document's id is journaled first, and the next load of the domain (or the failing load itself) deletes what a
 * dead load wrote and no column lists ({@link RedisLoadJournal}); a replacing load that dies never switched a day, so
 * all it wrote goes.
 *
 * <p>A row dated after tomorrow in the business zone is not loaded, and the load ends with an error naming it
 * ({@link LoadGuard}). {@code --ttl-days N} expires every key N days after it is written, and drops from each kind's
 * days those more than N days before {@code --as-of} (today), never counted from the newest date loaded; dropping
 * more than {@code --max-drop-share} (0.5) of a kind's days needs {@code --force-drop}. {@code --publish} announces
 * each written entity on {@code <domain>:changes}, for open views to refresh (meant for intraday updates, not bulk
 * loads).
 */
public final class RedisLoader {

    private static final JsonFactory JSON = new JsonFactory();

    /**
     * Switches a day to a generation: KEYS[1] the kind's generations, KEYS[2] the kind's days (one slot: the first's
     * hash tag is the second's name); ARGV[1] the day, ARGV[2] the generation. Returns the generation replaced ("" for
     * none). One script, so a reader finds the day with its new generation, or neither change.
     */
    private static final String SWITCH = """
            local was = redis.call('HGET', KEYS[1], ARGV[1])
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            redis.call('ZADD', KEYS[2], tonumber(ARGV[1]), ARGV[1])
            if was then return was end
            return ''
            """;

    /** The loader's settings, from the command line. */
    record Options(String uri, boolean cluster, int ttlDays, DocCodec.Kind codec, int level, int chunkRows, int inFlight, int threads, boolean publish,
            boolean retrain, int dictSamples, int dictKb, String user, String password, boolean replace, int keepReplacedSeconds, LoadGuard guard) {

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
                    Integer.parseInt(flags.getOrDefault("dict-kb", "112")), System.getenv("DRISHTI_REDIS_USER"), System.getenv("DRISHTI_REDIS_PASSWORD"),
                    replace(flags), Integer.parseInt(flags.getOrDefault("keep-replaced-seconds", "120")), LoadGuard.fromArgs(args));
        }

        /** Replacing each day is the default; {@code --merge} opts into merging (both at once is a mistake). */
        private static boolean replace(Map<String, String> flags) {
            if (flags.containsKey("merge") && flags.containsKey("replace")) {
                throw new IllegalArgumentException("--merge and --replace contradict each other: a load replaces each day (the default) or merges into it");
            }
            return !flags.containsKey("merge");
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
    private final RedisLoadJournal journal;
    private final Semaphore inFlight;
    private final Map<String, KindState> kinds = new ConcurrentHashMap<>();
    private final Map<DayKey, DayColumns> days = new ConcurrentHashMap<>();
    private final Map<DayKey, String> targets = new ConcurrentHashMap<>();   // the generation each day's documents go to
    private final AtomicReference<Throwable> failed = new AtomicReference<>();
    private final AtomicLong rows = new AtomicLong();
    private final AtomicLong rawBytes = new AtomicLong();
    private final AtomicLong storedBytes = new AtomicLong();
    private final long t0 = System.nanoTime();

    RedisLoader(Options options, RedisConnection redis) {
        this.options = options;
        this.redis = redis;
        this.async = redis.async();
        this.journal = new RedisLoadJournal(async, Duration.ofSeconds(60));
        this.inFlight = new Semaphore(options.inFlight());
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: RedisLoader <file|-> [uri] [--cluster] [--merge] [--ttl-days N] [--codec zstd-dict|zstd|deflate|none] [--publish] …"
                    + " (each day the stream reaches is replaced; --merge adds to it instead)");
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

    /**
     * Streams every line in, then writes each day's columns and applies the retention; a load that fails clears the
     * documents it wrote that no day's columns list.
     */
    void load(BufferedReader in) throws Exception {
        try (journal) {
            try {
                stream(in);
            } catch (Exception | Error e) {
                inFlight.acquireUninterruptibly(options.inFlight());   // every document sent is answered
                inFlight.release(options.inFlight());
                journal.abandon();
                throw e;
            }
        }
        retain();
        options.guard().finish();
    }

    private void stream(BufferedReader in) throws Exception {
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
        if (options.replace()) {
            Map<DayKey, Previous> previous = new LinkedHashMap<>();
            for (Map.Entry<DayKey, DayColumns> e : days.entrySet()) {          // every day checked before any is switched
                previous.put(e.getKey(), previous(e.getKey(), e.getValue()));
            }
            for (Map.Entry<DayKey, DayColumns> e : days.entrySet()) {
                replace(e.getKey(), e.getValue(), previous.get(e.getKey()));
            }
        } else {
            for (Map.Entry<DayKey, DayColumns> e : days.entrySet()) {
                merge(e.getKey(), e.getValue());
            }
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
        if (!options.guard().accept(row.date(), row.domain() + ":" + row.kind() + " " + row.id())) {
            return;
        }
        journal.begin(row.domain());
        DayKey day = new DayKey(row.domain(), row.kind(), row.date());
        target(day);
        days.computeIfAbsent(day, k -> new DayColumns()).put(row.id(), row.columns());
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

    /**
     * The generation the day's documents go to: this load's own when it replaces the day (no reader looks at it until
     * the switch), the day's current one when it merges (written in place, as before generations).
     */
    private String target(DayKey k) throws Exception {
        String gen = targets.get(k);
        if (gen != null) {
            return gen;
        }
        String chosen = options.replace() ? journal.load() : ColumnReader.generation(async, k.domain(), k.kind(), k.date(), Duration.ofSeconds(60));
        String was = targets.putIfAbsent(k, chosen);
        return was == null ? chosen : was;
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

    /**
     * The document and the entity's days, sent without waiting once Redis holds the id in the day's journal; at most
     * {@code --in-flight} lines unanswered.
     */
    private void write(Row row, DocCodec codec) throws Exception {
        byte[] json = row.doc().getBytes(StandardCharsets.UTF_8);
        byte[] value = codec.encode(json);
        rawBytes.addAndGet(json.length);
        storedBytes.addAndGet(value.length);
        String gen = target(new DayKey(row.domain(), row.kind(), row.date()));
        journal.day(row.domain(), row.kind(), row.date(), gen);
        inFlight.acquire();
        async.sadd(journal.key(row.domain(), row.kind(), row.date()), RedisLayout.bytes(row.id())).whenComplete((v, e) -> {
            if (e != null) {
                failed.compareAndSet(null, e);
                inFlight.release();
            } else {
                send(row, value, gen);
            }
        });
    }

    private void send(Row row, byte[] value, String gen) {
        byte[] entity = RedisLayout.bytes(RedisLayout.entity(row.domain(), row.kind(), row.id()));
        long day = RedisLayout.day(row.date());
        byte[] dayBytes = RedisLayout.bytes(String.valueOf(day));
        List<RedisFuture<?>> sent = new ArrayList<>(5);
        long ttl = options.ttlSeconds();
        byte[] doc = RedisLayout.bytes(RedisLayout.doc(row.domain(), row.kind(), row.id(), row.date(), gen));
        sent.add(ttl > 0 ? async.set(doc, value, SetArgs.Builder.ex(ttl)) : async.set(doc, value));
        sent.add(async.zadd(entity, day, dayBytes));
        if (ttl > 0) {
            // the entity forgets days older than the retention, and goes itself when nothing is written for that long
            sent.add(async.zremrangebyscore(entity, before(options.guard().asOf().minusDays(options.ttlDays()))));
            sent.add(async.expire(entity, ttl));
        }
        if (options.publish() && !options.replace()) {          // a replaced day's entities are announced once it is switched
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

    /** What a day held before this load replaces it: its generation and ids (none when Redis did not hold the day). */
    private record Previous(String gen, List<String> ids) {}

    /**
     * The day as Redis holds it now, and the guard: replacing it with a stream that drops more than
     * {@code --max-drop-share} of its entities is refused unless {@code --force-drop} (nothing is switched).
     */
    private Previous previous(DayKey k, DayColumns cols) throws Exception {
        Duration timeout = Duration.ofMinutes(5);
        String gen = ColumnReader.generation(async, k.domain(), k.kind(), k.date(), timeout);
        List<String> ids = ids(k, gen, timeout);
        long dropping = ids.stream().filter(id -> !cols.has(id)).count();
        try {
            options.guard().checkDrop(k.domain() + ":" + k.kind() + " " + k.date(), dropping, ids.size(), "entities");
        } catch (IllegalStateException e) {
            throw new IllegalStateException(String.format(java.util.Locale.ROOT,
                    "%s:%s %s: replacing the day would remove %,d of its %,d entities (the stream carries %,d; more than --max-drop-share); "
                            + "no day was switched. To add to the day, load with --merge; to replace it anyway, pass --force-drop.",
                    k.domain(), k.kind(), k.date(), dropping, ids.size(), cols.rows()), e);
        }
        return new Previous(gen, ids);
    }

    /** The ids the day's columns list in generation {@code gen} (none when Redis does not hold them). */
    private List<String> ids(DayKey k, String gen, Duration timeout) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<ColumnCodec.Meta> meta = ColumnReader.meta(async, k.domain(), k.kind(), k.date(), gen, timeout);
            if (meta.isEmpty()) {
                return List.of();
            }
            Optional<ColumnSet> read = ColumnReader.read(async, k.domain(), k.kind(), k.date(), gen, meta.get(), List.of(), timeout);
            if (read.isPresent()) {
                return List.of(read.get().ids());
            }
        }
        throw new IllegalStateException("the columns of " + k.domain() + ":" + k.kind() + " " + k.date() + " kept changing while they were read");
    }

    /**
     * Replaces a day: its columns written under this load's generation (beside its documents, which no reader looks at
     * yet), then the day switched to the generation in one step; then the replaced generation retired and the day
     * announced.
     */
    private void replace(DayKey k, DayColumns cols, Previous before) throws Exception {
        Duration timeout = Duration.ofMinutes(5);
        String gen = targets.get(k);
        DayColumns.Chunks chunks = writeColumns(k, gen, cols, timeout);
        byte[] was = ColumnReader.await(async.<byte[]>eval(SWITCH, ScriptOutputType.VALUE,
                new byte[][] {RedisLayout.bytes(RedisLayout.generations(k.domain(), k.kind())), RedisLayout.bytes(RedisLayout.days(k.domain(), k.kind()))},
                RedisLayout.bytes(String.valueOf(RedisLayout.day(k.date()))), RedisLayout.bytes(gen)), timeout);
        String replaced = was == null ? "" : new String(was, StandardCharsets.UTF_8);
        // another load may have switched the day since it was read: what this switch replaced is retired
        Previous retired = replaced.equals(before.gen()) ? before : new Previous(replaced, ids(k, replaced, timeout));
        if (!replaced.equals(gen)) {
            retire(k, retired, cols, timeout);
        }
        journal.done(k.domain(), k.kind(), k.date());
        announce(k, chunks, timeout);
        if (options.publish()) {                                       // every entity of the day, now that readers see them
            List<RedisFuture<?>> sent = new ArrayList<>();
            byte[] channel = RedisLayout.bytes(RedisLayout.changes(k.domain()));
            for (String id : cols.ids()) {
                sent.add(async.publish(channel, RedisLayout.bytes(RedisLayout.change(k.kind(), id, k.date()))));
            }
            for (RedisFuture<?> f : sent) {
                ColumnReader.await(f, timeout);
            }
        }
    }

    /**
     * The replaced generation of a day: its documents and columns expire {@code --keep-replaced-seconds} later (a
     * reader that has not yet seen the switch still reads a whole old day), and the entities the new day does not
     * hold lose the day from their days.
     */
    private void retire(DayKey k, Previous old, DayColumns cols, Duration timeout) throws Exception {
        long keep = options.keepReplacedSeconds();
        byte[] dayBytes = RedisLayout.bytes(String.valueOf(RedisLayout.day(k.date())));
        List<RedisFuture<?>> sent = new ArrayList<>();
        long removed = 0;
        for (String id : old.ids()) {
            byte[] doc = RedisLayout.bytes(RedisLayout.doc(k.domain(), k.kind(), id, k.date(), old.gen()));
            sent.add(keep > 0 ? async.expire(doc, keep) : async.del(doc));
            if (!cols.has(id)) {
                sent.add(async.zrem(RedisLayout.bytes(RedisLayout.entity(k.domain(), k.kind(), id)), dayBytes));
                removed++;
            }
            if (sent.size() >= options.inFlight()) {
                for (RedisFuture<?> f : sent) {
                    ColumnReader.await(f, timeout);
                }
                sent.clear();
            }
        }
        byte[] columns = RedisLayout.bytes(RedisLayout.columns(k.domain(), k.kind(), k.date(), old.gen()));
        sent.add(keep > 0 ? async.expire(columns, keep) : async.del(columns));
        for (RedisFuture<?> f : sent) {
            ColumnReader.await(f, timeout);
        }
        if (removed > 0) {
            System.err.printf("redis: %s:%s %s: replaced; %,d entities the stream does not carry left the day%n", k.domain(), k.kind(), k.date(), removed);
        }
    }

    /**
     * {@code --merge}: the day's columns in its current generation, merged with what Redis holds for it (rows this
     * load did not write are kept), written to a staging hash and renamed over the day's; then the day joins the
     * kind's days and is announced, and its journal goes.
     */
    private void merge(DayKey k, DayColumns cols) throws Exception {
        Duration timeout = Duration.ofMinutes(5);
        String gen = targets.get(k);
        Optional<ColumnCodec.Meta> old = ColumnReader.meta(async, k.domain(), k.kind(), k.date(), gen, timeout);
        if (old.isPresent()) {
            Optional<ColumnSet> was = ColumnReader.read(async, k.domain(), k.kind(), k.date(), gen, old.get(), null, timeout);
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
        DayColumns.Chunks chunks = writeColumns(k, gen, cols, timeout);
        long day = RedisLayout.day(k.date());
        ColumnReader.await(async.zadd(RedisLayout.bytes(RedisLayout.days(k.domain(), k.kind())), day, RedisLayout.bytes(String.valueOf(day))), timeout);
        journal.done(k.domain(), k.kind(), k.date());
        announce(k, chunks, timeout);
    }

    /** The day's columns in generation {@code gen}: a staging hash, given the TTL, renamed over the generation's in one step. */
    private DayColumns.Chunks writeColumns(DayKey k, String gen, DayColumns cols, Duration timeout) throws Exception {
        DayColumns.Chunks chunks = cols.chunks(options.chunkRows(), System.currentTimeMillis());
        byte[] staging = RedisLayout.bytes(RedisLayout.columnsStaging(k.domain(), k.kind(), k.date(), gen));
        byte[] target = RedisLayout.bytes(RedisLayout.columns(k.domain(), k.kind(), k.date(), gen));
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
        return chunks;
    }

    /** The kind joins the domain's kinds and the day is announced on the domain's changes. */
    private void announce(DayKey k, DayColumns.Chunks chunks, Duration timeout) throws Exception {
        ColumnReader.await(async.sadd(RedisLayout.bytes(RedisLayout.kinds(k.domain())), RedisLayout.bytes(k.kind())), timeout);
        ColumnReader.await(async.publish(RedisLayout.bytes(RedisLayout.changes(k.domain())), RedisLayout.bytes(RedisLayout.change(k.kind(), "*", k.date()))), timeout);
        if (chunks.meta().rows() >= 100_000) {
            System.err.printf("redis: %s:%s %s: %,d rows, %d columns in %,d chunks of %,d bytes%n", k.domain(), k.kind(), k.date(), chunks.meta().rows(),
                    chunks.meta().columns().size(), chunks.fields().size(), chunks.bytes());
        }
    }

    /**
     * {@code --ttl-days N}: each kind loaded forgets the days more than N days before {@code --as-of} (today); refused
     * when that is more than {@code --max-drop-share} of the kind's days.
     */
    private void retain() throws Exception {
        if (options.ttlDays() <= 0) {
            return;
        }
        Duration timeout = Duration.ofMinutes(1);
        Range<Long> old = before(options.guard().asOf().minusDays(options.ttlDays()));
        Map<String, byte[]> kindsLoaded = new LinkedHashMap<>();
        days.keySet().forEach(k -> kindsLoaded.put(k.domain() + ":" + k.kind(), RedisLayout.bytes(RedisLayout.days(k.domain(), k.kind()))));
        for (Map.Entry<String, byte[]> e : kindsLoaded.entrySet()) {
            long dropping = ColumnReader.await(async.zcount(e.getValue(), old), timeout);
            if (dropping > 0) {
                options.guard().checkDrop(e.getKey(), dropping, ColumnReader.await(async.zcard(e.getValue()), timeout), "business days");
                List<byte[]> gone = ColumnReader.await(async.zrangebyscore(e.getValue(), old), timeout);
                ColumnReader.await(async.zremrangebyscore(e.getValue(), old), timeout);
                String[] dk = e.getKey().split(":", 2);
                ColumnReader.await(async.hdel(RedisLayout.bytes(RedisLayout.generations(dk[0], dk[1])), gone.toArray(byte[][]::new)), timeout);
            }
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
