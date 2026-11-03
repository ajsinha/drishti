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
package com.ash.drishti.lakeio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * The {@link Storage} contract every lake storage meets, as the Delta and Iceberg engines rely on it: one suite run on local disk and on
 * {@code mem://} (docs/architecture/IN_MEMORY_LAKE.md, section 3), so the memory store answers exactly as a disk does.
 */
public abstract class StorageContract {

    /** The storage under test. */
    protected abstract Storage storage();

    /** A folder of the storage, empty, as a path string the storage accepts. */
    protected abstract String root();

    private String at(String rel) throws Exception {
        return storage().qualify(root()) + "/" + rel;
    }

    private static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void writesAreReadBackWhole() throws Exception {
        Storage s = storage();
        s.put(at("t/_delta_log/00000000000000000000.json"), bytes("{\"commitInfo\":{}}"));
        try (OutputStream out = s.create(at("t/part-0.parquet"))) {
            for (int i = 0; i < 3000; i++) {
                out.write(bytes("row " + i + "\n"));
            }
        }
        assertThat(new String(s.readAll(at("t/_delta_log/00000000000000000000.json")), StandardCharsets.UTF_8)).isEqualTo("{\"commitInfo\":{}}");
        byte[] data = s.readAll(at("t/part-0.parquet"));
        assertThat(new String(data, StandardCharsets.UTF_8)).startsWith("row 0\n").endsWith("row 2999\n");
        assertThat(s.status(at("t/part-0.parquet")).size()).isEqualTo(data.length);
    }

    @Test
    void positionalReadsAnywhere() throws Exception {
        Storage s = storage();
        byte[] content = new byte[300_000];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31);
        }
        s.put(at("f.bin"), content);
        try (RangeReader r = s.open(at("f.bin"), -1)) {
            assertThat(r.length()).isEqualTo(content.length);
            ByteBuffer b = ByteBuffer.allocate(5000);
            r.readFully(140_000, b);
            for (int i = 0; i < 5000; i++) {
                assertThat(b.get(i)).isEqualTo(content[140_000 + i]);
            }
            ByteBuffer tail = ByteBuffer.allocate(10);
            assertThat(r.read(content.length, tail)).isEqualTo(-1);
            assertThatThrownBy(() -> r.readFully(content.length - 5, ByteBuffer.allocate(10))).isInstanceOf(EOFException.class);
        }
    }

    @Test
    void listingsAreSortedFromANameAndOfOneFolderOnly() throws Exception {
        Storage s = storage();
        for (String n : List.of("00000000000000000002.json", "00000000000000000000.json", "00000000000000000001.json",
                "00000000000000000010.checkpoint.parquet")) {
            s.put(at("t/_delta_log/" + n), bytes(n));
        }
        s.put(at("t/_delta_log/sub/00000000000000000005.json"), bytes("deeper"));
        List<String> all = s.listFrom(at("t/_delta_log/0")).stream().map(e -> name(e.path())).toList();
        assertThat(all).containsExactly("00000000000000000000.json", "00000000000000000001.json", "00000000000000000002.json",
                "00000000000000000010.checkpoint.parquet");
        List<String> from = s.listFrom(at("t/_delta_log/00000000000000000001.json")).stream().map(e -> name(e.path())).toList();
        assertThat(from).containsExactly("00000000000000000001.json", "00000000000000000002.json", "00000000000000000010.checkpoint.parquet");
        assertThat(s.listFrom(at("t/_delta_log/0")).get(0).size()).isEqualTo(25);
    }

    @Test
    void aMissingFolderOrFileIsNotFound() throws Exception {
        Storage s = storage();
        assertThatThrownBy(() -> s.listFrom(at("nothing/_delta_log/0"))).isInstanceOf(FileNotFoundException.class);
        assertThatThrownBy(() -> s.status(at("nothing/x.json"))).isInstanceOf(FileNotFoundException.class);
        assertThatThrownBy(() -> s.open(at("nothing/x.json"), -1)).isInstanceOf(FileNotFoundException.class);
        assertThat(s.directories(at("nothing"))).isEmpty();
        assertThat(s.isDirectory(at("nothing"))).isFalse();
    }

    @Test
    void foldersAreListedSorted() throws Exception {
        Storage s = storage();
        s.put(at("lake/trade/_delta_log/0.json"), bytes("a"));
        s.put(at("lake/news/_delta_log/0.json"), bytes("b"));
        s.put(at("lake/quote/business_date=2026-11-02/p.parquet"), bytes("c"));
        s.put(at("lake/readme.txt"), bytes("d"));
        assertThat(s.directories(at("lake"))).containsExactly("news", "quote", "trade");
        assertThat(s.isDirectory(at("lake/news"))).isTrue();
        assertThat(s.isDirectory(at("lake/news/_delta_log"))).isTrue();
        assertThat(s.isDirectory(at("lake/readme.txt"))).isFalse();
    }

    @Test
    void putReplacesAndDeleteRemoves() throws Exception {
        Storage s = storage();
        s.put(at("v/_last_checkpoint"), bytes("one"));
        s.put(at("v/_last_checkpoint"), bytes("two!"));
        assertThat(new String(s.readAll(at("v/_last_checkpoint")), StandardCharsets.UTF_8)).isEqualTo("two!");
        assertThat(s.delete(at("v/_last_checkpoint"))).isTrue();
        assertThat(s.delete(at("v/_last_checkpoint"))).isFalse();
        assertThatThrownBy(() -> s.status(at("v/_last_checkpoint"))).isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void anExclusiveCreateWinsOnce() throws Exception {
        Storage s = storage();
        assertThat(s.exclusiveCreates()).isTrue();
        assertThat(s.createIfAbsent(at("c/_delta_log/00000000000000000001.json"), bytes("first"))).isTrue();
        assertThat(s.createIfAbsent(at("c/_delta_log/00000000000000000001.json"), bytes("second"))).isFalse();
        assertThat(new String(s.readAll(at("c/_delta_log/00000000000000000001.json")), StandardCharsets.UTF_8)).isEqualTo("first");
    }

    @Test
    void ofManyConcurrentCommitsOfOneVersionExactlyOneWins() throws Exception {
        Storage s = storage();
        int writers = 8;
        for (int round = 0; round < 20; round++) {
            String path = at("race/_delta_log/" + String.format(java.util.Locale.ROOT, "%020d", round) + ".json");
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(writers);
            try {
                List<Future<Boolean>> wins = new ArrayList<>();
                for (int w = 0; w < writers; w++) {
                    byte[] mine = bytes("writer " + w);
                    wins.add(pool.submit(() -> {
                        go.await();
                        return s.createIfAbsent(path, mine);
                    }));
                }
                go.countDown();
                int won = 0;
                for (Future<Boolean> f : wins) {
                    won += f.get() ? 1 : 0;
                }
                assertThat(won).as("round " + round).isEqualTo(1);
                assertThat(new String(s.readAll(path), StandardCharsets.UTF_8)).startsWith("writer ");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void qualifiedPathsAreStable() throws Exception {
        Storage s = storage();
        String q = s.qualify(at("q/t"));
        assertThat(s.qualify(q)).isEqualTo(q);
        assertThat(s.qualify(q + "/")).isEqualTo(q);
    }
}
