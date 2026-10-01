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

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import com.github.luben.zstd.ZstdDictTrainer;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import java.util.zip.CRC32C;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * How a document is stored in Redis: Redis keeps everything in memory, and trade documents are repetitive JSON, so they
 * are compressed. The first byte says how:
 *
 * <ul>
 *   <li>{@code '{'} or {@code '['}: plain JSON (what any other writer may store; read as it is);</li>
 *   <li>{@code 1}: Deflate ({@code java.util.zip}, no header);</li>
 *   <li>{@code 2}: a zstd frame;</li>
 *   <li>{@code 3}: four bytes naming a zstd dictionary (stored at {@link RedisLayout#dictionary}), then a zstd frame
 *       compressed with it.</li>
 * </ul>
 *
 * <p>Measured on 20,000 generated trade documents (6.3 KB of JSON on average): Deflate 1.9 KB (3.3 times smaller, 77
 * µs a document), zstd level 3 2.0 KB (3.2 times, 13 µs), zstd level 3 with a 112 KB dictionary trained on 2,000 of the
 * documents 0.82 KB (7.7 times, 9 µs). A dictionary captures what documents of a kind share (field names, products,
 * books, curves), which a single 6 KB document cannot: hence {@code zstd-dict}, the loader's default.
 */
public final class DocCodec {

    public static final byte DEFLATE = 1;
    public static final byte ZSTD = 2;
    public static final byte ZSTD_DICT = 3;

    /** The codecs a loader can write with. */
    public enum Kind {
        NONE, DEFLATE, ZSTD, ZSTD_DICT;

        public static Kind of(String name) {
            return valueOf(name.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        }
    }

    /** A trained dictionary, digested once for compressing and decompressing (both are thread-safe to share). */
    public record Dictionary(int id, byte[] bytes, ZstdDictCompress compress, ZstdDictDecompress decompress) {

        public static Dictionary of(byte[] bytes, int level) {
            long own = Zstd.getDictIdFromDict(bytes);
            int id = own != 0 ? (int) own : crc(bytes);
            return new Dictionary(id, bytes, new ZstdDictCompress(bytes, level), new ZstdDictDecompress(bytes));
        }

        /** A dictionary for decompressing only (the level does not matter). */
        public static Dictionary of(byte[] bytes) {
            return of(bytes, 3);
        }
    }

    private final Kind kind;
    private final int level;
    private final Dictionary dictionary;

    public DocCodec(Kind kind, int level, Dictionary dictionary) {
        this.kind = kind == Kind.ZSTD_DICT && dictionary == null ? Kind.ZSTD : kind;
        this.level = level;
        this.dictionary = dictionary;
    }

    public Kind kind() {
        return kind;
    }

    /** Trains a dictionary of at most {@code size} bytes on sample documents; null when zstd cannot (too few samples). */
    public static byte[] train(List<byte[]> samples, int size) {
        long total = samples.stream().mapToLong(s -> s.length).sum();
        ZstdDictTrainer trainer = new ZstdDictTrainer((int) Math.min(Integer.MAX_VALUE - 16, total + 16), size);
        samples.forEach(trainer::addSample);
        try {
            return trainer.trainSamples();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public byte[] encode(byte[] json) {
        return switch (kind) {
            case NONE -> json;
            case DEFLATE -> {
                Deflater d = new Deflater(Math.min(9, Math.max(1, level)), true);
                try {
                    d.setInput(json);
                    d.finish();
                    ByteArrayOutputStream out = new ByteArrayOutputStream(json.length / 3 + 16);
                    out.write(DEFLATE);
                    byte[] buf = new byte[8192];
                    while (!d.finished()) {
                        out.write(buf, 0, d.deflate(buf));
                    }
                    yield out.toByteArray();
                } finally {
                    d.end();
                }
            }
            case ZSTD -> prefixed(new byte[] {ZSTD}, Zstd.compress(json, level));
            case ZSTD_DICT -> prefixed(ByteBuffer.allocate(5).put(ZSTD_DICT).putInt(dictionary.id()).array(), Zstd.compress(json, dictionary.compress()));
        };
    }

    private static byte[] prefixed(byte[] head, byte[] body) {
        byte[] out = new byte[head.length + body.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(body, 0, out, head.length, body.length);
        return out;
    }

    /** The dictionary a stored value needs, or 0 when it needs none. */
    public static int dictionaryOf(byte[] value) {
        return value.length >= 5 && value[0] == ZSTD_DICT ? ByteBuffer.wrap(value, 1, 4).getInt() : 0;
    }

    /** The JSON of a stored value; {@code dictionaries} gives a dictionary by id (for values written with one). */
    public static byte[] decode(byte[] value, IntFunction<Dictionary> dictionaries) {
        if (value.length == 0) {
            return value;
        }
        return switch (value[0]) {
            case DEFLATE -> inflate(value);
            case ZSTD -> {
                byte[] body = java.util.Arrays.copyOfRange(value, 1, value.length);
                yield Zstd.decompress(body, size(body));
            }
            case ZSTD_DICT -> {
                Dictionary d = dictionaries.apply(dictionaryOf(value));
                if (d == null) {
                    throw new IllegalStateException("zstd dictionary " + Integer.toUnsignedString(dictionaryOf(value), 16) + " is missing");
                }
                byte[] body = java.util.Arrays.copyOfRange(value, 5, value.length);
                yield Zstd.decompress(body, d.decompress(), size(body));
            }
            default -> value;                                  // plain JSON
        };
    }

    private static int size(byte[] frame) {
        long n = Zstd.getFrameContentSize(frame);
        if (n < 0 || n > Integer.MAX_VALUE - 16) {
            throw new IllegalStateException("not a zstd frame with a known size");
        }
        return (int) n;
    }

    private static byte[] inflate(byte[] value) {
        Inflater in = new Inflater(true);
        try {
            in.setInput(value, 1, value.length - 1);
            ByteArrayOutputStream out = new ByteArrayOutputStream(value.length * 4);
            byte[] buf = new byte[16384];
            while (!in.finished()) {
                int n = in.inflate(buf);
                if (n == 0 && (in.needsInput() || in.needsDictionary())) {
                    break;
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IllegalStateException("a corrupt Deflate document", e);
        } finally {
            in.end();
        }
    }

    /** A stable number for a stored value (the provenance generation: a changed document has another). */
    public static long generation(byte[] value) {
        CRC32C c = new CRC32C();
        c.update(value);
        return c.getValue();
    }

    private static int crc(byte[] bytes) {
        CRC32C c = new CRC32C();
        c.update(bytes);
        return (int) c.getValue();
    }
}
