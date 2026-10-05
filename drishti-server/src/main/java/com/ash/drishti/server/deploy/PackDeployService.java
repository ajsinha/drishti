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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.design.AutoDesigner;
import com.ash.drishti.engine.shape.ShapeService;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.server.cli.SutraCli;
import com.ash.drishti.server.registry.PackRegistryClient;
import com.ash.drishti.server.registry.RegistryProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;

/**
 * Deploys a pack from an archive: {@link #stage} unpacks and checks it (archive, signature, manifest and checksums,
 * required server version, Sutra lint and tests by the same checker as {@code sutra lint|test}, dependencies) and
 * previews the difference from the running version; {@link #deploy} then swaps it into
 * {@code drishti.packs.installed-dir}, keeping the version it replaces under {@code .previous/<pack>/<version>-<millis>} (the
 * layout the registry installer uses, so either can roll the other back); {@link #rollback} puts a kept version back.
 * Loading the files into the running server (the in-place restart with automatic rollback) is the controller's step.
 * The archive carries the pack only, never data. File changes are serialised by one lock.
 */
public final class PackDeployService {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");

    /** One check of an upload: {@code ok}, or a reason. {@code warn} marks an ok that deserves a look. */
    public record Check(String name, boolean ok, String detail, boolean warn) {
        static Check ok(String name, String detail) {
            return new Check(name, true, detail, false);
        }

        static Check warn(String name, String detail) {
            return new Check(name, true, detail, true);
        }

        static Check fail(String name, String detail) {
            return new Check(name, false, detail, false);
        }
    }

    private record Staged(String id, Path dir, Path root, String name, String version, String file, String sha256, String by, Instant expires,
            int breaking, boolean sameOrOlder) {}

    /** What a deploy did to the files: what to load, and how to put them back. */
    public record Deployed(String name, String version, String previous, boolean wasLoaded, Runnable undo) {}

    private final Path installed;
    private final Path packsDir;
    private final DeployProperties props;
    private final RegistryProperties registryProps;
    private final PackRegistry running;
    private final ObjectProvider<BuildProperties> build;
    private final SutraRegistry sutras;
    private final ViewPipeline pipeline;
    private final ShapeService shapes;
    private final AutoDesigner designer;
    private final JsonCodec codec;
    private final DeployHistory history;
    private final Map<String, Staged> staged = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public PackDeployService(Environment env, DeployProperties props, RegistryProperties registryProps, PackRegistry running,
            ObjectProvider<BuildProperties> build, SutraRegistry sutras, ViewPipeline pipeline, ShapeService shapes, AutoDesigner designer,
            JsonCodec codec) {
        this.installed = Path.of(env.getProperty("drishti.packs.installed-dir", "./data/packs/installed")).toAbsolutePath().normalize();
        this.packsDir = Path.of(env.getProperty("drishti.packs.dir", "./packs")).toAbsolutePath().normalize();
        this.props = props;
        this.registryProps = registryProps;
        this.running = running;
        this.build = build;
        this.sutras = sutras;
        this.pipeline = pipeline;
        this.shapes = shapes;
        this.designer = designer;
        this.codec = codec;
        this.history = new DeployHistory(Path.of(props.historyFile()));
    }

    public DeployHistory history() {
        return history;
    }

    public DeployProperties properties() {
        return props;
    }

    // -- stage: verify and preview ----------------------------------------------------------------------------------

