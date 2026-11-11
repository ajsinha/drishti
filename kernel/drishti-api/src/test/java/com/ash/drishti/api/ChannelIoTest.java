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
package com.ash.drishti.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Channel transfers in slices through a bounded pool of direct buffers: the same bytes as a plain channel read or write. */
class ChannelIoTest {

    @TempDir
    Path dir;

    private byte[] random(int n) {
        byte[] b = new byte[n];
        new Random(n).nextBytes(b);
        return b;
    }

    @Test
    void positionalReadsFillHeapAndDirectBuffersAndStopAtTheEnd() throws Exception {
        byte[] content = random(ChannelIo.CHUNK_BYTES * 5 + 123);
        Path f = Files.write(dir.resolve("a.bin"), content);
        try (FileChannel ch = FileChannel.open(f)) {
            byte[] into = new byte[content.length];
            assertThat(ChannelIo.read(ch, ByteBuffer.wrap(into), 0)).isEqualTo(content.length);
            assertThat(into).isEqualTo(content);
            ByteBuffer part = ByteBuffer.allocate(1000).position(10);         // into the middle of a buffer
            assertThat(ChannelIo.read(ch, part, 7)).isEqualTo(990);
            assertThat(Arrays.copyOfRange(part.array(), 10, 1000)).isEqualTo(Arrays.copyOfRange(content, 7, 997));
            ByteBuffer tail = ByteBuffer.allocate(500);
            assertThat(ChannelIo.read(ch, tail, content.length - 100)).isEqualTo(100);                   // short at the end
            assertThat(ChannelIo.read(ch, ByteBuffer.allocate(5), content.length)).isEqualTo(-1);
            assertThat(ChannelIo.read(ch, ByteBuffer.allocate(0), 0)).isZero();
            ByteBuffer direct = ByteBuffer.allocateDirect(4096);
            assertThat(ChannelIo.read(ch, direct, 1)).isEqualTo(4096);
            assertThat(direct.get(0)).isEqualTo(content[1]);
            assertThatThrownBy(() -> ChannelIo.readFully(ch, ByteBuffer.allocate(200), content.length - 100)).isInstanceOf(EOFException.class);
            ByteBuffer seq = ByteBuffer.allocate(content.length);
            ch.position(0);
            while (seq.hasRemaining() && ChannelIo.read((java.nio.channels.ReadableByteChannel) ch, seq) > 0) {
                // sequential
            }
            assertThat(seq.array()).isEqualTo(content);
        }
        assertThat(ChannelIo.readAllBytes(f)).isEqualTo(content);
        assertThat(ChannelIo.readAllBytes(Files.write(dir.resolve("empty"), new byte[0]))).isEmpty();
    }

    @Test
    void writesAndStreamsCarryTheSameBytes() throws Exception {
        byte[] content = random(ChannelIo.CHUNK_BYTES * 3 + 77);
        Path f = dir.resolve("w.bin");
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            assertThat(ChannelIo.write(ch, ByteBuffer.wrap(content), 0)).isEqualTo(content.length);
            assertThat(ChannelIo.write(ch, ByteBuffer.wrap(content, 0, 10), content.length)).isEqualTo(10);
        }
        byte[] back = Files.readAllBytes(f);
        assertThat(Arrays.copyOf(back, content.length)).isEqualTo(content);
        assertThat(Arrays.copyOfRange(back, content.length, back.length)).isEqualTo(Arrays.copyOf(content, 10));
        Path g = dir.resolve("s.bin");
        try (OutputStream out = ChannelIo.newOutputStream(g)) {
            out.write(content, 5, content.length - 5);
            out.write(9);
        }
        try (InputStream in = ChannelIo.newInputStream(g)) {
            byte[] all = in.readAllBytes();
            assertThat(Arrays.copyOf(all, all.length - 1)).isEqualTo(Arrays.copyOfRange(content, 5, content.length));
            assertThat(all[all.length - 1]).isEqualTo((byte) 9);
            assertThat(in.read()).isEqualTo(-1);
        }
        try (OutputStream out = ChannelIo.newOutputStream(g)) {                // no options: truncates
            out.write(new byte[] {1, 2});
        }
        assertThat(Files.readAllBytes(g)).containsExactly(1, 2);
    }

    @Test
    void moreConcurrentTransfersThanPooledBuffersStillReadRightAndThePoolStaysBounded() throws Exception {
        byte[] content = random(ChannelIo.CHUNK_BYTES * 4);
        Path f = Files.write(dir.resolve("c.bin"), content);
        int threads = ChannelIo.POOL_BUFFERS * 2;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try (FileChannel ch = FileChannel.open(f)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                results.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < 20; i++) {
                        byte[] into = new byte[content.length];
                        ChannelIo.readFully(ch, ByteBuffer.wrap(into), 0);
                        if (!Arrays.equals(into, content)) {
                            return false;
                        }
                    }
                    return true;
                }));
            }
            go.countDown();
            for (Future<Boolean> r : results) {
                assertThat(r.get()).isTrue();
            }
        } finally {
            pool.shutdown();
        }
        assertThat(ChannelIo.pooledBuffers()).isBetween(1, ChannelIo.POOL_BUFFERS);
    }
}
