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
    private static final java.util.concurrent.locks.ReentrantLock LOCK = new java.util.concurrent.locks.ReentrantLock();

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
            LOCK.lock();
            try {
                if (instance == null) {
                    instance = new SharedLib(folder(), SharedLib.class.getClassLoader());
                }
                s = instance;
            } finally {
                LOCK.unlock();
            }
        }
        return s;
    }

    /** The lib of a folder (the server's start, from its settings): the process's one, replaced when the folder differs. */
    public static SharedLib open(Path folder) {
        Path f = folder.toAbsolutePath().normalize();
        LOCK.lock();
        try {
            if (instance == null || !instance.folder.equals(f)) {
                instance = new SharedLib(f, SharedLib.class.getClassLoader());
            }
            return instance;
        } finally {
            LOCK.unlock();
        }
    }

    private static volatile List<String> moved = List.of();

    /**
     * The one-time move of the two older ways into the lib folder (no dual machinery): the jars of each old folder ({@code external_lib/java},
     * once on the launcher's class path; {@code drishti.sources.plugin-dir}, connector jars in their own class loaders) are moved into
     * {@code lib}, and {@code lib/moved-<date-time>.bak} lists what moved from where. Called at start before the lib is opened; a folder
     * that does not exist or holds no jar moves nothing. Returns (and keeps for Health) one note per folder that moved something and one
     * per old setting still named. A jar of the same name already in {@code lib} is left where it was and named in the note.
     *
     * @param old each old folder by what it was ({@code "external_lib/java"}, {@code "drishti.sources.plugin-dir"})
     * @param deprecated the old settings still set, by name to the note Health shows
     */
    public static List<String> migrate(Path lib, Map<String, Path> old, Map<String, String> deprecated) {
        List<String> notes = new ArrayList<>();
        Path target = lib.toAbsolutePath().normalize();
        StringBuilder listing = new StringBuilder();
        for (Map.Entry<String, Path> e : old.entrySet()) {
            Path from = e.getValue() == null ? null : e.getValue().toAbsolutePath().normalize();
            if (from == null || from.equals(target) || !Files.isDirectory(from)) {
                continue;
            }
            List<String> done = new ArrayList<>();
            List<String> left = new ArrayList<>();
            try (Stream<Path> s = Files.list(from)) {
                for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".jar") && Files.isRegularFile(p)).sorted().toList()) {
                    Path to = target.resolve(f.getFileName().toString());
                    if (Files.exists(to)) {
                        left.add(f.getFileName().toString());
                        continue;
                    }
                    Files.createDirectories(target);
                    Files.move(f, to);
                    done.add(f.getFileName().toString());
                    listing.append(f).append(" -> ").append(to).append('\n');
                }
            } catch (IOException ex) {
                notes.add(e.getKey() + " (" + from + "): not moved: " + ex.getMessage());
                continue;
            }
            if (!done.isEmpty() || !left.isEmpty()) {
                notes.add(e.getKey() + " (" + from + "): " + done.size() + " jar(s) moved into " + target + (left.isEmpty() ? ""
                        : "; left there, a jar of that name is in lib already: " + left) + ". The folder is no longer read: delete it.");
            }
        }
        if (!listing.isEmpty()) {
            try {
                Path bak = target.resolve("moved-" + java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".bak");
                Files.writeString(bak, "# jars moved into the lib folder (drishti.lib.path) from the older folders, at the first start that found them\n"
                        + listing, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException ex) {
                notes.add("the listing of what moved was not written: " + ex.getMessage());
            }
        }
        deprecated.forEach((k, v) -> notes.add(k + " is deprecated and ignored: " + v));
        notes.forEach(JUL::warning);
        moved = List.copyOf(notes);
        return moved;
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
        out.put("moved", moved);                     // the one-time move of external_lib/java and drishti.sources.plugin-dir, and old settings
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
