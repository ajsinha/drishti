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
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.zip.GZIPInputStream;
import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.compression.CompressionCodecFactory;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.xerial.snappy.Snappy;

/**
 * Parquet page decompression without Hadoop's codec classes (Parquet's own factory builds a Hadoop
 * {@code Configuration} and loads codecs through Hadoop's {@code ReflectionUtils}): Snappy (delta-rs's default),
 * ZSTD, GZIP and uncompressed pages. Stateless and shared; it never compresses (the engine only reads).
 */
final class NativeCodecs implements CompressionCodecFactory {

    static final NativeCodecs INSTANCE = new NativeCodecs();

    private NativeCodecs() {}

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
            default -> throw new UnsupportedOperationException("the native Delta engine does not decompress " + codecName
                    + " Parquet pages (Snappy, ZSTD and GZIP it does); rewrite the table with one of those or set engine: hadoop");
        };
    }

    @Override
    public void release() {
        // nothing is pooled
    }

    /** One codec: whole pages in, whole pages out. */
    private enum Codec implements BytesInputDecompressor {
        NONE {
            @Override
            byte[] decompress(byte[] in, int off, int len, int size) {
                byte[] out = new byte[size];
                System.arraycopy(in, off, out, 0, Math.min(len, size));
                return out;
            }
        },
        SNAPPY {
            @Override
            byte[] decompress(byte[] in, int off, int len, int size) throws IOException {
                byte[] out = new byte[size];
                int n = Snappy.uncompress(in, off, len, out, 0);
                return check(n, size, out);
            }
        },
        ZSTD {
            @Override
            byte[] decompress(byte[] in, int off, int len, int size) throws IOException {
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
            byte[] decompress(byte[] in, int off, int len, int size) throws IOException {
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

        abstract byte[] decompress(byte[] in, int off, int len, int size) throws IOException;

        private static byte[] check(int n, int size, byte[] out) throws IOException {
            if (n != size) {
                throw new IOException("a Parquet page decompressed to " + n + " bytes, not the " + size + " its header says");
            }
            return out;
        }

        @Override
        public BytesInput decompress(BytesInput bytes, int uncompressedSize) throws IOException {
            byte[] in = new byte[Math.toIntExact(bytes.size())];
            bytes.writeAllTo(new OutputStream() {                // one copy, into the array
                private int at;

                @Override
                public void write(int b) {
                    in[at++] = (byte) b;
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    System.arraycopy(b, off, in, at, len);
                    at += len;
                }
            });
            return BytesInput.from(decompress(in, 0, in.length, uncompressedSize));
        }

        @Override
        public void decompress(ByteBuffer input, int compressedSize, ByteBuffer output, int uncompressedSize) throws IOException {
            byte[] in = new byte[compressedSize];
            input.duplicate().get(in);
            output.put(decompress(in, 0, compressedSize, uncompressedSize));
        }

        @Override
        public void release() {
            // stateless
        }
    }
}
