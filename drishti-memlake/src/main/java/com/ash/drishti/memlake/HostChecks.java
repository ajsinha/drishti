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
package com.ash.drishti.memlake;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The host checks of a server that holds memory-only data (HOW_IT_FITS.md sections D3a and 8), for Admin, Health: the JVM's direct-memory
 * limit covers the stores' caps (red when not: the stores would refuse writes long before their caps), no swap, no heap dump on
 * out-of-memory and no JFR recording to a disk, the temporary folder on a RAM disk (amber when not).
 */
final class HostChecks {

    /** The server's own direct buffers, beside the stores (every launcher sets -XX:MaxDirectMemorySize=256m without stores). */
    static final long BASE_DIRECT_BYTES = 256L << 20;

    /** One check: its name, green/amber/red, and why in words. */
    record Check(String name, String state, String words) {

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("state", state);
            m.put("words", words);
            return m;
        }
    }

    private HostChecks() {}

    /** The JVM's direct-memory limit in bytes ({@code -XX:MaxDirectMemorySize}; when unset, Java's default: the heap's maximum). */
    static long directLimitBytes() {
        String v = option("MaxDirectMemorySize");
        long n = v == null ? 0 : parse(v);
        return n > 0 ? n : Runtime.getRuntime().maxMemory();
    }

    /** The checks for stores whose caps add up to {@code capsBytes}. */
    static List<Check> run(long capsBytes) {
        List<Check> out = new ArrayList<>();
        long limit = directLimitBytes();
        long needed = BASE_DIRECT_BYTES + capsBytes;
        out.add(limit >= needed
                ? new Check("direct-memory", "green", "the JVM's direct-memory limit (" + mb(limit) + ") covers 256 MB plus the stores' caps (" + mb(capsBytes) + ")")
                : new Check("direct-memory", "red", "the JVM's direct-memory limit is " + mb(limit) + ", below 256 MB plus the stores' caps (" + mb(needed)
                        + "): start the server with -XX:MaxDirectMemorySize=" + ((needed + (1 << 20) - 1) >> 20) + "m (the start scripts compute it)"));
        long swap = swapBytes();
        out.add(swap <= 0 ? new Check("swap", "green", swap == 0 ? "no swap" : "swap not known on this system")
                : new Check("swap", "amber", "the host has " + mb(swap) + " of swap: memory-only data could be paged to a disk (turn swap off or encrypt it)"));
        boolean dump = "true".equalsIgnoreCase(option("HeapDumpOnOutOfMemoryError"));
        String dumpPath = option("HeapDumpPath");
        out.add(!dump ? new Check("heap-dump", "green", "no heap dump on out-of-memory")
                : new Check("heap-dump", ramDisk(dumpPath == null || dumpPath.isBlank() ? "." : dumpPath) ? "green" : "amber",
                        "a heap dump is written on out-of-memory to " + (dumpPath == null || dumpPath.isBlank() ? "the working folder" : dumpPath)
                                + (ramDisk(dumpPath == null || dumpPath.isBlank() ? "." : dumpPath) ? " (a RAM disk)" : ": put it on a RAM disk or turn it off")));
        boolean jfr = ManagementFactory.getRuntimeMXBean().getInputArguments().stream().anyMatch(a -> a.startsWith("-XX:StartFlightRecording"));
        out.add(new Check("jfr", jfr ? "amber" : "green", jfr ? "a JFR recording is started at launch: it may write object samples to a disk"
                : "no JFR recording at launch"));
        String tmp = System.getProperty("java.io.tmpdir", "/tmp");
        out.add(ramDisk(tmp) ? new Check("temp-folder", "green", "the temporary folder " + tmp + " is on a RAM disk")
                : new Check("temp-folder", "amber", "the temporary folder " + tmp + " is on a disk; memory-only data never uses it, but a RAM disk "
                        + "(-Djava.io.tmpdir on tmpfs) closes the last way"));
        return out;
    }

    private static String option(String name) {
        try {
            HotSpotDiagnosticMXBean b = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            return b == null ? null : b.getVMOption(name).getValue();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static long parse(String v) {
        String s = v.trim().toLowerCase(Locale.ROOT);
        long mult = 1;
        if (s.endsWith("k")) {
            mult = 1L << 10;
        } else if (s.endsWith("m")) {
            mult = 1L << 20;
        } else if (s.endsWith("g")) {
            mult = 1L << 30;
        }
        String digits = mult == 1 ? s : s.substring(0, s.length() - 1);
        try {
            return Long.parseLong(digits) * mult;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The host's swap in bytes (Linux {@code /proc/meminfo}); -1 when not known. */
    static long swapBytes() {
        Path meminfo = Path.of("/proc/meminfo");
        if (!Files.isReadable(meminfo)) {
            return -1;
        }
        try {
            for (String line : Files.readAllLines(meminfo)) {
                if (line.startsWith("SwapTotal:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024;
                }
            }
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
        return -1;
    }

    static boolean ramDisk(String folder) {
        try {
            Path p = Path.of(folder).toAbsolutePath();
            while (p != null && !Files.exists(p)) {
                p = p.getParent();
            }
            if (p == null) {
                return false;
            }
            String type = Files.getFileStore(p).type().toLowerCase(Locale.ROOT);
            return type.equals("tmpfs") || type.equals("ramfs");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static String mb(long bytes) {
        return (bytes >> 20) + " MB";
    }
}
