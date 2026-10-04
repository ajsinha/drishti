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
 * A compressed Parquet page that does not decode (an LZ4 page that is neither Hadoop-framed nor a single block, or one
 * that decodes to the wrong size): the file is damaged or written by a writer this reader does not know. Unchecked, so it
 * crosses Parquet's page-reading interfaces; readers that recognise it show its message to whoever asked.
 */
public final class PageDecodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String codec;

    PageDecodeException(String codec, String why) {
        this(codec, why, null);
    }

    PageDecodeException(String codec, String why, Throwable cause) {
        super("a " + codec + " Parquet page cannot be decoded: " + why, cause);
        this.codec = codec;
    }

    private PageDecodeException(PageDecodeException e, String file) {
        super(e.getMessage() + " [file " + file + "]", e.getCause());
        this.codec = e.codec;
    }

    /** The same failure naming the Parquet file it was read from. */
    PageDecodeException inFile(String file) {
        return new PageDecodeException(this, file);
    }

    /** The codec's Parquet name ({@code LZ4}, {@code LZ4_RAW}). */
    public String codec() {
        return codec;
    }
}
