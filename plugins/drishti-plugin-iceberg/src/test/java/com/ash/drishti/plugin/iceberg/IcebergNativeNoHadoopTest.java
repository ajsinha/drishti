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
package com.ash.drishti.plugin.iceberg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * The goal itself: the Iceberg connector on the native engine (start, table discovery, single reads, columns,
 * type-ahead, reverse lookups, time travel, delete files, Health) runs in a class loader that refuses Hadoop's file
 * systems, its {@code Shell} (winutils) and its {@code Configuration}, and answers exactly as the library engine does
 * on the same lake. {@link NoHadoopProbe} runs the loader and maintenance the same way.
 */
public class IcebergNativeNoHadoopTest {                          // public: the probe is called reflectively

    static final Set<String> FORBIDDEN = Set.of("org.apache.hadoop.fs.FileSystem", "org.apache.hadoop.fs.LocalFileSystem",
            "org.apache.hadoop.fs.RawLocalFileSystem", "org.apache.hadoop.fs.ChecksumFileSystem", "org.apache.hadoop.fs.FilterFileSystem",
            "org.apache.hadoop.fs.FileContext", "org.apache.hadoop.fs.AbstractFileSystem", "org.apache.hadoop.util.Shell",
            "org.apache.hadoop.fs.s3a.S3AFileSystem", "org.apache.hadoop.conf.Configuration");

    /** Loads everything itself (the JDK from the platform), refusing {@link #FORBIDDEN}; records every Hadoop class asked for. */
    static final class Isolated extends URLClassLoader {
        final Set<String> refused = ConcurrentHashMap.newKeySet();
        final Set<String> hadoop = ConcurrentHashMap.newKeySet();

        Isolated(URL[] urls) {
            super(urls, ClassLoader.getPlatformClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("org.apache.hadoop.") && !name.startsWith("org.apache.hadoop.shaded.")) {
                hadoop.add(name);
            }
            String outer = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
            if (FORBIDDEN.contains(outer)) {
                refused.add(name);
                throw new ClassNotFoundException("forbidden with engine: native: " + name);
            }
            return super.loadClass(name, resolve);
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

    /** Every kind of read the connector does, described as text (nothing but JDK types crosses the loaders). */
    public static String probe(String root, String engine) throws Exception {
        IcebergSourcePlugin p = new IcebergSourcePlugin();
        p.start(DatedSourceContract.context(new java.util.HashMap<>(Map.of("root", root, "domain", "desk", "engine", engine,
                "mode.counterparty", "effective", "layout.trade.columns", "mtm,nettingSet"))));
        try {
            List<String> out = new ArrayList<>();
            out.add("kinds " + new java.util.TreeSet<>(p.manifest().kinds()));
            out.add("t1 " + p.fetch(EntityRef.of("trade", "T-1")).orElseThrow().data().get("mtm").asDouble());
            out.add("t1@D1 " + p.fetch(EntityRef.of("trade", "T-1"), AsOf.of(DatedSourceContract.D1)).map(d -> d.data().get("mtm").asDouble()));
            out.add("cp " + p.fetch(EntityRef.of("counterparty", "CP-1")).map(d -> d.data().toString()));
            var c = p.columns("trade", List.of("mtm", "nettingSet"), AsOf.LATEST).orElseThrow();
            List<String> cols = new ArrayList<>();
            for (int i = 0; i < c.size(); i++) {
                cols.add(c.ids()[i] + "=" + c.value("mtm", i) + "/" + c.value("nettingSet", i));
            }
            cols.sort(null);
            out.add("columns " + cols);
            out.add("reverse " + p.reverse(EntityRef.of("netting-set", "NS-1"), "trade", AsOf.LATEST));
            out.add("search " + p.search("trade", "T-", 10).stream().map(h -> h.ref().id()).sorted().toList());
            out.add("health " + p.health());
            return String.join("; ", out);
        } finally {
            p.close();
        }
    }

    static Path lake() throws Exception {
        Path root = Files.createTempDirectory("drishti-iceberg-nohadoop");
        Map<String, Boolean> trade = new LinkedHashMap<>();
        trade.put("mtm", true);
        trade.put("nettingSet", false);
        try (IcebergLake lake = IcebergLake.of(Map.of("root", root.toString(), "domain", "desk"))) {   // written outside, by the library
            IcebergSourcePluginTest.write(lake, DatedSourceContract.ROWS, Map.of("trade", trade));
        }
        return root;
    }

    @Test
    void theConnectorOnTheNativeEngineLoadsNoHadoopAndAnswersAsTheLibraryDoes() throws Exception {
        Path root = lake();
        String library = probe(root.toString(), "library");
        try (Isolated loader = isolated()) {
            Class<?> self = loader.loadClass(IcebergNativeNoHadoopTest.class.getName());
            String result;
            try {
                result = (String) self.getMethod("probe", String.class, String.class).invoke(null, root.toString(), "native");
            } catch (InvocationTargetException e) {
                throw new AssertionError("the connector failed on the native engine; refused: " + loader.refused, e.getCause());
            }
            assertThat(loader.refused).isEmpty();
            assertThat(result).isEqualTo(library).contains("kinds [counterparty, trade]", "health UP");
            assertThat(loader.hadoop).as("Hadoop classes loaded by native reads")
                    .noneMatch(n -> n.endsWith("FileSystem") || n.contains("Shell") || n.contains(".s3a.") || n.endsWith(".Configuration"));
        }
    }

    @Test
    void theGuardCatchesTheLibraryEngine() throws Exception {
        Path root = lake();
        try (Isolated loader = isolated()) {
            Class<?> self = loader.loadClass(IcebergNativeNoHadoopTest.class.getName());
            assertThatThrownBy(() -> self.getMethod("probe", String.class, String.class).invoke(null, root.toString(), "library"))
                    .isInstanceOf(InvocationTargetException.class);
            assertThat(loader.refused).isNotEmpty();
        }
    }
}
