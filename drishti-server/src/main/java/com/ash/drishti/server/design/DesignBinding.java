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
package com.ash.drishti.server.design;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.design.DesignService;
import com.ash.drishti.identity.design.StoredDesign;
import com.ash.drishti.rachana.RachanaProperties;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Development file binding: a Design bound to a file in the author's <em>own</em> development directory
 * ({@code drishti.builder.dev-dir}/&lt;user&gt;/) saves by writing that file, and an edit made in an IDE comes back by sync.
 * The directory is never one the registry loads, so saving a file never makes anything live: going live always goes through
 * propose and approve ({@link DesignShip}). The directory must lie outside {@code drishti.rachana.dirs}; symbolic links are never
 * followed (a bound name, or a directory on the way to it, that is a link is refused) and a file is written through a new
 * temporary file ({@code O_EXCL}, no link followed) renamed into place. Stateless and thread-safe.
 */
@Service
public class DesignBinding {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int NAME_MAX = 200;
    private static final int DEPTH_MAX = 8;

    private final DesignService designs;
    private final SutraRegistry sutras;
    private final RachanaProperties props;
    private final Entitlements entitlements;
    private final boolean enabled;
    private final String devDir;

    public DesignBinding(DesignService designs, SutraRegistry sutras, RachanaProperties props, Entitlements entitlements,
            @Value("${drishti.builder.file-binding:false}") boolean enabled, @Value("${drishti.builder.dev-dir:./dev-sutras}") String devDir) {
        this.designs = designs;
        this.sutras = sutras;
        this.props = props;
        this.entitlements = entitlements;
        this.enabled = enabled;
        this.devDir = devDir;
    }

    public boolean enabled() {
        return enabled;
    }

    /** What the workbench shows: the directory as a name relative to the server (never an absolute path), and whether it is usable. */
    public String label(Principal who) {
        Path name = Path.of(devDir).normalize();
        String shown = name.isAbsolute() ? name.getFileName().toString() : name.toString();
        return shown + "/" + owner(who.user());
    }

    /** The absolute directory, for administrators only. */
    public String absolute(Principal who) {
        return root(who).toString();
    }

    public boolean isAdmin(Principal who) {
        return entitlements.isAdmin(who);
    }

