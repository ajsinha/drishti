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

import com.github.luben.zstd.Zstd;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import org.apache.parquet.compression.CompressionCodecFactory;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.xerial.snappy.Snappy;

/**
 * Parquet page decompression without Hadoop's codec classes (Parquet's own factory builds a Hadoop
 * {@code Configuration} and loads codecs through Hadoop's {@code ReflectionUtils}): Snappy (delta-rs's default),
 * ZSTD, GZIP, LZ4 / LZ4_RAW ({@link Lz4Pages}) and uncompressed pages. Stateless and shared; it never compresses (the engine only reads).
 */
final class NativeCodecs implements CompressionCodecFactory {

    /** The default: the safe (pure Java) LZ4 decoder. */
    static final NativeCodecs INSTANCE = new NativeCodecs(false);

    /** The connector setting that picks the LZ4 decoder: {@code safe} (the default) or {@code fast}. */
    static final String LZ4_DECODER = "drishti.lz4.decoder";

    private final Lz4Pages lz4;
    private final Lz4Pages lz4Raw;

    private NativeCodecs(boolean fastestLz4) {
        this.lz4 = new Lz4Pages(fastestLz4, true);
        this.lz4Raw = new Lz4Pages(fastestLz4, false);
    }

    /** From the {@link #LZ4_DECODER} value ({@code safe}, {@code fast}, null = safe). */
    static NativeCodecs of(String decoder) {
        if (decoder == null || decoder.isBlank() || "safe".equalsIgnoreCase(decoder.trim())) {
            return INSTANCE;
        }
        if ("fast".equalsIgnoreCase(decoder.trim())) {
            return new NativeCodecs(true);
        }
        throw new IllegalArgumentException("unknown lz4-decoder '" + decoder + "': use safe or fast");
    }

    @Override
    public BytesInputCompressor getCompressor(CompressionCodecName codecName) {
        throw new UnsupportedOperationException(NativeFileIO.READ_ONLY);
    }

    @Override
    public BytesInputDecompressor getDecompressor(CompressionCodecName codecName) {
        return switch (codecName) {
            case UNCOMPRESSED -> Codec.NONE;
            case SNAPPY -> Codec.SNAPPY;
            case ZSTD -> Codec.ZSTD;
            case GZIP -> Codec.GZIP;
            case LZ4 -> lz4;
            case LZ4_RAW -> lz4Raw;
            default -> throw new UnsupportedCodec(codecName.name());
        };
    }

    @Override
    public void release() {
        // nothing is pooled
    }

    /** One codec: whole pages in, whole pages out. */
    private enum Codec implements PageCodec {
        NONE {
            @Override
            public byte[] decompress(byte[] in, int off, int len, int size) {
                byte[] out = new byte[size];
                System.arraycopy(in, off, out, 0, Math.min(len, size));
                return out;
            }
        },
        SNAPPY {
            @Override
            public byte[] decompress(byte[] in, int off, int len, int size) throws IOException {
                byte[] out = new byte[size];
                int n = Snappy.uncompress(in, off, len, out, 0);
                return check(n, size, out);
            }
        },
        ZSTD {
            @Override
            public byte[] decompress(byte[] in, int off, int len, int size) throws IOException {
                byte[] out = new byte[size];
                long n = Zstd.decompressByteArray(out, 0, size, in, off, len);
                if (Zstd.isError(n)) {
                    throw new IOException("ZSTD page: " + Zstd.getErrorName(n));
                }
                return check((int) n, size, out);
            }
        },
        GZIP {
            @Override
            public byte[] decompress(byte[] in, int off, int len, int size) throws IOException {
                byte[] out = new byte[size];
                try (InputStream z = new GZIPInputStream(new ByteArrayInputStream(in, off, len))) {
                    int at = 0;
                    while (at < size) {
                        int n = z.read(out, at, size - at);
                        if (n < 0) {
                            break;
                        }
                        at += n;
                    }
                    return check(at, size, out);
                }
            }
        };

        @Override
        public abstract byte[] decompress(byte[] in, int off, int len, int size) throws IOException;

        private static byte[] check(int n, int size, byte[] out) throws IOException {
            if (n != size) {
                throw new IOException("a Parquet page decompressed to " + n + " bytes, not the " + size + " its header says");
            }
            return out;
        }
    }
}
