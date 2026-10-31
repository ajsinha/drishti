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
package com.ash.drishti.server.disk;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * One budget for the local disk (RESOURCES_AND_SCALE.md section 7.2, O5, 2.9.7). Each folder Drishti fills registers its name, path, cap
 * in bytes, how big it is and how to free space (its least recently used items that nobody has open). Before anything large is written
 * (a hot file, a shard book, a currency's book) the writer asks {@link #reserve}: a folder over its cap frees its oldest items first; when
 * the disk's free space after the write would fall under the <b>guard</b> ({@code drishti.disk.min-free-percent} and
 * {@code min-free-gb}, both must hold) every folder frees down to 90% of its cap and the check is made again; when it still would not fit,
 * the write is refused with {@code DRS-5218} and says what to free. A date already open keeps answering; the lake is never touched.
 */
public final class DiskBudget {

    /** A folder Drishti fills. */
    public interface Folder {
        String name();

        Path path();

        /** Its cap in bytes (0: none). */
        long limitBytes();

        /** Its size now. */
        long usedBytes();

        /** Frees its least recently used items nobody has open until it is at most {@code targetBytes}; returns the bytes freed. */
        long free(long targetBytes);

        /** Its oldest item and when it was last used, for Health, Disk (empty when it cannot say). */
        default String oldest() {
            return "";
        }
    }

    /** A folder made of a path and a cap, whose size is measured by walking it and which frees nothing itself (logs, dumps, temp). */
    public static Folder measured(String name, Path path, LongSupplier limit) {
        return new Folder() {
            public String name() {
                return name;
            }

            public Path path() {
                return path;
            }

            public long limitBytes() {
                return limit.getAsLong();
            }

            public long usedBytes() {
                return bytes(path);
            }

            public long free(long target) {
                return 0;
            }
        };
    }

    private final DiskProperties props;
    private final List<Folder> folders = new CopyOnWriteArrayList<>();
    private final Path root;

    public DiskBudget(DiskProperties props, Path root) {
        this.props = props;
        this.root = root;
    }

    /** Registers a folder (a later registration of the same name replaces the earlier: a test's second server, a reload). */
    public void register(Folder f) {
        folders.removeIf(x -> x.name().equals(f.name()));
        folders.add(f);
    }

    public List<Folder> folders() {
        return List.copyOf(folders);
    }

    /** The guard on the disk holding {@code p}: the larger of the two settings, in bytes. */
    public long guardBytes(Path p) {
        long total = total(p);
        return (long) Math.max(props.minFreeGb() * (1L << 30), total * props.minFreePercent() / 100.0);
    }

    /**
     * Reserves {@code bytes} in the folder {@code name} before they are written: refused with {@code DRS-5218} when the disk would fall
     * under the guard even after every folder freed down to 90% of its cap. {@code what} names the write in the refusal.
     */
    public void reserve(String name, long bytes, String what) {
        Folder f = folders.stream().filter(x -> x.name().equals(name)).findFirst().orElse(null);
        Path at = f == null ? root : f.path();
        if (f != null && f.limitBytes() > 0 && f.usedBytes() + bytes > f.limitBytes()) {
            f.free(Math.max(0, f.limitBytes() - bytes));
        }
        long guard = guardBytes(at);
        if (usable(at) - bytes >= guard) {
            return;
        }
        for (Folder x : folders) {
            if (x.limitBytes() > 0) {
                x.free((long) (x.limitBytes() * 0.9));
            }
        }
        long free = usable(at);
        if (free - bytes >= guard) {
            return;
        }
        throw new DrishtiException(ErrorCode.DISK_GUARD, String.format(Locale.ROOT,
                "not enough free disk to %s: %s free, %s needed, guard %s (drishti.disk.min-free-percent %.0f%%, min-free-gb %.0f). Free space on "
                        + "%s, lower a folder's cap, or purge a date in Admin > Caches; a date already open keeps answering", what, gb(free),
                gb(bytes), gb(guard), props.minFreePercent(), props.minFreeGb(), at));
    }

    /** True when {@code bytes} more would still leave the guard (no freeing, no refusal): a quiet check. */
    public boolean fits(Path at, long bytes) {
        return usable(at) - bytes >= guardBytes(at);
    }

    /** Brings every folder under its cap (the periodic check); returns the bytes freed. */
    public long trim() {
        long freed = 0;
        for (Folder f : folders) {
            try {
                if (f.limitBytes() > 0 && f.usedBytes() > f.limitBytes()) {
                    freed += f.free(f.limitBytes());
                }
            } catch (RuntimeException e) {
                // a folder that cannot be measured now: the next check
            }
        }
        return freed;
    }

    /** What Admin, Health, Disk shows: the disk's free space against the guard, and every folder's size against its cap. */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        long free = usable(root);
        long guard = guardBytes(root);
        out.put("path", root.toString());
        out.put("totalBytes", total(root));
        out.put("freeBytes", free);
        out.put("guardBytes", guard);
        out.put("state", free < guard ? "red" : free < 2 * guard ? "amber" : "ok");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Folder f : folders) {
            Map<String, Object> r = new LinkedHashMap<>();
            long used;
            try {
                used = f.usedBytes();
            } catch (RuntimeException e) {
                used = -1;
            }
            long limit = f.limitBytes();
            r.put("name", f.name());
            r.put("path", f.path().toString());
            r.put("usedBytes", used);
            r.put("limitBytes", limit);
            r.put("state", limit <= 0 ? "ok" : used >= limit ? "red" : used >= 0.8 * limit ? "amber" : "ok");
            r.put("oldest", f.oldest());
            rows.add(r);
        }
        out.put("folders", rows);
        return out;
    }

    /** The usable bytes on the disk holding {@code p} (its nearest existing ancestor). */
    public static long usable(Path p) {
        FileStore s = store(p);
        try {
            return s == null ? Long.MAX_VALUE / 4 : s.getUsableSpace();
        } catch (IOException e) {
            return Long.MAX_VALUE / 4;
        }
    }

    /** The size of the disk holding {@code p}. */
    public static long total(Path p) {
        FileStore s = store(p);
        try {
            return s == null ? 0 : s.getTotalSpace();
        } catch (IOException e) {
            return 0;
        }
    }

    private static FileStore store(Path p) {
        Path x = p == null ? null : p.toAbsolutePath();
        while (x != null && !Files.exists(x)) {
            x = x.getParent();
        }
        try {
            return x == null ? null : Files.getFileStore(x);
        } catch (IOException e) {
            return null;
        }
    }

    /** The bytes of every regular file under {@code dir}. */
    public static long bytes(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return 0;
        }
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException | java.io.UncheckedIOException e) {
            return 0;
        }
    }

    static String gb(long bytes) {
        return String.format(Locale.ROOT, bytes >= (1L << 30) ? "%.1f GB" : "%.0f MB", bytes >= (1L << 30) ? bytes / (double) (1L << 30)
                : bytes / (double) (1L << 20));
    }

    /** The default cap of a folder given as a share of its disk ({@code 0.5}: half), for a setting of 0. */
    public static long shareOfDisk(Path p, double share) {
        return (long) (total(p) * share);
    }
}
