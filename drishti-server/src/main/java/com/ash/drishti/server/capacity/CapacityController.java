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

import com.ash.drishti.common.Branding;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.api.RequestActivity;
import com.ash.drishti.server.disk.DiskBudget;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.var.VarController;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Admin → Capacity (RESOURCES_AND_SCALE.md 9.7), administrators only:
 * <pre>
 *   GET /api/v1/admin/capacity                                      the planner (capacity/planner.yaml) as JSON, for the page to evaluate
 *   GET /api/v1/admin/capacity/live                                 this server's measured figures, with when, and the inputs they stand for
 *   GET /api/v1/admin/capacity.xlsx?inputs=plan|live|both&amp;Trades=…   the planner workbook, every result a live formula; audited
 * </pre>
 * The workbook's inputs are the planner's defaults with any input given by its name in place ({@code Trades=450000}); an unknown name is
 * refused with the list. {@code live} puts the measured figures in the inputs (the plan beside them), {@code both} keeps the plan's inputs
 * with the live figures beside them; either lists the measured figures that are not inputs under them. Each download is recorded in the
 * audit log as {@code capacity-workbook-downloaded}.
 */
@RestController
public class CapacityController {

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String AUDIT_ACTION = "capacity-workbook-downloaded";

    private final Entitlements entitlements;
    private final AuditLog audit;
    private final CapacityPlanner planner;
    private final CapacityWorkbook workbook;
    private final CapacityLive live;

    public CapacityController(Entitlements entitlements, AuditLog audit, ObjectProvider<BuildProperties> build, ObjectProvider<VarController> var,
            ObjectProvider<DiskBudget> disk, ObjectProvider<RequestActivity> activity, ObjectProvider<MeterRegistry> meters) {
        this(entitlements, audit, CapacityPlanner.builtIn(build.getIfAvailable() == null ? "dev" : build.getIfAvailable().getVersion()),
                CapacityWorkbook.builtIn(), new CapacityLive(var::getIfAvailable, disk::getIfAvailable, activity::getIfAvailable, meters::getIfAvailable));
    }

    CapacityController(Entitlements entitlements, AuditLog audit, CapacityPlanner planner, CapacityWorkbook workbook, CapacityLive live) {
        this.entitlements = entitlements;
        this.audit = audit;
        this.planner = planner;
        this.workbook = workbook;
        this.live = live;
    }

    @GetMapping("/api/v1/admin/capacity")
    public Map<String, Object> plan(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return planner.plan();
    }

    @GetMapping("/api/v1/admin/capacity/live")
    public Map<String, Object> live(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return live.measure(p);
    }

