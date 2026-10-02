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

import io.lettuce.core.RedisFuture;
import io.lettuce.core.SetArgs;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * What a {@link RedisLoader} writes before a day's columns list it, so that a load that dies half way leaves no
 * document the day's columns do not list ({@link RedisLayout}):
 *
 * <ul>
 *   <li>Before the first document of a (kind, day), the load records the day in {@code <domain>:loading} as
 *       {@code load TAB kind TAB yyyyMMdd}; before each document, the entity's id goes into the day's journal
 *       ({@code {<domain>:<kind>:<yyyyMMdd>}:cols:loading:<load>}), and the document is sent only once Redis has it.</li>
 *   <li>Once the day's columns are renamed into place, its journal and its entry go.</li>
 *   <li>While it runs, the load renews {@code <domain>:loader:<load>} (expiring after 90 seconds). The first time a
 *       load reaches a domain, it clears what dead loads left: for each entry whose loader key is gone, the documents
 *       of journaled ids that the day's columns do not list are deleted, with the day in the entity's days. A load
 *       that fails in the process clears its own entries the same way.</li>
 * </ul>
 *
 * <p>A document of an id the day's columns do list was replaced by the dead load and stays (loads merge: see
 * {@link RedisLoader}); loading the day again makes the two agree.
 */
final class RedisLoadJournal implements AutoCloseable {

    private static final long ALIVE_MS = 90_000;

