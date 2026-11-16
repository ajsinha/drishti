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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads a pack archive: a {@code .tar.gz} made by {@code drishti.py pack bundle|make} (a folder named after the pack
 * holding {@code MANIFEST.json}, see tools/packbundle.py) or a {@code .zip} (the registry's format: {@code pack.yaml} at the
 * root, or one folder holding it). Extraction refuses everything that could leave the staging folder or exhaust the disk:
 * absolute paths, {@code ..}, links, devices, too many files, too many bytes. The manifest is then checked against the files.
 */
public final class PackArchive {

    /** A refusal with a one-line reason, shown to the administrator as it is. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Refused(String message) {
            super(message);
        }
    }

    /** What an archive unpacked to: the pack folder and its manifest (null when it has none). */
    public record Extracted(Path root, JsonNode manifest, int files, long bytes) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String MANIFEST = "MANIFEST.json";
    private static final int MANIFEST_FORMAT = 1;

    private final long maxBytes;
    private final int maxFiles;

    public PackArchive(long maxUnpackedBytes, int maxFiles) {
        this.maxBytes = maxUnpackedBytes;
        this.maxFiles = maxFiles;
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Unpacks {@code data} into {@code dest} (an empty folder) and finds the pack folder in it. */
    public Extracted extract(byte[] data, Path dest) throws IOException {
        Counter c = new Counter();
        if (data.length > 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b) {
            try (InputStream in = new BufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(data)))) {
                untar(in, dest, c);
            } catch (java.util.zip.ZipException | EOFException e) {
                throw new Refused("not a readable .tar.gz archive (" + e.getMessage() + ")");
            }
        } else if (data.length > 3 && data[0] == 'P' && data[1] == 'K') {
            try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data))) {
                unzip(zin, dest, c);
            } catch (java.util.zip.ZipException e) {
                throw new Refused("not a readable .zip archive (" + e.getMessage() + ")");
            }
        } else {
            throw new Refused("not a .tar.gz or .zip archive");
        }
        Path root = packRoot(dest);
        JsonNode manifest = null;
        Path mf = root.resolve(MANIFEST);
        if (Files.isRegularFile(mf)) {
            try {
                manifest = JSON.readTree(mf.toFile());
            } catch (IOException e) {
                throw new Refused(MANIFEST + " is not valid JSON (" + e.getMessage() + ")");
            }
        }
        return new Extracted(root, manifest, c.files, c.bytes);
    }

    private static final class Counter {
        int files;
        long bytes;
    }

    private Path safe(Path dest, String name) {
        String n = name.replace('\\', '/');
        if (n.startsWith("/") || n.matches("^[A-Za-z]:.*") || n.contains("\0")) {
            throw new Refused("unsafe path in the archive: " + printable(name));
        }
        for (String part : n.split("/")) {
            if (part.equals("..")) {
                throw new Refused("unsafe path in the archive (leaves the pack folder): " + printable(name));
            }
        }
        Path out = dest.resolve(n).normalize();
        if (!out.startsWith(dest)) {
            throw new Refused("unsafe path in the archive (leaves the pack folder): " + printable(name));
        }
        return out;
    }

    private static String printable(String s) {
        String t = s.length() > 120 ? s.substring(0, 120) + "..." : s;
        return t.replaceAll("[\\p{Cntrl}]", "?");
    }

    private void file(Path out, InputStream in, long declared, Counter c) throws IOException {
        if (++c.files > maxFiles) {
            throw new Refused("the archive has more than " + maxFiles + " files");
        }
        Files.createDirectories(out.getParent());
        try (var w = Files.newOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            long left = declared;
            while (left > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (r < 0) {
                    throw new Refused("the archive is cut short");
                }
                c.bytes += r;
                if (c.bytes > maxBytes) {
                    throw new Refused("the archive unpacks to more than " + maxBytes / 1024 / 1024 + " MB");
                }
                w.write(buf, 0, r);
                left -= r;
            }
        }
    }

    private void unzip(ZipInputStream zin, Path dest, Counter c) throws IOException {
        for (ZipEntry z; (z = zin.getNextEntry()) != null;) {
            Path out = safe(dest, z.getName());
            if (z.isDirectory()) {
                Files.createDirectories(out);
                continue;
            }
            if (++c.files > maxFiles) {
                throw new Refused("the archive has more than " + maxFiles + " files");
            }
            Files.createDirectories(out.getParent());
            try (var w = Files.newOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                for (int r; (r = zin.read(buf)) > 0;) {
                    c.bytes += r;
                    if (c.bytes > maxBytes) {
                        throw new Refused("the archive unpacks to more than " + maxBytes / 1024 / 1024 + " MB");
                    }
                    w.write(buf, 0, r);
                }
            }
        }
    }

    /** A tar reader for what tools/packbundle.py writes (ustar and pax headers) and what GNU tar writes for long names. */
    private void untar(InputStream in, Path dest, Counter c) throws IOException {
        String longName = null;
        Map<String, String> pax = Map.of();
        byte[] h = new byte[512];
        while (true) {
            if (!readBlock(in, h)) {
                return;
            }
            if (allZero(h)) {
                return;
            }
            char type = h[156] == 0 ? '0' : (char) h[156];
            long size = octal(h, 124, 12);
            String name = field(h, 0, 100);
            String prefix = field(h, 345, 155);
            if (!prefix.isEmpty() && new String(h, 257, 5, StandardCharsets.US_ASCII).equals("ustar")) {
                name = prefix + "/" + name;
            }
            if (type == 'x' || type == 'g' || type == 'L') {
                byte[] body = in.readNBytes((int) Math.min(size, 1 << 20));
                skipPad(in, size);
                if (type == 'x') {
                    pax = paxRecords(body);
                } else if (type == 'L') {
                    longName = new String(body, StandardCharsets.UTF_8).replace("\0", "");
                }
                continue;
            }
            if (longName != null) {
                name = longName;
            }
            if (pax.containsKey("path")) {
                name = pax.get("path");
            }
            if (pax.containsKey("size")) {
                size = Long.parseLong(pax.get("size"));
            }
            longName = null;
            pax = Map.of();
            if (type == '5') {
                Files.createDirectories(safe(dest, name));
                continue;
            }
            if (type != '0' && type != '7') {
                throw new Refused("the archive holds a link, device or special file (" + printable(name) + "); only plain files and folders are allowed");
            }
            file(safe(dest, name), in, size, c);
            skipPad(in, size);
        }
    }

    private static boolean readBlock(InputStream in, byte[] b) throws IOException {
        int n = in.readNBytes(b, 0, b.length);
        if (n == 0) {
            return false;
        }
        if (n < b.length) {
            throw new Refused("the archive is cut short");
        }
        return true;
    }

    private static void skipPad(InputStream in, long size) throws IOException {
        in.skipNBytes((512 - size % 512) % 512);
    }

    private static boolean allZero(byte[] b) {
        for (byte x : b) {
            if (x != 0) {
                return false;
            }
        }
        return true;
    }

    private static String field(byte[] h, int off, int len) {
        int end = off;
        while (end < off + len && h[end] != 0) {
            end++;
        }
        return new String(h, off, end - off, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] h, int off, int len) {
        if ((h[off] & 0x80) != 0) {
            throw new Refused("an entry in the archive is larger than 8 GB");
        }
        String s = field(h, off, len).trim();
        try {
            return s.isEmpty() ? 0 : Long.parseLong(s, 8);
        } catch (NumberFormatException e) {
            throw new Refused("not a tar archive (a header is damaged)");
        }
    }

    private static Map<String, String> paxRecords(byte[] body) {
        Map<String, String> out = new TreeMap<>();
        int i = 0;
        while (i < body.length) {
            int sp = i;
            while (sp < body.length && body[sp] != ' ') {
                sp++;
            }
            if (sp >= body.length) {
                break;
            }
            int len;
            try {
                len = Integer.parseInt(new String(body, i, sp - i, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                break;
            }
            if (len <= 0 || i + len > body.length) {
                break;
            }
            String rec = new String(body, sp + 1, i + len - sp - 2, StandardCharsets.UTF_8);
            int eq = rec.indexOf('=');
            if (eq > 0) {
                out.put(rec.substring(0, eq), rec.substring(eq + 1));
            }
            i += len;
        }
        return out;
    }

    private static Path packRoot(Path dest) throws IOException {
        if (Files.isRegularFile(dest.resolve("pack.yaml"))) {
            return dest;
        }
        try (Stream<Path> s = Files.list(dest)) {
            List<Path> dirs = s.filter(Files::isDirectory).toList();
            if (dirs.size() == 1 && Files.isRegularFile(dirs.get(0).resolve("pack.yaml"))) {
                return dirs.get(0);
            }
            throw new Refused(dirs.isEmpty() ? "the archive has no pack.yaml"
                    : "expected one folder holding pack.yaml (found " + dirs.stream().map(d -> d.getFileName().toString()).toList() + ")");
        }
    }

    /**
     * Every file the manifest lists exists with its size and SHA-256, nothing present is unlisted, and the manifest agrees
     * with pack.yaml (the same rules as {@code check_manifest} in tools/packbundle.py).
     */
    public static List<String> checkManifest(Path root, JsonNode manifest, String packName, String packVersion) throws IOException {
        List<String> problems = new ArrayList<>();
        if (manifest.path("format").asInt(MANIFEST_FORMAT) != MANIFEST_FORMAT) {
            problems.add("manifest format " + manifest.path("format").asText() + " is not understood by this server (expects " + MANIFEST_FORMAT + ")");
        }
        TreeSet<String> listed = new TreeSet<>();
        for (JsonNode f : manifest.path("files")) {
            String path = f.path("path").asText();
            listed.add(path);
            Path p = root.resolve(path).normalize();
            if (path.isEmpty() || path.startsWith("/") || path.contains("..") || !p.startsWith(root)) {
                problems.add("manifest lists an unsafe path: " + printable(path));
            } else if (!Files.isRegularFile(p)) {
                problems.add("missing file: " + path);
            } else {
                byte[] bytes = Files.readAllBytes(p);
                if (!sha256(bytes).equalsIgnoreCase(f.path("sha256").asText())) {
                    problems.add("checksum mismatch: " + path);
                } else if (f.has("size") && f.path("size").asLong() != bytes.length) {
                    problems.add("size mismatch: " + path);
                }
            }
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                String base = p.getFileName().toString();
                if (!rel.equals(MANIFEST) && !listed.contains(rel) && !base.equals(".DS_Store") && !base.equals("Thumbs.db")) {
                    problems.add("file not in the manifest: " + rel);
                }
            }
        }
        if (!packName.equals(manifest.path("pack").asText()) || !packVersion.equals(manifest.path("version").asText())) {
            problems.add("pack.yaml says " + packName + " " + packVersion + " but the manifest says " + manifest.path("pack").asText() + " "
                    + manifest.path("version").asText());
        }
        return problems;
    }

    /** {@code >=9.9.0} against the running version; empty when satisfied, unknown, or no requirement is stated. */
    public static String checkServerVersion(JsonNode manifest, String running) {
        String req = manifest == null ? "" : manifest.path("requiresServer").asText("");
        if (req.isBlank() || running == null || running.isBlank()) {
            return null;
        }
        if (compare(running, req.replaceFirst("^>=\\s*", "")) < 0) {
            return "the archive needs server " + req + " but this server is " + running;
        }
        return null;
    }

    /** Numeric parts up to the first '-', as tools/packbundle.py's version_tuple does. */
    public static int compare(String a, String b) {
        long[] x = parts(a);
        long[] y = parts(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? x[i] : 0;
            long q = i < y.length ? y[i] : 0;
            if (p != q) {
                return Long.compare(p, q);
            }
        }
        return 0;
    }

    private static long[] parts(String v) {
        String head = v.split("-")[0];
        var m = java.util.regex.Pattern.compile("\\d+").matcher(head);
        List<Long> out = new ArrayList<>();
        while (m.find() && out.size() < 3) {
            out.add(Long.parseLong(m.group()));
        }
        return out.isEmpty() ? new long[] {0} : out.stream().mapToLong(Long::longValue).toArray();
    }
}
