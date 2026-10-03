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
package com.ash.drishti.identity.design;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Designs as files: {@code <dir>/<user>/<id>/design.json} and {@code samples/<hash of name>.json}. Directories are
 * created readable by the server's account only ({@code rwx------}, files {@code rw-------}) where the file system has
 * POSIX permissions. User names are escaped into directory names (never joined as they are) and ids must match
 * {@link #ID}, so no request can name a path outside the store. Files are written whole to a sibling and moved into place.
 */
public final class FileDesignStore implements DesignStore {

    static final Pattern ID = Pattern.compile("[a-z0-9]{8,32}");
    private static final Set<PosixFilePermission> DIR_MODE = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
    private final Path root;
    private final ObjectMapper json = new ObjectMapper();

    public FileDesignStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public List<StoredDesign> list(String user) {
        Path dir = userDir(user);
        List<StoredDesign> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path d : (Iterable<Path>) s::iterator) {
                String id = d.getFileName().toString();
                if (ID.matcher(id).matches()) {
                    read(user, id).ifPresent(out::add);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    @Override
    public Optional<StoredDesign> get(String user, String id) {
        return ID.matcher(id).matches() ? read(user, id) : Optional.empty();
    }

    private Optional<StoredDesign> read(String user, String id) {
        Path f = designDir(user, id).resolve("design.json");
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(f.toFile(), StoredDesign.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void save(StoredDesign d) {
        try {
            write(designDir(d.owner, d.id).resolve("design.json"), json.writeValueAsBytes(d));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean delete(String user, String id) {
        if (!ID.matcher(id).matches()) {
            return false;
        }
        Path dir = designDir(user, id);
        if (!Files.isDirectory(dir)) {
            return false;
        }
        deleteTree(dir);
        return true;
    }

    @Override
    public void putSample(String user, String id, String name, String jsonText) {
        write(designDir(user, id).resolve("samples").resolve(fileOf(name)), jsonText.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Optional<String> sample(String user, String id, String name) {
        if (!ID.matcher(id).matches()) {
            return Optional.empty();
        }
        Path f = designDir(user, id).resolve("samples").resolve(fileOf(name));
        try {
            return Files.isRegularFile(f) ? Optional.of(Files.readString(f, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void removeSample(String user, String id, String name) {
        if (!ID.matcher(id).matches()) {
            return;
        }
        try {
            Files.deleteIfExists(designDir(user, id).resolve("samples").resolve(fileOf(name)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<String> users() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> s = Files.list(root)) {
            s.filter(Files::isDirectory).forEach(p -> out.add(unescape(p.getFileName().toString())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    @Override
    public void forget(String user) {
        Path dir = userDir(user);
        if (Files.isDirectory(dir)) {
            deleteTree(dir);
        }
    }

    // ---- paths ---------------------------------------------------------------------------------------------------

    private Path userDir(String user) {
        Path p = root.resolve(escape(user)).normalize();
        if (!p.startsWith(root) || p.equals(root)) {
            throw new DrishtiException(ErrorCode.INVALID_USER, "bad user name");
        }
        return p;
    }

    private Path designDir(String user, String id) {
        if (!ID.matcher(id).matches()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "bad design id");
        }
        return userDir(user).resolve(id);
    }

    /** A sample's file name: a hash of its name, so a name never becomes part of a path. */
    private static String fileOf(String name) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8)), 0, 16) + ".json";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Letters, digits, {@code -} and a non-leading {@code .} stay; every other character is {@code _} plus its hex. */
    static String escape(String user) {
        StringBuilder b = new StringBuilder();
        for (byte c : user.getBytes(StandardCharsets.UTF_8)) {
            char ch = (char) (c & 0xff);
            boolean plain = ch < 128 && (Character.isLetterOrDigit(ch) || ch == '-' || (ch == '.' && b.length() > 0));
            if (plain) {
                b.append(ch);
            } else {
                b.append('_').append(String.format("%02x", c & 0xff));
            }
        }
        return b.length() == 0 ? "_" : b.toString();
    }

    static String unescape(String dir) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < dir.length(); i++) {
            char c = dir.charAt(i);
            if (c == '_' && i + 2 < dir.length()) {
                out.write(Integer.parseInt(dir.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.write(c);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    // ---- files ---------------------------------------------------------------------------------------------------

    private static FileAttribute<Set<PosixFilePermission>> dirAttr() {
        return PosixFilePermissions.asFileAttribute(DIR_MODE);
    }

    private void write(Path file, byte[] bytes) {
        try {
            makeDirs(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);
            try {
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(FILE_MODE));
            } catch (UnsupportedOperationException e) {
                Files.createFile(tmp);
            }
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void makeDirs(Path dir) throws IOException {
        try {
            Files.createDirectories(dir, dirAttr());
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(dir);
        }
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
