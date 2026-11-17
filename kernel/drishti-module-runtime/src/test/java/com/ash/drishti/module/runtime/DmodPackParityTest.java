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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.module.tool.ModuleAssembler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parity of the two packagers while both exist (step I1 of i7a; deleted with {@link ModuleAssembler} in A2): every module folder of the
 * repository is packed by {@link ModuleAssembler} and by {@code tools/modulepack.py} from the same folder, the same stand-in jars and the
 * same kernel build, and the two packages must hold the same entry names in the same order, each with the same SHA-256, and the same
 * {@code META-INF/DMOD.MF}. Any difference fails the test, naming the module, the entry and both hashes. Zip bytes and entry times are not
 * compared here: the Java side stamps the build time into its entries, the Python side fixes them (1980-01-01); the Java side must match
 * before it is the reproducible packer, which A2 achieves by deleting it. Skipped where there is no Python, no tools folder or no modules
 * folder, unless {@code -Ddrishti.test.requires-python=true}: then each of those is a failure.
 */
class DmodPackParityTest {

    private static final String BUILD = "2.9.11+parity";

    @TempDir
    Path tmp;

    @Test
    void everyModuleIsPackedEntryForEntryTheSameByBothPackagers() throws Exception {
        String root = System.getProperty("drishti.repo.root");
        Path repo = root == null ? null : Path.of(root);
        Path packager = repo == null ? null : repo.resolve("tools/modulepack.py");
        needs(packager != null && Files.isRegularFile(packager), "tools/modulepack.py is not under drishti.repo.root");
        Path modules = repo.resolve("modules");
        needs(Files.isDirectory(modules), "there is no modules folder under drishti.repo.root");
        String python = python();
        needs(python != null, "no python with PyYAML on the path");

        List<Path> folders;
        try (Stream<Path> s = Files.list(modules)) {
            folders = s.filter(d -> Files.isRegularFile(d.resolve("module.yaml"))).sorted().toList();
        }
        assertThat(folders).as("module folders under " + modules).hasSizeGreaterThanOrEqualTo(12);
        List<String> differences = new ArrayList<>();
        for (Path folder : folders) {
            differences.addAll(compare(folder, packager, python));
        }
        assertThat(differences).as("the packagers differ").isEmpty();
    }

    private List<String> compare(Path folder, Path packager, String python) throws Exception {
        String name = folder.getFileName().toString();
        DmodManifest m = ModuleAssembler.manifest(folder);
        List<Path> jars = new ArrayList<>();
        if (m.java() != null) {                                       // stand-in jars, the same bytes for both: the build's are not under test
            Path dir = tmp.resolve("jars-" + name);
            Files.createDirectories(dir);
            for (String jar : m.java().classPath()) {
                if (!Files.isRegularFile(folder.resolve(jar))) {
                    Path f = dir.resolve(jar.substring(jar.lastIndexOf('/') + 1));
                    Files.writeString(f, "stand-in for " + name + "/" + jar, StandardCharsets.UTF_8);
                    jars.add(f);
                }
            }
        }
        Path javaOut = tmp.resolve("java-" + name);
        Path pyOut = tmp.resolve("py-" + name);
        Path javaOwn = jars.isEmpty() || !m.java().jars().get(0).equals(jars.get(0).getFileName().toString()) ? null : jars.get(0);
        ModuleAssembler.assemble(folder, javaOwn, jars.isEmpty() ? List.of() : jars.subList(javaOwn == null ? 0 : 1, jars.size()),
                tmp.resolve("stage-" + name), javaOut, BUILD);

        List<String> cmd = new ArrayList<>(List.of(python, packager.toString(), "pack", folder.toString(), "--out", pyOut.toString(),
                "--kernel-build", BUILD));
        for (Path j : jars) {
            cmd.add("--jar");
            cmd.add(j.toString());
        }
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        Process p = pb.start();
        String said = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor()).as(name + ": python packer said " + said).isZero();

        String file = Dmod.fileName(m.id(), m.version());
        Map<String, String> java = entries(javaOut.resolve(file));
        Map<String, String> py = entries(pyOut.resolve(file));
        List<String> out = new ArrayList<>();
        if (!List.copyOf(java.keySet()).equals(List.copyOf(py.keySet()))) {
            List<String> onlyJava = new ArrayList<>(java.keySet());
            onlyJava.removeAll(py.keySet());
            List<String> onlyPy = new ArrayList<>(py.keySet());
            onlyPy.removeAll(java.keySet());
            out.add(name + ": entry names or order differ; only in java " + onlyJava + ", only in python " + onlyPy
                    + (onlyJava.isEmpty() && onlyPy.isEmpty() ? " (the order differs)" : ""));
        }
        for (Map.Entry<String, String> e : java.entrySet()) {
            String other = py.get(e.getKey());
            if (other != null && !other.equals(e.getValue())) {
                out.add(name + ": " + e.getKey() + " java " + e.getValue() + " python " + other);
            }
        }
        return out;
    }

    /** Entry name to SHA-256 of its content, in package order. */
    private static Map<String, String> entries(Path dmod) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipFile z = new ZipFile(dmod.toFile())) {
            for (var en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                MessageDigest d = MessageDigest.getInstance("SHA-256");
                try (var in = z.getInputStream(e)) {
                    out.put(e.getName(), java.util.HexFormat.of().formatHex(d.digest(in.readAllBytes())));
                }
            }
        }
        return out;
    }

    private static void needs(boolean there, String why) {
        if (!there && Boolean.getBoolean("drishti.test.requires-python")) {
            fail("drishti.test.requires-python=true, and " + why);
        }
        assumeTrue(there, why);
    }

    private static String python() {
        for (String name : List.of("python3", "python")) {
            try {
                Process p = new ProcessBuilder(name, "-c", "import yaml").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor() == 0) {
                    return name;
                }
            } catch (IOException | InterruptedException e) {
                // try the next name
            }
        }
        return null;
    }
}
