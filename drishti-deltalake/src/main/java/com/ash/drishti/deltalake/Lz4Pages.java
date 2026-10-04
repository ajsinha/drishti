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

import java.io.IOException;
import net.jpountz.lz4.LZ4Exception;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4SafeDecompressor;

/**
 * LZ4 Parquet pages. {@code LZ4_RAW} is one LZ4 block. The deprecated {@code LZ4} codec is ambiguous in the wild: Hadoop
 * wrote frames {@code [4-byte BE uncompressed length][4-byte BE compressed length][block]} (a frame may hold several
 * blocks), while Arrow and parquet-rs (so delta-rs) wrote a raw block under it. Like Arrow, {@code LZ4} tries the Hadoop
 * framing first, accepting it only when the lengths add up exactly to the page's sizes, and otherwise decodes one raw
 * block. Immutable and thread-safe: lz4-java's decompressors keep no state.
 */
final class Lz4Pages implements PageCodec {

    private static final int HEADER = 8;

    private final LZ4SafeDecompressor lz4;
    private final boolean hadoopFramingFirst;
    private final String codec;

    /**
     * @param fastest the fastest decoder the platform has (JNI, else unsafe Java) rather than the safe pure-Java one
     * @param deprecatedLz4 the deprecated {@code LZ4} codec (Hadoop framing, then raw) rather than {@code LZ4_RAW}
     */
    Lz4Pages(boolean fastest, boolean deprecatedLz4) {
        LZ4Factory factory = fastest ? LZ4Factory.fastestInstance() : LZ4Factory.safeInstance();
        this.lz4 = factory.safeDecompressor();
        this.hadoopFramingFirst = deprecatedLz4;
        this.codec = deprecatedLz4 ? "LZ4" : "LZ4_RAW";
    }

    @Override
    public byte[] decompress(byte[] in, int off, int len, int size) throws IOException {
        if (hadoopFramingFirst) {
            byte[] framed = hadoopFrames(in, off, len, size);
            if (framed != null) {
                return framed;
            }
        }
        try {
            byte[] out = new byte[size];
            int n = lz4.decompress(in, off, len, out, 0, size);
            if (n == size) {
                return out;
            }
            throw new PageDecodeException(codec, "the page decodes to " + n + " bytes, not the " + size + " its header says");
        } catch (LZ4Exception e) {
            throw new PageDecodeException(codec, hadoopFramingFirst
                    ? "the page is neither Hadoop-framed LZ4 nor a single raw LZ4 block (" + e.getMessage() + ")"
                    : "the page is not a single LZ4 block (" + e.getMessage() + ")", e);
        }
    }

    /** The page decoded as Hadoop frames, or null when it is not framed that way (lengths that do not add up exactly). */
    private byte[] hadoopFrames(byte[] in, int off, int len, int size) {
        byte[] out = new byte[size];
        int at = off;
        int end = off + len;
        int written = 0;
        try {
            while (at < end) {
                if (end - at < HEADER) {
                    return null;
                }
                int frame = be32(in, at);
                at += 4;
                if (frame < 0 || frame > size - written) {
                    return null;
                }
                int frameEnd = written + frame;
                while (written < frameEnd) {                       // a frame holds one or more [compressed length][block]
                    if (end - at < 4) {
                        return null;
                    }
                    int clen = be32(in, at);
                    at += 4;
                    if (clen <= 0 || clen > end - at) {
                        return null;
                    }
                    written += lz4.decompress(in, at, clen, out, written, frameEnd - written);
                    at += clen;
                }
            }
        } catch (LZ4Exception | ArrayIndexOutOfBoundsException e) {
            return null;                                           // not Hadoop's framing: the caller tries a raw block
        }
        return written == size && at == end ? out : null;
    }

    private static int be32(byte[] b, int at) {
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | (b[at + 3] & 0xFF);
    }
}
