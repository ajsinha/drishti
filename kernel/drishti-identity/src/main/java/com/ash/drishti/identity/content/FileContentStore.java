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
package com.ash.drishti.identity.content;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The filesystem content store (the default; docs/architecture/DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 3). An item is one file, {@code items/<encoded key>};
 * its version is the SHA-256 of its bytes, so the file alone is the truth. Writes go to a visible {@code <name>~tmp-<uuid>} and are renamed
 * into place; compare-and-set holds {@code <name>~lock} (create-new: atomic on local disks, NFS v3+ and EFS; broken when older than
 * {@link #STALE_LOCK_MS}). Windows refusals of a rename or delete (a reader holds the file open) are retried with back-off. Key segments are
 * encoded ({@code %XX}), so stored names are visible, Windows-safe and never contain {@code ~}, which marks the store's own sidecars.
 */
public final class FileContentStore implements ContentStore {

    static final long STALE_LOCK_MS = 30_000;
    private static final long LOCK_WAIT_MS = 10_000;
    private static final Set<String> WINDOWS_DEVICES = Set.of("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6",
            "com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");
    private final Path root;
    private final Path items;
    private final Path tombstones;

    public FileContentStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.items = this.root.resolve("items");
        this.tombstones = this.root.resolve("tombstones");
        try {
            Files.createDirectories(items);
            Files.createDirectories(tombstones);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create the content store at " + this.root + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String root() {
        return "filesystem " + root;
    }

    @Override
    public Optional<Item> get(String key) {
        Path f = file(items, key);
        for (int attempt = 0; ; attempt++) {
            try {
                byte[] b = com.ash.drishti.api.ChannelIo.readAllBytes(f);  // no temporary direct buffer the item's size
                return Optional.of(new Item(b, hash(b)));
            } catch (NoSuchFileException e) {
                return Optional.empty();
            } catch (AccessDeniedException e) {
                pause(attempt, e);                                       // Windows: a rename into place is under way
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(file(items, key));
    }

    @Override
    public String put(String key, byte[] bytes, String expectedVersion) {
        Path f = file(items, key);
        return locked(f, () -> {
            String now = current(f);
            if (expectedVersion == null ? now != null : !expectedVersion.equals(now)) {
                throw ContentStore.conflict(key);
            }
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + "~tmp-" + UUID.randomUUID());
            try {
                Files.write(tmp, bytes);
                move(tmp, f);
            } finally {
                Files.deleteIfExists(tmp);
            }
            Path t = file(tombstones, key);
            if (Files.exists(t)) {
                remove(t);
            }
            return hash(bytes);
        });
    }

    @Override
    public boolean delete(String key, String expectedVersion) {
        Path f = file(items, key);
        return locked(f, () -> {
            String now = current(f);
            if (now == null) {
                return false;
            }
            if (!now.equals(expectedVersion)) {
                throw ContentStore.conflict(key);
            }
            Path t = file(tombstones, key);
            Files.createDirectories(t.getParent());
            Files.write(t, new byte[0]);
            Files.setLastModifiedTime(t, FileTime.fromMillis(System.currentTimeMillis()));
            remove(f);
            return true;
        });
    }

    @Override
    public List<String> list(String prefix) {
        List<String> out = new ArrayList<>();
        walk(items, (p, k) -> {
            if (k.startsWith(prefix)) {
                out.add(k);
            }
        }, dirFor(prefix));
        out.sort(null);
        return out;
    }

    @Override
    public Changes changes(long since) {
        List<String> out = new ArrayList<>();
        long[] cursor = {since};
        for (Path base : List.of(items, tombstones)) {
            walk(base, (p, k) -> {
                try {
                    long m = Files.getLastModifiedTime(p).toMillis();
                    if (m > since) {
                        out.add(k);
                        cursor[0] = Math.max(cursor[0], m);
                    }
                } catch (IOException e) {
                    // gone while walking: a later poll sees its tombstone
                }
            }, base);
        }
        out.sort(null);
        return new Changes(out.stream().distinct().toList(), cursor[0]);
    }

    // ---- locking ------------------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface Io<T> {
        T run() throws IOException;
    }

    private <T> T locked(Path f, Io<T> work) {
        Path lock = f.resolveSibling(f.getFileName() + "~lock");
        long deadline = System.currentTimeMillis() + LOCK_WAIT_MS;
        try {
            try {
                Files.createDirectories(f.getParent());
            } catch (FileAlreadyExistsException e) {
                throw itemAndFolder(f);
            }
            if (Files.isDirectory(f)) {
                throw itemAndFolder(f);
            }
            for (int attempt = 0; ; attempt++) {
                try {
                    Files.write(lock, (ProcessHandle.current().pid() + "\n").getBytes(StandardCharsets.UTF_8),
                            java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
                    break;
                } catch (FileAlreadyExistsException | AccessDeniedException e) {
                    breakIfStale(lock);
                    if (System.currentTimeMillis() > deadline) {
                        throw new DrishtiException(ErrorCode.CONTENT_CONFLICT, "'" + f.getFileName() + "' is being written by another node: try again");
                    }
                    sleep(Math.min(50, 2 + attempt));
                } catch (NoSuchFileException e) {
                    Files.createDirectories(f.getParent());              // a delete of the folder raced with us
                }
            }
            try {
                return work.run();
            } finally {
                for (int attempt = 0; ; attempt++) {
                    try {
                        Files.deleteIfExists(lock);
                        break;
                    } catch (AccessDeniedException e) {
                        if (attempt > 40) {
                            break;                                       // left behind: broken as stale later
                        }
                        sleep(5);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** On a filesystem a key cannot be both an item and the folder of other keys ({@code a} and {@code a/b}). */
    private DrishtiException itemAndFolder(Path f) {
        return new DrishtiException(ErrorCode.CONTENT_BAD_KEY, "'" + items.relativize(f) + "': a key cannot be both an item and a prefix of other keys");
    }

    private static void breakIfStale(Path lock) {
        try {
            if (System.currentTimeMillis() - Files.getLastModifiedTime(lock).toMillis() > STALE_LOCK_MS) {
                Files.deleteIfExists(lock);
            }
        } catch (IOException e) {
            // released meanwhile
        }
    }

    private String current(Path f) throws IOException {
        try {
            return hash(f);                                              // streamed: the item is never read whole
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    /** Rename into place, retried while Windows refuses because a reader has the target open. */
    private static void move(Path from, Path to) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                try {
                    Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AccessDeniedException e) {
                pause(attempt, e);
            } catch (FileSystemException e) {
                if (e instanceof NoSuchFileException) {
                    throw e;
                }
                pause(attempt, e);
            }
        }
    }

    private static void remove(Path p) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                Files.deleteIfExists(p);
                return;
            } catch (AccessDeniedException e) {
                pause(attempt, e);
            }
        }
    }

    private static void pause(int attempt, IOException e) {
        if (attempt >= 50) {
            throw new UncheckedIOException(e);
        }
        sleep(Math.min(100, 5L << Math.min(attempt, 4)));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new DrishtiException(ErrorCode.CONTENT_CONFLICT, "interrupted while waiting for the content store");
        }
    }

    // ---- names --------------------------------------------------------------------------------------------------

    private Path file(Path base, String key) {
        ContentStore.check(key);
        Path p = base;
        for (String s : key.split("/")) {
            p = p.resolve(encode(s));
        }
        return p;
    }

    /** The deepest folder that holds every key with this prefix (its complete segments). */
    private Path dirFor(String prefix) {
        int slash = prefix.lastIndexOf('/');
        if (slash <= 0) {
            return items;
        }
        try {
            return file(items, prefix.substring(0, slash));
        } catch (DrishtiException e) {
            return items;
        }
    }

    private interface Visit {
        void at(Path file, String key);
    }

    private void walk(Path base, Visit visit, Path from) {
        if (!Files.isDirectory(from)) {
            return;
        }
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!Files.isRegularFile(p) || p.getFileName().toString().contains("~")) {
                    continue;                                          // sidecars: ~lock, ~tmp-
                }
                StringBuilder k = new StringBuilder();
                for (Path part : base.relativize(p)) {
                    k.append(k.length() == 0 ? "" : "/").append(decode(part.toString()));
                }
                visit.at(p, k.toString());
            }
        } catch (UncheckedIOException e) {
            if (!(e.getCause() instanceof NoSuchFileException)) {
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code A-Za-z0-9-_.} stay; every other byte is {@code %XX}; a leading {@code .}, a trailing {@code .} and Windows device names are encoded too. */
    static String encode(String seg) {
        StringBuilder b = new StringBuilder();
        byte[] bytes = seg.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            int c = bytes[i] & 0xff;
            boolean plain = c < 128 && (Character.isLetterOrDigit(c) || c == '-' || c == '_' || (c == '.' && i > 0 && i < bytes.length - 1));
            if (plain) {
                b.append((char) c);
            } else {
                b.append('%').append(String.format("%02X", c));
            }
        }
        String base = b.toString();
        int dot = base.indexOf('.');
        String stem = (dot < 0 ? base : base.substring(0, dot)).toLowerCase(Locale.ROOT);
        if (WINDOWS_DEVICES.contains(stem)) {
            base = String.format("%%%02X", (int) base.charAt(0)) + base.substring(1);
        }
        return base;
    }

    static String decode(String name) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '%' && i + 2 < name.length()) {
                out.write(Integer.parseInt(name.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.write(c);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** The SHA-256 of a file's bytes, read in 64 KB pieces (the same as {@link #hash(byte[])} of the whole file). */
    static String hash(Path f) throws IOException {
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(f, java.nio.file.StandardOpenOption.READ)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(64 << 10);
            long at = 0;
            int n;
            while ((n = com.ash.drishti.api.ChannelIo.read(ch, b.clear(), at)) > 0) {
                md.update(b.array(), 0, n);
                at += n;
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hash(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
