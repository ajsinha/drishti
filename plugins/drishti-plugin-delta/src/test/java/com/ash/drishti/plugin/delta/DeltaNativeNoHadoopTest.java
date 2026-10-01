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
package com.ash.drishti.plugin.delta;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * The whole connector on the native engine (start, table discovery, single reads, columns, reverse lookups, Health)
 * loads no Hadoop file system, no {@code Shell} (winutils) and no Hadoop {@code Configuration}: it runs in a class
 * loader that refuses them. This is what makes it work on Windows without {@code winutils.exe}.
 */
public class DeltaNativeNoHadoopTest {                            // public: the probe is called reflectively

    static final Set<String> FORBIDDEN = Set.of("org.apache.hadoop.fs.FileSystem", "org.apache.hadoop.fs.LocalFileSystem",
            "org.apache.hadoop.fs.RawLocalFileSystem", "org.apache.hadoop.fs.ChecksumFileSystem", "org.apache.hadoop.fs.FileContext",
            "org.apache.hadoop.fs.AbstractFileSystem", "org.apache.hadoop.util.Shell", "org.apache.hadoop.fs.s3a.S3AFileSystem",
            "org.apache.hadoop.conf.Configuration");

    /** Loads everything itself (the JDK from the platform), refusing {@link #FORBIDDEN}. */
    static final class Isolated extends URLClassLoader {
        final Set<String> refused = ConcurrentHashMap.newKeySet();

        Isolated(URL[] urls) {
            super(urls, ClassLoader.getPlatformClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            String outer = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
            if (FORBIDDEN.contains(outer)) {
                refused.add(name);
                throw new ClassNotFoundException("forbidden with engine: native: " + name);
            }
            return super.loadClass(name, resolve);
        }
    }

    /** Runs inside the isolated loader. */
    public static String probe(String root) throws Exception {
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(DatedSourceContract.context(Map.of("root", root, "domain", "desk", "engine", "native",
                "layout.trade.columns", "mtm,book,nettingSet,counterparty.id")));
        try {
            List<String> out = new ArrayList<>();
            out.add("kinds " + p.manifest().kinds());
            out.add("mtm " + p.fetch(EntityRef.of("trade", "T-033")).orElseThrow().data().get("mtm").asDouble());
            out.add("columns " + p.columns("trade", List.of("mtm", "book"), AsOf.LATEST).orElseThrow().size());
            out.add("reverse " + p.reverse(EntityRef.of("netting-set", "NS-2"), "trade", AsOf.LATEST).size());
            out.add("health " + p.health());
            return String.join("; ", out);
        } finally {
            p.close();
        }
    }

    @Test
    void theConnectorOnTheNativeEngineLoadsNoHadoopFileSystem() throws Exception {
        Path root = DeltaDeletionVectorTest.lake("lake-layout");
        List<URL> urls = new ArrayList<>();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String entry : classpath.split(File.pathSeparator, -1)) {
            if (!entry.isBlank()) {
                urls.add(Path.of(entry).toUri().toURL());
            }
        }
        try (Isolated loader = new Isolated(urls.toArray(new URL[0]))) {
            Class<?> self = loader.loadClass(DeltaNativeNoHadoopTest.class.getName());
            String result;
            try {
                result = (String) self.getMethod("probe", String.class).invoke(null, root.toString());
            } catch (InvocationTargetException e) {
                throw new AssertionError("the connector failed on the native engine; refused: " + loader.refused, e.getCause());
            }
            assertThat(loader.refused).isEmpty();
            assertThat(result).contains("kinds [trade]", "mtm 13100.0", "columns 40", "reverse 15", "health UP (engine: native)");
        }
    }
}
