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
package com.ash.drishti.server.api;

import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a long run exhausts, for Admin, Health ({@code server} in {@code GET /api/v1/admin/health}): direct and mapped buffer
 * memory against {@code -XX:MaxDirectMemorySize}, open file descriptors against the limit (where the operating system tells:
 * not on Windows) and, on Linux, the descriptors still open on deleted files. Cheap enough for the page's 5-second refresh.
 */
final class ProcessResources {

    /** Direct memory at or above this share of its limit is shown as a warning. */
    static final int WARN_PERCENT = 80;
    private static final int MAX_FDS_SCANNED = 20_000;

    private ProcessResources() {}

    static Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        long directMax = maxDirectMemory();
        long directUsed = 0;
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            String prefix = switch (pool.getName()) {
                case "direct" -> "direct";
                case "mapped" -> "mapped";
                default -> null;                                 // "mapped - 'non-volatile memory'"
            };
            if (prefix == null) {
                continue;
            }
            m.put(prefix + "UsedBytes", Math.max(0, pool.getMemoryUsed()));
            m.put(prefix + "CapacityBytes", Math.max(0, pool.getTotalCapacity()));
            m.put(prefix + "Buffers", pool.getCount());
            if (prefix.equals("direct")) {
                directUsed = Math.max(0, pool.getMemoryUsed());
            }
        }
        m.put("directMaxBytes", directMax);
        int percent = directMax > 0 ? (int) Math.min(100, directUsed * 100 / directMax) : 0;
        m.put("directPercent", percent);
        m.put("directWarning", percent >= WARN_PERCENT);
        m.put("ioPoolBytes", (long) com.ash.drishti.api.ChannelIo.pooledBuffers() * com.ash.drishti.api.ChannelIo.CHUNK_BYTES);
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
            m.put("openFiles", unix.getOpenFileDescriptorCount());
            m.put("maxFiles", unix.getMaxFileDescriptorCount());
        }
        Long deleted = deletedOpenFiles(Path.of("/proc/self/fd"));
        if (deleted != null) {
            m.put("deletedOpenFiles", deleted);
        }
        return m;
    }

    /** {@code -XX:MaxDirectMemorySize}, or the JVM's default when it is not set (the heap's maximum). */
    static long maxDirectMemory() {
        try {
            com.sun.management.HotSpotDiagnosticMXBean hs = ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
            long set = Long.parseLong(hs.getVMOption("MaxDirectMemorySize").getValue());
            return set > 0 ? set : Runtime.getRuntime().maxMemory();
        } catch (RuntimeException e) {
            return Runtime.getRuntime().maxMemory();             // not a HotSpot JVM
        }
    }

    /** Descriptors open on files that were deleted (Linux: {@code /proc/self/fd}); null where there is no such folder. */
    static Long deletedOpenFiles(Path fds) {
        if (!Files.isDirectory(fds)) {
            return null;
        }
        long n = 0;
        int seen = 0;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(fds)) {
            for (Path fd : s) {
                if (++seen > MAX_FDS_SCANNED) {
                    break;
                }
                try {
                    if (Files.readSymbolicLink(fd).toString().endsWith(" (deleted)")) {
                        n++;
                    }
                } catch (IOException | UnsupportedOperationException e) {
                    // closed while listing
                }
            }
        } catch (IOException e) {
            return null;
        }
        return n;
    }
}
