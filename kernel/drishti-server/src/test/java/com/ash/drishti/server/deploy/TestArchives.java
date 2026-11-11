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
package com.ash.drishti.server.deploy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds pack archives for tests: a .tar.gz laid out as tools/packbundle.py does (folder, MANIFEST.json), or a .zip. */
public final class TestArchives {

    private TestArchives() {}

    /** A pack folder's files: path to text. */
    public static Map<String, String> pack(String name, String version, String extra) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("pack.yaml", "pack: " + name + "\nversion: " + version + "\ntitle: " + name + "\n" + extra);
        return m;
    }

    /** MANIFEST.json for the files (format 1, sha256 and size per file). */
    public static String manifest(String name, String version, String requiresServer, Map<String, String> files) {
        StringBuilder sb = new StringBuilder("{\"format\":1,\"pack\":\"").append(name).append("\",\"version\":\"").append(version).append("\",");
        if (requiresServer != null) {
            sb.append("\"requiresServer\":\"").append(requiresServer).append("\",");
        }
        sb.append("\"files\":[");
        boolean first = true;
        for (Map.Entry<String, String> e : files.entrySet()) {
            byte[] b = e.getValue().getBytes(StandardCharsets.UTF_8);
            sb.append(first ? "" : ",").append("{\"path\":\"").append(e.getKey()).append("\",\"sha256\":\"").append(PackArchive.sha256(b)).append("\",\"size\":").append(b.length).append('}');
            first = false;
        }
        return sb.append("]}").toString();
    }

    /** A bundle: {@code <name>/MANIFEST.json} first, then every file; the manifest lists the files as given (pass different text to tamper). */
    public static byte[] tarGz(String name, String version, String requiresServer, Map<String, String> files, Map<String, String> actual) {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("MANIFEST.json", manifest(name, version, requiresServer, files));
        all.putAll(actual);
        Map<String, String> prefixed = new LinkedHashMap<>();
        all.forEach((k, v) -> prefixed.put(name + "/" + k, v));
        return tarGz(prefixed);
    }

    public static byte[] bundle(String name, String version, Map<String, String> files) {
        return tarGz(name, version, null, files, files);
    }

    /** A tar.gz of exactly these entries (name to text); a name ending in '/' is a folder. */
    public static byte[] tarGz(Map<String, String> entries) {
        return gz(tar(entries, '0'));
    }

    /** A tar.gz with one entry of the given type flag ('2' symbolic link, '1' hard link) pointing at {@code target}. */
    public static byte[] tarGzLink(String name, char type, String target) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header(name, 0, type, target));
        out.writeBytes(new byte[1024]);
        return gz(out.toByteArray());
    }

    private static byte[] gz(byte[] raw) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream g = new GZIPOutputStream(out)) {
                g.write(raw);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] tar(Map<String, String> entries, char type) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            byte[] data = e.getValue().getBytes(StandardCharsets.UTF_8);
            boolean dir = e.getKey().endsWith("/");
            out.writeBytes(header(e.getKey(), dir ? 0 : data.length, dir ? '5' : type, ""));
            if (!dir) {
                out.writeBytes(data);
                out.writeBytes(new byte[(512 - data.length % 512) % 512]);
            }
        }
        out.writeBytes(new byte[1024]);
        return out.toByteArray();
    }

    private static byte[] header(String name, long size, char type, String link) {
        byte[] h = new byte[512];
        put(h, 0, 100, name);
        put(h, 100, 8, "0000644");
        put(h, 108, 8, "0000000");
        put(h, 116, 8, "0000000");
        put(h, 124, 12, String.format("%011o", size));
        put(h, 136, 12, "00000000000");
        java.util.Arrays.fill(h, 148, 156, (byte) ' ');
        h[156] = (byte) type;
        put(h, 157, 100, link);
        put(h, 257, 6, "ustar");
        put(h, 263, 2, "00");
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        put(h, 148, 7, String.format("%06o", sum));
        h[155] = ' ';
        return h;
    }

    private static void put(byte[] h, int off, int len, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(b, 0, h, off, Math.min(len, b.length));
    }

    /** A zip with the given entries. */
    public static byte[] zip(Map<String, String> entries) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream z = new ZipOutputStream(out)) {
                for (Map.Entry<String, String> e : entries.entrySet()) {
                    z.putNextEntry(new ZipEntry(e.getKey()));
                    z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                    z.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