    private void requireBinding(Principal who) {
        if (!enabled) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "binding a design to a file is off on this server (drishti.builder.file-binding)");
        }
        if (!props.studioSave() || !entitlements.mayAuthor(who)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "binding to a file needs the author right and drishti.rachana.studio-save");
        }
    }

    /** The user's own folder name: safe characters only; a name that needed changing carries a short hash so two users never share one. */
    static String owner(String user) {
        String safe = user.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.startsWith(".")) {
            safe = "_" + safe.substring(1);
        }
        if (safe.length() > 64 || !safe.equals(user) || safe.isEmpty()) {
            safe = (safe.length() > 48 ? safe.substring(0, 48) : safe) + "-" + DesignShip.hash(user).substring(0, 8);
        }
        return safe;
    }

    /** The author's directory; refused when the development directory lies in (or around) a directory the registry loads. */
    Path root(Principal who) {
        Path dev = Path.of(devDir).toAbsolutePath().normalize();
        for (String dir : props.dirs()) {
            Path loaded = Path.of(dir).toAbsolutePath().normalize();
            if (dev.startsWith(loaded) || loaded.startsWith(dev) || real(dev).startsWith(real(loaded)) || real(loaded).startsWith(real(dev))) {
                throw new DrishtiException(ErrorCode.FORBIDDEN, "drishti.builder.dev-dir must lie outside drishti.rachana.dirs: a file saved there would"
                        + " go live without review");
            }
        }
        return dev.resolve(owner(who.user()));
    }

    /** {@code p} with its existing part resolved through links, so a path compared against another is compared as the disk has it. */
    private static Path real(Path p) {
        Path existing = p;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        try {
            return existing == null ? p : existing.toRealPath().resolve(existing.relativize(p)).normalize();
        } catch (IOException e) {
            return p;
        }
    }

    /** The file {@code rel} names in the author's directory, never outside it and never through a symbolic link. */
    Path resolve(Principal who, String rel) {
        if (rel == null || rel.isBlank() || rel.startsWith("/") || rel.contains("\\") || rel.chars().anyMatch(Character::isISOControl)
                || !(rel.endsWith(".yaml") || rel.endsWith(".yml"))) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'file' is a path in your development directory, ending in .yaml");
        }
        String[] parts = rel.split("/", -1);
        if (parts.length > DEPTH_MAX) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'file' is at most " + DEPTH_MAX + " folders deep");
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > NAME_MAX || ".".equals(part) || "..".equals(part)) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "'file' must stay inside your development directory (names of 1-" + NAME_MAX
                        + " characters, no '.' or '..')");
            }
        }
        Path root = root(who);
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "'file' must stay inside your development directory");
        }
        Path at = root;
        for (String part : parts) {
            at = at.resolve(part);
            if (Files.isSymbolicLink(at)) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "'file' goes through a symbolic link (" + part + "), which is never followed");
            }
            if (Files.exists(at, LinkOption.NOFOLLOW_LINKS) && !at.equals(target) && !Files.isDirectory(at, LinkOption.NOFOLLOW_LINKS)) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "'file' needs a folder where " + part + " is a file");
            }
        }
        if (Files.isSymbolicLink(root)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "your development directory is a symbolic link, which is never followed");
        }
        return target;
    }

    private String read(Path file) {
        long max = designs.limits().maxSutraKb() * 1024L;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE - 8));
            if (bytes.length > max) {
                throw new DrishtiException(ErrorCode.PAYLOAD_TOO_LARGE, "the file is over " + designs.limits().maxSutraKb() + " KB (drishti.builder.designs.max-sutra-kb)");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the file cannot be read (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Writes through a new temporary file in the same folder (created exclusively, no link followed), then renames it over the target. */
    private void write(Path file, String text) {
        Path dir = file.getParent();
        Path tmp = dir.resolve(".drishti-" + HexFormat.of().formatHex(randomBytes()) + ".tmp");
        try {
            Files.createDirectories(dir);
            try (OutputStream out = Channels.newOutputStream(Files.newByteChannel(tmp, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)))) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // nothing more can be done for a temporary file
            }
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the file cannot be written (" + e.getClass().getSimpleName() + "): check the name and the folder");
        }
    }

    private static byte[] randomBytes() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        return b;
    }

    /** Binds the Design to {@code rel}; an existing file's text becomes the Sutra (undo brings the old one back). */
    public StoredDesign bind(Principal who, String id, String rel) {
        requireBinding(who);
        designs.get(who.user(), id);
        Path file = resolve(who, rel);
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            String text = read(file);
            designs.setBinding(who.user(), id, rel, DesignShip.hash(text));
            return designs.adoptFileText(who.user(), id, text, DesignShip.hash(text));
        }
        return designs.setBinding(who.user(), id, rel, null);
    }

    public StoredDesign unbind(Principal who, String id) {
        return designs.setBinding(who.user(), id, null, null);
    }

    /**
     * Writes the Sutra to the bound file in the author's development directory; refused when the file was edited elsewhere since the
     * Design last read it (409). This never makes the Sutra live: that is {@link DesignShip#propose}.
     */
    public StoredDesign saveFile(Principal who, String id) {
        requireBinding(who);
        StoredDesign d = designs.get(who.user(), id);
        if (d.boundFile == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "this design is not bound to a file");
        }
        Path file = resolve(who, d.boundFile);
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            String onDisk = read(file);
            if (!onDisk.equals(d.sutra) && !DesignShip.hash(onDisk).equals(d.boundSync)) {
                throw new DrishtiException(ErrorCode.STALE_REVISION, d.boundFile + " changed on disk after the design last read it: load it first (or unbind)");
            }
        }
        sutras.check(d.sutra);
        write(file, d.sutra);
        return designs.setBinding(who.user(), id, d.boundFile, DesignShip.hash(d.sutra));
    }

    /** Brings an edit made to the bound file (in an IDE) back into the Design; the result says whether it changed. */
    public record Synced(StoredDesign design, boolean changed, boolean missing) {}

    public Synced sync(Principal who, String id) {
        StoredDesign d = designs.get(who.user(), id);
        if (d.boundFile == null || !enabled) {
            return new Synced(d, false, false);
        }
        Path file = resolve(who, d.boundFile);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return new Synced(d, false, true);
        }
        String onDisk = read(file);
        String h = DesignShip.hash(onDisk);
        if (h.equals(d.boundSync) || onDisk.equals(d.sutra)) {
            if (!h.equals(d.boundSync)) {
                d = designs.setBinding(who.user(), id, d.boundFile, h);
            }
            return new Synced(d, false, false);
        }
        return new Synced(designs.adoptFileText(who.user(), id, onDisk, h), true, false);
    }
}