    private final RedisClusterAsyncCommands<byte[], byte[]> redis;
    private final Duration timeout;
    private final String load = Long.toHexString(ThreadLocalRandom.current().nextLong() | Long.MIN_VALUE);
    private final Set<String> domains = ConcurrentHashMap.newKeySet();
    private final Set<String> entries = ConcurrentHashMap.newKeySet();      // domain TAB kind TAB yyyyMMdd
    private final ScheduledExecutorService renew = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("redis-loader-alive")
            .unstarted(r));

    RedisLoadJournal(RedisClusterAsyncCommands<byte[], byte[]> redis, Duration timeout) {
        this.redis = redis;
        this.timeout = timeout;
        renew.scheduleWithFixedDelay(this::alive, 30, 30, TimeUnit.SECONDS);
    }

    /** This load's name in the journal. */
    String load() {
        return load;
    }

    /** Before the first document of a domain: what dead loads left is cleared, and this load is marked alive. */
    void begin(String domain) throws Exception {
        if (domains.contains(domain)) {
            return;
        }
        synchronized (this) {
            if (domains.contains(domain)) {
                return;
            }
            ColumnReader.await(redis.set(RedisLayout.bytes(RedisLayout.loader(domain, load)), RedisLayout.bytes("1"), SetArgs.Builder.px(ALIVE_MS)), timeout);
            long cleared = recover(domain, false);
            if (cleared > 0) {
                System.err.printf("redis: %s: deleted %,d documents a load that did not finish had written%n", domain, cleared);
            }
            domains.add(domain);
        }
    }

    /** Before the first document of a (kind, day): the day recorded as being loaded. */
    void day(String domain, String kind, LocalDate date) throws Exception {
        String entry = domain + "\t" + kind + "\t" + RedisLayout.day(date);
        if (entries.add(entry)) {
            ColumnReader.await(redis.sadd(RedisLayout.bytes(RedisLayout.loads(domain)), RedisLayout.bytes(load + "\t" + kind + "\t" + RedisLayout.day(date))),
                    timeout);
        }
    }

    /** The day's journal key for this load. */
    byte[] key(String domain, String kind, LocalDate date) {
        return RedisLayout.bytes(RedisLayout.journal(domain, kind, date, load));
    }

    /** The day's columns are in place: its journal and entry go. */
    void done(String domain, String kind, LocalDate date) throws Exception {
        ColumnReader.await(redis.del(key(domain, kind, date)), timeout);
        ColumnReader.await(redis.srem(RedisLayout.bytes(RedisLayout.loads(domain)), RedisLayout.bytes(load + "\t" + kind + "\t" + RedisLayout.day(date))),
                timeout);
        entries.remove(domain + "\t" + kind + "\t" + RedisLayout.day(date));
    }

    /** After this load failed in the process: its unfinished days cleared as a dead load's would be. */
    void abandon() {
        for (String domain : domains) {
            try {
                long cleared = recover(domain, true);
                System.err.printf("redis: %s: the load failed; deleted the %,d documents it had written that no day's columns list%n", domain, cleared);
            } catch (Exception e) {
                System.err.println("redis: " + domain + ": could not clear the failed load's documents (the next load will): " + e.getMessage());
            }
        }
    }

    /**
     * Clears the unfinished days of dead loads ({@code own}: of this load): the documents of journaled ids the day's
     * columns do not list, and the day in those entities' days. Returns the documents deleted.
     */
    private long recover(String domain, boolean own) throws Exception {
        long deleted = 0;
        byte[] loadsKey = RedisLayout.bytes(RedisLayout.loads(domain));
        for (byte[] m : ColumnReader.await(redis.smembers(loadsKey), timeout)) {
            String[] e = new String(m, StandardCharsets.UTF_8).split("\t", 3);
            if (e.length != 3 || own != e[0].equals(load)
                    || !own && ColumnReader.await(redis.exists(RedisLayout.bytes(RedisLayout.loader(domain, e[0]))), timeout) > 0) {
                continue;                                               // another load, still running
            }
            String kind = e[1];
            LocalDate date = RedisLayout.date(Long.parseLong(e[2]));
            byte[] journal = RedisLayout.bytes(RedisLayout.journal(domain, kind, date, e[0]));
            Set<String> listed = listed(domain, kind, date);
            List<RedisFuture<?>> sent = new ArrayList<>();
            byte[] day = RedisLayout.bytes(String.valueOf(RedisLayout.day(date)));
            for (byte[] idBytes : ColumnReader.await(redis.smembers(journal), timeout)) {
                String id = new String(idBytes, StandardCharsets.UTF_8);
                if (!listed.contains(id)) {
                    sent.add(redis.del(RedisLayout.bytes(RedisLayout.doc(domain, kind, id, date))));
                    sent.add(redis.zrem(RedisLayout.bytes(RedisLayout.entity(domain, kind, id)), day));
                    deleted++;
                }
            }
            for (RedisFuture<?> f : sent) {
                ColumnReader.await(f, timeout);
            }
            ColumnReader.await(redis.del(journal), timeout);
            ColumnReader.await(redis.srem(loadsKey, m), timeout);
        }
        return deleted;
    }

    /** The ids the day's columns list (none when the day was never written or expired). */
    private Set<String> listed(String domain, String kind, LocalDate date) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<ColumnCodec.Meta> meta = ColumnReader.meta(redis, domain, kind, date, timeout);
            if (meta.isEmpty()) {
                return Set.of();
            }
            var read = ColumnReader.read(redis, domain, kind, date, meta.get(), List.of(), timeout);
            if (read.isPresent()) {
                return new HashSet<>(List.of(read.get().ids()));
            }
        }
        throw new IllegalStateException("the columns of " + domain + ":" + kind + " " + date + " kept changing while they were read");
    }

    private void alive() {
        for (String domain : domains) {
            var unused = redis.set(RedisLayout.bytes(RedisLayout.loader(domain, load)), RedisLayout.bytes("1"), SetArgs.Builder.px(ALIVE_MS));
        }
    }

    @Override
    public void close() {
        renew.shutdownNow();
        for (String domain : domains) {
            try {
                ColumnReader.await(redis.del(RedisLayout.bytes(RedisLayout.loader(domain, load))), timeout);
            } catch (Exception e) {
                // it expires by itself
            }
        }
    }
}
