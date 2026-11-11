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
package com.ash.drishti.identity.content;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * JSON documents in a content store: typed reads, read-modify-write with compare-and-set (retried outside a transaction, once inside
 * one), sequence counters, and key helpers. Shared by the stores that moved onto the content store (DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 9).
 */
public final class ContentDocs {

    public static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final ContentStore store;

    public ContentDocs(ContentStore store) {
        this.store = store;
    }

    public ContentStore store() {
        return store;
    }

    /** A document and the version it was read at. */
    public record Doc<T>(T value, String version) {}

    public <T> Optional<Doc<T>> read(String key, Class<T> type) {
        return store.get(key).map(it -> new Doc<>(decode(it.bytes(), type), it.version()));
    }

    public <T> Optional<T> value(String key, Class<T> type) {
        return read(key, type).map(Doc::value);
    }

    /** Creates the document; false when it exists already. */
    public boolean create(String key, Object value) {
        try {
            store.put(key, encode(value), null);
            return true;
        } catch (DrishtiException e) {
            if (e.errorCode() == ErrorCode.CONTENT_CONFLICT) {
                return false;
            }
            throw e;
        }
    }

    /** Writes the document whatever is there (last writer wins). */
    public void putLatest(String key, Object value) {
        store.putLatest(key, encode(value));
    }

    /**
     * Read, change, compare-and-set: {@code edit} gets the current value (null when absent) and returns the new one (null = delete; it may
     * change and return the value it was given). Retried when another writer got in between, unless inside a transaction (the commit checks).
     */
    public <T> T update(String key, Class<T> type, UnaryOperator<T> edit) {
        boolean tx = store instanceof TxContentStore t && t.inTransaction();
        for (int attempt = 0; ; attempt++) {
            Optional<Doc<T>> cur = read(key, type);
            T old = cur.map(Doc::value).orElse(null);
            T next = edit.apply(old);
            try {
                if (next == null) {
                    if (cur.isPresent()) {
                        store.delete(key, cur.get().version());
                    }
                } else {
                    store.put(key, encode(next), cur.map(Doc::version).orElse(null));
                }
                return next;
            } catch (DrishtiException e) {
                if (tx || e.errorCode() != ErrorCode.CONTENT_CONFLICT || attempt >= 20) {
                    throw e;
                }
            }
        }
    }

    /** The next number of a sequence kept as the document {@code key} (at least {@code floor} + 1); never reused, never rolled back. */
    public static long next(ContentStore store, String key) {
        ContentStore base = store instanceof TxContentStore t ? t.base() : store;
        for (int attempt = 0; ; attempt++) {
            Optional<ContentStore.Item> it = base.get(key);
            long n = it.map(i -> Long.parseLong(i.text().trim())).orElse(0L) + 1;
            try {
                base.put(key, Long.toString(n).getBytes(StandardCharsets.UTF_8), it.map(ContentStore.Item::version).orElse(null));
                return n;
            } catch (DrishtiException e) {
                if (e.errorCode() != ErrorCode.CONTENT_CONFLICT || attempt >= 50) {
                    throw e;
                }
            }
        }
    }

    /** The current value of a sequence (0 when never used). */
    public static long current(ContentStore store, String key) {
        return store.get(key).map(i -> Long.parseLong(i.text().trim())).orElse(0L);
    }

    /** Raises the sequence to at least {@code value} (an import keeps the old numbers, new ones follow them). */
    public static void raise(ContentStore store, String key, long value) {
        ContentStore base = store instanceof TxContentStore t ? t.base() : store;
        for (int attempt = 0; ; attempt++) {
            Optional<ContentStore.Item> it = base.get(key);
            long n = it.map(i -> Long.parseLong(i.text().trim())).orElse(0L);
            if (n >= value) {
                return;
            }
            try {
                base.put(key, Long.toString(value).getBytes(StandardCharsets.UTF_8), it.map(ContentStore.Item::version).orElse(null));
                return;
            } catch (DrishtiException e) {
                if (e.errorCode() != ErrorCode.CONTENT_CONFLICT || attempt >= 50) {
                    throw e;
                }
            }
        }
    }

    /** A number as a key segment that sorts as the number does (19 digits). */
    public static String pad(long n) {
        return String.format("%019d", n);
    }

    /** A free-text value (a user name, an entity id) as one short key segment: its SHA-1, hex. */
    public static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A short name (a kind, a pack) as a readable key segment: anything outside {@code A-Za-z0-9._-} becomes {@code _}. */
    public static String name(String s) {
        String out = s == null || s.isBlank() ? "_" : s.replaceAll("[^A-Za-z0-9._-]", "_");
        if (out.startsWith(".")) {
            out = "_" + out.substring(1);
        }
        return out.length() > 100 ? out.substring(0, 60) + "-" + hash(s).substring(0, 16) : out;
    }

    public static byte[] encode(Object value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T> T decode(byte[] bytes, Class<T> type) {
        try {
            return JSON.readValue(bytes, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
