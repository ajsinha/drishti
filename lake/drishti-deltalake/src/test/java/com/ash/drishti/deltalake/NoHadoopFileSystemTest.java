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

import io.delta.kernel.Table;
import io.delta.kernel.defaults.engine.DefaultEngine;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.hadoop.conf.Configuration;
import org.junit.jupiter.api.Test;

/**
 * The point of the native engine: its reads never reach Hadoop's file systems or its {@code Shell} (which looks for
 * {@code winutils.exe} on Windows). The reads run in a class loader of their own that refuses to load
 * {@code FileSystem}, {@code LocalFileSystem}, {@code RawLocalFileSystem}, {@code FileContext}, {@code Shell} and the
 * S3A file system, and records every Hadoop class asked for: a read that touched any of them would fail here.
 */
class NoHadoopFileSystemTest {

    /** Classes whose loading means a Hadoop file system (or winutils) is in play. */
    static final Set<String> FORBIDDEN = Set.of("org.apache.hadoop.fs.FileSystem", "org.apache.hadoop.fs.LocalFileSystem",
            "org.apache.hadoop.fs.RawLocalFileSystem", "org.apache.hadoop.fs.ChecksumFileSystem", "org.apache.hadoop.fs.FilterFileSystem",
            "org.apache.hadoop.fs.FileContext", "org.apache.hadoop.fs.AbstractFileSystem", "org.apache.hadoop.util.Shell",
            "org.apache.hadoop.fs.s3a.S3AFileSystem", "org.apache.hadoop.conf.Configuration");

    /** Loads everything itself (the JDK from the platform), refusing {@link #FORBIDDEN}. */
    static final class Isolated extends URLClassLoader {
        final Set<String> hadoop = ConcurrentHashMap.newKeySet();
        final Set<String> refused = ConcurrentHashMap.newKeySet();

        Isolated(URL[] urls) {
            super(urls, ClassLoader.getPlatformClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("org.apache.hadoop.") && !name.startsWith("org.apache.hadoop.shaded.")) {
                hadoop.add(name);
                String outer = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
                if (FORBIDDEN.contains(outer)) {
                    refused.add(name);
                    throw new ClassNotFoundException("forbidden in a native read: " + name);
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    @Test
    void readsLoadNoHadoopFileSystemNorShell() throws Exception {
        Path lake = NativeEngineTest.copy(Path.of("src/test/resources/lake-layout"));
        Path checkpointed = NativeEngineTest.copy(Path.of("src/test/resources/lake-layout"));
        String cp = checkpointed.resolve("desk/trade").toString();
        var hadoop = DefaultEngine.create(new Configuration());                    // writing the fixture only, outside
        Table.forPath(hadoop, cp).checkpoint(hadoop, 5);
        for (int v = 0; v <= 4; v++) {
            Files.delete(checkpointed.resolve("desk/trade/_delta_log/%020d.json".formatted(v)));
        }
        try (Isolated loader = isolated()) {
            Class<?> probe = loader.loadClass(HadoopFreeProbe.class.getName());
            assertThat(probe.getClassLoader()).isSameAs(loader);
            Object result;
            try {
                result = probe.getMethod("run", String.class, String.class).invoke(null, lake.resolve("desk/trade").toString(), cp);
            } catch (InvocationTargetException e) {
                throw new AssertionError("a native read failed; Hadoop classes asked for: " + loader.hadoop + ", refused: " + loader.refused,
                        e.getCause());
            }
            assertThat(loader.refused).as("Hadoop file system classes a native read asked for").isEmpty();
            assertThat(result.toString()).contains("latest 5", "rows 80", "checkpointed 80", "one ", "tables 1");
            // parquet-hadoop's classes name a few Hadoop types (FileStatus, PathFilter, InputFormat) in their method
            // signatures and constants, so linking them loads those types; no file system, Shell or Configuration
            assertThat(loader.hadoop).as("Hadoop classes loaded by native reads")
                    .noneMatch(n -> n.endsWith("FileSystem") || n.contains("Shell") || n.contains(".s3a.") || n.endsWith(".Configuration"));
        }
    }

    @Test
    void theGuardCatchesTheHadoopEngine() throws Exception {
        Path lake = NativeEngineTest.copy(Path.of("src/test/resources/lake-layout"));
        try (Isolated loader = isolated()) {
            Class<?> probe = loader.loadClass(HadoopFreeProbe.class.getName());
            assertThatThrownBy(() -> probe.getMethod("runWithHadoop", String.class).invoke(null, lake.resolve("desk/trade").toString()))
                    .isInstanceOf(InvocationTargetException.class);
            assertThat(loader.refused).isNotEmpty();
        }
    }

    static Isolated isolated() throws Exception {
        List<URL> urls = new ArrayList<>();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String entry : classpath.split(File.pathSeparator, -1)) {
            if (!entry.isBlank()) {
                urls.add(Path.of(entry).toUri().toURL());
            }
        }
        return new Isolated(urls.toArray(new URL[0]));
    }
}
