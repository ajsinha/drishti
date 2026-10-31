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
package com.ash.drishti.identity.bi;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.content.ContentStore;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * JSON documents in the content store for BI content and BI runs (docs/architecture/DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 9): typed reads that carry the
 * store version, compare-and-set writes, safe key segments, and a list snapshot per prefix for the listing queries.
 *
 * <p><b>The version a write compares against.</b> A read through {@link #get} remembers, for the calling thread, the version it returned (for
 * {@link #SEEN_MS}); a {@link #save} of that key then compares against it, so two nodes (or two requests) that read the same item and both save
 * it give one success and one {@code 409 DRS-5057}. A save of a key the thread has not read lately writes over whatever is there (the last
 * writer wins, as the database store did).
 */
public final class BiDocs {

    /** How long a read's version is the one a save on the same thread compares against. */
    static final long SEEN_MS = 30_000;

    public static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** A document read with its store version. */
    public record Read<T>(T value, String version) {}

    private record Seen(String version, long at) {}

    private final ContentStore store;
    private final ThreadLocal<Map<String, Seen>> seen = ThreadLocal.withInitial(HashMap::new);
    private final Map<String, Snapshot<?>> snapshots = new ConcurrentHashMap<>();
    private final long listTtlMs;

    /** {@code listTtlMs}: how old a list snapshot may be before a listing reads the store again (0: every listing reads it). */
    public BiDocs(ContentStore store, long listTtlMs) {
        this.store = store;
        this.listTtlMs = Math.max(0, listTtlMs);
    }

    public ContentStore store() {
        return store;
    }

    /** A key segment: letters, digits, {@code -} and {@code .} kept, every other byte {@code _xx}; past 100 bytes, a prefix and a hash. */
    public static String seg(String raw) {
        StringBuilder b = new StringBuilder();
        for (byte c : (raw == null ? "" : raw).getBytes(StandardCharsets.UTF_8)) {
            char ch = (char) (c & 0xff);
            if (ch < 128 && (Character.isLetterOrDigit(ch) || ch == '-' || (ch == '.' && b.length() > 0))) {
                b.append(ch);
            } else {
                b.append('_').append(String.format("%02x", c & 0xff));
            }
        }
        if (b.length() == 0) {
            return "_";
        }
        if (b.length() > 100) {
            try {
                byte[] h = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
                return b.substring(0, 60) + "~" + HexFormat.of().formatHex(h, 0, 16);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        return b.toString();
    }

    /** A number as a fixed-width segment, so keys sort by number. */
    public static String num(long n) {
        return String.format("%010d", n);
    }

    public static byte[] bytes(Object value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T> T parse(byte[] bytes, Class<T> type) {
        try {
            return JSON.readValue(bytes, type);
        } catch (IOException e) {
            throw new UncheckedIOException("a BI document could not be read: " + e.getMessage(), e);
        }
    }

    /** Reads a document; its version is remembered for this thread's next {@link #save} of the key. */
    public <T> Optional<Read<T>> get(String key, Class<T> type) {
        Optional<ContentStore.Item> it = store.get(key);
        seen.get().put(key, new Seen(it.map(ContentStore.Item::version).orElse(null), System.currentTimeMillis()));
        return it.map(i -> new Read<>(parse(i.bytes(), type), i.version()));
    }

    /** Reads a document without remembering its version. */
    public <T> Optional<T> peek(String key, Class<T> type) {
        return store.get(key).map(i -> parse(i.bytes(), type));
    }

    /** Writes a document: against the version this thread read lately, else over whatever is there. */
    public String save(String key, Object value) {
        Map<String, Seen> mine = seen.get();
        Seen s = mine.get(key);
        byte[] b = bytes(value);
        String v;
        if (s != null && System.currentTimeMillis() - s.at() < SEEN_MS) {
            v = store.put(key, b, s.version());
        } else {
            v = store.putLatest(key, b);
        }
        mine.put(key, new Seen(v, System.currentTimeMillis()));
        touched(key);
        return v;
    }

    /** Creates a document that must not exist yet; false when it does. */
    public boolean create(String key, Object value) {
        try {
            store.put(key, bytes(value), null);
            touched(key);
            return true;
        } catch (DrishtiException e) {
            if (e.errorCode() == ErrorCode.CONTENT_CONFLICT) {
                return false;
            }
            throw e;
        }
    }

    /** Read, change and write a document as one compare-and-set, again while another writer gets in between ({@code change} gets null when absent). */
    public <T> T update(String key, Class<T> type, UnaryOperator<T> change) {
        for (int attempt = 0; ; attempt++) {
            Optional<ContentStore.Item> it = store.get(key);
            T now = change.apply(it.map(i -> parse(i.bytes(), type)).orElse(null));
            try {
                if (now == null) {
                    if (it.isPresent()) {
                        store.delete(key, it.get().version());
                    }
                } else {
                    store.put(key, bytes(now), it.map(ContentStore.Item::version).orElse(null));
                }
                touched(key);
                return now;
            } catch (DrishtiException e) {
                if (e.errorCode() != ErrorCode.CONTENT_CONFLICT || attempt >= 20) {
                    throw e;
                }
            }
        }
    }

    public boolean delete(String key) {
        seen.get().remove(key);
        boolean had = store.deleteLatest(key);
        touched(key);
        return had;
    }

    /** Deletes every document under {@code prefix}. */
    public void deleteAll(String prefix) {
        for (String k : store.list(prefix)) {
            store.deleteLatest(k);
        }
        touched(prefix);
    }

    public List<String> keys(String prefix) {
        return store.list(prefix);
    }

    /** Every document under {@code prefix} now, read from the store (no snapshot). */
    public <T> List<T> all(String prefix, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (String k : store.list(prefix)) {
            store.get(k).ifPresent(i -> out.add(parse(i.bytes(), type)));
        }
        return out;
    }

    /**
     * Every document directly named {@code <prefix><name>.json} (sub-folders skipped), from a snapshot at most the list TTL old: documents whose
     * version did not change are not parsed again; this node's own writes drop the snapshot at once.
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> listed(String prefix, Class<T> type) {
        Snapshot<T> s = (Snapshot<T>) snapshots.computeIfAbsent(prefix, p -> new Snapshot<>(p, type));
        return s.values();
    }

    /** Drops the list snapshots that hold {@code key}: a write made around this class (a compare-and-set of its own). */
    public void changed(String key) {
        touched(key);
    }

    private void touched(String key) {
        snapshots.values().forEach(s -> {
            if (key.startsWith(s.prefix) || s.prefix.startsWith(key)) {
                s.stamp = 0;
            }
        });
    }

    private final class Snapshot<T> {
        final String prefix;
        final Class<T> type;
        volatile long stamp;
        volatile List<T> values = List.of();
        final Map<String, Read<T>> parsed = new ConcurrentHashMap<>();

        Snapshot(String prefix, Class<T> type) {
            this.prefix = prefix;
            this.type = type;
        }

        synchronized List<T> values() {
            long now = System.currentTimeMillis();
            if (stamp != 0 && now - stamp <= listTtlMs) {
                return values;
            }
            stamp = now;                                     // a write while reading drops it again
            List<T> out = new ArrayList<>();
            java.util.Set<String> live = new java.util.HashSet<>();
            for (String k : store.list(prefix)) {
                if (k.indexOf('/', prefix.length()) >= 0 || !k.endsWith(".json")) {
                    continue;
                }
                Optional<ContentStore.Item> it = store.get(k);
                if (it.isEmpty()) {
                    continue;
                }
                live.add(k);
                Read<T> r = parsed.get(k);
                if (r == null || !r.version().equals(it.get().version())) {
                    r = new Read<>(parse(it.get().bytes(), type), it.get().version());
                    parsed.put(k, r);
                }
                out.add(r.value());
            }
            parsed.keySet().retainAll(live);
            values = List.copyOf(out);
            return values;
        }
    }
}
