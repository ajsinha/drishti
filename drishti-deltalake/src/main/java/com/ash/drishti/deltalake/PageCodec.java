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
import java.io.OutputStream;
import java.nio.ByteBuffer;
import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.compression.CompressionCodecFactory.BytesInputDecompressor;

/** A codec that decompresses whole pages held in arrays: Parquet's decompressor API on top of one method. Stateless. */
interface PageCodec extends BytesInputDecompressor {

    /** The page {@code in[off, off+len)} decompressed to exactly {@code size} bytes. */
    byte[] decompress(byte[] in, int off, int len, int size) throws IOException;

    @Override
    default BytesInput decompress(BytesInput bytes, int uncompressedSize) throws IOException {
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
    default void decompress(ByteBuffer input, int compressedSize, ByteBuffer output, int uncompressedSize) throws IOException {
        byte[] in = new byte[compressedSize];
        input.duplicate().get(in);
        output.put(decompress(in, 0, compressedSize, uncompressedSize));
    }

    @Override
    default void release() {
        // stateless
    }
}