    /** Unpacks and checks an uploaded archive; the answer says what passed, what the pack changes, and (when everything passed) an {@code uploadId}. */
    public Map<String, Object> stage(byte[] data, String filename, String expectedSha, String signature, String publisher, String user) {
        expire();
        List<Check> checks = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String sha = PackArchive.sha256(data);
        out.put("file", filename == null ? "" : filename);
        out.put("size", data.length);
        out.put("sha256", sha);
        checks.add(Check.ok("archive", data.length + " bytes, sha256 " + sha.substring(0, 16) + "..."));
        Path dir = installed.resolve(".uploads").resolve(id);
        boolean keep = false;
        try {
            if (expectedSha != null && !expectedSha.isBlank() && !expectedSha.trim().equalsIgnoreCase(sha)) {
                checks.add(Check.fail("checksum", "the upload's sha256 is " + sha + ", expected " + expectedSha.trim() + " (the file changed in transit)"));
                return finish(out, checks, null, null);
            }
            if (expectedSha != null && !expectedSha.isBlank()) {
                checks.add(Check.ok("checksum", "matches the sha256 you gave"));
            }
            Check sig = signature(data, signature, publisher);
            checks.add(sig);
            Files.createDirectories(dir.resolve("pack"));
            PackArchive.Extracted ex;
            try {
                ex = new PackArchive(props.maxUnpackedMb() * 1024L * 1024, props.maxFiles()).extract(data, dir.resolve("pack"));
            } catch (PackArchive.Refused r) {
                checks.add(Check.fail("unpack", r.getMessage()));
                return finish(out, checks, null, null);
            }
            checks.add(Check.ok("unpack", ex.files() + " files, " + ex.bytes() / 1024 + " KB; no path leaves the pack folder, no links"));
            Map<String, Object> meta;
            try {
                meta = PackDiffer.load(ex.root()).meta();
            } catch (IOException | RuntimeException e) {
                checks.add(Check.fail("pack.yaml", "cannot be read: " + e.getMessage()));
                return finish(out, checks, null, null);
            }
            String name = String.valueOf(meta.get("pack"));
            String version = String.valueOf(meta.get("version"));
            if (!NAME.matcher(name).matches() || !VERSION.matcher(version).matches()) {
                checks.add(Check.fail("pack.yaml", "needs a plain 'pack' name and a 'version' (got '" + name + "', '" + version + "')"));
                return finish(out, checks, null, null);
            }
            out.put("pack", name);
            out.put("version", version);
            out.put("title", meta.get("title"));
            out.put("extends", meta.get("extends") instanceof List<?> l ? l : List.of());
            out.put("kinds", meta.get("kinds") instanceof List<?> l ? l : List.of());
            checks.add(Check.ok("pack.yaml", name + " " + version));
            manifestCheck(ex, name, version, checks);
            if (checks.stream().anyMatch(c -> !c.ok())) {
                return finish(out, checks, null, null);
            }
            lintAndTest(ex.root(), checks);
            dependencies(meta, checks);
            Map<String, Object> preview = preview(ex.root(), name, version);
            boolean ok = checks.stream().allMatch(Check::ok);
            if (ok) {
                int breaking = ((Number) ((Map<?, ?>) preview.get("counts")).get("breaking")).intValue();
                boolean old = !"newer".equals(preview.get("versionOrder")) && !"new".equals(preview.get("state"));
                staged.put(id, new Staged(id, dir, ex.root(), name, version, filename == null ? "" : filename, sha, user,
                        Instant.now().plusSeconds(props.stagingMinutes() * 60L), breaking, old));
                out.put("uploadId", id);
                out.put("expiresAt", staged.get(id).expires().toString());
                keep = true;
            }
            return finish(out, checks, preview, ok);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (!keep) {
                deleteQuietly(dir);
            }
        }
    }

    private Map<String, Object> finish(Map<String, Object> out, List<Check> checks, Map<String, Object> preview, Boolean ok) {
        out.put("ok", ok != null ? ok : checks.stream().allMatch(Check::ok));
        out.put("checks", checks);
        if (preview != null) {
            out.put("preview", preview);
        }
        return out;
    }

    private Check signature(byte[] data, String signature, String publisher) {
        if (signature == null || signature.isBlank()) {
            return props.requireSignature() ? Check.fail("signature", "this server accepts signed archives only (drishti.packs.deploy.require-signature)")
                    : Check.warn("signature", "not signed; sign it with a publisher key listed in drishti.packs.registry.trusted-keys to prove where it came from");
        }
        String key = publisher == null ? null : registryProps.trustedKeys().get(publisher.trim());
        if (key == null) {
            return Check.fail("signature", "publisher '" + publisher + "' is not trusted on this server (drishti.packs.registry.trusted-keys)");
        }
        return PackRegistryClient.verify(data, signature, key) ? Check.ok("signature", "signed by " + publisher.trim())
                : Check.fail("signature", "the signature does not verify with " + publisher.trim() + "'s key");
    }

