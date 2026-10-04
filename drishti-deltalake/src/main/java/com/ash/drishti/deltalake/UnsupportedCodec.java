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

/**
 * A Parquet file whose pages use a compression codec the native engine does not decompress (Brotli, LZO). Its message names the codec and what to do; readers that recognise this type show it to whoever asked rather
 * than only logging it.
 */
public final class UnsupportedCodec extends UnsupportedOperationException {

    private static final long serialVersionUID = 1L;

    private final String codec;

    UnsupportedCodec(String codec) {
        super("the native Delta engine does not decompress " + codec + " Parquet pages (it reads Snappy, ZSTD, GZIP, LZ4, LZ4_RAW and uncompressed);"
                + " rewrite the date with Snappy or ZSTD (tools/lake/maintain.py relayout --force --dates <date>)");
        this.codec = codec;
    }

    private UnsupportedCodec(UnsupportedCodec e, String file) {
        super(e.getMessage() + " [file " + file + "]");
        this.codec = e.codec;
    }

    /** The same failure naming the Parquet file it was read from. */
    UnsupportedCodec inFile(String file) {
        return new UnsupportedCodec(this, file);
    }

    /** The codec's Parquet name ({@code BROTLI}, {@code LZO}). */
    public String codec() {
        return codec;
    }
}
