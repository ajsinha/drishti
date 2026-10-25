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
package com.ash.drishti.compute.approval;

/**
 * One thing an administrator approves (docs/architecture/PYTHON_COMPUTE.md section 6): a Python package at a version with its hashes from a
 * source, a Java library with its sha256, or a plugin. A new package, version, hash or source is a new item and needs its own approval;
 * removing one needs none.
 *
 * @param kind {@code python}, {@code java} or {@code plugin}
 * @param name the package's normalised name, {@code group:artifact[:classifier]}, or the plugin's name
 * @param version its version (empty for a plugin)
 * @param hash its sha256 (a lock may list several, comma-separated; empty for a plugin)
 * @param source where it comes from ({@code shipped}, a named index, {@code bank-maven}, {@code installed})
 * @param nativeCode it carries native code
 */
public record ApprovalItem(String kind, String name, String version, String hash, String source, boolean nativeCode) {

    /** The identity of the item: every field that, changed, needs a new approval. */
    public String key() {
        return kind + ":" + name + ":" + version + ":" + hash + ":" + source;
    }
}
