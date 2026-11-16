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
package com.ash.drishti.module.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The {@code .dmod} package (KERNEL_AND_MODULES.md section 3.1): a zip with {@code module.yaml} first and stored, and
 * {@code META-INF/DMOD.MF}, the hash list: one line per entry, {@code <sha-256 hex>  <path>} (the {@code sha256sum} shape), every file of
 * the package except the trust files themselves. Packs a folder, unpacks a package (refusing a path that leaves the folder), and verifies
 * an unpacked folder and the package kept beside it against the list (V2: a file not listed, missing or changed refuses the module).
 * {@code DMOD.SIG} (Ed25519 over the list) and {@code publisher.pem} are checked from release j; until then an absent signature is accepted
 * with a Health note.
 */
public final class Dmod {

    public static final String HASHES = "META-INF/DMOD.MF";
    public static final String SIGNATURE = "META-INF/DMOD.SIG";
    public static final String CERTIFICATE = "META-INF/publisher.pem";

    private Dmod() {
    }

    /** The file name of a package: {@code <id>-<version>.dmod}. */
    public static String fileName(String id, String version) {
        return id + "-" + version + ".dmod";
    }

    /** Writes the hash list of a module folder, then the package; returns the package. */
    public static Path pack(Path dir, Path out) {
        try {
            Map<String, String> hashes = hashFolder(dir);
            StringBuilder mf = new StringBuilder("# DMOD.MF format 1: sha-256 of every entry\n");
            hashes.forEach((p, h) -> mf.append(h).append("  ").append(p).append('\n'));
            Files.createDirectories(dir.resolve("META-INF"));
            Files.writeString(dir.resolve(HASHES), mf, StandardCharsets.UTF_8);
            Files.createDirectories(out.toAbsolutePath().getParent());
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
                byte[] manifest = Files.readAllBytes(dir.resolve(DmodManifest.FILE));
                ZipEntry first = new ZipEntry(DmodManifest.FILE);
                first.setMethod(ZipEntry.STORED);
                first.setSize(manifest.length);
                CRC32 crc = new CRC32();
                crc.update(manifest);
                first.setCrc(crc.getValue());
                zip.putNextEntry(first);
                zip.write(manifest);
                zip.closeEntry();
                for (String p : files(dir)) {
                    if (!DmodManifest.FILE.equals(p)) {
                        zip.putNextEntry(new ZipEntry(p));
                        Files.copy(dir.resolve(p), zip);
                        zip.closeEntry();
                    }
                }
                for (String trust : List.of(HASHES, SIGNATURE, CERTIFICATE)) {
                    if (Files.isRegularFile(dir.resolve(trust))) {
                        zip.putNextEntry(new ZipEntry(trust));
                        Files.copy(dir.resolve(trust), zip);
                        zip.closeEntry();
                    }
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Unpacks a package into an empty folder; refuses an entry whose path leaves it. */
    public static void unpack(Path dmod, Path dir) {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(dmod))) {
            Path root = dir.toAbsolutePath().normalize();
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                Path target = root.resolve(e.getName()).normalize();
                if (!target.startsWith(root) || e.getName().contains("\\")) {
                    throw new ModuleRefusedException("V2", dmod.getFileName() + ": entry " + e.getName() + " leaves the module folder");
                }
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Verifies an unpacked module folder against its hash list and, when present, the package beside it; returns Health notes (an absent
     * signature). Refuses (V2) a file missing, changed or not listed.
     */
    public static List<String> verify(Path dir, Path dmod, String label) {
        Map<String, String> listed = readHashes(dir, label);
        Map<String, String> actual;
        try {
            actual = hashFolder(dir);
        } catch (IOException e) {
            throw new ModuleRefusedException("V2", label + ": the module folder does not read: " + e.getMessage(), e);
        }
        compare(listed, actual, label, "");
        if (dmod != null && Files.isRegularFile(dmod)) {
            compare(listed, hashArchive(dmod, label), label, " in " + dmod.getFileName());
        }
        List<String> notes = new ArrayList<>();
        if (!Files.isRegularFile(dir.resolve(SIGNATURE))) {
            notes.add("unsigned: the hash list is verified; signatures are checked from release j");
        } else {
            notes.add("signature present: checked from release j (the hash list is verified)");
        }
        if (dmod == null || !Files.isRegularFile(dmod)) {
            notes.add("the original .dmod is not kept beside the module folder");
        }
        return notes;
    }

    private static void compare(Map<String, String> listed, Map<String, String> actual, String label, String where) {
        for (Map.Entry<String, String> e : actual.entrySet()) {
            String want = listed.get(e.getKey());
            if (want == null) {
                throw new ModuleRefusedException("V2", label + ": file " + e.getKey() + where + " is not in the hash list");
            }
            if (!want.equalsIgnoreCase(e.getValue())) {
                throw new ModuleRefusedException("V2", label + ": hash list does not match (file " + e.getKey() + where + " changed)");
            }
        }
        for (String p : listed.keySet()) {
            if (!actual.containsKey(p)) {
                throw new ModuleRefusedException("V2", label + ": file " + p + where + " is in the hash list but missing");
            }
        }
    }

    /** The hash list of an unpacked module. */
    public static Map<String, String> readHashes(Path dir, String label) {
        Path mf = dir.resolve(HASHES);
        if (!Files.isRegularFile(mf)) {
            throw new ModuleRefusedException("V2", label + ": " + HASHES + " (the hash list) is missing");
        }
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(mf, StandardCharsets.UTF_8)) {
                String l = line.strip();
                if (l.isEmpty() || l.startsWith("#")) {
                    continue;
                }
                int sp = l.indexOf(' ');
                if (sp != 64 || !l.substring(0, 64).matches("[0-9a-fA-F]{64}")) {
                    throw new ModuleRefusedException("V2", label + ": " + HASHES + " line is not `<sha-256>  <path>`: " + l);
                }
                out.put(l.substring(sp).strip(), l.substring(0, 64));
            }
        } catch (IOException e) {
            throw new ModuleRefusedException("V2", label + ": " + HASHES + " does not read", e);
        }
        return out;
    }

    /** The relative paths ({@code /}-separated, sorted) of every file of a module folder except the trust files. */
    public static List<String> files(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> !p.equals(HASHES) && !p.equals(SIGNATURE) && !p.equals(CERTIFICATE)).sorted().toList();
        }
    }

    private static Map<String, String> hashFolder(Path dir) throws IOException {
        Map<String, String> out = new TreeMap<>();
        for (String p : files(dir)) {
            try (InputStream in = Files.newInputStream(dir.resolve(p))) {
                out.put(p, sha256(in));
            }
        }
        return out;
    }

    private static Map<String, String> hashArchive(Path dmod, String label) {
        Map<String, String> out = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(dmod))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String n = e.getName();
                if (!e.isDirectory() && !n.equals(HASHES) && !n.equals(SIGNATURE) && !n.equals(CERTIFICATE)) {
                    out.put(n, sha256(zip));
                }
            }
        } catch (IOException e) {
            throw new ModuleRefusedException("V2", label + ": " + dmod.getFileName() + " does not read as a package", e);
        }
        return out;
    }

    /** The SHA-256 of a stream, in lower-case hex (the stream is read to its end, not closed). */
    public static String sha256(InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes a file, making its folder (a convenience for builders and tests). */
    public static void write(Path file, String text) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (OutputStream o = Files.newOutputStream(file)) {
                o.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
