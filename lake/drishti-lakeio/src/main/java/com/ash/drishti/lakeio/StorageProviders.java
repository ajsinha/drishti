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
package com.ash.drishti.lakeio;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.TreeMap;

/**
 * The {@link StorageProvider}s on the class path, by scheme: how the engines reach a storage other than the local disk and S3 (the
 * in-memory lake's {@code mem://}) without depending on the module that implements it. Found once, with {@link ServiceLoader}, on first use.
 */
public final class StorageProviders {

    private StorageProviders() {}

    /** Loaded on first use (the holder idiom: thread-safe, once). */
    private static final class Loaded {
        static final Map<String, StorageProvider> BY_SCHEME = load();

        private static Map<String, StorageProvider> load() {
            Map<String, StorageProvider> out = new TreeMap<>();
            for (StorageProvider p : ServiceLoader.load(StorageProvider.class, StorageProviders.class.getClassLoader())) {
                String scheme = p.scheme().toLowerCase(Locale.ROOT);
                if (scheme.equals("file") || scheme.startsWith("s3")) {
                    throw new IllegalStateException("storage provider " + p.getClass().getName() + " claims scheme " + scheme
                            + "://, which drishti-lakeio serves itself");
                }
                StorageProvider before = out.putIfAbsent(scheme, p);
                if (before != null) {
                    throw new IllegalStateException("two storage providers claim scheme " + scheme + "://: " + before.getClass().getName()
                            + " and " + p.getClass().getName());
                }
            }
            return Map.copyOf(out);
        }
    }

    /** The provider of {@code path}'s scheme, if one is on the class path. */
    public static Optional<StorageProvider> forPath(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(Loaded.BY_SCHEME.get(LocalPaths.scheme(path.trim())));
    }

    /** Whether a provider serves {@code path}'s scheme. */
    public static boolean supports(String path) {
        return forPath(path).isPresent();
    }

    /**
     * The storage of {@code path} from its scheme's provider.
     *
     * @throws UnsupportedOperationException when no provider serves the scheme
     */
    public static Storage storage(String path) {
        return forPath(path).orElseThrow(() -> new UnsupportedOperationException("no storage serves " + LocalPaths.scheme(path.trim())
                + ":// here (the in-memory lake's mem:// needs drishti-memlake on the class path)")).storage(path);
    }

    /** Whether {@code path} is under a memory-only scheme ({@link StorageProvider#memoryOnly()}): nothing of it may reach a disk. */
    public static boolean memoryOnly(String path) {
        return forPath(path).map(StorageProvider::memoryOnly).orElse(false);
    }
}
