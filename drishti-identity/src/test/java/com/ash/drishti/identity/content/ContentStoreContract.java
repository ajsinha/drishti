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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What every {@link ContentStore} backend must do the same way (docs/architecture/CONTENT_STORE.md section 7). A backend's test opens a
 * fresh, empty store in {@link #open()} and the same store again in {@link #reopen()}.
 */
public abstract class ContentStoreContract {

    protected ContentStore store;

    /** A new, empty store for this test. */
    protected abstract ContentStore open() throws Exception;

    /** The same store as {@link #open()} gave, opened again (a restart). */
    protected abstract ContentStore reopen() throws Exception;

    @BeforeEach
    void start() throws Exception {
        store = open();
    }

    @AfterEach
    void stop() {
        if (store != null) {
            store.close();
        }
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static ErrorCode code(Runnable r) {
        try {
            r.run();
        } catch (DrishtiException e) {
            return e.errorCode();
        }
        return null;
    }

    @Test
    void createReadUpdateWithVersions() {
        assertThat(store.get("designs/ann/a.json")).isEmpty();
        String v1 = store.put("designs/ann/a.json", b("{\"n\":1}"), null);
        assertThat(store.get("designs/ann/a.json")).get().satisfies(it -> {
            assertThat(it.text()).isEqualTo("{\"n\":1}");
            assertThat(it.version()).isEqualTo(v1);
        });
        String v2 = store.put("designs/ann/a.json", b("{\"n\":2}"), v1);
        assertThat(v2).isNotEqualTo(v1);
        assertThat(store.get("designs/ann/a.json").orElseThrow().version()).isEqualTo(v2);
    }

    @Test
    void conflictsAnswerDrs5057() {
        String v1 = store.put("k/a", b("one"), null);
        assertThat(code(() -> store.put("k/a", b("again"), null))).isEqualTo(ErrorCode.CONTENT_CONFLICT);
        store.put("k/a", b("two"), v1);
        assertThatThrownBy(() -> store.put("k/a", b("three"), v1)).isInstanceOf(DrishtiException.class).hasMessageContaining("DRS-5057");
        assertThat(code(() -> store.put("k/missing", b("x"), v1))).isEqualTo(ErrorCode.CONTENT_CONFLICT);
        assertThat(code(() -> store.put("k/a", b("x"), "not-a-version"))).isEqualTo(ErrorCode.CONTENT_CONFLICT);
        assertThat(store.get("k/a").orElseThrow().text()).isEqualTo("two");
        assertThat(ErrorCode.CONTENT_CONFLICT.code()).isEqualTo("DRS-5057");
    }

    @Test
    void concurrentCreatesAndUpdatesHaveExactlyOneWinner() throws Exception {
        int n = 8;
        assertThat(race(n, i -> store.put("race/new", b("writer " + i), null))).isEqualTo(1);
        String v = store.get("race/new").orElseThrow().version();
        assertThat(race(n, i -> store.put("race/new", b("update " + i), v))).isEqualTo(1);
        assertThat(store.get("race/new").orElseThrow().text()).startsWith("update ");
    }

    private interface Writer {
        void write(int i);
    }

    private int race(int n, Writer w) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int me = i;
            fs.add(pool.submit(() -> {
                go.await();
                try {
                    w.write(me);
                    return true;
                } catch (DrishtiException e) {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.CONTENT_CONFLICT);
                    return false;
                }
            }));
        }
        go.countDown();
        int wins = 0;
        for (Future<Boolean> f : fs) {
            wins += f.get(60, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdown();
        return wins;
    }

    @Test
    void listByPrefix() {
        for (String k : List.of("designs/ann/1/design.json", "designs/ann/2/design.json", "designs/annie/1/design.json", "designs/bob/1/design.json", "other/x")) {
            store.put(k, b(k), null);
        }
        assertThat(store.list("designs/ann/")).containsExactly("designs/ann/1/design.json", "designs/ann/2/design.json");
        assertThat(store.list("designs/ann")).containsExactly("designs/ann/1/design.json", "designs/ann/2/design.json", "designs/annie/1/design.json");
        assertThat(store.list("designs/")).hasSize(4);
        assertThat(store.list("nothing/")).isEmpty();
    }

    @Test
    void deleteIsCompareAndSet() {
        String v1 = store.put("d/a", b("one"), null);
        String v2 = store.put("d/a", b("two"), v1);
        assertThat(code(() -> store.delete("d/a", v1))).isEqualTo(ErrorCode.CONTENT_CONFLICT);
        assertThat(store.delete("d/a", v2)).isTrue();
        assertThat(store.get("d/a")).isEmpty();
        assertThat(store.list("d/")).isEmpty();
        assertThat(store.delete("d/a", v2)).isFalse();
        store.put("d/a", b("back"), null);                                      // a deleted key can be created again
        assertThat(store.get("d/a").orElseThrow().text()).isEqualTo("back");
    }

    @Test
    void changesReportPutsAndDeletes() throws Exception {
        long start = System.currentTimeMillis() - 2_000;
        String v = store.put("c/a", b("a"), null);
        store.put("c/b", b("b"), null);
        ContentStore.Changes ch = store.changes(start);
        assertThat(ch.keys()).contains("c/a", "c/b");
        assertThat(ch.cursor()).isGreaterThan(start);
        store.delete("c/a", v);
        assertThat(store.changes(start).keys()).contains("c/a");
        assertThat(store.changes(System.currentTimeMillis() + 60_000).keys()).isEmpty();
    }

    @Test
    void contentIsBinarySafe() {
        byte[] all = new byte[512];
        for (int i = 0; i < all.length; i++) {
            all[i] = (byte) i;
        }
        store.put("bin/all", all, null);
        assertThat(store.get("bin/all").orElseThrow().bytes()).isEqualTo(all);
        store.put("bin/empty", new byte[0], null);
        assertThat(store.get("bin/empty").orElseThrow().bytes()).isEmpty();
    }

    @Test
    void largeItems() {
        byte[] big = new byte[6 * 1024 * 1024];
        new Random(7).nextBytes(big);
        String v = store.put("big/one", big, null);
        assertThat(store.get("big/one").orElseThrow().bytes()).isEqualTo(big);
        big[0] ^= 1;
        store.put("big/one", big, v);
        assertThat(store.get("big/one").orElseThrow().bytes()).isEqualTo(big);
    }

    @Test
    void unicodeKeys() {
        String k = "designs/ánn émile/日本 データ/ракета 🚀.json";
        store.put(k, b("ok"), null);
        assertThat(store.get(k).orElseThrow().text()).isEqualTo("ok");
        assertThat(store.list("designs/ánn émile/")).containsExactly(k);
        assertThat(store.list("designs/")).containsExactly(k);
        String odd = "x/CON/.hidden/a~b%41*?<>|:\"";
        store.put(odd, b("odd"), null);
        assertThat(store.get(odd).orElseThrow().text()).isEqualTo("odd");
        assertThat(store.list("x/")).containsExactly(odd);
    }

    @Test
    void badKeysAnswerDrs5058() {
        for (String k : List.of("", "a//b", "../etc/passwd", "a/./b", "/a", "a/", "a\\b", "a\u0000b", "x".repeat(121))) {
            assertThat(code(() -> store.get(k))).as(k).isEqualTo(ErrorCode.CONTENT_BAD_KEY);
            assertThat(code(() -> store.put(k, b("x"), null))).as(k).isEqualTo(ErrorCode.CONTENT_BAD_KEY);
        }
    }

    @Test
    void reopeningKeepsEverything() throws Exception {
        byte[] big = new byte[5 * 1024 * 1024 + 3];
        new Random(3).nextBytes(big);
        String v = store.put("keep/a", b("a"), null);
        store.put("keep/big", big, null);
        store.put("keep/gone", b("g"), null);
        store.deleteLatest("keep/gone");
        store.close();
        store = reopen();
        assertThat(store.get("keep/a").orElseThrow().version()).isEqualTo(v);
        assertThat(store.get("keep/big").orElseThrow().bytes()).isEqualTo(big);
        assertThat(store.list("keep/")).containsExactly("keep/a", "keep/big");
        store.put("keep/a", b("a2"), v);
    }
}
