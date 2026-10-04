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
package com.ash.drishti.deltalake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** LZ4 and LZ4_RAW page decoding: raw blocks, Hadoop frames (one block or several), Arrow's detection, bad pages, both decoders. */
class Lz4PagesTest {

    private static final LZ4Compressor COMPRESSOR = LZ4Factory.safeInstance().fastCompressor();

    private static byte[] page(int size) {
        byte[] b = new byte[size];
        Random r = new Random(size);
        for (int i = 0; i < size; i++) {
            b[i] = (byte) ("abcdefgh".charAt(r.nextInt(8)));
        }
        return b;
    }

    private static byte[] block(byte[] data, int off, int len) {
        return COMPRESSOR.compress(data, off, len);
    }

    private static void be32(ByteArrayOutputStream o, int v) {
        o.writeBytes(ByteBuffer.allocate(4).putInt(v).array());
    }

    /** Hadoop's framing: [uncompressed total][ [compressed][block] ... ], here with {@code blocksPerFrame} blocks per frame. */
    private static byte[] hadoopFramed(byte[] data, int frameSize, int blocksPerFrame) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int at = 0; at < data.length; at += frameSize) {
            int frame = Math.min(frameSize, data.length - at);
            be32(out, frame);
            int per = Math.max(1, frame / blocksPerFrame);
            for (int b = 0; b < frame; b += per) {
                int n = Math.min(per, frame - b);
                byte[] c = block(data, at + b, n);
                be32(out, c.length);
                out.writeBytes(c);
            }
        }
        return out.toByteArray();
    }

    private static byte[] decode(PageCodec codec, byte[] compressed, int size) throws Exception {
        return codec.decompress(BytesInput.from(compressed), size).toByteArray();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lz4RawIsOneBlock(boolean fast) throws Exception {
        byte[] data = page(5000);
        PageCodec codec = (PageCodec) NativeCodecs.of(fast ? "fast" : "safe").getDecompressor(CompressionCodecName.LZ4_RAW);
        assertThat(decode(codec, block(data, 0, data.length), data.length)).isEqualTo(data);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lz4ReadsARawBlockAsArrowWritesIt(boolean fast) throws Exception {
        byte[] data = page(20_000);
        PageCodec codec = (PageCodec) NativeCodecs.of(fast ? "fast" : "safe").getDecompressor(CompressionCodecName.LZ4);
        assertThat(decode(codec, block(data, 0, data.length), data.length)).isEqualTo(data);
    }

    @Test
    void lz4ReadsHadoopFramesOfOneOrSeveralBlocks() throws Exception {
        byte[] data = page(70_000);
        PageCodec codec = (PageCodec) NativeCodecs.INSTANCE.getDecompressor(CompressionCodecName.LZ4);
        assertThat(decode(codec, hadoopFramed(data, data.length, 1), data.length)).as("one frame, one block").isEqualTo(data);
        assertThat(decode(codec, hadoopFramed(data, data.length, 4), data.length)).as("one frame, four blocks").isEqualTo(data);
        assertThat(decode(codec, hadoopFramed(data, 30_000, 1), data.length)).as("three frames").isEqualTo(data);
        assertThat(decode(codec, hadoopFramed(new byte[0], 1, 1), 0)).isEmpty();
    }

    @Test
    void framingThatDoesNotAddUpIsNotTakenForHadoopsAndTheRawBlockIsTried() throws Exception {
        byte[] data = page(3000);
        PageCodec codec = (PageCodec) NativeCodecs.INSTANCE.getDecompressor(CompressionCodecName.LZ4);
        byte[] framed = hadoopFramed(data, data.length, 1);
        byte[] shortInput = java.util.Arrays.copyOf(framed, framed.length - 3);          // truncated frame: neither framing nor a raw block
        assertThatThrownBy(() -> decode(codec, shortInput, data.length)).isInstanceOf(PageDecodeException.class)
                .hasMessageContaining("LZ4 Parquet page cannot be decoded").hasMessageContaining("neither Hadoop-framed LZ4 nor a single raw LZ4 block");
        assertThatThrownBy(() -> decode(codec, framed, data.length + 1)).isInstanceOf(PageDecodeException.class);   // sizes do not match
        assertThatThrownBy(() -> decode(codec, new byte[] {1, 2, 3}, 10)).isInstanceOf(PageDecodeException.class);
    }

    @Test
    void lz4RawRefusesHadoopFramesAndWrongSizes() {
        byte[] data = page(3000);
        PageCodec codec = (PageCodec) NativeCodecs.INSTANCE.getDecompressor(CompressionCodecName.LZ4_RAW);
        assertThatThrownBy(() -> decode(codec, hadoopFramed(data, data.length, 1), data.length)).isInstanceOf(PageDecodeException.class)
                .hasMessageContaining("LZ4_RAW Parquet page");
        assertThatThrownBy(() -> decode(codec, block(data, 0, data.length), data.length - 1)).isInstanceOf(PageDecodeException.class);
    }

    @Test
    void theByteBufferFormDecodesToo() throws Exception {
        byte[] data = page(4000);
        byte[] c = block(data, 0, data.length);
        PageCodec codec = (PageCodec) NativeCodecs.INSTANCE.getDecompressor(CompressionCodecName.LZ4_RAW);
        ByteBuffer out = ByteBuffer.allocate(data.length);
        codec.decompress(ByteBuffer.wrap(c), c.length, out, data.length);
        assertThat(out.array()).isEqualTo(data);
    }

    @Test
    void manyThreadsShareOneCodec() throws Exception {
        PageCodec codec = (PageCodec) NativeCodecs.INSTANCE.getDecompressor(CompressionCodecName.LZ4);
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            var jobs = IntStream.range(0, 64).mapToObj(i -> pool.submit(() -> {
                byte[] data = page(1000 + i * 37);
                byte[] c = i % 2 == 0 ? block(data, 0, data.length) : hadoopFramed(data, data.length, 2);
                return java.util.Arrays.equals(decode(codec, c, data.length), data);
            })).toList();
            for (Future<Boolean> j : jobs) {
                assertThat(j.get()).isTrue();
            }
        }
    }

    @Test
    void theDecoderSettingIsSafeOrFast() {
        assertThat(NativeCodecs.of((String) null)).isSameAs(NativeCodecs.INSTANCE);
        assertThat(NativeCodecs.of("safe")).isSameAs(NativeCodecs.INSTANCE);
        assertThat(NativeCodecs.of("fast")).isNotSameAs(NativeCodecs.INSTANCE);
        assertThatThrownBy(() -> NativeCodecs.of("turbo")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("safe or fast");
    }

    @Test
    void aCodecNoOneReadsNamesItself() {
        assertThatThrownBy(() -> NativeCodecs.INSTANCE.getDecompressor(CompressionCodecName.BROTLI)).isInstanceOf(UnsupportedCodec.class)
                .hasMessageContaining("BROTLI").hasMessageContaining("LZ4_RAW");
        assertThatThrownBy(() -> NativeCodecs.INSTANCE.getDecompressor(CompressionCodecName.LZO)).isInstanceOf(UnsupportedCodec.class)
                .hasMessageContaining("LZO");
    }
}
