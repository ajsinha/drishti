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
package com.ash.drishti.server.capacity;

import com.ash.drishti.kernel.api.capacity.CapacityFigures;
import com.ash.drishti.server.api.ProcessResources;
import com.ash.drishti.server.api.RequestActivity;
import com.ash.drishti.server.disk.DiskBudget;
import com.ash.drishti.kernel.api.security.Principal;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * This server's measured figures for Admin → Capacity's "today against the plan" (README of the capacity mock-ups, section 4), each from
 * what the server already keeps: the VaR engine's status (positions and trades of the newest date, dates open, the open dates' heap and
 * the safety net, the mapped hot files, the machine's memory), the JVM (heap used and max, heap after the last collection, cores, Java),
 * the process's resident memory ({@code VmRSS}, Linux), the disk budget (free space, the guard, the folders) and the request activity
 * (people active in the last 5 minutes, requests a second now and at today's busiest minute). {@code inputs} gives the planner inputs these
 * figures stand for, so a page, a command or a workbook can put the live server through the same formulas. A figure the server cannot
 * tell is null, never guessed.
 */
public final class CapacityLive {

    static final long MB = 1_048_576L;

    private final Supplier<List<CapacityFigures>> figures;
    private final Supplier<DiskBudget> disk;
    private final Supplier<RequestActivity> activity;
    private final Supplier<MeterRegistry> meters;

    public CapacityLive(Supplier<List<CapacityFigures>> figures, Supplier<DiskBudget> disk, Supplier<RequestActivity> activity, Supplier<MeterRegistry> meters) {
        this.figures = figures;
        this.disk = disk;
        this.activity = activity;
        this.meters = meters;
    }

    /** The engines' contributions (a module's), in order. */
    public List<CapacityFigures> figures() {
        List<CapacityFigures> f = figures.get();
        return f == null ? List.of() : f;
    }

    /** The figures, measured now. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> measure(Principal p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("measuredAt", Instant.now().toString());
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("java", System.getProperty("java.version"));
        server.put("javaVersion", Runtime.version().feature());
        server.put("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / MB);
        server.put("heapMaxMb", rt.maxMemory() / MB);
        Long afterGc = heapAfterGcBytes();
        server.put("heapAfterGcMb", afterGc == null ? null : afterGc / MB);
        server.put("rssMb", ProcessResources.rssMb(Path.of("/proc/self/status")));
        server.put("cpus", rt.availableProcessors());
        long ram = physicalMemory();
        server.put("ramMb", ram > 0 ? ram / MB : null);
        out.put("server", server);

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("JavaVersion", Runtime.version().feature());
        for (CapacityFigures f : figures()) {                     // each module's engine, under its own key (DOMAIN_MODULES.md J3)
            out.put(f.key(), f.figures(p, inputs));
        }

        DiskBudget d = disk.get();
        Map<String, Object> dk = null;
        if (d != null) {
            Map<String, Object> desc = d.describe();
            dk = new LinkedHashMap<>();
            dk.put("path", desc.get("path"));
            dk.put("totalBytes", desc.get("totalBytes"));
            dk.put("freeBytes", desc.get("freeBytes"));
            dk.put("guardBytes", desc.get("guardBytes"));
            dk.put("state", desc.get("state"));
            long used = 0;
            for (Object o : desc.get("folders") instanceof List<?> l ? l : List.of()) {
                if (o instanceof Map<?, ?> f && f.get("usedBytes") instanceof Number u && u.longValue() > 0) {
                    used += u.longValue();
                }
            }
            dk.put("foldersUsedBytes", used);
            dk.put("folders", desc.get("folders"));
            if (desc.get("path") != null) {
                inputs.put("DataFolder", String.valueOf(desc.get("path")).replace('\\', '/'));
            }
        }
        out.put("disk", dk);

        RequestActivity a = activity.get();
        Map<String, Object> load = null;
        if (a != null) {
            load = new LinkedHashMap<>();
            int active = a.usersActive(5);
            load.put("usersActive", active);
            load.put("usersPeakToday", a.usersPeakToday());
            load.put("requestsPerSecond", a.requestsPerSecondNow());
            load.put("requestsPerSecondPeakToday", a.requestsPerSecondPeakToday());
            load.put("note", "people: the signed-in users who asked something in the last 5 minutes; requests: this server's API requests, "
                    + "a minute's count ÷ 60 (UTC days)");
            inputs.put("Users", Math.max(1, a.usersPeakToday()));
            inputs.put("PeakRps", Math.max(1.0, a.requestsPerSecondPeakToday()));
        }
        out.put("load", load);
        out.put("inputs", inputs);
        return out;
    }

    static Long mb(Object bytes) {
        return bytes instanceof Number n ? n.longValue() / MB : null;
    }

    /** Micrometer's {@code jvm.gc.live.data.size} (the old generation after the last collection); else the heap pools' usage after their last collection. */
    Long heapAfterGcBytes() {
        MeterRegistry r = meters.get();
        if (r != null) {
            Gauge g = r.find("jvm.gc.live.data.size").gauge();
            if (g != null && g.value() > 0) {
                return (long) g.value();
            }
        }
        long sum = 0;
        boolean any = false;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.isCollectionUsageThresholdSupported()) {
                MemoryUsage u = pool.getCollectionUsage();
                if (u != null) {
                    sum += u.getUsed();
                    any = true;
                }
            }
        }
        return any ? sum : null;
    }

    private static long physicalMemory() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return os.getTotalMemorySize();
        }
        return -1;
    }
}
