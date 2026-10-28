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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * User content as keyed documents every node of a cluster can reach (docs/architecture/CONTENT_STORE.md). A key is a {@code /}-separated
 * path ({@code designs/<owner>/<id>/design.json}); an item is its bytes and an opaque version. Writes are compare-and-set: a stale or
 * unexpected version is {@code 409 DRS-5057}, so two nodes editing the same item never silently lose one edit. Implementations are
 * thread safe and safe across processes sharing the same backing store.
 */
public interface ContentStore extends AutoCloseable {

    /** One item: its bytes and its version (opaque: a content hash, a counter or an ETag, by backend). */
    record Item(byte[] bytes, String version) {
        public String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** What changed after a point in time: the keys (written or deleted), and the cursor to pass next time. */
    record Changes(List<String> keys, long cursor) {}

    Optional<Item> get(String key);

    /** Whether an item exists, without reading it (a run's answer can be large). */
    default boolean exists(String key) {
        return get(key).isPresent();
    }

    /**
     * Compare-and-set write. {@code expectedVersion} null: the item must not exist (a create); otherwise it must still have that
     * version. Returns the new version; throws {@link ErrorCode#CONTENT_CONFLICT} on a mismatch.
     */
    String put(String key, byte[] bytes, String expectedVersion);

    /** Every key starting with {@code prefix} (plain string prefix), sorted. */
    List<String> list(String prefix);

    /** Compare-and-set delete: false when there was no item; {@link ErrorCode#CONTENT_CONFLICT} when its version is not {@code expectedVersion}. */
    boolean delete(String key, String expectedVersion);

    /** Keys written or deleted after {@code since} (epoch milliseconds). At least once: a key may repeat. */
    Changes changes(long since);

    /** Where the store is, as text: the backend and its root, bucket or database (never a password). */
    String root();

    @Override
    default void close() {}

    /** A write whose last writer may win: reads the version and puts, retrying a few times when another writer got in between. */
    default String putLatest(String key, byte[] bytes) {
        for (int attempt = 0; ; attempt++) {
            String v = get(key).map(Item::version).orElse(null);
            try {
                return put(key, bytes, v);
            } catch (DrishtiException e) {
                if (e.errorCode() != ErrorCode.CONTENT_CONFLICT || attempt >= 8) {
                    throw e;
                }
            }
        }
    }

    /** Deletes whatever version is there; false when there was nothing. */
    default boolean deleteLatest(String key) {
        for (int attempt = 0; ; attempt++) {
            Optional<Item> it = get(key);
            if (it.isEmpty()) {
                return false;
            }
            try {
                return delete(key, it.get().version());
            } catch (DrishtiException e) {
                if (e.errorCode() != ErrorCode.CONTENT_CONFLICT || attempt >= 8) {
                    throw e;
                }
            }
        }
    }

    static DrishtiException conflict(String key) {
        return new DrishtiException(ErrorCode.CONTENT_CONFLICT, "'" + key + "' was changed elsewhere (or already exists): reload and try again");
    }

    /** Checks a key: 1 to 16 segments of 1 to 120 UTF-8 bytes, no empty, {@code .} or {@code ..} segment, no {@code \} or control characters. */
    static String check(String key) {
        if (key == null || key.isEmpty() || key.length() > 2048) {
            throw bad(key);
        }
        String[] segs = key.split("/", -1);
        if (segs.length > 16) {
            throw bad(key);
        }
        for (String s : segs) {
            if (s.isEmpty() || s.equals(".") || s.equals("..") || s.getBytes(StandardCharsets.UTF_8).length > 120) {
                throw bad(key);
            }
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < 0x20 || c == 0x7f || c == '\\') {
                    throw bad(key);
                }
            }
        }
        return key;
    }

    private static DrishtiException bad(String key) {
        return new DrishtiException(ErrorCode.CONTENT_BAD_KEY, "not a content key: '" + (key == null ? "" : key.replaceAll("\\p{Cntrl}", "?")) + "'");
    }
}
