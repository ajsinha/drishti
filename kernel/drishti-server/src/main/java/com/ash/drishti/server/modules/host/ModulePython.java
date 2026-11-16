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
package com.ash.drishti.server.modules.host;

import com.ash.drishti.compute.ComputeService;
import com.ash.drishti.compute.PoolSettings;
import com.ash.drishti.module.runtime.Contributions;
import com.ash.drishti.module.runtime.LoadedModule;
import com.ash.drishti.module.runtime.PythonPart;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A module's Python compute in the kernel's worker pool (KERNEL_AND_MODULES.md section 3.3, {@code python:}): one implementation, the compute
 * service's own pools and workers, never a second worker mechanism. On load the module's vendored wheels are unpacked into
 * {@code <data>/compute/modules/<id>/site} (no network, pure Python only) and a pool {@code m-<id>} is added whose workers see the SDK, that
 * folder and the module's package folders and nothing else, so two modules cannot clash; the reactive connector or a compute request names
 * the pool as any other. On unload the pool is withdrawn (its workers stop) and the folder removed, in the unload's own steps, from the
 * registry of contributions. A module whose {@code compute} part is placed on other nodes only does not get the pool here.
 */
final class ModulePython {

    private static final Logger LOG = LoggerFactory.getLogger(ModulePython.class);

    private final Supplier<ComputeService> compute;
    private final Path dataDir;
    private final Predicate<LoadedModule> placedHere;

    ModulePython(Supplier<ComputeService> compute, Path dataDir, Predicate<LoadedModule> placedHere) {
        this.compute = compute;
        this.dataDir = dataDir;
        this.placedHere = placedHere == null ? m -> true : placedHere;
    }

    /** The folder a module's wheels are unpacked into. */
    Path site(String module) {
        return dataDir.resolve("compute").resolve("modules").resolve(module).resolve("site");
    }

    /** Installs the module's Python, if it ships any and its compute part is placed here. Returns the pool's name, or empty. */
    Optional<String> install(LoadedModule module, Contributions contributions) {
        Optional<PythonPart> part = module.manifest() == null ? Optional.empty() : module.manifest().python();
        if (part.isEmpty() || module.candidate() == null || module.candidate().dir() == null) {
            return Optional.empty();
        }
        ComputeService c = compute.get();
        if (c == null) {
            LOG.warn("module {}: it ships Python compute, but this server has no compute service", module.id());
            return Optional.empty();
        }
        if (!placedHere.test(module)) {
            LOG.info("module {}: its compute part is placed on other nodes; no Python pool here", module.id());
            return Optional.empty();
        }
        PythonPart py = part.get();
        Path dir = module.candidate().dir().toAbsolutePath().normalize();
        String id = module.id();
        String pool = PythonPart.poolName(id);
        Path site = site(id);
        List<String> path = new ArrayList<>();
        try {
            unpack(py.wheelFiles(dir), site);
        } catch (IOException e) {
            remove(site);
            throw new UncheckedIOException(id + ": its wheels did not unpack: " + e.getMessage(), e);
        }
        path.add(site.toString());
        py.path().forEach(f -> path.add(dir.resolve(f).normalize().toString()));
        contributions.add(id, Contributions.Surface.PYTHON_PATH, "python path " + site, null, () -> remove(site));
        PoolSettings settings = PoolSettings.forModule(pool, py.pool(), py.engines(), path);
        try {
            c.addModulePool(settings);
        } catch (RuntimeException e) {
            remove(site);
            throw e;
        }
        contributions.add(id, Contributions.Surface.PYTHON_ITEM, "python pool " + pool + " " + py.engines(), null, () -> c.removeModulePool(pool));
        LOG.info("module {}: python pool {} ({} engine{}, {} wheel{})", id, pool, py.engines().size(), py.engines().size() == 1 ? "" : "s",
                py.wheelFiles(dir).size(), py.wheelFiles(dir).size() == 1 ? "" : "s");
        return Optional.of(pool);
    }

    /** Unpacks the wheels (zip files) into {@code site}, refusing an entry that would land outside it. */
    static void unpack(List<Path> wheels, Path site) throws IOException {
        remove(site);
        Files.createDirectories(site);
        Path root = site.toAbsolutePath().normalize();
        for (Path w : wheels) {
            try (ZipFile z = new ZipFile(w.toFile())) {
                for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    Path out = root.resolve(e.getName()).normalize();
                    if (!out.startsWith(root)) {
                        throw new IOException(w.getFileName() + " has an entry outside its folder (" + e.getName() + ")");
                    }
                    if (e.isDirectory()) {
                        Files.createDirectories(out);
                        continue;
                    }
                    Files.createDirectories(out.getParent());
                    try (InputStream in = z.getInputStream(e)) {
                        Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    /** Removes the module's folder under the data folder (idempotent), and its parent when it is then empty. */
    static void remove(Path site) {
        if (!Files.exists(site)) {
            return;
        }
        try (Stream<Path> s = Files.walk(site)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
            Path parent = site.getParent();
            for (int up = 0; up < 2 && parent != null; up++, parent = parent.getParent()) {      // <id>, then modules/ when nothing else is in it
                try (Stream<Path> left = Files.list(parent)) {
                    if (left.findAny().isPresent()) {
                        break;
                    }
                }
                Files.deleteIfExists(parent);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not remove " + site + ": " + e.getMessage(), e);
        }
    }
}
