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
package com.ash.drishti.server.admin;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.server.PackOverlay;
import com.ash.drishti.server.PackReloader;
import com.ash.drishti.server.bi.BiFolder;
import com.ash.drishti.server.connectors.ConnectorManager;
import com.ash.drishti.server.security.PackAccess;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin, Artifacts: the actions on installed packs, connector files and shipped BI files, and the safe way out of them.
 * <b>Disable</b> keeps the artifact and switches it off (a pack off for everyone, a connector {@code enabled: false}, a BI file hidden
 * from the catalogue). <b>Uninstall</b> is refused with the impact while anything depends on the artifact, unless the administrator
 * chooses to take the dependants along (and types the name), and it <em>moves</em> the folder or file into the archive
 * ({@link ArtifactArchive}); the watchers then see it gone and unload or stop it as they always do. <b>Restore</b> moves it back.
 * Every action is audited with what depended on it, what was chosen and what happened, and every refusal is audited too.
 * One action at a time, so two administrators cannot take the same pack out from under each other.
 */
public final class ArtifactService {

    private static final Logger LOG = LoggerFactory.getLogger(ArtifactService.class);

    private final ArtifactPaths paths;
    private final ArtifactArchive archive;
    private final ArtifactImpact impact;
    private final PackRegistry packs;
    private final PackAccess access;
    private final PackReloader reloader;
    private final PackOverlay overlay;
    private final ConnectorManager connectors;
    private final BiFolder folder;
    private final AuditLog audit;
    private final Path hiddenFile;
    private final Duration keep;
    private final Set<String> configured;
    private final int biUploadMax;
    private final ReentrantLock lock = new ReentrantLock();

