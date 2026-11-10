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
package com.ash.drishti.common;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The shared library folder, as Tomcat's {@code lib/} (docs/admin/CONFIGURATION.md, {@code drishti.lib.path}; default
 * {@code <data folder>/lib}): every jar in it is loaded once, at start, into one "lib" class loader under the kernel's. Connectors (source
 * plugins found by the {@link ServiceLoader}), JDBC drivers and modules see its classes; it never replaces a kernel class (the kernel's
 * loader is asked first). A change to the folder takes effect at the next start. Health and Admin → Modules list each jar with its SHA-256,
 * marked {@code lib}: code the site added beside the product.
 */
public final class SharedLib {

    /** One jar of the folder. */
    public record Jar(String name, long bytes, String sha256) {
    }

    private static final java.util.logging.Logger JUL = java.util.logging.Logger.getLogger(SharedLib.class.getName());
    private static volatile SharedLib instance;

    private final Path folder;
    private final List<Jar> jars;
    private final ClassLoader loader;

    private SharedLib(Path folder, ClassLoader parent) {
        this.folder = folder;
        List<Jar> found = new ArrayList<>();
        List<URL> urls = new ArrayList<>();
        if (Files.isDirectory(folder)) {
            try (Stream<Path> s = Files.list(folder)) {
                for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".jar") && Files.isRegularFile(p)).sorted().toList()) {
                    found.add(new Jar(f.getFileName().toString(), Files.size(f), sha256(f)));
                    urls.add(f.toUri().toURL());
                }
            } catch (IOException e) {
                JUL.warning("the lib folder " + folder + " does not read: " + e.getMessage());
            }
        }
        this.jars = List.copyOf(found);
        this.loader = urls.isEmpty() ? parent : new URLClassLoader("drishti-lib", urls.toArray(URL[]::new), parent);
        if (!urls.isEmpty()) {
            registerDrivers();
        }
    }

    /** The folder of the settings: {@code drishti.lib.path} (system property or {@code DRISHTI_LIB_PATH}), else {@code <data folder>/lib}. */
    public static Path folder() {
        String p = System.getProperty("drishti.lib.path");
        if (p == null || p.isBlank()) {
            p = System.getenv("DRISHTI_LIB_PATH");
        }
        if (p == null || p.isBlank()) {
            String data = System.getProperty("drishti.data.dir");
            if (data == null || data.isBlank()) {
                data = System.getenv("DRISHTI_DATA_DIR");
            }
            return Path.of(data == null || data.isBlank() ? "./data" : data, "lib").toAbsolutePath().normalize();
        }
        return Path.of(p).toAbsolutePath().normalize();
    }

    /** The one lib of this process, read at first use from {@link #folder()}. */
    public static SharedLib get() {
        SharedLib s = instance;
        if (s == null) {
            synchronized (SharedLib.class) {
                if (instance == null) {
                    instance = new SharedLib(folder(), SharedLib.class.getClassLoader());
                }
                s = instance;
            }
        }
        return s;
    }

    /** The lib of a folder (the server's start, from its settings): the process's one, replaced when the folder differs. */
    public static synchronized SharedLib open(Path folder) {
        Path f = folder.toAbsolutePath().normalize();
        if (instance == null || !instance.folder.equals(f)) {
            instance = new SharedLib(f, SharedLib.class.getClassLoader());
        }
        return instance;
    }

    /** The lib class loader (the kernel's own loader when the folder holds no jar). */
    public ClassLoader loader() {
        return loader;
    }

    /** True when the folder held at least one jar. */
    public boolean any() {
        return !jars.isEmpty();
    }

    /** The jars, by name. */
    public List<Jar> jars() {
        return jars;
    }

    /** The lib's own objects of a service type (its jars' {@code META-INF/services} entries), none of the kernel's. */
    public <T> List<T> services(Class<T> type) {
        List<T> out = new ArrayList<>();
        if (!any()) {
            return out;
        }
        for (ServiceLoader.Provider<T> p : ServiceLoader.load(type, loader).stream().toList()) {
            if (p.type().getClassLoader() == loader) {
                out.add(p.get());
            }
        }
        return out;
    }

    /** What Health and Admin → Modules show: the folder and each jar with its size and SHA-256, marked {@code lib}. */
    public Map<String, Object> info() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("folder", folder.toString());
        List<Map<String, Object>> list = new ArrayList<>();
        for (Jar j : jars) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", j.name());
            m.put("bytes", j.bytes());
            m.put("sha256", j.sha256());
            m.put("mark", "lib");
            list.add(m);
        }
        out.put("jars", list);
        out.put("note", "loaded at start; a change takes effect at the next restart");
        return out;
    }

    /** The lib's JDBC drivers, registered with the {@link DriverManager} through a shim (it accepts only drivers its caller's loader sees). */
    private void registerDrivers() {
        for (Driver d : services(Driver.class)) {
            try {
                DriverManager.registerDriver(new Shim(d));
            } catch (SQLException e) {
                JUL.warning("the lib's JDBC driver " + d.getClass().getName() + " did not register: " + e.getMessage());
            }
        }
    }

    private static String sha256(Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A JDBC driver of the lib, as the kernel's loader sees it. */
    private record Shim(Driver driver) implements Driver {

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            return driver.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return driver.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return driver.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return driver.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return driver.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return driver.jdbcCompliant();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return driver.getParentLogger();
        }
    }
}