    private void manifestCheck(PackArchive.Extracted ex, String name, String version, List<Check> checks) throws IOException {
        JsonNode man = ex.manifest();
        if (man == null) {
            checks.add(props.requireManifest() ? Check.fail("manifest", "no " + PackArchive.MANIFEST + ": build the archive with drishti.py pack bundle or pack make")
                    : Check.warn("manifest", "no " + PackArchive.MANIFEST + "; file checksums were not checked"));
            return;
        }
        List<String> problems = PackArchive.checkManifest(ex.root(), man, name, version);
        if (problems.isEmpty()) {
            checks.add(Check.ok("manifest", man.path("files").size() + " files listed; every checksum matches; nothing unlisted"));
        } else {
            checks.add(Check.fail("manifest", String.join("; ", problems.subList(0, Math.min(5, problems.size()))) + (problems.size() > 5 ? "; and " + (problems.size() - 5) + " more" : "")));
        }
        String running = build.getIfAvailable() == null ? null : build.getIfAvailable().getVersion();
        String why = PackArchive.checkServerVersion(man, running);
        checks.add(why != null ? Check.fail("server version", why)
                : Check.ok("server version", man.path("requiresServer").asText("").isBlank() ? "no minimum stated" : "needs " + man.path("requiresServer").asText() + ", this is " + running));
    }

    private void lintAndTest(Path root, List<Check> checks) {
        SutraCli.Services services = new SutraCli.Services(sutras, pipeline, shapes, designer, codec);
        for (String cmd : List.of("lint", "test")) {
            ByteArrayOutputStream so = new ByteArrayOutputStream();
            ByteArrayOutputStream se = new ByteArrayOutputStream();
            int code;
            try (PrintStream o = new PrintStream(so, true, StandardCharsets.UTF_8); PrintStream e = new PrintStream(se, true, StandardCharsets.UTF_8)) {
                code = new SutraCli(services, o, e).run(List.of(cmd, root.toString()));
            }
            String text = (so.toString(StandardCharsets.UTF_8) + se.toString(StandardCharsets.UTF_8)).strip();
            long ok = so.toString(StandardCharsets.UTF_8).lines().filter(l -> l.startsWith("ok") || l.startsWith("PASS")).count();
            if (code == 0) {
                checks.add(Check.ok("sutra " + cmd, ok > 0 ? ok + " Sutra file(s) passed" : "nothing to " + cmd));
            } else {
                List<String> bad = text.lines().filter(l -> l.startsWith("PROBLEM") || l.startsWith("FAIL") || l.contains("DRS-")).limit(6).toList();
                checks.add(Check.fail("sutra " + cmd, bad.isEmpty() ? "failed (exit " + code + ")" : String.join("; ", bad)));
            }
        }
    }

    private void dependencies(Map<String, Object> meta, List<Check> checks) {
        List<String> parents = new ArrayList<>();
        for (String key : List.of("extends", "requires")) {
            if (meta.get(key) instanceof List<?> l) {
                l.forEach(x -> parents.add(String.valueOf(x).trim()));
            }
        }
        List<String> missing = parents.stream().filter(p -> running.packs().stream().noneMatch(x -> x.name().equals(p))
                && !Files.isRegularFile(installed.resolve(p).resolve("pack.yaml")) && !Files.isRegularFile(packsDir.resolve(p).resolve("pack.yaml"))).toList();
        checks.add(missing.isEmpty() ? Check.ok("dependencies", parents.isEmpty() ? "extends no other pack" : "extends " + String.join(", ", parents) + ": available")
                : Check.fail("dependencies", "extends " + String.join(", ", missing) + ", which is neither loaded nor on disk; deploy that pack first"));
    }