    @SuppressWarnings("java:S107")
    public ArtifactService(ArtifactPaths paths, ArtifactArchive archive, ArtifactImpact impact, PackRegistry packs, PackAccess access, PackReloader reloader,
            PackOverlay overlay, ConnectorManager connectors, BiFolder folder, AuditLog audit, Path hiddenFile, Duration keep, String configuredPacks, int biUploadMax) {
        this.biUploadMax = biUploadMax;
        this.paths = paths;
        this.archive = archive;
        this.impact = impact;
        this.packs = packs;
        this.access = access;
        this.reloader = reloader;
        this.overlay = overlay;
        this.connectors = connectors;
        this.folder = folder;
        this.audit = audit;
        this.hiddenFile = hiddenFile;
        this.keep = keep;
        this.configured = new LinkedHashSet<>(Arrays.stream(configuredPacks == null ? new String[0] : configuredPacks.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList());
    }

    public ArtifactArchive archive() {
        return archive;
    }

    public ArtifactPaths paths() {
        return paths;
    }

    public ArtifactImpact impacts() {
        return impact;
    }

    public Duration keep() {
        return keep;
    }

    /** Reads the saved set of hidden BI files and applies it; called once at start. */
    public void start() {
        folder.hide(readHidden());
    }

    // ---- impact -----------------------------------------------------------------------------------------------------------------

    public ArtifactImpact.Impact impact(String kind, String subtype, String name) {
        requireExists(kind, subtype, name);
        return impact.of(kind, subtype, name);
    }

    // ---- disable / enable -------------------------------------------------------------------------------------------------------

    /** Switches the artifact off and keeps it. A pack that other enabled packs extend needs {@code cascade} (they are switched off first). */
    public Map<String, Object> disable(String kind, String subtype, String name, boolean cascade, String actor) {
        lock.lock();
        try {
            requireExists(kind, subtype, name);
            ArtifactImpact.Impact im = impact.of(kind, subtype, name);
            List<String> done = new ArrayList<>();
            try {
                switch (kind) {
                    case "pack" -> disablePack(name, cascade, im, done);
                    case "connector" -> connectors.setEnabled(name, false, null, true);
                    default -> hide(subtype, name, true);
                }
            } catch (ArtifactRefused r) {
                audit.record(actor, "artifact-refused", subject(kind, name), "disable refused: " + r.impact().summary());
                throw r;
            }
            audit.record(actor, "artifact-disabled", subject(kind, name), "impact: " + im.summary() + "; choice: " + (cascade ? "with dependants" : "alone")
                    + "; result: off, kept on disk" + (done.isEmpty() ? "" : "; also off: " + done));
            return result("disable", kind, subtype, name, im, null, done, "switched off; kept on disk");
        } finally {
            lock.unlock();
        }
    }

    /** Switches a disabled artifact back on. */
    public Map<String, Object> enable(String kind, String subtype, String name, String actor) {
        lock.lock();
        try {
            requireExists(kind, subtype, name);
            switch (kind) {
                case "pack" -> access.setEnabled(name, true, actor);
                case "connector" -> connectors.setEnabled(name, true, null, true);
                default -> hide(subtype, name, false);
            }
            audit.record(actor, "artifact-enabled", subject(kind, name), "switched back on");
            return result("enable", kind, subtype, name, null, null, List.of(), "switched on");
        } finally {
            lock.unlock();
        }
    }

    private void disablePack(String name, boolean cascade, ArtifactImpact.Impact im, List<String> done) {
        if (packs.packs().stream().noneMatch(p -> p.name().equals(name))) {
            throw new DrishtiException(ErrorCode.ARTIFACT_INVALID, "pack '" + name + "' is not loaded, so there is nothing to switch off");
        }
        List<String> needs = access.requiredBy(name);
        if (!needs.isEmpty() && !cascade) {
            throw new ArtifactRefused("pack '" + name + "' is needed by enabled pack(s) " + needs + "; switch those off too (also disable dependants) or switch them off first", im);
        }
        List<String> todo = new ArrayList<>(needs);
        for (int guard = 0; !todo.isEmpty() && guard < 100; guard++) {
            for (String p : List.copyOf(todo)) {
                if (access.requiredBy(p).isEmpty()) {
                    access.setEnabled(p, false, "artifacts");
                    done.add(p);
                    todo.remove(p);
                }
            }
        }
        access.setEnabled(name, false, "artifacts");
    }

    // ---- uninstall --------------------------------------------------------------------------------------------------------------

    /**
     * Moves the artifact into the archive. Refused with the impact while anything depends on it unless {@code cascade}; with
     * {@code cascade} the administrator must also type the artifact's name ({@code confirm}). A connector a pack's template names
     * is only disabled or reset, never uninstalled.
     */
    public Map<String, Object> uninstall(String kind, String subtype, String name, boolean cascade, String confirm, String actor) {
        lock.lock();
        try {
            requireExists(kind, subtype, name);
            ArtifactImpact.Impact im = impact.of(kind, subtype, name);
            String why = null;
            if (im.templated()) {
                why = "connector '" + name + "' is named by pack " + im.templatePack() + "'s template, so it would be written back: disable or reset it instead";
            } else if ("pack".equals(kind) && configured.contains(name) && !overlay.added().contains(name)) {
                why = "pack '" + name + "' is named in drishti.packs.enabled (DRISHTI_PACKS): take it out there and restart, or disable it here";
            }
            if (why != null) {
                audit.record(actor, "artifact-refused", subject(kind, name), "uninstall refused: " + why);
                throw new DrishtiException(ErrorCode.ARTIFACT_INVALID, why);
            }
            if (im.blocked() && !cascade) {
                audit.record(actor, "artifact-refused", subject(kind, name), "uninstall refused: " + im.summary());
                throw new ArtifactRefused(kind + " '" + name + "' has dependants: " + im.summary() + "; disable it instead, or choose to take the dependants along", im);
            }
            if (cascade && im.blocked() && !name.equals(confirm)) {
                audit.record(actor, "artifact-refused", subject(kind, name), "uninstall with dependants refused: the name was not typed");
                throw new DrishtiException(ErrorCode.ARTIFACT_CONFIRM, "type the name '" + name + "' to uninstall it together with what depends on it");
            }
            List<String> also = new ArrayList<>();
            if (cascade && im.blocked()) {
                cascade(kind, name, im, actor, also);
            }
            ArtifactArchive.Entry e = move(kind, subtype, name, actor, im.summary());
            settle(kind);
            audit.record(actor, "artifact-uninstalled", subject(kind, name), "impact: " + im.summary() + "; choice: " + (cascade && im.blocked() ? "with dependants" : "alone")
                    + "; result: archived as " + e.id() + (also.isEmpty() ? "" : "; also: " + also));
            return result("uninstall", kind, subtype, name, im, e.id(), also, "moved to the archive " + e.id() + "; restore it from the Uninstalled tab");
        } finally {
            lock.unlock();
        }
    }

    /** What taking the dependants along means: extending packs are archived, exclusive connectors and dependent BI files are switched off. */
    private void cascade(String kind, String name, ArtifactImpact.Impact im, String actor, List<String> also) {
        for (ArtifactImpact.Group g : im.groups()) {
            switch (g.category()) {
                case "packs" -> {
                    if ("pack".equals(kind)) {
                        extenders(name).forEach(p -> {
                            if (paths.packFolder(p).isPresent()) {
                                move("pack", "", p, actor, "with " + name);
                                also.add("uninstalled pack " + p);
                            }
                        });
                    }
                }
                case "connectors" -> g.items().forEach(i -> {
                    try {
                        connectors.setEnabled(i.name(), false, null, true);
                        also.add("disabled connector " + i.name());
                    } catch (RuntimeException ex) {
                        LOG.info("could not disable connector {}: {}", i.name(), ex.getMessage());
                    }
                });
                case "datasets", "reports", "boards", "notebooks", "python" -> {
                    String sub = switch (g.category()) {
                        case "datasets" -> "dataset";
                        case "reports" -> "report";
                        case "boards" -> "board";
                        case "notebooks" -> "notebook";
                        default -> "python";
                    };
                    g.items().forEach(i -> {
                        if (Files.isRegularFile(paths.biFile(sub, i.name()))) {
                            hide(sub, i.name(), true);
                            also.add("hid " + sub + " " + i.name());
                        }
                    });
                }
                default -> {
                    // saved searches, monitors, alerts and the like are people's own: they stay, and say so when opened
                }
            }
        }
    }

    /** The packs that extend {@code name}, directly or not, deepest first (so each goes before the pack it builds on). */
    private List<String> extenders(String name) {
        List<String> order = new ArrayList<>();
        List<String> frontier = List.of(name);
        for (int depth = 0; depth < 20 && !frontier.isEmpty(); depth++) {
            List<String> next = new ArrayList<>();
            for (Pack p : packs.packs()) {
                if (frontier.stream().anyMatch(p.parents()::contains) && !order.contains(p.name())) {
                    order.add(p.name());
                    next.add(p.name());
                }
            }
            frontier = next;
        }
        java.util.Collections.reverse(order);
        return order;
    }

    private ArtifactArchive.Entry move(String kind, String subtype, String name, String actor, String summary) {
        return switch (kind) {
            case "pack" -> {
                ArtifactPaths.PackFolder f = paths.packFolder(name).orElseThrow(() -> notFound(kind, name));
                yield archive.archive("pack", "", name, f.area(), f.dir().getFileName().toString(), f.dir(), actor, summary);
            }
            case "connector" -> {
                Path file = connectors.files().file(name);
                yield archive.archive("connector", "", name, "connectors", file.getFileName().toString(), file, actor, summary);
            }
            default -> {
                Path file = paths.biFile(subtype, name);
                ArtifactArchive.Entry e = archive.archive("bi", subtype, name, "bi", paths.biRel(subtype, name), file, actor, summary);
                hide(subtype, name, false);
                yield e;
            }
        };
    }

    /** Tells the running server to look: the watchers would, but the answer should not wait for them. */
    private void settle(String kind) {
        switch (kind) {
            case "pack" -> reloader.check();
            case "connector" -> connectors.scan();
            default -> folder.reload();
        }
    }

    // ---- restore / purge / prune -------------------------------------------------------------------------------------------------

    /** Moves an archive back where it came from. A same-named artifact in the way refuses it unless {@code replace} (which archives that one first, and needs the name typed). */
    public Map<String, Object> restore(String id, boolean replace, String confirm, String actor) {
        lock.lock();
        try {
            ArtifactArchive.Entry e = archive.find(id).orElseThrow(() -> new DrishtiException(ErrorCode.ARTIFACT_NOT_FOUND, "no archive '" + id + "'"));
            Path target = paths.resolve(e.area(), e.rel());
            boolean clash = Files.exists(target) || exists(e.kind(), e.subtype(), e.name());
            List<String> also = new ArrayList<>();
            if (clash) {
                if (!replace) {
                    audit.record(actor, "artifact-refused", subject(e.kind(), e.name()), "restore of " + id + " refused: " + e.kind() + " '" + e.name() + "' exists");
                    throw new DrishtiException(ErrorCode.ARTIFACT_CONFLICT, e.kind() + " '" + e.name() + "' exists now; restore with replace to archive the current one and put this back");
                }
                if (!e.name().equals(confirm)) {
                    throw new DrishtiException(ErrorCode.ARTIFACT_CONFIRM, "type the name '" + e.name() + "' to replace the " + e.kind() + " that is there now");
                }
                ArtifactArchive.Entry old = move(e.kind(), e.subtype(), e.name(), actor, "replaced by restoring " + id);
                also.add("the current one is archived as " + old.id());
            }
            archive.restoreTo(e, target);
            settle(e.kind());
            audit.record(actor, "artifact-restored", subject(e.kind(), e.name()), "from " + id + "; choice: " + (clash ? "replace" : "plain") + "; result: back in " + e.area()
                    + (also.isEmpty() ? "" : "; " + also));
            Map<String, Object> out = result("restore", e.kind(), e.subtype(), e.name(), null, id, also, "restored to " + e.area() + "/" + e.rel());
            out.put("replaced", clash);
            return out;
        } catch (UncheckedIOException ex) {
            throw new DrishtiException(ErrorCode.ARTIFACT_INVALID, ex.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /** Deletes an archive for good. The administrator types its name. */
    public Map<String, Object> purge(String id, String confirm, String actor) {
        lock.lock();
        try {
            ArtifactArchive.Entry e = archive.find(id).orElseThrow(() -> new DrishtiException(ErrorCode.ARTIFACT_NOT_FOUND, "no archive '" + id + "'"));
            if (!e.name().equals(confirm)) {
                audit.record(actor, "artifact-refused", subject(e.kind(), e.name()), "delete of " + id + " refused: the name was not typed");
                throw new DrishtiException(ErrorCode.ARTIFACT_CONFIRM, "type the name '" + e.name() + "' to delete the archive for good");
            }
            archive.purge(id);
            audit.record(actor, "artifact-purged", subject(e.kind(), e.name()), "archive " + id + " deleted permanently (" + e.bytes() + " bytes)");
            return result("purge", e.kind(), e.subtype(), e.name(), null, id, List.of(), "deleted for good");
        } finally {
            lock.unlock();
        }
    }

    /** Removes the archives older than {@code drishti.admin.uninstalled-keep}. Run daily; also callable by tests. */
    public List<String> prune() {
        lock.lock();
        try {
            List<String> gone = archive.prune(keep);
            if (!gone.isEmpty()) {
                audit.record("system", "artifact-pruned", "uninstalled", gone.size() + " archive(s) older than " + keep.toDays() + " days removed: " + gone);
            }
            return gone;
        } finally {
            lock.unlock();
        }
    }

    // ---- install a shipped BI file ---------------------------------------------------------------------------------------------

    /** The dry run of a BI upload: is it valid, would it replace a file, and what depends on the one it would replace. Writes nothing. */
    public Map<String, Object> checkBi(String filename, byte[] body) {
        BiUploadChecks.Outcome o = BiUploadChecks.check(filename, body, biUploadMax);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", o.valid());
        out.put("name", o.name());
        out.put("subtype", o.subtype());
        out.put("problems", o.problems());
        out.put("warnings", o.warnings());
        boolean exists = o.valid() && Files.isRegularFile(paths.biFile(o.subtype(), o.name()));
        out.put("exists", exists);
        if (exists) {
            out.put("replacesImpact", impact.of("bi", o.subtype(), o.name()).summary());
        }
        return out;
    }

    /** Places a checked BI file in the BI folder (atomically). An existing file of that name is archived first and needs {@code replace} and its name typed. */
    public Map<String, Object> installBi(String filename, byte[] body, boolean replace, String confirm, String actor) {
        lock.lock();
        try {
            BiUploadChecks.Outcome o = BiUploadChecks.check(filename, body, biUploadMax);
            if (!o.valid()) {
                audit.record(actor, "artifact-refused", subject("bi", o.name().isEmpty() ? String.valueOf(filename) : o.name()), "install refused: " + o.problems());
                throw new DrishtiException(ErrorCode.ARTIFACT_INVALID, "the file is not valid: " + String.join("; ", o.problems()));
            }
            Path target = paths.biFile(o.subtype(), o.name());
            List<String> also = new ArrayList<>();
            if (Files.isRegularFile(target)) {
                if (!replace) {
                    audit.record(actor, "artifact-refused", subject("bi", o.name()), "install refused: " + o.subtype() + " '" + o.name() + "' exists");
                    throw new DrishtiException(ErrorCode.ARTIFACT_CONFLICT, o.subtype() + " '" + o.name() + "' exists; upload with replace to archive it and put this one in its place");
                }
                if (!o.name().equals(confirm)) {
                    throw new DrishtiException(ErrorCode.ARTIFACT_CONFIRM, "type the name '" + o.name() + "' to replace the " + o.subtype() + " that is there now");
                }
                ArtifactArchive.Entry old = move("bi", o.subtype(), o.name(), actor, "replaced by an upload");
                also.add("the previous file is archived as " + old.id());
            }
            try {
                Files.createDirectories(target.getParent());
                Path tmp = com.ash.drishti.common.TempFiles.createFile(target.getParent(), "." + o.name() + "-", ".part");
                Files.write(tmp, body);
                Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot place " + target.getFileName() + ": " + e.getMessage(), e);
            }
            folder.reload();
            audit.record(actor, "artifact-installed", subject("bi", o.name()), o.subtype() + " file uploaded (" + body.length + " bytes); choice: " + (also.isEmpty() ? "new" : "replace")
                    + (o.warnings().isEmpty() ? "" : "; warnings: " + o.warnings()) + (also.isEmpty() ? "" : "; " + also));
            Map<String, Object> out = result("install", "bi", o.subtype(), o.name(), null, null, also, "placed in " + paths.biRel(o.subtype(), o.name()));
            out.put("warnings", o.warnings());
            return out;
        } finally {
            lock.unlock();
        }
    }

    // ---- BI hidden set ------------------------------------------------------------------------------------------------------------

    private void hide(String subtype, String name, boolean on) {
        String id = "file:" + subtype + ":" + name;
        Set<String> now = new LinkedHashSet<>(folder.hidden());
        if (on ? !now.add(id) : !now.remove(id)) {
            return;
        }
        folder.hide(now);
        try {
            Files.createDirectories(hiddenFile.getParent());
            Files.writeString(hiddenFile, String.join("\n", now) + (now.isEmpty() ? "" : "\n"));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot save the hidden BI files: " + e.getMessage(), e);
        }
    }

    private Set<String> readHidden() {
        try {
            if (!Files.isRegularFile(hiddenFile)) {
                return Set.of();
            }
            return new LinkedHashSet<>(Files.readAllLines(hiddenFile).stream().map(String::strip).filter(s -> !s.isEmpty()).toList());
        } catch (IOException e) {
            LOG.warn("cannot read {}: {}", hiddenFile, e.getMessage());
            return Set.of();
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------------------

    boolean exists(String kind, String subtype, String name) {
        return switch (kind) {
            case "pack" -> paths.packFolder(name).isPresent();
            case "connector" -> connectors.files().exists(name);
            default -> Files.isRegularFile(paths.biFile(subtype, name));
        };
    }

    private void requireExists(String kind, String subtype, String name) {
        if (!List.of("pack", "connector", "bi").contains(kind)) {
            throw new DrishtiException(ErrorCode.ARTIFACT_INVALID, "kind is pack, connector or bi, not '" + kind + "'");
        }
        boolean there = switch (kind) {
            case "pack" -> paths.packFolder(name).isPresent() || packs.packs().stream().anyMatch(p -> p.name().equals(name));
            case "connector" -> connectors.defined(name);
            default -> Files.isRegularFile(paths.biFile(subtype, name));
        };
        if (!there) {
            throw notFound(kind, name);
        }
    }

    private static DrishtiException notFound(String kind, String name) {
        return new DrishtiException(ErrorCode.ARTIFACT_NOT_FOUND, "no " + kind + " named '" + name + "'");
    }

    static String subject(String kind, String name) {
        return kind + ":" + name;
    }

    private static Map<String, Object> result(String action, String kind, String subtype, String name, ArtifactImpact.Impact im, String archiveId, List<String> also, String note) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", action);
        out.put("kind", kind);
        out.put("subtype", subtype == null ? "" : subtype);
        out.put("name", name);
        out.put("ok", true);
        out.put("note", note);
        if (im != null) {
            out.put("impact", im.summary());
        }
        if (archiveId != null) {
            out.put("archive", archiveId);
        }
        out.put("also", also);
        return out;
    }
}