    @GetMapping("/api/v1/admin/capacity.xlsx")
    @SuppressWarnings("unchecked")
    public ResponseEntity<StreamingResponseBody> download(@RequestParam(defaultValue = "plan") String inputs, @RequestParam Map<String, String> all,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        String mode = inputs.strip().toLowerCase(Locale.ROOT);
        if (!List.of("plan", "live", "both").contains(mode)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "inputs is plan, live or both");
        }
        Map<String, String> given = new LinkedHashMap<>(all);
        given.remove("inputs");
        Map<String, Object> plan = planner.inputs(given);
        Map<String, Object> values = plan;
        String header = null;
        Map<String, Object> extra = Map.of();
        List<String[]> measured = List.of();
        String measuredAt = null;
        if (!mode.equals("plan")) {
            Map<String, Object> m = live.measure(p);
            measuredAt = String.valueOf(m.get("measuredAt"));
            Map<String, Object> liveInputs = new LinkedHashMap<>(plan);
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("inputs")).entrySet()) {
                if (e.getValue() != null && plan.containsKey(e.getKey())) {
                    liveInputs.put(e.getKey(), planner.typed(e.getKey(), e.getValue()));
                }
            }
            String stamp = "Live, measured " + when(measuredAt);
            if (mode.equals("live")) {
                values = liveInputs;
                header = "Plan (as on the page)";
                extra = plan;
            } else {
                header = stamp;
                Map<String, Object> only = new LinkedHashMap<>();
                ((Map<String, Object>) m.get("inputs")).forEach((k, v) -> {
                    if (v != null && plan.containsKey(k)) {
                        only.put(k, liveInputs.get(k));
                    }
                });
                extra = only;
            }
            measured = measured(m);
        }
        List<String> changed = new ArrayList<>();
        Map<String, Object> defaults = planner.defaults();
        plan.forEach((k, v) -> {
            if (!Objects.equals(String.valueOf(planner.typed(k, defaults.get(k))), String.valueOf(v))) {
                changed.add(k + "=" + v);
            }
        });
        audit.record(p.user(), AUDIT_ACTION, "capacity", "inputs=" + mode + (changed.isEmpty() ? "; the defaults" : "; " + String.join(", ", changed))
                + (measuredAt == null ? "" : "; live measured " + measuredAt));
        Map<String, Object> finalValues = values;
        String finalHeader = header;
        Map<String, Object> finalExtra = extra;
        List<String[]> finalMeasured = measured;
        StreamingResponseBody body = out -> workbook.write(out, finalValues, finalHeader, finalExtra, finalMeasured, Branding.current());
        String name = "capacity-" + mode + "-" + ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".xlsx";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"").header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    private static String when(String iso) {
        return iso == null ? "" : iso.replace('T', ' ').replaceAll("\\.\\d+Z$", "Z").replaceAll(":\\d\\dZ$", " UTC");
    }

    /** The measured figures that are not inputs, for the block under the inputs. */
    @SuppressWarnings("unchecked")
    static List<String[]> measured(Map<String, Object> m) {
        List<String[]> rows = new ArrayList<>();
        Map<String, Object> s = (Map<String, Object>) m.getOrDefault("server", Map.of());
        add(rows, "Heap max (-Xmx)", s.get("heapMaxMb"), "MB");
        add(rows, "Heap after the last collection", s.get("heapAfterGcMb"), "MB");
        add(rows, "Heap used now", s.get("heapUsedMb"), "MB");
        add(rows, "Resident memory (RSS)", s.get("rssMb"), "MB");
        add(rows, "Machine memory", s.get("ramMb"), "MB");
        add(rows, "Cores (hardware threads the JVM sees)", s.get("cpus"), "cores");
        if (m.get("var") instanceof Map<?, ?> v) {
            add(rows, "VaR dates open", v.get("openDates"), "dates");
            add(rows, "Open dates' heap", v.get("openHeapMb"), "MB");
            add(rows, "Safety net (open-heap limit)", v.get("openHeapLimitMb"), "MB");
            add(rows, "Mapped hot files", v.get("mappedMb"), "MB");
        }
        if (m.get("disk") instanceof Map<?, ?> d) {
            add(rows, "Disk free (data folder)", d.get("freeBytes") instanceof Number n ? n.longValue() / CapacityLive.MB / 1024.0 : null, "GB");
            add(rows, "Free-space guard", d.get("guardBytes") instanceof Number n ? n.longValue() / CapacityLive.MB / 1024.0 : null, "GB");
            add(rows, "Data folder total", d.get("totalBytes") instanceof Number n ? n.longValue() / CapacityLive.MB / 1024.0 : null, "GB");
        }
        if (m.get("load") instanceof Map<?, ?> l) {
            add(rows, "People active (last 5 minutes)", l.get("usersActive"), "users");
            add(rows, "People active, today's most", l.get("usersPeakToday"), "users");
            add(rows, "Requests a second, last minute", l.get("requestsPerSecond"), "requests/s");
            add(rows, "Requests a second, today's busiest minute", l.get("requestsPerSecondPeakToday"), "requests/s");
        }
        return rows;
    }

    private static void add(List<String[]> rows, String label, Object v, String unit) {
        if (v instanceof Number n) {
            rows.add(new String[] {label, String.valueOf(Math.round(n.doubleValue() * 10) / 10.0), unit});
        }
    }
}