    /** The difference from the running version (or the copy on disk when the pack is not loaded). */
    private Map<String, Object> preview(Path root, String name, String version) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        Path against = null;
        String where = null;
        String current = null;
        boolean loaded = false;
        for (Pack p : running.packs()) {
            if (p.name().equals(name)) {
                against = p.dir();
                current = p.version();
                loaded = true;
                where = "loaded";
            }
        }
        if (against == null) {
            for (Path d : List.of(installed, packsDir)) {
                if (Files.isRegularFile(d.resolve(name).resolve("pack.yaml"))) {
                    against = d.resolve(name);
                    where = d.equals(installed) ? "installed, not loaded" : "shipped, not loaded";
                    current = PackDiffer.load(against).meta().get("version") + "";
                    break;
                }
            }
        }
        out.put("pack", name);
        out.put("newVersion", version);
        if (against == null) {
            out.put("state", "new");
            out.put("running", null);
            out.put("counts", PackDiffer.counts(List.of()));
            out.put("findings", List.of());
            out.put("versionOrder", "new");
            return out;
        }
        List<PackDiffer.Finding> found = PackDiffer.diff(PackDiffer.load(against), PackDiffer.load(root));
        int order = PackArchive.compare(version, current);
        out.put("state", "upgrade");
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("version", current);
        run.put("loaded", loaded);
        run.put("where", where);
        out.put("running", run);
        out.put("versionOrder", order > 0 ? "newer" : order == 0 ? "same" : "older");
        out.put("counts", PackDiffer.counts(found));
        out.put("findings", found);
        return out;
    }

    // -- deploy / rollback --------------------------------------------------------------------------------------------

    /** Swaps a staged upload into the installed folder (the replaced version is kept). Does not load it. */
    public Deployed deploy(String uploadId, boolean acceptBreaking, String user) {
        expire();
        Staged s = staged.get(uploadId);
        if (s == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no such upload (it expired after " + props.stagingMinutes() + " minutes, or was deployed or discarded); upload it again");
        }
        if (s.breaking() > 0 && !acceptBreaking) {
            throw new DrishtiException(ErrorCode.PROPOSAL_CONFLICT, "this version has " + s.breaking() + " breaking change(s) (kinds or mnemonics removed or renamed); confirm with acceptBreaking=true after reading the preview");
        }
        lock.lock();
        try {
            Path target = installed.resolve(s.name());
            Path previousDir = null;
            String previousVersion = null;
            boolean wasLoaded = running.packs().stream().anyMatch(p -> p.name().equals(s.name()));
            if (Files.exists(target)) {
                previousVersion = versionOf(target);
                previousDir = keep(s.name(), previousVersion, target);
            } else {
                previousVersion = running.packs().stream().filter(p -> p.name().equals(s.name())).map(Pack::version).findFirst().orElse(null);
            }
            Files.createDirectories(installed);
            Files.move(s.root(), target, StandardCopyOption.ATOMIC_MOVE);
            staged.remove(uploadId);
            deleteQuietly(s.dir());
            prune(s.name());
            history.append("deploy", s.name(), s.version(), previousVersion, user, "from " + (s.file().isBlank() ? "an upload" : s.file()) + ", sha256 " + s.sha256(),
                    Map.of("sha256", s.sha256(), "file", s.file()));
            final Path restore = previousDir;
            final String pv = previousVersion;
            Runnable undo = () -> {
                lock.lock();
                try {
                    deleteQuietly(target);
                    if (restore != null && Files.isDirectory(restore)) {
                        Files.move(restore, target, StandardCopyOption.ATOMIC_MOVE);
                    }
                    history.append("reverted", s.name(), pv, s.version(), user, "the server could not use " + s.version() + "; the earlier files are back", null);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } finally {
                    lock.unlock();
                }
            };
            return new Deployed(s.name(), s.version(), previousVersion, wasLoaded, undo);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.unlock();
        }
    }

    /** Puts a kept version back ({@code version} null: the newest kept; "shipped": remove the installed copy so the shipped one shows). */
    public Deployed rollback(String name, String version, String user) {
        if (!NAME.matcher(name).matches()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "not a pack name");
        }
        lock.lock();
        try {
            Path target = installed.resolve(name);
            boolean toShipped = "shipped".equals(version);
            Path chosen = null;
            String chosenVersion = null;
            if (toShipped) {
                if (!Files.isRegularFile(packsDir.resolve(name).resolve("pack.yaml")) || !Files.isDirectory(target)) {
                    throw new DrishtiException(ErrorCode.BAD_REQUEST, "'" + name + "' has no shipped version to go back to");
                }
                chosenVersion = versionOf(packsDir.resolve(name));
            } else {
                List<Path> kept = keptDirs(name);
                for (int i = kept.size() - 1; i >= 0 && chosen == null; i--) {
                    if (version == null || version.equals(versionOfDir(kept.get(i)))) {
                        chosen = kept.get(i);
                    }
                }
                if (chosen == null) {
                    throw new DrishtiException(ErrorCode.BAD_REQUEST, "there is no earlier " + (version == null ? "" : version + " ") + "version of '" + name + "' to go back to");
                }
                chosenVersion = versionOfDir(chosen);
            }
            String replaced = Files.exists(target) ? versionOf(target) : null;
            Path replacedDir = Files.exists(target) ? keep(name, replaced, target) : null;
            if (chosen != null) {
                Files.createDirectories(installed);
                Files.move(chosen, target, StandardCopyOption.ATOMIC_MOVE);
            }
            history.append("rollback", name, chosenVersion, replaced, user, toShipped ? "back to the shipped version" : "to a kept version", null);
            final Path back = replacedDir;
            final Path used = chosen;
            final String cv = chosenVersion;
            final String rv = replaced;
            Runnable undo = () -> {
                lock.lock();
                try {
                    if (used != null && Files.isDirectory(target)) {
                        Files.move(target, used, StandardCopyOption.ATOMIC_MOVE);       // the kept version stays kept
                    } else {
                        deleteQuietly(target);
                    }
                    if (back != null && Files.isDirectory(back)) {
                        Files.move(back, target, StandardCopyOption.ATOMIC_MOVE);
                    }
                    history.append("reverted", name, rv, cv, user, "the server could not use " + cv + "; the files are back as they were", null);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } finally {
                    lock.unlock();
                }
            };
            return new Deployed(name, chosenVersion, replaced, running.packs().stream().anyMatch(p -> p.name().equals(name)), undo);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.unlock();
        }
    }

    /** Throws an upload away. */
    public boolean discard(String uploadId) {
        Staged s = staged.remove(uploadId);
        if (s != null) {
            deleteQuietly(s.dir());
        }
        return s != null;
    }

    /** The versions kept for a rollback, newest first; plus {@code shipped} when the installed copy hides a shipped one. */
    public List<Map<String, Object>> kept(String name) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Path> dirs = keptDirs(name);
        for (int i = dirs.size() - 1; i >= 0; i--) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", versionOfDir(dirs.get(i)));
            m.put("keptAt", Instant.ofEpochMilli(stamp(dirs.get(i))).toString());
            out.add(m);
        }
        if (Files.isDirectory(installed.resolve(name)) && Files.isRegularFile(packsDir.resolve(name).resolve("pack.yaml"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", "shipped");
            m.put("keptAt", null);
            m.put("shippedVersion", versionOf(packsDir.resolve(name)));
            out.add(m);
        }
        return out;
    }

    // -- helpers -----------------------------------------------------------------------------------------------------

    private Path keep(String name, String version, Path dir) throws IOException {
        Path keepDir = installed.resolve(".previous").resolve(name).resolve(version + "-" + System.currentTimeMillis());
        Files.createDirectories(keepDir.getParent());
        Files.move(dir, keepDir, StandardCopyOption.ATOMIC_MOVE);
        return keepDir;
    }

    private List<Path> keptDirs(String name) {
        Path d = installed.resolve(".previous").resolve(name);
        if (!Files.isDirectory(d)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(d)) {
            return s.filter(Files::isDirectory).filter(p -> p.getFileName().toString().matches(".*-\\d+")).sorted(Comparator.comparingLong(PackDeployService::stamp)).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private void prune(String name) {
        List<Path> kept = keptDirs(name);
        for (int i = 0; i < kept.size() - props.keepVersions(); i++) {
            deleteQuietly(kept.get(i));
        }
    }

    private static long stamp(Path p) {
        String n = p.getFileName().toString();
        return Long.parseLong(n.substring(n.lastIndexOf('-') + 1));
    }

    private static String versionOfDir(Path p) {
        String n = p.getFileName().toString();
        return n.substring(0, n.lastIndexOf('-'));
    }

    private static String versionOf(Path packDir) {
        try {
            return String.valueOf(PackDiffer.load(packDir).meta().get("version"));
        } catch (IOException | RuntimeException e) {
            return "unknown";
        }
    }

    private void expire() {
        Instant now = Instant.now();
        staged.values().removeIf(s -> {
            if (now.isAfter(s.expires())) {
                deleteQuietly(s.dir());
                return true;
            }
            return false;
        });
    }

    static void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // best effort: a leftover folder under .uploads or .previous is harmless
                }
            });
        } catch (IOException e) {
            // best effort
        }
    }
}
